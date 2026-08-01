import { DocumentSnapshot, FieldValue } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { db, now } from "./firebase";
import { normalizedName, sha256 } from "./validation";

type CanonicalCategory = { id: string; name: string; normalizedName: string; sortOrder: number; isDefault: boolean };

/**
 * Idempotently upgrades pre-v4 pantry documents before a client starts realtime sync.
 * Legacy display names are used only here to recover missing IDs; normal mutations are
 * strictly ID-based.
 */
export async function migratePantryCanonicalSchema(pantryId: string): Promise<void> {
  const pantryRef = db.doc(`pantries/${pantryId}`);
  const pantry = await pantryRef.get();
  if (Number(pantry.get("contentSchemaVersion") || 1) >= 2) {
    if (!pantry.get("quantityProjectionRemovedAt")) {
      const products = await pantryRef.collection("products").get();
      const cleanup = db.bulkWriter();
      products.docs.filter((product) => product.get("totalQuantity") !== undefined)
        .forEach((product) => cleanup.update(product.ref, { totalQuantity: FieldValue.delete() }));
      cleanup.set(pantryRef, { quantityProjectionRemovedAt: now() }, { merge: true });
      await cleanup.close();
    }
    return;
  }
  const [shelves, categories, products, variants, stocks, shopping] = await Promise.all([
    pantryRef.collection("shelves").get(),
    pantryRef.collection("categories").get(),
    pantryRef.collection("products").get(),
    pantryRef.collection("variants").get(),
    pantryRef.collection("stocks").get(),
    pantryRef.collection("shoppingItems").get(),
  ]);
  const activeShelves = shelves.docs.filter(activeDocument);
  const activeCategories = categories.docs.filter(activeDocument);
  if (activeCategories.length === 0) throw new HttpsError("failed-precondition", "Smočnica nema aktivnu kategoriju.");

  assertUnique(activeShelves, "police");
  assertUnique(activeCategories, "kategorije");
  const categoryModels = activeCategories
    .map((document): CanonicalCategory => ({
      id: document.id,
      name: String(document.get("name")),
      normalizedName: normalizedName(String(document.get("name"))),
      sortOrder: Number(document.get("sortOrder") || 0),
      isDefault: document.get("isDefault") === true,
    }))
    .sort((left, right) => left.sortOrder - right.sortOrder || left.id.localeCompare(right.id));
  const requestedDefault = categoryModels.filter((category) => category.isDefault);
  const canonicalDefault = requestedDefault[0]
    ?? categoryModels.find((category) => category.normalizedName === normalizedName("Ostalo"))
    ?? categoryModels[0];
  const categoryById = new Map(categoryModels.map((category) => [category.id, category]));
  const categoryByName = new Map(categoryModels.map((category) => [category.normalizedName, category]));
  const productCategory = new Map<string, CanonicalCategory>();
  const timestamp = now();
  const writer = db.bulkWriter();
  const genericSchemaComplete = false;
  await pantryRef.set({ genericMigration: { targetVersion: 2, phase: "WRITING", updatedAt: timestamp } }, { merge: true });
  const existingVariantIds = new Set(variants.docs.map((variant) => variant.id));

  for (const shelf of activeShelves) {
    const normalized = normalizedName(String(shelf.get("name")));
    writer.set(shelf.ref, { normalizedName: normalized }, { merge: true });
    writer.set(pantryRef.collection("shelfNames").doc(sha256(normalized)), {
      normalizedName: normalized, shelfId: shelf.id, updatedAt: timestamp,
    }, { merge: true });
  }
  for (const category of categoryModels) {
    writer.set(pantryRef.collection("categories").doc(category.id), {
      normalizedName: category.normalizedName,
      isDefault: category.id === canonicalDefault!.id,
    }, { merge: true });
    writer.set(pantryRef.collection("categoryNames").doc(sha256(category.normalizedName)), {
      normalizedName: category.normalizedName, categoryId: category.id, updatedAt: timestamp,
    }, { merge: true });
  }

  for (const product of products.docs.filter(activeDocument)) {
    const category = categoryById.get(String(product.get("categoryId") || ""))
      ?? categoryByName.get(normalizedName(String(product.get("category") || "")));
    if (!category) throw new HttpsError("failed-precondition", `Artikl ${product.id} nema valjanu aktivnu kategoriju.`);
    productCategory.set(product.id, category);
    writer.set(product.ref, {
      categoryId: category.id, category: category.name,
      ...(genericSchemaComplete ? {} : {
        normalizedName: normalizedName(String(product.get("name"))),
        barcode: null, description: "", photoUrl: null, photoSource: "NONE",
        minimumMode: "PACKAGES",
        minimumAmountBase: Number(product.get("minimumQuantity") || 0),
        preferredVariantId: product.id,
        doNotGroup: false,
        groupingRevision: 0,
        totalQuantity: FieldValue.delete(),
      }),
    }, { merge: true });
    if (!genericSchemaComplete && !existingVariantIds.has(product.id)) {
      const code = typeof product.get("barcode") === "string" ? String(product.get("barcode")) : null;
      // `set` makes the migration safe when two devices trigger it concurrently.
      // Both writers persist the same deterministic initial variant instead of
      // surfacing an asynchronous ALREADY_EXISTS error from BulkWriter.
      writer.set(pantryRef.collection("variants").doc(product.id), {
        productId: product.id,
        displayName: String(product.get("name")), manufacturer: "", barcode: code,
        packageAmountBase: null, packageUnit: "UNKNOWN", packageLabel: "",
        description: String(product.get("description") || ""),
        photoUrl: product.get("photoUrl") || null, photoSource: product.get("photoSource") || "NONE",
        minimumPackages: null, purchaseCount: 0,
        revision: Number(product.get("revision") || 0),
        createdAt: product.get("createdAt") || timestamp, updatedAt: timestamp,
        deletedAt: product.get("deletedAt") || null, purgeAfter: product.get("purgeAfter") || null,
      }, { merge: true });
      if (code) writer.set(db.doc(`barcodes/${sha256(`${pantryId}:${code}`)}`), {
        pantryId, productId: product.id, variantId: product.id, barcode: code, updatedAt: timestamp,
      }, { merge: true });
    }
  }

  if (!genericSchemaComplete) {
    for (const stock of stocks.docs) {
      writer.set(stock.ref, {
        productId: String(stock.get("productId")),
        variantId: String(stock.get("variantId") || stock.get("productId")),
      }, { merge: true });
    }
  }

  for (const item of shopping.docs.filter(activeDocument)) {
    const productId = String(item.get("productId") || "");
    const category = productId
      ? productCategory.get(productId)
      : categoryById.get(String(item.get("categoryId") || ""))
        ?? categoryByName.get(normalizedName(String(item.get("category") || "")));
    if (!category) throw new HttpsError("failed-precondition", `Stavka kupnje ${item.id} nema valjanu aktivnu kategoriju.`);
    writer.set(item.ref, {
      categoryId: category.id, category: category.name,
      preferredVariantId: productId || null,
    }, { merge: true });
  }
  await writer.close();
  if (!genericSchemaComplete) {
    await pantryRef.set({
      contentSchemaVersion: 2,
      genericMigration: { targetVersion: 2, phase: "COMPLETED", completedAt: now(), updatedAt: now() },
      updatedAt: now(),
    }, { merge: true });
  }
}

function activeDocument(document: DocumentSnapshot): boolean {
  return !document.get("deletedAt");
}

function assertUnique(documents: DocumentSnapshot[], label: string): void {
  const used = new Map<string, string>();
  for (const document of documents) {
    const normalized = normalizedName(String(document.get("name")));
    const existing = used.get(normalized);
    if (existing && existing !== document.id) {
      throw new HttpsError("failed-precondition", `Pronađeni su dupli nazivi ${label}. Potrebna je administratorska provjera.`);
    }
    used.set(normalized, document.id);
  }
}
