import { DocumentSnapshot } from "firebase-admin/firestore";
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
  const [shelves, categories, products, shopping] = await Promise.all([
    pantryRef.collection("shelves").get(),
    pantryRef.collection("categories").get(),
    pantryRef.collection("products").get(),
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
    writer.set(product.ref, { categoryId: category.id, category: category.name }, { merge: true });
  }

  for (const item of shopping.docs.filter(activeDocument)) {
    const productId = String(item.get("productId") || "");
    const category = productId
      ? productCategory.get(productId)
      : categoryById.get(String(item.get("categoryId") || ""))
        ?? categoryByName.get(normalizedName(String(item.get("category") || "")));
    if (!category) throw new HttpsError("failed-precondition", `Stavka kupnje ${item.id} nema valjanu aktivnu kategoriju.`);
    writer.set(item.ref, { categoryId: category.id, category: category.name }, { merge: true });
  }
  await writer.close();
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
