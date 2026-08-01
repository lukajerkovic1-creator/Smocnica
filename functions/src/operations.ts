import { FieldValue, Transaction, DocumentReference, DocumentSnapshot, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { db, daysFromNow, now } from "./firebase";
import { PANTRY_LIMITS, assertFinalResourceLimit, assertResourceLimit } from "./limits";
import { Data, authUid, barcode, boolean, integer, normalizedName, object, safeId, sha256, stringArray, text } from "./validation";
import { migratePantryCanonicalSchema } from "./canonical-schema";

const callable = { region: "europe-west1", enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== "true", timeoutSeconds: 60, memory: "512MiB" as const };
const metadataTypes = new Set(["rename_shelf", "reorder_shelves", "reorder_categories", "delete_shelf", "upsert_category", "delete_category", "upsert_product", "upsert_variant", "move_variant", "split_variant", "upsert_synonym_rule", "set_do_not_group", "upsert_shopping", "delete_shopping", "soft_delete", "restore"]);

type HandlerContext = {
  tx: Transaction;
  pantryRef: DocumentReference;
  pantryId: string;
  payload: Data;
  baseRevision: number;
  operationId: string;
  uid: string;
  memberRole: string;
  deviceId: string;
  deviceDisplayName: string;
  timestamp: Timestamp;
};

type ActivityMetadata = {
  productId?: string;
  shelfId?: string;
  fromShelfId?: string;
  toShelfId?: string;
  displayLabel?: string;
  oldValue?: string | null;
  newValue?: string | null;
  quantityDelta?: number | null;
};

type HandlerResult = number | { revision: number; activity?: ActivityMetadata; activities?: ActivityMetadata[] };

export const applyOperation = onCall(callable, async (request) => {
  const uid = authUid(request);
  const data = object(request.data);
  const operationId = safeId(text(data, "operationId"), "operationId");
  const pantryId = safeId(text(data, "pantryId"), "pantryId");
  const aggregateType = text(data, "aggregateType", 1, 30);
  const aggregateId = safeId(text(data, "aggregateId"), "aggregateId");
  const baseRevision = integer(data, "baseRevision", 0);
  const payload = object(data.payload, "payload");
  const type = text(payload, "type", 1, 40);
  const deviceId = safeId(text(data, "deviceId", 1, 128), "deviceId");
  const pantryRef = db.doc(`pantries/${pantryId}`);
  const operationRef = pantryRef.collection("operations").doc(operationId);
  const deviceRef = db.doc(`users/${uid}/devices/${deviceId}`);
  const timestamp = now();

  const [migrationMember, migrationPantry] = await Promise.all([
    pantryRef.collection("members").doc(uid).get(),
    pantryRef.get(),
  ]);
  if (!migrationMember.exists || migrationMember.get("active") !== true) {
    throw new HttpsError("permission-denied", "Korisnik nije aktivan član smočnice.");
  }
  if (!migrationPantry.exists || migrationPantry.get("deletedAt")) {
    throw new HttpsError("not-found", "Smočnica nije dostupna.");
  }
  // Stari klijenti mogu poslati prvu operaciju prije listMyPantries poziva. Migracija je
  // verzionirana i idempotentna, pa se dovršava prije transakcijske mutacije.
  await migratePantryCanonicalSchema(pantryId);

  return db.runTransaction(async (tx) => {
    const [operation, member, pantry, device] = await Promise.all([
      tx.get(operationRef),
      tx.get(pantryRef.collection("members").doc(uid)),
      tx.get(pantryRef),
      tx.get(deviceRef),
    ]);
    if (operation.exists) {
      return { status: "ALREADY_APPLIED", revision: Number(operation.get("resultRevision") || baseRevision) };
    }
    if (!member.exists || member.get("active") !== true) throw new HttpsError("permission-denied", "Korisnik nije aktivan član smočnice.");
    if (!pantry.exists || pantry.get("deletedAt")) throw new HttpsError("not-found", "Smočnica nije dostupna.");
    if (pantry.get("importInProgress")) throw new HttpsError("unavailable", "Uvoz sigurnosne kopije je u tijeku. Pokušajte ponovno.");
    if (!device.exists || device.get("active") !== true) throw new HttpsError("permission-denied", "Uređaj nije aktivno registriran za prijavljenog korisnika.");
    const deviceDisplayName = registeredDeviceName(device.get("name"));
    if (metadataTypes.has(type) && (type === "reorder_shelves" || type === "reorder_categories") && Number(pantry.get("revision") || 0) !== baseRevision) {
      return { status: "CONFLICT", revision: Number(pantry.get("revision") || 0) };
    }

    const handled = await handle({ tx, pantryRef, pantryId, payload, baseRevision, operationId, uid, memberRole: String(member.get("role") || "MEMBER"), deviceId, deviceDisplayName, timestamp });
    const revision = typeof handled === "number" ? handled : handled.revision;
    const metadata = typeof handled === "number" ? {} : handled.activity || {};
    tx.create(operationRef, {
      actorUid: uid, aggregateType, aggregateId, payloadType: type,
      resultRevision: revision, appliedAt: timestamp,
      expiresAt: daysFromNow(365),
      resultDigest: sha256(JSON.stringify({ operationId, revision })),
    });
    const activityEntries = typeof handled === "number" || !handled.activities ? [metadata] : handled.activities;
    activityEntries.forEach((entry, index) => {
      const values = activityValues(entry);
      const references = activityReferences(payload, type, entry);
      const activityId = activityEntries.length === 1 ? operationId : `${operationId}_${index}`;
      tx.create(pantryRef.collection("activities").doc(activityId), {
        type: activityType(type, payload), aggregateId: references.productId || aggregateId,
        displayLabel: entry.displayLabel || activityLabel(payload, type),
        quantityDelta: entry.quantityDelta !== undefined ? entry.quantityDelta : quantityDelta(payload, type),
        oldValue: values.oldValue, newValue: values.newValue,
        productId: references.productId, shelfId: references.shelfId,
        fromShelfId: references.fromShelfId, toShelfId: references.toShelfId,
        actorUid: uid, deviceId, deviceDisplayName,
        createdAt: timestamp, expiresAt: daysFromNow(365),
      });
    });
    return { status: "APPLIED", revision };
  });
});

async function handle(context: HandlerContext): Promise<HandlerResult> {
  const type = text(context.payload, "type");
  switch (type) {
    case "create_shelf": return createShelf(context);
    case "rename_shelf": return renameShelf(context);
    case "reorder_shelves": return reorderShelves(context);
    case "reorder_categories": return reorderCategories(context);
    case "delete_shelf": return deleteShelf(context);
    case "upsert_category": return upsertCategory(context);
    case "delete_category": return deleteCategory(context);
    case "upsert_product": return upsertProduct(context);
    case "upsert_variant": return upsertVariant(context);
    case "move_variant": return moveVariant(context);
    case "split_variant": return splitVariant(context);
    case "upsert_synonym_rule": return upsertSynonymRule(context);
    case "set_do_not_group": return setDoNotGroup(context);
    case "adjust_stock": return adjustStock(context);
    case "move_stock": return moveStock(context);
    case "bulk_change_product_category": return bulkChangeProductCategory(context);
    case "bulk_delete_products": return bulkDeleteProducts(context);
    case "bulk_move_stock": return bulkMoveStock(context);
    case "upsert_shopping": return upsertShopping(context);
    case "delete_shopping": return deleteShopping(context);
    case "apply_inventory": return applyInventory(context);
    case "soft_delete": return softDelete(context);
    case "restore": return restore(context);
    case "import_snapshot": return importSnapshot(context);
    default: throw new HttpsError("invalid-argument", `Nepodržana operacija: ${type}.`);
  }
}

async function createShelf({ tx, pantryRef, payload, timestamp }: HandlerContext): Promise<HandlerResult> {
  const shelf = object(payload.shelf, "shelf");
  const id = safeId(text(shelf, "id"));
  const name = text(shelf, "name", 1, 100);
  const normalized = normalizedName(name);
  const ref = pantryRef.collection("shelves").doc(id);
  const nameRef = pantryRef.collection("shelfNames").doc(sha256(normalized));
  const [existing, active, reserved] = await Promise.all([
    tx.get(ref),
    tx.get(pantryRef.collection("shelves").where("deletedAt", "==", null).limit(PANTRY_LIMITS.shelves + 1)),
    tx.get(nameRef),
  ]);
  if (existing.exists) throw new HttpsError("already-exists", "Polica već postoji.");
  assertResourceLimit(active.size, PANTRY_LIMITS.shelves, "polica");
  assertUniqueNormalizedName(active.docs, normalized, id, "Polica");
  if (reserved.exists && reserved.get("shelfId") !== id) throw new HttpsError("already-exists", "Aktivna polica s tim nazivom već postoji.");
  tx.create(ref, {
    name, normalizedName: normalized, sortOrder: integer(shelf, "sortOrder", 0, 10_000),
    revision: 1, createdAt: timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
  });
  tx.set(nameRef, { normalizedName: normalized, shelfId: id, updatedAt: timestamp });
  return { revision: 1, activity: { shelfId: id, displayLabel: name } };
}

async function renameShelf({ tx, pantryRef, payload, baseRevision, timestamp }: HandlerContext): Promise<HandlerResult> {
  const id = safeId(text(payload, "shelfId"));
  const name = text(payload, "name", 1, 100);
  const normalized = normalizedName(name);
  const ref = pantryRef.collection("shelves").doc(id);
  const shelf = await tx.get(ref);
  assertRevision(shelf, baseRevision, "Polica");
  const oldNormalized = documentNormalizedName(shelf);
  const newNameRef = pantryRef.collection("shelfNames").doc(sha256(normalized));
  const oldNameRef = pantryRef.collection("shelfNames").doc(sha256(oldNormalized));
  const [active, reserved] = await Promise.all([
    tx.get(pantryRef.collection("shelves").where("deletedAt", "==", null).limit(PANTRY_LIMITS.shelves + 1)),
    tx.get(newNameRef),
  ]);
  assertUniqueNormalizedName(active.docs, normalized, id, "Polica");
  if (reserved.exists && reserved.get("shelfId") !== id) throw new HttpsError("already-exists", "Aktivna polica s tim nazivom već postoji.");
  const revision = baseRevision + 1;
  tx.update(ref, { name, normalizedName: normalized, revision, updatedAt: timestamp });
  tx.set(newNameRef, { normalizedName: normalized, shelfId: id, updatedAt: timestamp });
  if (oldNameRef.path !== newNameRef.path) tx.delete(oldNameRef);
  return { revision, activity: { shelfId: id, displayLabel: name } };
}

async function reorderShelves({ tx, pantryRef, payload, baseRevision, timestamp }: HandlerContext): Promise<number> {
  const orderedIds = stringArray(payload, "orderedShelfIds", 200).map((id) => safeId(id));
  if (new Set(orderedIds).size !== orderedIds.length) throw new HttpsError("invalid-argument", "Poredak sadrži duplikate.");
  const snapshot = await tx.get(pantryRef.collection("shelves").where("deletedAt", "==", null));
  const activeIds = snapshot.docs.map((doc) => doc.id).sort();
  if (activeIds.join("|") !== [...orderedIds].sort().join("|")) throw new HttpsError("failed-precondition", "Poredak mora sadržavati sve aktivne police.");
  snapshot.docs.forEach((doc) => tx.update(doc.ref, {
    sortOrder: orderedIds.indexOf(doc.id), revision: FieldValue.increment(1), updatedAt: timestamp,
  }));
  const revision = baseRevision + 1;
  tx.update(pantryRef, { revision, updatedAt: timestamp });
  return revision;
}

async function reorderCategories({ tx, pantryRef, payload, baseRevision, timestamp }: HandlerContext): Promise<number> {
  const orderedIds = stringArray(payload, "orderedCategoryIds", 200).map((id) => safeId(id));
  if (new Set(orderedIds).size !== orderedIds.length) throw new HttpsError("invalid-argument", "Poredak sadrži duplikate.");
  const snapshot = await tx.get(pantryRef.collection("categories").where("deletedAt", "==", null));
  const activeIds = snapshot.docs.map((doc) => doc.id).sort();
  if (activeIds.join("|") !== [...orderedIds].sort().join("|")) throw new HttpsError("failed-precondition", "Poredak mora sadržavati sve aktivne kategorije.");
  snapshot.docs.forEach((doc) => tx.update(doc.ref, {
    sortOrder: orderedIds.indexOf(doc.id), revision: FieldValue.increment(1), updatedAt: timestamp,
  }));
  const revision = baseRevision + 1;
  tx.update(pantryRef, { revision, updatedAt: timestamp });
  return revision;
}

async function deleteShelf({ tx, pantryRef, payload, baseRevision, timestamp }: HandlerContext): Promise<HandlerResult> {
  const id = safeId(text(payload, "shelfId"));
  const ref = pantryRef.collection("shelves").doc(id);
  const [shelf, occupied] = await Promise.all([
    tx.get(ref),
    tx.get(pantryRef.collection("stocks").where("shelfId", "==", id).where("quantity", ">", 0).limit(1)),
  ]);
  assertRevision(shelf, baseRevision, "Polica");
  if (!occupied.empty) throw new HttpsError("failed-precondition", "Polica se može obrisati tek kada je prazna.");
  const revision = baseRevision + 1;
  tx.update(ref, { deletedAt: timestamp, purgeAfter: daysFromNow(30), revision, updatedAt: timestamp });
  tx.delete(pantryRef.collection("shelfNames").doc(sha256(documentNormalizedName(shelf))));
  return {
    revision,
    activity: { shelfId: id, displayLabel: serverActivityLabel(shelf.get("name"), "Polica") },
  };
}

async function upsertCategory({ tx, pantryRef, payload, baseRevision, timestamp }: HandlerContext): Promise<number> {
  const category = object(payload.category, "category");
  const id = safeId(text(category, "id"));
  const ref = pantryRef.collection("categories").doc(id);
  const existing = await tx.get(ref);
  if (existing.exists && Number(existing.get("revision") || 0) !== baseRevision) return conflict(Number(existing.get("revision") || 0));
  const name = text(category, "name", 1, 100);
  const normalized = normalizedName(name);
  const newNameRef = pantryRef.collection("categoryNames").doc(sha256(normalized));
  const oldNormalized = existing.exists ? documentNormalizedName(existing) : normalized;
  const oldNameRef = pantryRef.collection("categoryNames").doc(sha256(oldNormalized));
  const active = await tx.get(pantryRef.collection("categories").where("deletedAt", "==", null).limit(PANTRY_LIMITS.categories + 1));
  const reserved = await tx.get(newNameRef);
  const renamed = existing.exists && existing.get("name") !== name;
  const [products, shopping] = renamed ? await Promise.all([
    tx.get(pantryRef.collection("products").where("categoryId", "==", id)),
    tx.get(pantryRef.collection("shoppingItems").where("categoryId", "==", id)),
  ]) : [null, null];
  if (!existing.exists || existing.get("deletedAt")) assertResourceLimit(active.size, PANTRY_LIMITS.categories, "kategorija");
  assertUniqueNormalizedName(active.docs, normalized, id, "Kategorija");
  if (reserved.exists && reserved.get("categoryId") !== id) throw new HttpsError("already-exists", "Aktivna kategorija s tim nazivom već postoji.");
  if (existing.exists && existing.get("name") !== name) {
    if (products!.size + shopping!.size > 400) throw new HttpsError("resource-exhausted", "Previše zapisa za atomarno preimenovanje kategorije.");
    products!.docs.forEach((product) => tx.update(product.ref, {
      category: name, revision: FieldValue.increment(1), updatedAt: timestamp,
    }));
    shopping!.docs.forEach((item) => tx.update(item.ref, {
      categoryId: id, category: name, revision: FieldValue.increment(1), updatedAt: timestamp,
    }));
  }
  const revision = existing.exists ? baseRevision + 1 : 1;
  const defaultCategoryId = canonicalDefaultCategoryId(active.docs, id, normalized, existing);
  tx.set(ref, {
    name, normalizedName: normalized, sortOrder: integer(category, "sortOrder", 0, 10_000),
    isDefault: defaultCategoryId === id, revision,
    createdAt: existing.get("createdAt") || timestamp, updatedAt: timestamp,
    deletedAt: null, purgeAfter: null,
  }, { merge: true });
  active.docs.filter((document) => document.id !== id).forEach((document) => {
    const shouldBeDefault = document.id === defaultCategoryId;
    if (document.get("isDefault") !== shouldBeDefault) tx.update(document.ref, { isDefault: shouldBeDefault, updatedAt: timestamp });
  });
  tx.set(newNameRef, { normalizedName: normalized, categoryId: id, updatedAt: timestamp });
  if (oldNameRef.path !== newNameRef.path) tx.delete(oldNameRef);
  return revision;
}

async function deleteCategory({ tx, pantryRef, payload, baseRevision, timestamp }: HandlerContext): Promise<number> {
  const id = safeId(text(payload, "categoryId"));
  const replacementId = safeId(text(payload, "replacementCategoryId"));
  const categoryRef = pantryRef.collection("categories").doc(id);
  const replacementRef = pantryRef.collection("categories").doc(replacementId);
  if (id === replacementId) throw new HttpsError("invalid-argument", "Zamjenska kategorija mora biti različita.");
  const [category, replacement, products, shopping] = await Promise.all([
    tx.get(categoryRef),
    tx.get(replacementRef),
    tx.get(pantryRef.collection("products").where("categoryId", "==", id)),
    tx.get(pantryRef.collection("shoppingItems").where("categoryId", "==", id)),
  ]);
  assertRevision(category, baseRevision, "Kategorija");
  if (category.get("isDefault") === true) throw new HttpsError("failed-precondition", "Zadana kategorija ne može se obrisati.");
  if (!replacement.exists || replacement.get("deletedAt")) throw new HttpsError("failed-precondition", "Zamjenska kategorija nije dostupna.");
  if (products.size + shopping.size > 400) throw new HttpsError("resource-exhausted", "Previše zapisa za atomarno brisanje kategorije.");
  products.docs.forEach((product) => tx.update(product.ref, {
    categoryId: replacementId, category: replacement.get("name"), revision: FieldValue.increment(1), updatedAt: timestamp,
  }));
  shopping.docs.forEach((item) => tx.update(item.ref, {
    categoryId: replacementId, category: replacement.get("name"), revision: FieldValue.increment(1), updatedAt: timestamp,
  }));
  const revision = baseRevision + 1;
  tx.update(categoryRef, { deletedAt: timestamp, purgeAfter: daysFromNow(30), revision, updatedAt: timestamp });
  tx.delete(pantryRef.collection("categoryNames").doc(sha256(documentNormalizedName(category))));
  return revision;
}

async function upsertProduct(context: HandlerContext): Promise<HandlerResult> {
  const { tx, pantryRef, pantryId, payload, baseRevision, operationId, timestamp } = context;
  const product = object(payload.product, "product");
  // Legacy clients may still send the former generic-product photo fields. The
  // value is no longer persisted there, but an untrusted URL must not bypass
  // the same allow-list enforced for variant photos.
  if (product.photoSource === "OPEN_FOOD_FACTS") openFoodFactsImageUrl(product.photoUri);
  const id = safeId(text(product, "id"));
  const ref = pantryRef.collection("products").doc(id);
  const shoppingRef = pantryRef.collection("shoppingItems").doc(`auto_${id}`);
  const initial = payload.initialVariant && typeof payload.initialVariant === "object"
    ? object(payload.initialVariant, "initialVariant")
    : null;
  const initialId = initial ? safeId(text(initial, "id"), "variantId") : (!initial ? id : null);
  const initialRef = initialId ? pantryRef.collection("variants").doc(initialId) : null;
  const [existing, currentShopping, variantsQuery, stocksQuery, initialExisting] = await Promise.all([
    tx.get(ref), tx.get(shoppingRef),
    tx.get(pantryRef.collection("variants").where("productId", "==", id)),
    tx.get(pantryRef.collection("stocks").where("productId", "==", id)),
    initialRef ? tx.get(initialRef) : Promise.resolve(null),
  ]);
  if (existing.exists && Number(existing.get("revision") || 0) !== baseRevision) return conflict(Number(existing.get("revision") || 0));
  if (!existing.exists || existing.get("deletedAt")) {
    const active = await tx.get(pantryRef.collection("products").where("deletedAt", "==", null).limit(PANTRY_LIMITS.products + 1));
    assertResourceLimit(active.size, PANTRY_LIMITS.products, "artikala");
  }
  const categoryId = safeId(text(product, "categoryId"), "categoryId");
  const categoryDocument = await tx.get(pantryRef.collection("categories").doc(categoryId));
  if (!categoryDocument.exists || categoryDocument.get("deletedAt")) {
    throw new HttpsError("failed-precondition", "Kategorija nije dostupna.");
  }
  const categoryName = text({ name: categoryDocument.get("name") }, "name", 1, 100);
  const initialPayload = initial || (!existing.exists ? {
    id, productId: id, displayName: text(product, "name"), manufacturer: "", barcode: product.barcode,
    packageAmountBase: null, packageUnit: "UNKNOWN", packageLabel: "", description: product.description,
    photoUri: product.photoUri, photoSource: product.photoSource, minimumPackages: null, purchaseCount: 0,
  } : null);
  if (initialPayload && safeId(text(initialPayload, "productId"), "productId") !== id) {
    throw new HttpsError("invalid-argument", "Početna varijanta mora pripadati artiklu.");
  }
  const code = initialPayload ? barcode(initialPayload.barcode) : null;
  const oldCode = initialExisting?.exists ? (initialExisting.get("barcode") as string | null) : null;
  const newReservation = code ? db.doc(`barcodes/${sha256(`${pantryId}:${code}`)}`) : null;
  const oldReservation = oldCode && oldCode !== code ? db.doc(`barcodes/${sha256(`${pantryId}:${oldCode}`)}`) : null;
  if (newReservation && code !== oldCode) {
    const reservation = await tx.get(newReservation);
    if (reservation.exists && reservation.get("variantId") !== initialId) throw new HttpsError("already-exists", "Barkod je već povezan s drugom varijantom.");
  }
  const minimum = integer(product, "minimumQuantity", 0, 1_000_000);
  const minimumMode = optionalEnum(product.minimumMode, ["PACKAGES", "MASS_MG", "VOLUME_ML", "COUNT"], "PACKAGES", "minimumMode");
  const minimumAmountBase = product.minimumAmountBase === undefined ? minimum : integer(product, "minimumAmountBase", 0, 1_000_000_000_000);
  const autoShopping = boolean(product, "autoShopping", true);
  assertVariantsCompatibleWithMinimum(minimumMode, [
    ...variantsQuery.docs.map((variant) => String(variant.get("packageUnit") || "UNKNOWN")),
    ...(initialPayload ? [String(initialPayload.packageUnit || "UNKNOWN")] : []),
  ]);
  const name = text(product, "name", 1, 100);
  const packageCount = stocksQuery.docs.reduce((sum, stock) => sum + Number(stock.get("quantity") || 0), 0);
  const preferredVariantId = typeof product.preferredVariantId === "string"
    ? safeId(product.preferredVariantId, "preferredVariantId")
    : (existing.get("preferredVariantId") as string | null) || initialId;
  if (preferredVariantId && preferredVariantId !== initialId && !variantsQuery.docs.some((variant) => variant.id === preferredVariantId && !variant.get("deletedAt"))) {
    throw new HttpsError("failed-precondition", "Preferirana varijanta nije dostupna.");
  }
  const revision = existing.exists ? baseRevision + 1 : 1;
  tx.set(ref, {
    name, normalizedName: normalizedName(name),
    barcode: null, description: "",
    category: categoryName, categoryId,
    photoUrl: null, photoSource: "NONE",
    minimumQuantity: minimumMode === "PACKAGES" ? minimumAmountBase : minimum,
    minimumMode, minimumAmountBase, preferredVariantId,
    doNotGroup: boolean(product, "doNotGroup", existing.get("doNotGroup") === true),
    groupingRevision: Number(existing.get("groupingRevision") || 0),
    autoShopping, totalQuantity: FieldValue.delete(), revision,
    createdAt: existing.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
  }, { merge: true });
  if (initialPayload && initialRef && initialId) {
    if (initialExisting?.exists && initialExisting.get("productId") !== id) throw new HttpsError("already-exists", "Varijanta već pripada drugom artiklu.");
    tx.set(initialRef, {
      ...canonicalVariant(initialPayload, pantryId, id, initialId),
      revision: initialExisting?.exists ? Number(initialExisting.get("revision") || 0) + 1 : 1,
      createdAt: initialExisting?.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
    }, { merge: true });
  }
  if (newReservation) tx.set(newReservation, { pantryId, productId: id, variantId: initialId, barcode: code, updatedAt: timestamp });
  if (oldReservation) tx.delete(oldReservation);
  const currentBase = minimumMode === "PACKAGES" ? packageCount : aggregateKnownBase(stocksQuery.docs, variantsQuery.docs, initialPayload, minimumMode);
  const required = requiredPackages(minimumAmountBase, currentBase, minimumMode, preferredVariantId, variantsQuery.docs, initialPayload,
    variantPackageTotals(stocksQuery.docs));
  reconcileShopping(tx, shoppingRef, currentShopping, { id, name, category: categoryName, categoryId, preferredVariantId }, required, 0, autoShopping, timestamp);
  const wasBelow = existing.exists && existing.get("autoShopping") !== false &&
    currentBase < Number(existing.get("minimumAmountBase") ?? existing.get("minimumQuantity") ?? 0);
  if (autoShopping && !wasBelow && required > 0) {
    tx.create(pantryRef.collection("notifications").doc(operationId), {
      productId: id, name, remaining: currentBase, required, createdAt: timestamp,
    });
  }
  return { revision, activity: { productId: id, displayLabel: name } };
}

async function upsertVariant(context: HandlerContext): Promise<HandlerResult> {
  const { tx, pantryRef, pantryId, payload, baseRevision, timestamp } = context;
  const raw = object(payload.variant, "variant");
  const id = safeId(text(raw, "id"), "variantId");
  const productId = safeId(text(raw, "productId"), "productId");
  const ref = pantryRef.collection("variants").doc(id);
  const productRef = pantryRef.collection("products").doc(productId);
  const [existing, product, active, allVariants, stocks, shopping] = await Promise.all([
    tx.get(ref), tx.get(productRef),
    tx.get(pantryRef.collection("variants").where("deletedAt", "==", null).limit(PANTRY_LIMITS.products + 1)),
    tx.get(pantryRef.collection("variants").where("productId", "==", productId)),
    tx.get(pantryRef.collection("stocks").where("productId", "==", productId)),
    tx.get(pantryRef.collection("shoppingItems").doc(`auto_${productId}`)),
  ]);
  if (!product.exists || product.get("deletedAt")) throw new HttpsError("not-found", "Artikl nije dostupan.");
  if (existing.exists && Number(existing.get("revision") || 0) !== baseRevision) return conflict(Number(existing.get("revision") || 0));
  if (!existing.exists || existing.get("deletedAt")) assertResourceLimit(active.size, PANTRY_LIMITS.products, "varijanti");
  if (existing.exists && existing.get("productId") !== productId) throw new HttpsError("failed-precondition", "Premještanje varijante zahtijeva posebnu operaciju.");
  const data = canonicalVariant(raw, pantryId, productId, id);
  const siblingUnits = allVariants.docs
    .filter((variant) => variant.id !== id && !variant.get("deletedAt"))
    .map((variant) => String(variant.get("packageUnit") || "UNKNOWN"));
  assertCompatibleVariantUnit(String(data.packageUnit), siblingUnits);
  assertVariantsCompatibleWithMinimum(String(product.get("minimumMode") || "PACKAGES"), [String(data.packageUnit)]);
  const oldCode = existing.exists ? (existing.get("barcode") as string | null) : null;
  const reservationRef = data.barcode ? db.doc(`barcodes/${sha256(`${pantryId}:${data.barcode}`)}`) : null;
  const oldReservationRef = oldCode && oldCode !== data.barcode ? db.doc(`barcodes/${sha256(`${pantryId}:${oldCode}`)}`) : null;
  if (reservationRef && data.barcode !== oldCode) {
    const reservation = await tx.get(reservationRef);
    if (reservation.exists && reservation.get("variantId") !== id) throw new HttpsError("already-exists", "Barkod je već povezan s drugom varijantom.");
  }
  const revision = existing.exists ? baseRevision + 1 : 1;
  tx.set(ref, { ...data, revision, createdAt: existing.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null }, { merge: true });
  if (reservationRef) tx.set(reservationRef, { pantryId, productId, variantId: id, barcode: data.barcode, updatedAt: timestamp });
  if (oldReservationRef) tx.delete(oldReservationRef);
  if (!product.get("preferredVariantId")) tx.update(productRef, { preferredVariantId: id, revision: FieldValue.increment(1), updatedAt: timestamp });
  const mode = String(product.get("minimumMode") || "PACKAGES");
  const currentBase = mode === "PACKAGES" ? stocks.docs.reduce((sum, stock) => sum + Number(stock.get("quantity") || 0), 0) :
    aggregateKnownBase(stocks.docs, allVariants.docs.filter((item) => item.id !== id), raw, mode);
  const required = requiredPackages(Number(product.get("minimumAmountBase") ?? product.get("minimumQuantity") ?? 0), currentBase, mode,
    String(product.get("preferredVariantId") || id), allVariants.docs.filter((item) => item.id !== id), raw,
    variantPackageTotals(stocks.docs));
  reconcileShopping(tx, pantryRef.collection("shoppingItems").doc(`auto_${productId}`), shopping,
    { id: productId, name: product.get("name"), category: product.get("category"), categoryId: product.get("categoryId"), preferredVariantId: product.get("preferredVariantId") || id },
    required, 0, product.get("autoShopping") !== false, timestamp);
  return { revision, activity: { productId, displayLabel: productVariantActivityLabel(product.get("name"), data.displayName) } };
}

async function moveVariant(context: HandlerContext): Promise<HandlerResult> {
  const { tx, pantryRef, payload, timestamp } = context;
  const variantId = safeId(text(payload, "variantId"), "variantId");
  const fromProductId = safeId(text(payload, "fromProductId"), "fromProductId");
  const toProductId = safeId(text(payload, "toProductId"), "toProductId");
  if (fromProductId === toProductId) throw new HttpsError("invalid-argument", "Odredišni artikl mora biti različit.");
  const expected = integer(payload, "expectedGroupingRevision", 0);
  const variantRef = pantryRef.collection("variants").doc(variantId);
  const sourceRef = pantryRef.collection("products").doc(fromProductId);
  const targetRef = pantryRef.collection("products").doc(toProductId);
  const sourceShoppingRef = pantryRef.collection("shoppingItems").doc(`auto_${fromProductId}`);
  const targetShoppingRef = pantryRef.collection("shoppingItems").doc(`auto_${toProductId}`);
  const [variant, source, target, sourceVariants, targetVariants, variantStocks, sourceStocks, targetStocks, sourceShopping, targetShopping] = await Promise.all([
    tx.get(variantRef), tx.get(sourceRef), tx.get(targetRef),
    tx.get(pantryRef.collection("variants").where("productId", "==", fromProductId).where("deletedAt", "==", null)),
    tx.get(pantryRef.collection("variants").where("productId", "==", toProductId).where("deletedAt", "==", null)),
    tx.get(pantryRef.collection("stocks").where("variantId", "==", variantId)),
    tx.get(pantryRef.collection("stocks").where("productId", "==", fromProductId)),
    tx.get(pantryRef.collection("stocks").where("productId", "==", toProductId)),
    tx.get(sourceShoppingRef), tx.get(targetShoppingRef),
  ]);
  if (!variant.exists || variant.get("deletedAt") || variant.get("productId") !== fromProductId) throw new HttpsError("not-found", "Varijanta nije dostupna u očekivanom artiklu.");
  if (!source.exists || source.get("deletedAt") || !target.exists || target.get("deletedAt")) throw new HttpsError("failed-precondition", "Izvorni ili odredišni artikl nije dostupan.");
  const movedUnit = String(variant.get("packageUnit") || "UNKNOWN");
  assertCompatibleVariantUnit(movedUnit, targetVariants.docs.map((item) => String(item.get("packageUnit") || "UNKNOWN")));
  assertVariantsCompatibleWithMinimum(String(target.get("minimumMode") || "PACKAGES"), [movedUnit]);
  if (Number(source.get("groupingRevision") || 0) !== expected) return conflict(Number(source.get("groupingRevision") || 0));
  const sourceRevision = expected + 1;
  const targetRevision = Number(target.get("groupingRevision") || 0) + 1;
  const remainingVariants = sourceVariants.docs.filter((item) => item.id !== variantId);
  const movedVariantData: Data = {
    id: variantId,
    packageAmountBase: variant.get("packageAmountBase"),
    packageUnit: variant.get("packageUnit"),
    purchaseCount: variant.get("purchaseCount"),
    minimumPackages: variant.get("minimumPackages"),
  };
  const sourceTotals = variantPackageTotals(sourceStocks.docs.filter((stock) => stock.get("variantId") !== variantId));
  const targetTotals = variantPackageTotals(targetStocks.docs);
  variantStocks.docs.forEach((stock) => targetTotals.set(variantId, (targetTotals.get(variantId) || 0) + Number(stock.get("quantity") || 0)));
  const sourcePreferred = remainingVariants.slice().sort((left, right) =>
    Number(right.get("purchaseCount") || 0) - Number(left.get("purchaseCount") || 0) || left.id.localeCompare(right.id))[0]?.id || null;
  const targetPreferred = [...targetVariants.docs, variant].slice().sort((left, right) =>
    Number(right.get("purchaseCount") || 0) - Number(left.get("purchaseCount") || 0) || left.id.localeCompare(right.id))[0]?.id || variantId;
  const sourceMode = String(source.get("minimumMode") || "PACKAGES");
  const targetMode = String(target.get("minimumMode") || "PACKAGES");
  const sourceCurrent = sourceMode === "PACKAGES"
    ? [...sourceTotals.values()].reduce((sum, value) => sum + value, 0)
    : aggregateKnownBase(sourceStocks.docs.filter((stock) => stock.get("variantId") !== variantId), remainingVariants, null, sourceMode);
  const targetCurrent = targetMode === "PACKAGES"
    ? [...targetTotals.values()].reduce((sum, value) => sum + value, 0)
    : aggregateKnownBase([...targetStocks.docs, ...variantStocks.docs], targetVariants.docs, movedVariantData, targetMode);
  const sourceRequired = requiredPackages(Number(source.get("minimumAmountBase") ?? source.get("minimumQuantity") ?? 0), sourceCurrent,
    sourceMode, sourcePreferred, remainingVariants, null, sourceTotals);
  const targetRequired = requiredPackages(Number(target.get("minimumAmountBase") ?? target.get("minimumQuantity") ?? 0), targetCurrent,
    targetMode, targetPreferred, targetVariants.docs, movedVariantData, targetTotals);
  tx.update(variantRef, { productId: toProductId, revision: FieldValue.increment(1), updatedAt: timestamp });
  variantStocks.docs.forEach((stock) => tx.update(stock.ref, { productId: toProductId, updatedAt: timestamp }));
  tx.update(sourceRef, {
    groupingRevision: sourceRevision, preferredVariantId: sourcePreferred, revision: FieldValue.increment(1), updatedAt: timestamp,
    ...(sourceVariants.size === 1 ? { deletedAt: timestamp, purgeAfter: daysFromNow(30) } : {}),
  });
  tx.update(targetRef, { groupingRevision: targetRevision, preferredVariantId: targetPreferred, revision: FieldValue.increment(1), updatedAt: timestamp });
  if (remainingVariants.length === 0) {
    if (sourceShopping.exists) tx.update(sourceShoppingRef, { deletedAt: timestamp, requiredQuantity: 0, updatedAt: timestamp, revision: FieldValue.increment(1) });
  } else {
    reconcileShopping(tx, sourceShoppingRef, sourceShopping, {
      id: fromProductId, name: source.get("name"), category: source.get("category"), categoryId: source.get("categoryId"), preferredVariantId: sourcePreferred,
    }, sourceRequired, 0, source.get("autoShopping") !== false, timestamp);
  }
  reconcileShopping(tx, targetShoppingRef, targetShopping, {
    id: toProductId, name: target.get("name"), category: target.get("category"), categoryId: target.get("categoryId"), preferredVariantId: targetPreferred,
  }, targetRequired, 0, target.get("autoShopping") !== false, timestamp);
  return { revision: sourceRevision, activity: { productId: toProductId, displayLabel: serverActivityLabel(variant.get("displayName"), "Varijanta"), oldValue: serverActivityLabel(source.get("name"), "Artikl"), newValue: serverActivityLabel(target.get("name"), "Artikl") } };
}

async function splitVariant(context: HandlerContext): Promise<HandlerResult> {
  const { tx, pantryRef, payload, timestamp } = context;
  const variantId = safeId(text(payload, "variantId"), "variantId");
  const fromProductId = safeId(text(payload, "fromProductId"), "fromProductId");
  const rawProduct = object(payload.newProduct, "newProduct");
  const newProductId = safeId(text(rawProduct, "id"), "newProductId");
  const expected = integer(payload, "expectedGroupingRevision", 0);
  if (newProductId === fromProductId) throw new HttpsError("invalid-argument", "Novi artikl mora imati novi ID.");
  const name = text(rawProduct, "name", 1, 100);
  const normalized = normalizedName(name);
  const variantRef = pantryRef.collection("variants").doc(variantId);
  const sourceRef = pantryRef.collection("products").doc(fromProductId);
  const newProductRef = pantryRef.collection("products").doc(newProductId);
  const sourceShoppingRef = pantryRef.collection("shoppingItems").doc(`auto_${fromProductId}`);
  const newShoppingRef = pantryRef.collection("shoppingItems").doc(`auto_${newProductId}`);
  const [variant, source, newProduct, sourceVariants, variantStocks, sourceStocks, sourceShopping, newShopping, activeProducts, sameName] = await Promise.all([
    tx.get(variantRef), tx.get(sourceRef), tx.get(newProductRef),
    tx.get(pantryRef.collection("variants").where("productId", "==", fromProductId).where("deletedAt", "==", null)),
    tx.get(pantryRef.collection("stocks").where("variantId", "==", variantId)),
    tx.get(pantryRef.collection("stocks").where("productId", "==", fromProductId)),
    tx.get(sourceShoppingRef), tx.get(newShoppingRef),
    tx.get(pantryRef.collection("products").where("deletedAt", "==", null).limit(PANTRY_LIMITS.products + 1)),
    tx.get(pantryRef.collection("products").where("normalizedName", "==", normalized).where("deletedAt", "==", null).limit(1)),
  ]);
  if (!variant.exists || variant.get("deletedAt") || variant.get("productId") !== fromProductId) {
    throw new HttpsError("not-found", "Varijanta nije dostupna u očekivanom artiklu.");
  }
  if (!source.exists || source.get("deletedAt")) throw new HttpsError("not-found", "Izvorni artikl nije dostupan.");
  if (newProduct.exists) throw new HttpsError("already-exists", "Novi artikl već postoji.");
  if (sourceVariants.size <= 1) throw new HttpsError("failed-precondition", "Posljednja varijanta ne može se izdvojiti.");
  if (Number(source.get("groupingRevision") || 0) !== expected) return conflict(Number(source.get("groupingRevision") || 0));
  assertResourceLimit(activeProducts.size, PANTRY_LIMITS.products, "artikala");
  if (!sameName.empty) throw new HttpsError("already-exists", "Generički artikl s tim nazivom već postoji.");

  const remainingVariants = sourceVariants.docs.filter((item) => item.id !== variantId);
  const remainingStocks = sourceStocks.docs.filter((item) => item.get("variantId") !== variantId);
  const preferredVariantId = remainingVariants
    .slice()
    .sort((left, right) => Number(right.get("purchaseCount") || 0) - Number(left.get("purchaseCount") || 0) || left.id.localeCompare(right.id))[0]!.id;
  const sourceMode = String(source.get("minimumMode") || "PACKAGES");
  const sourceCurrent = sourceMode === "PACKAGES"
    ? remainingStocks.reduce((sum, stock) => sum + Number(stock.get("quantity") || 0), 0)
    : aggregateKnownBase(remainingStocks, remainingVariants, null, sourceMode);
  const sourceRequired = requiredPackages(
    Number(source.get("minimumAmountBase") ?? source.get("minimumQuantity") ?? 0),
    sourceCurrent,
    sourceMode,
    preferredVariantId,
    remainingVariants,
    null,
    variantPackageTotals(remainingStocks),
  );

  tx.create(newProductRef, {
    name, normalizedName: normalized,
    barcode: null, description: "",
    category: source.get("category"), categoryId: source.get("categoryId"),
    photoUrl: null, photoSource: "NONE",
    minimumQuantity: 0, minimumMode: "PACKAGES", minimumAmountBase: 0,
    preferredVariantId: variantId, doNotGroup: false, groupingRevision: 0,
    autoShopping: false, revision: 1,
    createdAt: timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
  });
  tx.update(variantRef, { productId: newProductId, revision: FieldValue.increment(1), updatedAt: timestamp });
  variantStocks.docs.forEach((stock) => tx.update(stock.ref, { productId: newProductId, updatedAt: timestamp }));
  tx.update(sourceRef, {
    preferredVariantId,
    groupingRevision: expected + 1,
    revision: FieldValue.increment(1),
    updatedAt: timestamp,
  });
  reconcileShopping(tx, sourceShoppingRef, sourceShopping, {
    id: fromProductId,
    name: source.get("name"),
    category: source.get("category"),
    categoryId: source.get("categoryId"),
    preferredVariantId,
  }, sourceRequired, 0, source.get("autoShopping") !== false, timestamp);
  if (newShopping.exists) tx.update(newShoppingRef, { deletedAt: timestamp, updatedAt: timestamp });
  return {
    revision: expected + 1,
    activity: {
      productId: newProductId,
      displayLabel: serverActivityLabel(variant.get("displayName"), "Varijanta"),
      oldValue: serverActivityLabel(source.get("name"), "Artikl"),
      newValue: name,
    },
  };
}

async function upsertSynonymRule({ tx, pantryRef, payload, baseRevision, memberRole, timestamp }: HandlerContext): Promise<HandlerResult> {
  if (memberRole !== "OWNER") throw new HttpsError("permission-denied", "Samo vlasnik smije mijenjati zajednički rječnik.");
  const raw = object(payload.rule, "rule");
  const id = safeId(text(raw, "id"));
  const ref = pantryRef.collection("synonymRules").doc(id);
  const existing = await tx.get(ref);
  if (existing.exists && Number(existing.get("revision") || 0) !== baseRevision) return conflict(Number(existing.get("revision") || 0));
  const sourceNormalized = normalizedName(text(raw, "sourceNormalized", 1, 100));
  const genericName = text(raw, "genericName", 1, 100);
  const productId = raw.productId == null ? null : safeId(text(raw, "productId"));
  if (productId) {
    const product = await tx.get(pantryRef.collection("products").doc(productId));
    if (!product.exists || product.get("deletedAt")) throw new HttpsError("failed-precondition", "Artikl pravila nije dostupan.");
  }
  const revision = existing.exists ? baseRevision + 1 : 1;
  tx.set(ref, { sourceNormalized, genericName, genericNameNormalized: normalizedName(genericName), productId,
    ownerConfirmed: true, revision, updatedAt: timestamp, deletedAt: null }, { merge: true });
  return revision;
}

async function setDoNotGroup({ tx, pantryRef, payload, memberRole, timestamp }: HandlerContext): Promise<HandlerResult> {
  if (memberRole !== "OWNER") throw new HttpsError("permission-denied", "Samo vlasnik smije mijenjati zajednička pravila grupiranja.");
  const productId = safeId(text(payload, "productId"));
  const expected = integer(payload, "expectedGroupingRevision", 0);
  const ref = pantryRef.collection("products").doc(productId);
  const product = await tx.get(ref);
  if (!product.exists || product.get("deletedAt")) throw new HttpsError("not-found", "Artikl nije dostupan.");
  if (Number(product.get("groupingRevision") || 0) !== expected) return conflict(Number(product.get("groupingRevision") || 0));
  const revision = expected + 1;
  tx.update(ref, { doNotGroup: boolean(payload, "doNotGroup"), groupingRevision: revision, revision: FieldValue.increment(1), updatedAt: timestamp });
  return revision;
}

async function adjustStock(context: HandlerContext): Promise<HandlerResult> {
  const { tx, pantryRef, payload, operationId, timestamp } = context;
  const productId = safeId(text(payload, "productId"));
  const variantId = typeof payload.variantId === "string" ? safeId(payload.variantId, "variantId") : productId;
  const shelfId = safeId(text(payload, "shelfId"));
  const delta = integerSigned(payload, "delta", -1_000_000, 1_000_000);
  if (delta === 0) throw new HttpsError("invalid-argument", "Promjena količine ne može biti nula.");
  const productRef = pantryRef.collection("products").doc(productId);
  const variantRef = pantryRef.collection("variants").doc(variantId);
  const shelfRef = pantryRef.collection("shelves").doc(shelfId);
  const stockRef = pantryRef.collection("stocks").doc(`${variantId}_${shelfId}`);
  const shoppingRef = pantryRef.collection("shoppingItems").doc(`auto_${productId}`);
  const [product, variant, shelf, stock, shopping, allStocks, allVariants] = await Promise.all([
    tx.get(productRef), tx.get(variantRef), tx.get(shelfRef), tx.get(stockRef), tx.get(shoppingRef),
    tx.get(pantryRef.collection("stocks").where("productId", "==", productId)),
    tx.get(pantryRef.collection("variants").where("productId", "==", productId)),
  ]);
  if (!product.exists || product.get("deletedAt")) throw new HttpsError("not-found", "Artikl nije dostupan.");
  if (!variant.exists || variant.get("deletedAt") || variant.get("productId") !== productId) throw new HttpsError("not-found", "Varijanta nije dostupna.");
  if (!shelf.exists || shelf.get("deletedAt")) throw new HttpsError("not-found", "Polica nije dostupna.");
  const previousOnShelf = Number(stock.get("quantity") || 0);
  const previousTotal = allStocks.docs.reduce((sum, row) => sum + Number(row.get("quantity") || 0), 0);
  if (previousOnShelf + delta < 0 || previousTotal + delta < 0) throw new HttpsError("failed-precondition", "Nije moguće izvaditi više od dostupne količine.");
  const revision = Number(stock.get("revision") || 0) + 1;
  const total = previousTotal + delta;
  const updatedPurchaseCount = Number(variant.get("purchaseCount") || 0) + Math.max(delta, 0);
  const preferredVariantId = delta > 0
    ? [...allVariants.docs.filter((item) => !item.get("deletedAt"))]
      .sort((left, right) => {
        const leftCount = left.id === variantId ? updatedPurchaseCount : Number(left.get("purchaseCount") || 0);
        const rightCount = right.id === variantId ? updatedPurchaseCount : Number(right.get("purchaseCount") || 0);
        return rightCount - leftCount || left.id.localeCompare(right.id);
      })[0]?.id || variantId
    : String(product.get("preferredVariantId") || variantId);
  tx.set(stockRef, { productId, variantId, shelfId, quantity: previousOnShelf + delta, revision, updatedAt: timestamp }, { merge: true });
  tx.update(productRef, { preferredVariantId, totalQuantity: FieldValue.delete(), revision: FieldValue.increment(1), updatedAt: timestamp });
  if (delta > 0) tx.update(variantRef, { purchaseCount: FieldValue.increment(delta), revision: FieldValue.increment(1), updatedAt: timestamp });
  const minimum = Number(product.get("minimumAmountBase") ?? product.get("minimumQuantity") ?? 0);
  const minimumMode = String(product.get("minimumMode") || "PACKAGES");
  const autoShopping = product.get("autoShopping") !== false;
  const previousBase = minimumMode === "PACKAGES" ? previousTotal : aggregateKnownBase(allStocks.docs, allVariants.docs, null, minimumMode);
  const packageBase = Number(variant.get("packageAmountBase") || 0);
  const currentBase = minimumMode === "PACKAGES" ? total : previousBase + delta * packageBase;
  const adjustedVariantTotals = variantPackageTotals(allStocks.docs);
  adjustedVariantTotals.set(variantId, (adjustedVariantTotals.get(variantId) || 0) + delta);
  const required = requiredPackages(minimum, currentBase, minimumMode, preferredVariantId, allVariants.docs, null, adjustedVariantTotals);
  reconcileShopping(tx, shoppingRef, shopping, {
    id: productId, name: product.get("name"), category: product.get("category"), categoryId: product.get("categoryId"), preferredVariantId,
  }, required, 0, autoShopping, timestamp);
  if (autoShopping && previousBase >= minimum && currentBase < minimum) {
    tx.create(pantryRef.collection("notifications").doc(operationId), {
      productId, name: product.get("name"), remaining: currentBase, required, createdAt: timestamp,
    });
  }
  return {
    revision,
    activity: {
      productId,
      shelfId,
      displayLabel: productVariantActivityLabel(product.get("name"), variant.get("displayName")),
      oldValue: serverActivityLabel(shelf.get("name"), "Polica"),
      newValue: serverActivityLabel(shelf.get("name"), "Polica"),
    },
  };
}

async function moveStock({ tx, pantryRef, payload, timestamp }: HandlerContext): Promise<HandlerResult> {
  const productId = safeId(text(payload, "productId"));
  const variantId = typeof payload.variantId === "string" ? safeId(payload.variantId, "variantId") : productId;
  const fromShelfId = safeId(text(payload, "fromShelfId"));
  const toShelfId = safeId(text(payload, "toShelfId"));
  const quantity = integer(payload, "quantity", 1, 1_000_000);
  if (fromShelfId === toShelfId) throw new HttpsError("invalid-argument", "Police moraju biti različite.");
  const productRef = pantryRef.collection("products").doc(productId);
  const variantRef = pantryRef.collection("variants").doc(variantId);
  const sourceShelfRef = pantryRef.collection("shelves").doc(fromShelfId);
  const targetShelfRef = pantryRef.collection("shelves").doc(toShelfId);
  const fromRef = pantryRef.collection("stocks").doc(`${variantId}_${fromShelfId}`);
  const toRef = pantryRef.collection("stocks").doc(`${variantId}_${toShelfId}`);
  const [product, variant, sourceShelf, targetShelf, from, to] = await Promise.all([
    tx.get(productRef), tx.get(variantRef), tx.get(sourceShelfRef), tx.get(targetShelfRef), tx.get(fromRef), tx.get(toRef),
  ]);
  if (!product.exists || product.get("deletedAt")) throw new HttpsError("not-found", "Artikl nije dostupan.");
  if (!variant.exists || variant.get("deletedAt") || variant.get("productId") !== productId) throw new HttpsError("not-found", "Varijanta nije dostupna.");
  if (!sourceShelf.exists || sourceShelf.get("deletedAt")) throw new HttpsError("not-found", "Izvorna polica nije dostupna.");
  if (!targetShelf.exists || targetShelf.get("deletedAt")) throw new HttpsError("not-found", "Odredišna polica nije dostupna.");
  if (!from.exists || Number(from.get("quantity") || 0) < quantity) throw new HttpsError("failed-precondition", "Nema dovoljno zalihe na izvornoj polici.");
  const revision = Math.max(Number(from.get("revision") || 0), Number(to.get("revision") || 0)) + 1;
  tx.update(fromRef, { quantity: Number(from.get("quantity")) - quantity, revision, updatedAt: timestamp });
  tx.set(toRef, { productId, variantId, shelfId: toShelfId, quantity: Number(to.get("quantity") || 0) + quantity, revision, updatedAt: timestamp }, { merge: true });
  return {
    revision,
    activity: {
      productId,
      fromShelfId,
      toShelfId,
      displayLabel: productVariantActivityLabel(product.get("name"), variant.get("displayName")),
      oldValue: serverActivityLabel(sourceShelf.get("name"), "Izvorna polica"),
      newValue: serverActivityLabel(targetShelf.get("name"), "Odredišna polica"),
    },
  };
}

async function bulkChangeProductCategory({ tx, pantryRef, payload, timestamp }: HandlerContext): Promise<HandlerResult> {
  const productIds = bulkProductIds(payload);
  const categoryId = safeId(text(payload, "categoryId"), "categoryId");
  const categoryRef = pantryRef.collection("categories").doc(categoryId);
  const productRefs = productIds.map((id) => pantryRef.collection("products").doc(id));
  const shoppingRefs = productIds.map((id) => pantryRef.collection("shoppingItems").doc(`auto_${id}`));
  const [category, productDocs, shoppingDocs] = await Promise.all([
    tx.get(categoryRef), tx.getAll(...productRefs), tx.getAll(...shoppingRefs),
  ]);
  if (!category.exists || category.get("deletedAt")) {
    throw new HttpsError("failed-precondition", "Odabrana kategorija nije aktivna.");
  }
  productDocs.forEach((product) => {
    if (!product.exists || product.get("deletedAt")) {
      throw new HttpsError("failed-precondition", "Jedan od odabranih artikala više nije dostupan.");
    }
  });
  const categoryName = serverActivityLabel(category.get("name"), "Kategorija");
  let revision = 0;
  const activities: ActivityMetadata[] = [];
  productDocs.forEach((product, index) => {
    const nextRevision = Number(product.get("revision") || 0) + 1;
    revision = Math.max(revision, nextRevision);
    tx.update(productRefs[index]!, { categoryId, category: categoryName, revision: nextRevision, updatedAt: timestamp });
    if (shoppingDocs[index]!.exists) {
      tx.update(shoppingRefs[index]!, { categoryId, category: categoryName, revision: FieldValue.increment(1), updatedAt: timestamp });
    }
    activities.push({
      productId: productIds[index],
      displayLabel: serverActivityLabel(product.get("name"), "Artikl"),
      oldValue: serverActivityLabel(product.get("category"), "Kategorija"),
      newValue: categoryName,
    });
  });
  return { revision, activities };
}

async function bulkDeleteProducts({ tx, pantryRef, payload, timestamp }: HandlerContext): Promise<HandlerResult> {
  const productIds = bulkProductIds(payload);
  const productRefs = productIds.map((id) => pantryRef.collection("products").doc(id));
  const shoppingRefs = productIds.map((id) => pantryRef.collection("shoppingItems").doc(`auto_${id}`));
  const [productDocs, shoppingDocs, variantQueries] = await Promise.all([
    tx.getAll(...productRefs), tx.getAll(...shoppingRefs),
    Promise.all(productIds.map((id) => tx.get(pantryRef.collection("variants").where("productId", "==", id)))),
  ]);
  productDocs.forEach((product) => {
    if (!product.exists || product.get("deletedAt")) {
      throw new HttpsError("failed-precondition", "Jedan od odabranih artikala više nije dostupan.");
    }
  });
  let revision = 0;
  const activities: ActivityMetadata[] = [];
  productDocs.forEach((product, index) => {
    const nextRevision = Number(product.get("revision") || 0) + 1;
    revision = Math.max(revision, nextRevision);
    tx.update(productRefs[index]!, {
      deletedAt: timestamp,
      purgeAfter: daysFromNow(30),
      revision: nextRevision,
      updatedAt: timestamp,
    });
    variantQueries[index]!.docs.forEach((variant) => tx.update(variant.ref, {
      deletedAt: timestamp, purgeAfter: daysFromNow(30), revision: FieldValue.increment(1), updatedAt: timestamp,
    }));
    if (shoppingDocs[index]!.exists) {
      tx.update(shoppingRefs[index]!, { deletedAt: timestamp, updatedAt: timestamp });
    }
    activities.push({
      productId: productIds[index],
      displayLabel: serverActivityLabel(product.get("name"), "Artikl"),
    });
  });
  return { revision, activities };
}

async function bulkMoveStock({ tx, pantryRef, payload, timestamp }: HandlerContext): Promise<HandlerResult> {
  const rawMoves = array(payload.moves, "moves");
  if (rawMoves.length < 1 || rawMoves.length > 100) {
    throw new HttpsError("invalid-argument", "Skupna radnja mora sadržavati između 1 i 100 artikala.");
  }
  const moves = rawMoves.map((value) => {
    const move = object(value, "move");
    const productId = safeId(text(move, "productId"));
    const variantId = typeof move.variantId === "string" ? safeId(move.variantId, "variantId") : productId;
    const fromShelfId = safeId(text(move, "fromShelfId"));
    const toShelfId = safeId(text(move, "toShelfId"));
    if (fromShelfId === toShelfId) throw new HttpsError("invalid-argument", "Police moraju biti različite.");
    return { productId, variantId, fromShelfId, toShelfId, quantity: integer(move, "quantity", 1, 1_000_000) };
  });
  if (new Set(moves.map((move) => `${move.variantId}:${move.fromShelfId}`)).size !== moves.length) {
    throw new HttpsError("invalid-argument", "Ista varijanta ne smije biti premještena više puta s iste police.");
  }
  const shelfIds = [...new Set(moves.flatMap((move) => [move.fromShelfId, move.toShelfId]))];
  const shelfRefs = shelfIds.map((id) => pantryRef.collection("shelves").doc(id));
  const productRefs = moves.map((move) => pantryRef.collection("products").doc(move.productId));
  const variantRefs = moves.map((move) => pantryRef.collection("variants").doc(move.variantId));
  const sourceRefs = moves.map((move) => pantryRef.collection("stocks").doc(`${move.variantId}_${move.fromShelfId}`));
  const targetRefs = moves.map((move) => pantryRef.collection("stocks").doc(`${move.variantId}_${move.toShelfId}`));
  const [shelfDocs, productDocs, variantDocs, sources, targets] = await Promise.all([
    tx.getAll(...shelfRefs), tx.getAll(...productRefs), tx.getAll(...variantRefs), tx.getAll(...sourceRefs), tx.getAll(...targetRefs),
  ]);
  const shelvesById = new Map(shelfDocs.map((shelf) => [shelf.id, shelf]));
  shelfDocs.forEach((shelf) => {
    if (!shelf.exists || shelf.get("deletedAt")) throw new HttpsError("failed-precondition", "Jedna od odabranih polica nije dostupna.");
  });
  moves.forEach((move, index) => {
    const product = productDocs[index]!;
    const variant = variantDocs[index]!;
    const source = sources[index]!;
    const target = targets[index]!;
    if (!product.exists || product.get("deletedAt")) {
      throw new HttpsError("failed-precondition", "Jedan od odabranih artikala više nije dostupan.");
    }
    if (!variant.exists || variant.get("deletedAt") || variant.get("productId") !== move.productId) {
      throw new HttpsError("failed-precondition", "Jedna od odabranih varijanti više nije dostupna.");
    }
    if (!source.exists || Number(source.get("quantity") || 0) < move.quantity) {
      throw new HttpsError("failed-precondition", "Jedan od artikala nema dovoljnu količinu na izvornoj polici.");
    }
    const targetQuantity = Number(target.get("quantity") || 0) + move.quantity;
    if (!Number.isSafeInteger(targetQuantity) || targetQuantity > 1_000_000) {
      throw new HttpsError("resource-exhausted", "Količina na odredišnoj polici je previsoka.");
    }
  });
  let revision = 0;
  const activities: ActivityMetadata[] = [];
  moves.forEach((move, index) => {
    const source = sources[index]!;
    const target = targets[index]!;
    const nextRevision = Math.max(Number(source.get("revision") || 0), Number(target.get("revision") || 0)) + 1;
    revision = Math.max(revision, nextRevision);
    tx.update(sourceRefs[index]!, { quantity: Number(source.get("quantity")) - move.quantity, revision: nextRevision, updatedAt: timestamp });
    tx.set(targetRefs[index]!, {
      productId: move.productId,
      variantId: move.variantId,
      shelfId: move.toShelfId,
      quantity: Number(target.get("quantity") || 0) + move.quantity,
      revision: nextRevision,
      updatedAt: timestamp,
    }, { merge: true });
    activities.push({
      productId: move.productId,
      fromShelfId: move.fromShelfId,
      toShelfId: move.toShelfId,
      displayLabel: productVariantActivityLabel(productDocs[index]!.get("name"), variantDocs[index]!.get("displayName")),
      oldValue: serverActivityLabel(shelvesById.get(move.fromShelfId)?.get("name"), "Izvorna polica"),
      newValue: serverActivityLabel(shelvesById.get(move.toShelfId)?.get("name"), "Odredišna polica"),
      quantityDelta: move.quantity,
    });
  });
  return { revision, activities };
}

function bulkProductIds(payload: Data): string[] {
  const rawIds = array(payload.productIds, "productIds");
  if (rawIds.length < 1 || rawIds.length > 100) {
    throw new HttpsError("invalid-argument", "Skupna radnja mora sadržavati između 1 i 100 artikala.");
  }
  const ids = rawIds.map((value) => safeId(typeof value === "string" ? value : ""));
  if (new Set(ids).size !== ids.length) {
    throw new HttpsError("invalid-argument", "Isti artikl ne smije biti odabran više puta.");
  }
  return ids;
}

async function upsertShopping({ tx, pantryRef, pantryId, payload, baseRevision, timestamp }: HandlerContext): Promise<HandlerResult> {
  const item = object(payload.item, "item");
  const id = safeId(text(item, "id"));
  const ref = pantryRef.collection("shoppingItems").doc(id);
  const existing = await tx.get(ref);
  const manual = boolean(item, "manual");
  if (!manual) {
    const productId = safeId(text(item, "productId"));
    if (id !== `auto_${productId}` || !existing.exists || existing.get("manual") !== false || existing.get("deletedAt")) {
      throw new HttpsError("failed-precondition", "Automatsku stavku stvara i količinski održava samo poslužitelj.");
    }
    const automaticRevision = Number(existing.get("revision") || 0) + 1;
    tx.update(ref, { checked: boolean(item, "checked"), revision: automaticRevision, updatedAt: timestamp });
    return automaticRevision;
  }
  if (item.productId !== null && item.productId !== undefined) {
    throw new HttpsError("invalid-argument", "Ručna stavka ne smije biti povezana s artiklom.");
  }
  const categoryId = safeId(text(item, "categoryId"), "categoryId");
  const activeCategory = await tx.get(pantryRef.collection("categories").doc(categoryId));
  if (!activeCategory.exists || activeCategory.get("deletedAt")) throw new HttpsError("failed-precondition", "Odabrana kategorija nije aktivna.");
  const name = text(item, "name", 1, 100);
  const deltaValue = payload.quantityDelta;
  if (deltaValue !== null && deltaValue !== undefined) {
    const delta = integer(payload, "quantityDelta", 1, 1_000_000);
    const expectedId = manualShoppingId(pantryId, categoryId, name);
    if (!existing.exists && id !== expectedId) {
      throw new HttpsError("invalid-argument", "Nova ručna stavka nema kanonski identitet.");
    }
    if (existing.exists && existing.get("manual") !== true) {
      throw new HttpsError("failed-precondition", "Stavka nije ručna stavka za kupnju.");
    }
    if (existing.exists && !existing.get("deletedAt")) {
      if (String(existing.get("categoryId") || "") !== categoryId || normalizedName(String(existing.get("name") || "")) !== normalizedName(name)) {
        throw new HttpsError("failed-precondition", "Identitet ručne stavke nije usklađen.");
      }
    }
    const existingActive = existing.exists && !existing.get("deletedAt");
    const requiredQuantity = (existingActive ? Number(existing.get("requiredQuantity") || 0) : 0) + delta;
    if (!Number.isSafeInteger(requiredQuantity) || requiredQuantity > 1_000_000) {
      throw new HttpsError("resource-exhausted", "Ukupna količina je previsoka.");
    }
    const revision = existing.exists ? Number(existing.get("revision") || 0) + 1 : 1;
    const displayName = existingActive ? String(existing.get("name")) : name;
    tx.set(ref, {
      productId: null,
      name: displayName, categoryId, category: activeCategory.get("name"),
      requiredQuantity, checked: existingActive ? false : boolean(item, "checked"), manual: true, revision,
      createdAt: existing.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
    }, { merge: true });
    return { revision, activity: { displayLabel: displayName } };
  }
  if (existing.exists && Number(existing.get("revision") || 0) !== baseRevision) return conflict(Number(existing.get("revision") || 0));
  const revision = existing.exists ? baseRevision + 1 : 1;
  tx.set(ref, {
    productId: null,
    name, categoryId, category: activeCategory.get("name"),
    requiredQuantity: integer(item, "requiredQuantity", 1, 1_000_000),
    checked: boolean(item, "checked"), manual: true, revision,
    createdAt: existing.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null,
  }, { merge: true });
  return { revision, activity: { displayLabel: name } };
}

function manualShoppingId(pantryId: string, categoryId: string, name: string): string {
  return `manual_${sha256(`${pantryId}\u0000${categoryId}\u0000${normalizedName(name)}`)}`;
}

async function deleteShopping({ tx, pantryRef, payload, baseRevision, timestamp }: HandlerContext): Promise<HandlerResult> {
  const itemId = safeId(text(payload, "itemId"));
  const ref = pantryRef.collection("shoppingItems").doc(itemId);
  const item = await tx.get(ref);
  assertRevision(item, baseRevision, "Stavka za kupnju");
  if (item.get("manual") !== true) {
    throw new HttpsError("failed-precondition", "Automatska stavka uklanja se promjenom stvarnog manjka.");
  }
  const revision = baseRevision + 1;
  tx.update(ref, {
    deletedAt: timestamp,
    purgeAfter: daysFromNow(30),
    updatedAt: timestamp,
    revision,
  });
  return { revision, activity: { displayLabel: serverActivityLabel(item.get("name"), "Stavka kupnje") } };
}

async function applyInventory({ tx, pantryRef, payload, baseRevision, operationId, uid, deviceId, deviceDisplayName, timestamp }: HandlerContext): Promise<HandlerResult> {
  const session = object(payload.session, "session");
  const shelfId = safeId(text(session, "shelfId"));
  const differences = session.differences;
  if (!Array.isArray(differences) || differences.length > 300) throw new HttpsError("invalid-argument", "Inventurne razlike nisu ispravne.");
  if (differences.length === 0) throw new HttpsError("failed-precondition", "Inventura nema promjena za primjenu.");
  const entries = differences.map((value) => {
    const difference = object(value, "difference");
    const productId = safeId(text(difference, "productId"));
    const variantId = typeof difference.variantId === "string" ? safeId(difference.variantId, "variantId") : productId;
    return { productId, variantId, actual: integer(difference, "actualQuantity", 0, 1_000_000) };
  });
  if (new Set(entries.map((entry) => entry.variantId)).size !== entries.length) {
    throw new HttpsError("invalid-argument", "Varijanta je u inventuri navedena više puta.");
  }
  const productIds = [...new Set(entries.map((entry) => entry.productId))];
  const stockRefs = entries.map((entry) => pantryRef.collection("stocks").doc(`${entry.variantId}_${shelfId}`));
  const variantRefs = entries.map((entry) => pantryRef.collection("variants").doc(entry.variantId));
  const productRefs = productIds.map((id) => pantryRef.collection("products").doc(id));
  const shoppingRefs = productIds.map((id) => pantryRef.collection("shoppingItems").doc(`auto_${id}`));
  const [shelf, shelfStocks, stockDocs, variantDocs, productDocs, shoppingDocs, productStockQueries, productVariantQueries] = await Promise.all([
    tx.get(pantryRef.collection("shelves").doc(shelfId)),
    tx.get(pantryRef.collection("stocks").where("shelfId", "==", shelfId)),
    tx.getAll(...stockRefs), tx.getAll(...variantRefs), tx.getAll(...productRefs), tx.getAll(...shoppingRefs),
    Promise.all(productIds.map((id) => tx.get(pantryRef.collection("stocks").where("productId", "==", id)))),
    Promise.all(productIds.map((id) => tx.get(pantryRef.collection("variants").where("productId", "==", id)))),
  ]);
  if (!shelf.exists || shelf.get("deletedAt")) throw new HttpsError("failed-precondition", "Polica inventure više nije dostupna.");
  const currentRevision = inventorySnapshotVersion(shelfStocks.docs.map((doc) => ({
    variantId: String(doc.get("variantId") || doc.get("productId")), quantity: Number(doc.get("quantity") || 0),
  })));
  if (currentRevision !== baseRevision) return conflict(currentRevision);
  entries.forEach((entry, index) => {
    const stock = stockDocs[index]!;
    const variant = variantDocs[index]!;
    if (!variant.exists || variant.get("deletedAt") || variant.get("productId") !== entry.productId) {
      throw new HttpsError("failed-precondition", "Varijanta u inventuri više nije dostupna.");
    }
    tx.set(stockRefs[index]!, {
      productId: entry.productId, variantId: entry.variantId, shelfId, quantity: entry.actual,
      revision: Number(stock.get("revision") || 0) + 1, updatedAt: timestamp,
    }, { merge: true });
  });
  productIds.forEach((productId, productIndex) => {
    const product = productDocs[productIndex]!;
    if (!product.exists || product.get("deletedAt")) throw new HttpsError("failed-precondition", "Artikl u inventuri više nije dostupan.");
    const productStocks = productStockQueries[productIndex]!.docs;
    const productVariants = productVariantQueries[productIndex]!.docs;
    const changed = entries.map((entry, index) => ({ entry, index })).filter(({ entry }) => entry.productId === productId);
    const previousPackages = productStocks.reduce((sum, row) => sum + Number(row.get("quantity") || 0), 0);
    const packageDelta = changed.reduce((sum, { entry, index }) => sum + entry.actual - Number(stockDocs[index]!.get("quantity") || 0), 0);
    const mode = String(product.get("minimumMode") || "PACKAGES");
    const previousBase = mode === "PACKAGES" ? previousPackages : aggregateKnownBase(productStocks, productVariants, null, mode);
    const baseDelta = mode === "PACKAGES" ? packageDelta : changed.reduce((sum, { entry, index }) => {
      const packageBase = Number(variantDocs[index]!.get("packageAmountBase") || 0);
      return sum + (entry.actual - Number(stockDocs[index]!.get("quantity") || 0)) * packageBase;
    }, 0);
    const currentBase = previousBase + baseDelta;
    const minimum = Number(product.get("minimumAmountBase") ?? product.get("minimumQuantity") ?? 0);
    const preferredVariantId = String(product.get("preferredVariantId") || productVariants.find((variant) => !variant.get("deletedAt"))?.id || "");
    const adjustedTotals = variantPackageTotals(productStocks);
    changed.forEach(({ entry }) => adjustedTotals.set(entry.variantId, entry.actual));
    const required = requiredPackages(minimum, currentBase, mode, preferredVariantId, productVariants, null, adjustedTotals);
    const automatic = product.get("autoShopping") !== false;
    reconcileShopping(tx, shoppingRefs[productIndex]!, shoppingDocs[productIndex]!, {
      id: productId, name: product.get("name"), category: product.get("category"), categoryId: product.get("categoryId"), preferredVariantId,
    }, required, 0, automatic, timestamp);
    tx.update(productRefs[productIndex]!, { revision: FieldValue.increment(1), updatedAt: timestamp });
    if (automatic && previousBase >= minimum && currentBase < minimum) {
      tx.set(pantryRef.collection("notifications").doc(`${operationId}_${productIndex}`), {
        productId, name: product.get("name"), remaining: currentBase, required, createdAt: timestamp,
      });
    }
  });
  tx.set(pantryRef.collection("inventorySessions").doc(safeId(text(session, "id"))), {
    shelfId, expectedRevision: baseRevision, status: "APPLIED",
    differences: differences.map((value) => {
      const difference = object(value, "difference");
      const productId = safeId(text(difference, "productId"));
      return {
        productId,
        variantId: typeof difference.variantId === "string" ? safeId(difference.variantId, "variantId") : productId,
        actualQuantity: integer(difference, "actualQuantity", 0, 1_000_000),
      };
    }),
    actorUid: uid, deviceId, deviceDisplayName, createdAt: timestamp, appliedAt: timestamp,
  }, { merge: true });
  return {
    revision: baseRevision + 1,
    activity: { shelfId, displayLabel: serverActivityLabel(shelf.get("name"), "Inventura") },
  };
}

async function softDelete({ tx, pantryRef, payload, baseRevision, timestamp }: HandlerContext): Promise<HandlerResult> {
  const kind = text(payload, "targetType", 1, 30);
  const id = safeId(text(payload, "id"));
  const collection = collectionForAggregate(kind);
  const ref = pantryRef.collection(collection).doc(id);
  const shoppingRef = collection === "products" ? pantryRef.collection("shoppingItems").doc(`auto_${id}`) : null;
  const variantsQuery = kind === "PRODUCT" ? pantryRef.collection("variants").where("productId", "==", id) : null;
  const [doc, shopping, productVariants] = await Promise.all([
    tx.get(ref),
    shoppingRef ? tx.get(shoppingRef) : Promise.resolve(null),
    variantsQuery ? tx.get(variantsQuery) : Promise.resolve(null),
  ]);
  assertRevision(doc, baseRevision, "Zapis");
  if (kind === "CATEGORY" && doc.get("isDefault") === true) {
    throw new HttpsError("failed-precondition", "Zadana kategorija ne može se obrisati.");
  }
  let variantProduct: DocumentSnapshot | null = null;
  let activeVariantSiblings: DocumentSnapshot[] = [];
  let variantShopping: DocumentSnapshot | null = null;
  if (kind === "VARIANT") {
    const productId = safeId(String(doc.get("productId") || ""), "productId");
    const [product, siblings, automaticShopping] = await Promise.all([
      tx.get(pantryRef.collection("products").doc(productId)),
      tx.get(pantryRef.collection("variants").where("productId", "==", productId).where("deletedAt", "==", null)),
      tx.get(pantryRef.collection("shoppingItems").doc(`auto_${productId}`)),
    ]);
    variantProduct = product;
    activeVariantSiblings = siblings.docs.filter((variant) => variant.id !== id);
    variantShopping = automaticShopping;
    if (!product.exists || product.get("deletedAt")) throw new HttpsError("failed-precondition", "Generički artikl nije dostupan.");
  }
  const revision = baseRevision + 1;
  tx.update(ref, { deletedAt: timestamp, purgeAfter: daysFromNow(30), revision, updatedAt: timestamp });
  if (kind === "PRODUCT" && productVariants) {
    productVariants.docs.forEach((variant) => tx.set(variant.ref, {
      deletedAt: timestamp, purgeAfter: daysFromNow(30), revision: Number(variant.get("revision") || 0) + 1, updatedAt: timestamp,
    }, { merge: true }));
  }
  if (kind === "VARIANT") {
    const productId = safeId(String(doc.get("productId")), "productId");
    const productRef = pantryRef.collection("products").doc(productId);
    if (activeVariantSiblings.length === 0) {
      tx.update(productRef, {
        deletedAt: timestamp, purgeAfter: daysFromNow(30), revision: FieldValue.increment(1), updatedAt: timestamp,
      });
      if (variantShopping?.exists) tx.update(variantShopping.ref, { deletedAt: timestamp, updatedAt: timestamp });
    } else if (variantProduct?.get("preferredVariantId") === id) {
      const preferred = [...activeVariantSiblings].sort((left, right) =>
        Number(right.get("purchaseCount") || 0) - Number(left.get("purchaseCount") || 0) || left.id.localeCompare(right.id))[0]!;
      tx.update(productRef, { preferredVariantId: preferred.id, revision: FieldValue.increment(1), updatedAt: timestamp });
    }
  }
  if (kind === "SHELF") tx.delete(pantryRef.collection("shelfNames").doc(sha256(documentNormalizedName(doc))));
  if (kind === "CATEGORY") tx.delete(pantryRef.collection("categoryNames").doc(sha256(documentNormalizedName(doc))));
  if (shoppingRef && shopping?.exists) {
    tx.update(shoppingRef, { deletedAt: timestamp, updatedAt: timestamp });
  }
  return {
    revision,
    activity: {
      productId: kind === "PRODUCT" ? id : kind === "VARIANT" ? String(doc.get("productId")) : undefined,
      shelfId: kind === "SHELF" ? id : undefined,
      displayLabel: serverActivityLabel(doc.get("name") || doc.get("displayName"), "Zapis"),
    },
  };
}

async function restore({ tx, pantryRef, payload, timestamp }: HandlerContext): Promise<HandlerResult> {
  const kind = text(payload, "targetType", 1, 30);
  const id = safeId(text(payload, "id"));
  const ref = pantryRef.collection(collectionForAggregate(kind)).doc(id);
  const shoppingRef = kind === "PRODUCT" ? pantryRef.collection("shoppingItems").doc(`auto_${id}`) : null;
  const productVariantsQuery = kind === "PRODUCT" ? pantryRef.collection("variants").where("productId", "==", id) : null;
  const productStocksQuery = kind === "PRODUCT" ? pantryRef.collection("stocks").where("productId", "==", id) : null;
  const [doc, shopping, productVariants, productStocks] = await Promise.all([
    tx.get(ref), shoppingRef ? tx.get(shoppingRef) : Promise.resolve(null),
    productVariantsQuery ? tx.get(productVariantsQuery) : Promise.resolve(null),
    productStocksQuery ? tx.get(productStocksQuery) : Promise.resolve(null),
  ]);
  if (!doc.exists || !doc.get("deletedAt")) throw new HttpsError("not-found", "Zapis nije pronađen u košu.");
  if (doc.get("purgeAfter")?.toMillis() <= Date.now()) throw new HttpsError("failed-precondition", "Rok za vraćanje je istekao.");
  const limit = kind === "PRODUCT" || kind === "VARIANT" ? PANTRY_LIMITS.products : kind === "SHELF" ? PANTRY_LIMITS.shelves : PANTRY_LIMITS.categories;
  const label = kind === "PRODUCT" ? "artikala" : kind === "VARIANT" ? "varijanti" : kind === "SHELF" ? "polica" : "kategorija";
  const active = await tx.get(pantryRef.collection(collectionForAggregate(kind)).where("deletedAt", "==", null).limit(limit + 1));
  assertResourceLimit(active.size, limit, label);
  const restoredNormalized = kind === "SHELF" || kind === "CATEGORY" ? documentNormalizedName(doc) : null;
  const restoredNameRef = kind === "SHELF" && restoredNormalized
    ? pantryRef.collection("shelfNames").doc(sha256(restoredNormalized))
    : kind === "CATEGORY" && restoredNormalized
      ? pantryRef.collection("categoryNames").doc(sha256(restoredNormalized))
      : null;
  const restoredReservation = restoredNameRef ? await tx.get(restoredNameRef) : null;
  if (restoredNormalized) {
    assertUniqueNormalizedName(active.docs, restoredNormalized, id, kind === "SHELF" ? "Polica" : "Kategorija");
    const reservedId = restoredReservation?.get(kind === "SHELF" ? "shelfId" : "categoryId");
    if (restoredReservation?.exists && reservedId !== id) throw new HttpsError("already-exists", "Aktivan zapis s tim nazivom već postoji.");
  }
  const restoredVariantProduct = kind === "VARIANT"
    ? await tx.get(pantryRef.collection("products").doc(safeId(String(doc.get("productId") || ""), "productId")))
    : null;
  if (kind === "VARIANT") {
    if (!restoredVariantProduct?.exists || restoredVariantProduct.get("deletedAt")) {
      throw new HttpsError("failed-precondition", "Generički artikl nije aktivan. Vratite cijeli artikl iz koša.");
    }
  }
  const variantReservationRef = kind === "VARIANT" && typeof doc.get("barcode") === "string"
    ? db.doc(`barcodes/${sha256(`${pantryRef.id}:${String(doc.get("barcode"))}`)}`)
    : null;
  const variantReservation = variantReservationRef ? await tx.get(variantReservationRef) : null;
  const productVariantReservationRefs = kind === "PRODUCT" && productVariants
    ? productVariants.docs.flatMap((variant) => typeof variant.get("barcode") === "string"
      ? [db.doc(`barcodes/${sha256(`${pantryRef.id}:${String(variant.get("barcode"))}`)}`)] : [])
    : [];
  const productVariantReservations = productVariantReservationRefs.length > 0
    ? await tx.getAll(...productVariantReservationRefs) : [];
  if (kind === "VARIANT" && typeof doc.get("barcode") === "string") {
    const code = String(doc.get("barcode"));
    if (variantReservation?.exists && variantReservation.get("variantId") !== id) throw new HttpsError("already-exists", "Barkod sada pripada drugoj varijanti.");
    tx.set(variantReservationRef!, { pantryId: pantryRef.id, productId: doc.get("productId"), variantId: id, barcode: code, updatedAt: timestamp });
  }
  productVariantReservations.forEach((reservation, index) => {
    const variant = productVariants!.docs.filter((item) => typeof item.get("barcode") === "string")[index]!;
    const expectedVariantId = variant.id;
    if (reservation.exists && reservation.get("variantId") !== expectedVariantId) {
      throw new HttpsError("already-exists", "Barkod jedne varijante sada pripada drugoj varijanti.");
    }
    tx.set(productVariantReservationRefs[index]!, {
      pantryId: pantryRef.id, productId: id, variantId: expectedVariantId,
      barcode: variant.get("barcode"), updatedAt: timestamp,
    });
  });
  const revision = Number(doc.get("revision") || 0) + 1;
  tx.update(ref, {
    deletedAt: null, purgeAfter: null, revision, updatedAt: timestamp,
    ...(restoredNormalized ? { normalizedName: restoredNormalized } : {}),
    ...(kind === "CATEGORY" ? { isDefault: false } : {}),
  });
  if (kind === "VARIANT" && !restoredVariantProduct?.get("preferredVariantId")) {
    tx.update(pantryRef.collection("products").doc(String(doc.get("productId"))), {
      preferredVariantId: id, revision: FieldValue.increment(1), updatedAt: timestamp,
    });
  }
  if (restoredNameRef && restoredNormalized) {
    tx.set(restoredNameRef, {
      normalizedName: restoredNormalized,
      [kind === "SHELF" ? "shelfId" : "categoryId"]: id,
      updatedAt: timestamp,
    });
  }
  if (kind === "PRODUCT" && shoppingRef && shopping) {
    productVariants?.docs.forEach((variant) => tx.update(variant.ref, {
      deletedAt: null, purgeAfter: null, revision: FieldValue.increment(1), updatedAt: timestamp,
    }));
    const minimumMode = String(doc.get("minimumMode") || "PACKAGES");
    const currentBase = minimumMode === "PACKAGES"
      ? productStocks!.docs.reduce((sum, stock) => sum + Number(stock.get("quantity") || 0), 0)
      : aggregateKnownBase(productStocks!.docs, productVariants!.docs, null, minimumMode);
    const preferredVariantId = String(doc.get("preferredVariantId") || productVariants!.docs[0]?.id || "");
    const required = requiredPackages(
      Number(doc.get("minimumAmountBase") ?? doc.get("minimumQuantity") ?? 0),
      currentBase,
      minimumMode,
      preferredVariantId || null,
      productVariants!.docs,
      null,
      variantPackageTotals(productStocks!.docs),
    );
    reconcileShopping(
      tx, shoppingRef, shopping,
      { id, name: doc.get("name"), category: doc.get("category"), categoryId: doc.get("categoryId"), preferredVariantId },
      required, 0, doc.get("autoShopping") !== false, timestamp,
    );
  }
  return {
    revision,
    activity: {
      productId: kind === "PRODUCT" ? id : undefined,
      shelfId: kind === "SHELF" ? id : undefined,
      displayLabel: serverActivityLabel(doc.get("name"), "Zapis"),
    },
  };
}

async function importSnapshot({ tx, pantryRef, payload, timestamp }: HandlerContext): Promise<number> {
  const snapshot = object(payload.snapshot, "snapshot");
  const shelves = array(snapshot.shelves, "shelves");
  const categories = array(snapshot.categories, "categories");
  const products = array(snapshot.products, "products");
  const stocks = array(snapshot.stocks, "stocks");
  const shopping = array(snapshot.shoppingItems, "shoppingItems");
  const replaceExisting = payload.replaceExisting === true;
  if (shelves.length + categories.length + products.length + stocks.length + shopping.length > 350) {
    throw new HttpsError("resource-exhausted", "Sigurnosna kopija je prevelika za atomarni uvoz.");
  }
  const shelfEntries = shelves.map((raw) => {
    const value = object(raw); const id = safeId(text(value, "id"));
    const name = text(value, "name", 1, 100);
    return { value, id, name, normalizedName: normalizedName(name), sortOrder: integer(value, "sortOrder", 0, 10_000) };
  });
  const categoryEntries = categories.map((raw) => {
    const value = object(raw); const id = safeId(text(value, "id"));
    const name = text(value, "name", 1, 100);
    return { value, id, name, normalizedName: normalizedName(name), sortOrder: integer(value, "sortOrder", 0, 10_000) };
  });
  const productEntries = products.map((raw) => {
    const value = object(raw);
    const categoryId = typeof value.categoryId === "string" && value.categoryId.trim() !== "" ? safeId(value.categoryId, "categoryId") : null;
    return { value, id: safeId(text(value, "id")), code: barcode(value.barcode), categoryId, legacyCategory: text(value, "category", 1, 100) };
  });
  const stockEntries = stocks.map((raw) => {
    const value = object(raw); const productId = safeId(text(value, "productId")); const shelfId = safeId(text(value, "shelfId"));
    return { value, productId, shelfId, id: `${productId}_${shelfId}`, quantity: integer(value, "quantity", 0, 1_000_000) };
  });
  const shoppingEntries = shopping.map((raw) => {
    const value = object(raw); const id = safeId(text(value, "id")); const manual = boolean(value, "manual");
    const productId = value.productId === null || value.productId === undefined ? null : safeId(text(value, "productId"));
    if (manual && (productId !== null || id.startsWith("auto_"))) throw new HttpsError("invalid-argument", "Ručna stavka ima rezerviranu vezu ili identifikator.");
    if (!manual && (productId === null || id !== `auto_${productId}`)) throw new HttpsError("invalid-argument", "Automatska stavka nema valjanu vezu s artiklom.");
    const categoryId = typeof value.categoryId === "string" && value.categoryId.trim() !== "" ? safeId(value.categoryId, "categoryId") : null;
    return {
      value, id, manual, productId, categoryId,
      name: text(value, "name", 1, 100), legacyCategory: text(value, "category", 1, 100),
      requiredQuantity: integer(value, "requiredQuantity", 1, 1_000_000), checked: boolean(value, "checked"),
    };
  });
  if (new Set(shelfEntries.map((entry) => entry.id)).size !== shelfEntries.length ||
      new Set(categoryEntries.map((entry) => entry.id)).size !== categoryEntries.length ||
      new Set(productEntries.map((entry) => entry.id)).size !== productEntries.length ||
      new Set(stockEntries.map((entry) => entry.id)).size !== stockEntries.length ||
      new Set(shoppingEntries.map((entry) => entry.id)).size !== shoppingEntries.length) {
    throw new HttpsError("already-exists", "Sigurnosna kopija sadrži duple identifikatore.");
  }
  const codes = productEntries.map((entry) => entry.code).filter((code): code is string => code !== null);
  if (new Set(codes).size !== codes.length) throw new HttpsError("already-exists", "Sigurnosna kopija sadrži dupli barkod.");
  if (new Set(shelfEntries.map((entry) => entry.normalizedName)).size !== shelfEntries.length) {
    throw new HttpsError("already-exists", "Sigurnosna kopija sadrži duple nazive polica.");
  }
  if (new Set(categoryEntries.map((entry) => entry.normalizedName)).size !== categoryEntries.length) {
    throw new HttpsError("already-exists", "Sigurnosna kopija sadrži duple nazive kategorija.");
  }
  const incoming = {
    shelves: new Set(shelfEntries.map((entry) => entry.id)),
    categories: new Set(categoryEntries.map((entry) => entry.id)),
    products: new Set(productEntries.map((entry) => entry.id)),
    stocks: new Set(stockEntries.map((entry) => entry.id)),
    shoppingItems: new Set(shoppingEntries.map((entry) => entry.id)),
  };
  const reservationRefs = codes.map((code) => db.doc(`barcodes/${sha256(`${pantryRef.id}:${code}`)}`));
  const reservationRefByCode = new Map(codes.map((code, index) => [code, reservationRefs[index]!]));
  const shelfNameRefs = shelfEntries.map((entry) => pantryRef.collection("shelfNames").doc(sha256(entry.normalizedName)));
  const categoryNameRefs = categoryEntries.map((entry) => pantryRef.collection("categoryNames").doc(sha256(entry.normalizedName)));
  const [existing, reservations, shelfNameReservations, categoryNameReservations] = await Promise.all([
    Promise.all([
      tx.get(pantryRef.collection("shelves")), tx.get(pantryRef.collection("categories")),
      tx.get(pantryRef.collection("products")), tx.get(pantryRef.collection("stocks")),
      tx.get(pantryRef.collection("shoppingItems")),
    ]),
    reservationRefs.length > 0 ? tx.getAll(...reservationRefs) : Promise.resolve([]),
    shelfNameRefs.length > 0 ? tx.getAll(...shelfNameRefs) : Promise.resolve([]),
    categoryNameRefs.length > 0 ? tx.getAll(...categoryNameRefs) : Promise.resolve([]),
  ]);
  const existingProducts = new Map(existing[2].docs.map((document) => [document.id, document]));
  const existingShelves = new Map(existing[0].docs.map((document) => [document.id, document]));
  const existingCategories = new Map(existing[1].docs.map((document) => [document.id, document]));
  const existingStocks = new Map(existing[3].docs.map((document) => [document.id, document]));
  const existingShopping = new Map(existing[4].docs.map((document) => [document.id, document]));
  const finalShelfNames = new Map<string, string>();
  const finalCategoryNames = new Map<string, string>();
  if (!replaceExisting) {
    existing[0].docs.filter((document) => !document.get("deletedAt")).forEach((document) => {
      const normalized = documentNormalizedName(document);
      const previous = finalShelfNames.get(normalized);
      if (previous && previous !== document.id) throw new HttpsError("failed-precondition", "Postojeće police sadrže duple nazive.");
      finalShelfNames.set(normalized, document.id);
    });
    existing[1].docs.filter((document) => !document.get("deletedAt")).forEach((document) => {
      const normalized = documentNormalizedName(document);
      const previous = finalCategoryNames.get(normalized);
      if (previous && previous !== document.id) throw new HttpsError("failed-precondition", "Postojeće kategorije sadrže duple nazive.");
      finalCategoryNames.set(normalized, document.id);
    });
  }
  shelfEntries.forEach((entry, index) => {
    const previous = finalShelfNames.get(entry.normalizedName);
    if (previous && previous !== entry.id) throw new HttpsError("already-exists", "Aktivna polica s tim nazivom već postoji.");
    const reservedId = shelfNameReservations[index]?.get("shelfId");
    const reservedOwner = typeof reservedId === "string" ? existingShelves.get(reservedId) : undefined;
    if (reservedId !== undefined && reservedId !== entry.id && reservedOwner && !reservedOwner.get("deletedAt")) {
      throw new HttpsError("already-exists", "Aktivna polica s tim nazivom već postoji.");
    }
    finalShelfNames.set(entry.normalizedName, entry.id);
  });
  categoryEntries.forEach((entry, index) => {
    const previous = finalCategoryNames.get(entry.normalizedName);
    if (previous && previous !== entry.id) throw new HttpsError("already-exists", "Aktivna kategorija s tim nazivom već postoji.");
    const reservedId = categoryNameReservations[index]?.get("categoryId");
    const reservedOwner = typeof reservedId === "string" ? existingCategories.get(reservedId) : undefined;
    if (reservedId !== undefined && reservedId !== entry.id && reservedOwner && !reservedOwner.get("deletedAt")) {
      throw new HttpsError("already-exists", "Aktivna kategorija s tim nazivom već postoji.");
    }
    finalCategoryNames.set(entry.normalizedName, entry.id);
  });
  const finalCategories = new Map<string, string>();
  if (!replaceExisting) existing[1].docs.forEach((document) => {
    if (!document.get("deletedAt")) finalCategories.set(document.id, text({ name: document.get("name") }, "name", 1, 100));
  });
  categoryEntries.forEach((entry) => finalCategories.set(entry.id, entry.name));
  const existingDefault = !replaceExisting
    ? existing[1].docs.filter((document) => !document.get("deletedAt") && document.get("isDefault") === true)
      .sort((left, right) => Number(left.get("sortOrder") || 0) - Number(right.get("sortOrder") || 0) || left.id.localeCompare(right.id))[0]?.id
    : undefined;
  const defaultCategoryId = existingDefault && finalCategories.has(existingDefault)
    ? existingDefault
    : categoryEntries.find((entry) => entry.normalizedName === normalizedName("Ostalo"))?.id
      ?? [...finalCategories.keys()].sort()[0];
  if (!defaultCategoryId) throw new HttpsError("failed-precondition", "Uvoz mora sadržavati barem jednu aktivnu kategoriju.");
  const resolvedCategoryIds = new Map<string, string>();
  productEntries.forEach((entry) => {
    if (entry.categoryId && finalCategories.has(entry.categoryId)) {
      resolvedCategoryIds.set(entry.id, entry.categoryId);
      return;
    }
    if (entry.categoryId) throw new HttpsError("failed-precondition", "Artikl upućuje na nepostojeću kategoriju.");
    const expected = normalizedName(entry.legacyCategory);
    const matches = [...finalCategories.entries()].filter(([, name]) => normalizedName(name) === expected);
    if (matches.length !== 1) throw new HttpsError("failed-precondition", "Artikl upućuje na nepostojeću kategoriju.");
    resolvedCategoryIds.set(entry.id, matches[0]![0]);
  });
  const reservationByCode = new Map(codes.map((code, index) => [code, reservations[index]]));
  const activeBarcodeOwners = new Map<string, string>();
  existing[2].docs.forEach((document) => {
    const code = document.get("barcode");
    if (!document.get("deletedAt") && typeof code === "string") activeBarcodeOwners.set(code, document.id);
  });
  productEntries.forEach((entry) => {
    text(entry.value, "name", 1, 100);
    if (typeof entry.value.description === "string" && entry.value.description.length > 500) throw new HttpsError("invalid-argument", "Opis artikla je predugačak.");
    integer(entry.value, "minimumQuantity", 0, 1_000_000);
    boolean(entry.value, "autoShopping", true);
    const currentOwner = entry.code ? activeBarcodeOwners.get(entry.code) : undefined;
    if (currentOwner && currentOwner !== entry.id) {
      throw new HttpsError("already-exists", `Barkod ${entry.code} već pripada drugom artiklu.`);
    }
    const reservedOwner = entry.code ? reservationByCode.get(entry.code)?.get("productId") : undefined;
    if (entry.code && typeof reservedOwner === "string" && reservedOwner !== entry.id) {
      throw new HttpsError("already-exists", `Barkod ${entry.code} već je rezerviran.`);
    }
  });
  const finalShelfIds = new Set(existing[0].docs.filter((document) => !document.get("deletedAt")).map((document) => document.id));
  const finalProductIds = new Set(existing[2].docs.filter((document) => !document.get("deletedAt")).map((document) => document.id));
  if (replaceExisting) { finalShelfIds.clear(); finalProductIds.clear(); }
  shelfEntries.forEach((entry) => finalShelfIds.add(entry.id));
  productEntries.forEach((entry) => finalProductIds.add(entry.id));
  assertFinalResourceLimit(finalShelfIds.size, PANTRY_LIMITS.shelves, "polica");
  assertFinalResourceLimit(finalCategories.size, PANTRY_LIMITS.categories, "kategorija");
  assertFinalResourceLimit(finalProductIds.size, PANTRY_LIMITS.products, "artikala");
  stockEntries.forEach((entry) => {
    if (!finalShelfIds.has(entry.shelfId) || !finalProductIds.has(entry.productId)) throw new HttpsError("failed-precondition", "Zaliha upućuje na nepostojeći artikl ili policu.");
  });
  shoppingEntries.forEach((entry) => {
    if (entry.productId !== null && !finalProductIds.has(entry.productId)) throw new HttpsError("failed-precondition", "Stavka kupnje upućuje na nepostojeći artikl.");
  });
  const resolvedShoppingCategoryIds = new Map<string, string>();
  shoppingEntries.forEach((entry) => {
    if (!entry.manual && entry.productId) {
      const productCategoryId = resolvedCategoryIds.get(entry.productId);
      if (!productCategoryId) throw new HttpsError("failed-precondition", "Automatska stavka nema kategoriju artikla.");
      resolvedShoppingCategoryIds.set(entry.id, productCategoryId);
      return;
    }
    if (entry.categoryId && finalCategories.has(entry.categoryId)) {
      resolvedShoppingCategoryIds.set(entry.id, entry.categoryId);
      return;
    }
    if (entry.categoryId) throw new HttpsError("failed-precondition", "Stavka kupnje upućuje na nepostojeću kategoriju.");
    const expected = normalizedName(entry.legacyCategory);
    const matches = [...finalCategories.entries()].filter(([, name]) => normalizedName(name) === expected);
    if (matches.length !== 1) throw new HttpsError("failed-precondition", "Stavka kupnje upućuje na nepostojeću kategoriju.");
    resolvedShoppingCategoryIds.set(entry.id, matches[0]![0]);
  });
  const finalStocks = new Map<string, { productId: string; quantity: number }>();
  if (!replaceExisting) existing[3].docs.forEach((document) => finalStocks.set(document.id, { productId: String(document.get("productId")), quantity: Number(document.get("quantity") || 0) }));
  stockEntries.forEach((entry) => finalStocks.set(entry.id, { productId: entry.productId, quantity: entry.quantity }));
  const totals = new Map<string, number>();
  finalStocks.forEach((stock) => totals.set(stock.productId, (totals.get(stock.productId) || 0) + stock.quantity));
  const productById = new Map(productEntries.map((entry) => [entry.id, {
    name: text(entry.value, "name", 1, 100), categoryId: resolvedCategoryIds.get(entry.id)!,
    category: finalCategories.get(resolvedCategoryIds.get(entry.id)!)!,
    minimum: integer(entry.value, "minimumQuantity", 0, 1_000_000), autoShopping: boolean(entry.value, "autoShopping", true),
  }]));
  shoppingEntries.filter((entry) => !entry.manual).forEach((entry) => {
    const product = entry.productId ? productById.get(entry.productId) : undefined;
    if (!product) throw new HttpsError("failed-precondition", "Automatska stavka nema uvezeni artikl.");
    const expected = product.autoShopping ? Math.max(product.minimum - (totals.get(entry.productId!) || 0), 0) : 0;
    if (expected === 0 || entry.requiredQuantity !== expected || entry.name !== product.name ||
        resolvedShoppingCategoryIds.get(entry.id) !== product.categoryId) {
      throw new HttpsError("invalid-argument", "Automatska stavka ne odgovara stvarnom manjku.");
    }
  });
  productById.forEach((product, productId) => {
    const expected = product.autoShopping ? Math.max(product.minimum - (totals.get(productId) || 0), 0) : 0;
    if (expected > 0 && !shoppingEntries.some((entry) => !entry.manual && entry.productId === productId)) {
      throw new HttpsError("failed-precondition", "Nedostaje automatska stavka za artikl ispod minimuma.");
    }
  });
  if (replaceExisting) {
    const writeCount = existing.reduce((sum, query) => sum + query.size, 0) + shelves.length + categories.length + products.length + stocks.length + shopping.length;
    if (writeCount > 450) throw new HttpsError("resource-exhausted", "Zamjenski uvoz prelazi sigurnu veličinu transakcije.");
    const keys = ["shelves", "categories", "products", "stocks", "shoppingItems"] as const;
    existing.forEach((query, index) => query.docs.forEach((doc) => {
      if (incoming[keys[index]!].has(doc.id)) return;
      if (keys[index] === "stocks") {
        if (incoming.products.has(String(doc.get("productId")))) tx.delete(doc.ref);
      } else {
        tx.set(doc.ref, { deletedAt: timestamp, purgeAfter: daysFromNow(30), updatedAt: timestamp, revision: Number(doc.get("revision") || 0) + 1 }, { merge: true });
        if (keys[index] === "shelves") tx.delete(pantryRef.collection("shelfNames").doc(sha256(documentNormalizedName(doc))));
        if (keys[index] === "categories") tx.delete(pantryRef.collection("categoryNames").doc(sha256(documentNormalizedName(doc))));
      }
    }));
  }
  shelfEntries.forEach(({ id, name, normalizedName: normalized, sortOrder }) => {
    const existing = existingShelves.get(id);
    tx.set(pantryRef.collection("shelves").doc(id), {
      name, normalizedName: normalized, sortOrder, revision: Number(existing?.get("revision") || 0) + 1,
      createdAt: existing?.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
    }, { merge: true });
    tx.set(pantryRef.collection("shelfNames").doc(sha256(normalized)), { normalizedName: normalized, shelfId: id, updatedAt: timestamp });
    if (existing && documentNormalizedName(existing) !== normalized) {
      tx.delete(pantryRef.collection("shelfNames").doc(sha256(documentNormalizedName(existing))));
    }
  });
  categoryEntries.forEach(({ id, name, normalizedName: normalized, sortOrder }) => {
    const existing = existingCategories.get(id);
    tx.set(pantryRef.collection("categories").doc(id), {
      name, normalizedName: normalized, sortOrder, isDefault: id === defaultCategoryId,
      revision: Number(existing?.get("revision") || 0) + 1,
      createdAt: existing?.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
    }, { merge: true });
    tx.set(pantryRef.collection("categoryNames").doc(sha256(normalized)), { normalizedName: normalized, categoryId: id, updatedAt: timestamp });
    if (existing && documentNormalizedName(existing) !== normalized) {
      tx.delete(pantryRef.collection("categoryNames").doc(sha256(documentNormalizedName(existing))));
    }
  });
  if (!replaceExisting) existing[1].docs
    .filter((document) => !document.get("deletedAt") && !incoming.categories.has(document.id))
    .forEach((document) => {
      const normalized = documentNormalizedName(document);
      tx.set(document.ref, { normalizedName: normalized, isDefault: document.id === defaultCategoryId, updatedAt: timestamp }, { merge: true });
      tx.set(pantryRef.collection("categoryNames").doc(sha256(normalized)), { normalizedName: normalized, categoryId: document.id, updatedAt: timestamp });
    });
  if (!replaceExisting) existing[0].docs
    .filter((document) => !document.get("deletedAt") && !incoming.shelves.has(document.id))
    .forEach((document) => {
      const normalized = documentNormalizedName(document);
      tx.set(document.ref, { normalizedName: normalized, updatedAt: timestamp }, { merge: true });
      tx.set(pantryRef.collection("shelfNames").doc(sha256(normalized)), { normalizedName: normalized, shelfId: document.id, updatedAt: timestamp });
    });
  productEntries.forEach(({ value, id, code }) => {
    const existing = existingProducts.get(id);
    const name = text(value, "name", 1, 100);
    const categoryId = resolvedCategoryIds.get(id)!;
    const imported = {
      name, normalizedName: normalizedName(name), barcode: code,
      description: typeof value.description === "string" ? value.description : "",
      category: finalCategories.get(categoryId)!, categoryId,
      photoSource: value.photoSource === "OPEN_FOOD_FACTS" && value.photoUri != null ? "OPEN_FOOD_FACTS" : "NONE",
      minimumQuantity: integer(value, "minimumQuantity", 0, 1_000_000),
      autoShopping: boolean(value, "autoShopping", true), totalQuantity: FieldValue.delete(),
      photoUrl: value.photoSource === "OPEN_FOOD_FACTS" ? openFoodFactsImageUrl(value.photoUri) : null,
      revision: Number(existing?.get("revision") || 0) + 1,
      createdAt: existing?.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
    };
    tx.set(pantryRef.collection("products").doc(id), imported, { merge: true });
    if (code) tx.set(reservationRefByCode.get(code)!, { pantryId: pantryRef.id, productId: id, barcode: code, updatedAt: timestamp });
    const oldCode = existingProducts.get(id)?.get("barcode");
    if (typeof oldCode === "string" && oldCode !== code) tx.delete(db.doc(`barcodes/${sha256(`${pantryRef.id}:${oldCode}`)}`));
  });
  stockEntries.forEach(({ id, productId, shelfId, quantity }) => {
    tx.set(pantryRef.collection("stocks").doc(id), {
      productId, shelfId, quantity, revision: Number(existingStocks.get(id)?.get("revision") || 0) + 1, updatedAt: timestamp,
    }, { merge: true });
  });
  shoppingEntries.forEach(({ id, productId, name, requiredQuantity, checked, manual }) => {
    const existing = existingShopping.get(id);
    const categoryId = resolvedShoppingCategoryIds.get(id)!;
    tx.set(pantryRef.collection("shoppingItems").doc(id), {
      productId, name, categoryId, category: finalCategories.get(categoryId)!, requiredQuantity, checked, manual,
      revision: Number(existing?.get("revision") || 0) + 1,
      createdAt: existing?.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null,
    }, { merge: true });
  });
  const nextRevision = Date.now();
  tx.update(pantryRef, { revision: nextRevision, updatedAt: timestamp });
  return nextRevision;
}

function optionalEnum(value: unknown, allowed: string[], fallback: string, label: string): string {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value !== "string" || !allowed.includes(value)) throw new HttpsError("invalid-argument", `${label} nije ispravan.`);
  return value;
}

function canonicalVariant(raw: Data, pantryId: string, productId: string, variantId: string): Data & { barcode: string | null; displayName: string } {
  const displayName = text(raw, "displayName", 1, 100);
  const code = barcode(raw.barcode);
  const packageUnit = optionalEnum(raw.packageUnit,
    ["MG", "G", "KG", "ML", "L", "PIECE", "ROLL", "BAG", "CAPSULE", "UNKNOWN"], "UNKNOWN", "packageUnit");
  const packageAmountBase = raw.packageAmountBase == null ? null : integer(raw, "packageAmountBase", 1, 1_000_000_000_000);
  if ((packageUnit === "UNKNOWN") !== (packageAmountBase === null)) {
    throw new HttpsError("invalid-argument", "Veličina pakiranja i mjerna jedinica moraju biti usklađene.");
  }
  const photoSource = optionalEnum(raw.photoSource, ["NONE", "OPEN_FOOD_FACTS", "CAMERA", "GALLERY"], "NONE", "photoSource");
  const photoUri = typeof raw.photoUri === "string" && raw.photoUri.length > 0 ? raw.photoUri : null;
  const publicPhotoUrl = photoSource === "OPEN_FOOD_FACTS" ? openFoodFactsImageUrl(photoUri) : null;
  if (photoSource === "NONE" && photoUri !== null) throw new HttpsError("invalid-argument", "Varijanta bez fotografije ne smije sadržavati URL.");
  if ((photoSource === "CAMERA" || photoSource === "GALLERY") &&
      (photoUri === null || !new RegExp(`^gs://[^/]+/pantries/${pantryId}/variants/${variantId}/main\\.jpg$`).test(photoUri))) {
    throw new HttpsError("invalid-argument", "Privatna fotografija nema dopuštenu Storage putanju varijante.");
  }
  return {
    productId, displayName, manufacturer: typeof raw.manufacturer === "string" ? raw.manufacturer.trim().slice(0, 100) : "",
    barcode: code, packageAmountBase, packageUnit,
    packageLabel: typeof raw.packageLabel === "string" ? raw.packageLabel.trim().slice(0, 100) : "",
    description: typeof raw.description === "string" ? raw.description.trim().slice(0, 500) : "",
    photoUrl: publicPhotoUrl ?? photoUri, photoSource,
    minimumPackages: raw.minimumPackages == null ? null : integer(raw, "minimumPackages", 0, 1_000_000),
    purchaseCount: raw.purchaseCount == null ? 0 : integer(raw, "purchaseCount", 0),
  };
}

function packageUnitKind(unit: string): "MASS" | "VOLUME" | "COUNT" | "UNKNOWN" {
  if (["MG", "G", "KG"].includes(unit)) return "MASS";
  if (["ML", "L"].includes(unit)) return "VOLUME";
  if (["PIECE", "ROLL", "BAG", "CAPSULE"].includes(unit)) return "COUNT";
  return "UNKNOWN";
}

function assertCompatibleVariantUnit(candidateUnit: string, siblingUnits: string[]): void {
  const candidate = packageUnitKind(candidateUnit);
  const knownKinds = new Set(siblingUnits.map(packageUnitKind).filter((kind) => kind !== "UNKNOWN"));
  if (candidate !== "UNKNOWN" && knownKinds.size > 0 && !knownKinds.has(candidate)) {
    throw new HttpsError("failed-precondition", "Varijanta koristi mjernu vrstu koja nije kompatibilna s generičkim artiklom.");
  }
}

function assertVariantsCompatibleWithMinimum(minimumMode: string, units: string[]): void {
  const requiredKind = minimumMode === "MASS_MG" ? "MASS" : minimumMode === "VOLUME_ML" ? "VOLUME" : minimumMode === "COUNT" ? "COUNT" : null;
  if (requiredKind && units.some((unit) => ![requiredKind, "UNKNOWN"].includes(packageUnitKind(unit)))) {
    throw new HttpsError("failed-precondition", "Minimum nije kompatibilan s mjernom jedinicom jedne varijante.");
  }
}

function variantKind(unit: unknown): string {
  if (["MG", "G", "KG"].includes(String(unit))) return "MASS_MG";
  if (["ML", "L"].includes(String(unit))) return "VOLUME_ML";
  if (["PIECE", "ROLL", "BAG", "CAPSULE"].includes(String(unit))) return "COUNT";
  return "UNKNOWN";
}

function aggregateKnownBase(stocks: DocumentSnapshot[], variants: DocumentSnapshot[], candidate: Data | null, mode: string): number {
  const amounts = new Map<string, { amount: number; kind: string }>();
  variants.filter((variant) => !variant.get("deletedAt")).forEach((variant) => amounts.set(variant.id, {
    amount: Number(variant.get("packageAmountBase") || 0), kind: variantKind(variant.get("packageUnit")),
  }));
  if (candidate) amounts.set(safeId(text(candidate, "id"), "variantId"), {
    amount: candidate.packageAmountBase == null ? 0 : integer(candidate, "packageAmountBase", 1, 1_000_000_000_000),
    kind: variantKind(candidate.packageUnit),
  });
  return stocks.reduce((sum, stock) => {
    const variant = amounts.get(String(stock.get("variantId") || stock.get("productId")));
    return sum + (variant && variant.kind === mode ? variant.amount * Number(stock.get("quantity") || 0) : 0);
  }, 0);
}

function requiredPackages(minimum: number, current: number, mode: string, preferredVariantId: string | null,
  variants: DocumentSnapshot[], candidate: Data | null, totals: Map<string, number>): number {
  const missing = Math.max(minimum - current, 0);
  const byId = new Map(variants.filter((variant) => !variant.get("deletedAt")).map((variant) => [variant.id, ({
    id: variant.id, amount: Number(variant.get("packageAmountBase") || 0), kind: variantKind(variant.get("packageUnit")),
    purchases: Number(variant.get("purchaseCount") || 0), minimumPackages: Number(variant.get("minimumPackages") || 0),
  })]));
  if (candidate) {
    const id = safeId(text(candidate, "id"), "variantId");
    byId.set(id, { id, amount: Number(candidate.packageAmountBase || 0), kind: variantKind(candidate.packageUnit),
      purchases: Number(candidate.purchaseCount || 0), minimumPackages: Number(candidate.minimumPackages || 0) });
  }
  const candidates = [...byId.values()];
  const variantRequired = candidates.reduce((sum, variant) => sum + Math.max(variant.minimumPackages - (totals.get(variant.id) || 0), 0), 0);
  if (mode === "PACKAGES") return Math.max(missing, variantRequired);
  const chosen = candidates.find((variant) => variant.id === preferredVariantId && variant.kind === mode && variant.amount > 0)
    || candidates.filter((variant) => variant.kind === mode && variant.amount > 0)
      .sort((left, right) => right.purchases - left.purchases || left.id.localeCompare(right.id))[0];
  const genericRequired = missing === 0 ? 0 : chosen ? Math.ceil(missing / chosen.amount) : 1;
  return Math.max(genericRequired, variantRequired);
}

function variantPackageTotals(stocks: DocumentSnapshot[]): Map<string, number> {
  const totals = new Map<string, number>();
  stocks.forEach((stock) => {
    const variantId = String(stock.get("variantId") || stock.get("productId"));
    totals.set(variantId, (totals.get(variantId) || 0) + Number(stock.get("quantity") || 0));
  });
  return totals;
}

function reconcileShopping(
  tx: Transaction,
  ref: DocumentReference,
  existing: DocumentSnapshot,
  product: { id: string; name: unknown; category: unknown; categoryId: unknown; preferredVariantId?: unknown },
  minimum: number,
  total: number,
  enabled: boolean,
  timestamp: Timestamp,
): void {
  const required = enabled ? Math.max(minimum - total, 0) : 0;
  if (required === 0) {
    if (existing.exists) tx.update(ref, { deletedAt: timestamp, requiredQuantity: 0, updatedAt: timestamp, revision: FieldValue.increment(1) });
    return;
  }
  tx.set(ref, {
    productId: product.id, name: product.name, categoryId: product.categoryId, category: product.category,
    preferredVariantId: product.preferredVariantId ?? null,
    requiredQuantity: required,
    checked: existing.exists && !existing.get("deletedAt") && existing.get("checked") === true &&
      required <= Number(existing.get("requiredQuantity") || 0),
    manual: false, revision: Number(existing.get("revision") || 0) + 1,
    createdAt: existing.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null,
  }, { merge: true });
}

function assertRevision(document: DocumentSnapshot, expected: number, label: string): void {
  if (!document.exists || document.get("deletedAt")) throw new HttpsError("not-found", `${label} nije dostupan.`);
  if (Number(document.get("revision") || 0) !== expected) conflict(Number(document.get("revision") || 0));
}

function documentNormalizedName(document: DocumentSnapshot): string {
  const stored = document.get("normalizedName");
  return typeof stored === "string" && stored.length > 0
    ? normalizedName(stored)
    : normalizedName(serverActivityLabel(document.get("name"), ""));
}

function assertUniqueNormalizedName(
  documents: DocumentSnapshot[],
  expected: string,
  ownId: string,
  label: string,
): void {
  if (documents.some((document) => document.id !== ownId && documentNormalizedName(document) === expected)) {
    throw new HttpsError("already-exists", `${label} s tim nazivom već postoji.`);
  }
}

function canonicalDefaultCategoryId(
  activeDocuments: DocumentSnapshot[],
  changedId: string,
  changedNormalizedName: string,
  existing: DocumentSnapshot,
): string {
  const candidates = activeDocuments
    .filter((document) => document.id !== changedId)
    .map((document) => ({
      id: document.id,
      normalized: documentNormalizedName(document),
      isDefault: document.get("isDefault") === true,
      sortOrder: Number(document.get("sortOrder") || 0),
    }));
  candidates.push({
    id: changedId,
    normalized: changedNormalizedName,
    isDefault: existing.exists && existing.get("isDefault") === true,
    sortOrder: existing.exists ? Number(existing.get("sortOrder") || 0) : Number.MAX_SAFE_INTEGER,
  });
  const ordered = [...candidates].sort((left, right) => left.sortOrder - right.sortOrder || left.id.localeCompare(right.id));
  return ordered.find((candidate) => candidate.isDefault)?.id
    ?? ordered.find((candidate) => candidate.normalized === normalizedName("Ostalo"))?.id
    ?? ordered[0]!.id;
}

function conflict(revision: number): never {
  throw new HttpsError("aborted", `REVISION_CONFLICT:${revision}`);
}

function integerSigned(data: Data, key: string, min: number, max: number): number {
  const value = data[key];
  if (!Number.isSafeInteger(value) || Number(value) < min || Number(value) > max) throw new HttpsError("invalid-argument", `${key} nije ispravan.`);
  return Number(value);
}

function inventorySnapshotVersion(stocks: Array<{ variantId: string; quantity: number }>): number {
  const canonical = stocks.sort((a, b) => a.variantId.localeCompare(b.variantId))
    .map((stock) => `${stock.variantId}:${stock.quantity}`).join("|");
  return Number.parseInt(sha256(canonical).slice(0, 12), 16);
}

function array(value: unknown, name: string): unknown[] {
  if (!Array.isArray(value)) throw new HttpsError("invalid-argument", `${name} mora biti polje.`);
  return value;
}

function collectionForAggregate(type: string): string {
  switch (type) {
    case "PRODUCT": return "products";
    case "SHELF": return "shelves";
    case "CATEGORY": return "categories";
    case "VARIANT": return "variants";
    default: throw new HttpsError("invalid-argument", "Ova vrsta zapisa ne podržava koš.");
  }
}

function activityType(type: string, payload: Data): string {
  const map: Record<string, string> = {
    create_shelf: "SHELF_CREATED", rename_shelf: "SHELF_RENAMED", reorder_shelves: "SHELF_REORDERED",
    delete_shelf: "SHELF_DELETED", upsert_category: "CATEGORY_UPDATED", reorder_categories: "CATEGORY_REORDERED",
    delete_category: "CATEGORY_DELETED", upsert_shopping: "SHOPPING_UPDATED", delete_shopping: "ITEM_DELETED",
    upsert_product: "PRODUCT_UPDATED", upsert_variant: "VARIANT_UPDATED", move_variant: "VARIANT_GROUPED", split_variant: "VARIANT_UNGROUPED",
    upsert_synonym_rule: "PRODUCT_UPDATED", set_do_not_group: "PRODUCT_UPDATED", adjust_stock: "STOCK_ADDED", move_stock: "STOCK_MOVED",
    bulk_change_product_category: "PRODUCT_UPDATED", bulk_delete_products: "ITEM_DELETED", bulk_move_stock: "STOCK_MOVED",
    apply_inventory: "INVENTORY_APPLIED", soft_delete: "ITEM_DELETED", restore: "ITEM_RESTORED",
    import_snapshot: "IMPORT_APPLIED",
  };
  if (type === "adjust_stock" && Number(payload.delta) < 0) return "STOCK_REMOVED";
  return map[type] || "UNKNOWN";
}

function activityLabel(payload: Data, type: string): string {
  if (type === "upsert_product") return String(object(payload.product).name || "Artikl").slice(0, 100);
  if (type === "upsert_variant") return String(object(payload.variant).displayName || "Varijanta").slice(0, 100);
  if (type === "create_shelf") return String(object(payload.shelf).name || "Polica").slice(0, 100);
  return type.replaceAll("_", " ").slice(0, 100);
}

function activityValues(metadata: ActivityMetadata): { oldValue: string | null; newValue: string | null } {
  if (metadata.oldValue !== undefined || metadata.newValue !== undefined) {
    return { oldValue: metadata.oldValue ?? null, newValue: metadata.newValue ?? null };
  }
  return { oldValue: null, newValue: null };
}

function activityReferences(payload: Data, type: string, metadata: ActivityMetadata): ActivityMetadata {
  if (metadata.productId || metadata.shelfId || metadata.fromShelfId || metadata.toShelfId) return metadata;
  if (type === "create_shelf") return { shelfId: safeId(text(object(payload.shelf), "id")) };
  if (type === "rename_shelf" || type === "delete_shelf") return { shelfId: safeId(text(payload, "shelfId")) };
  if (type === "upsert_product") return { productId: safeId(text(object(payload.product), "id")) };
  if (type === "upsert_variant") return { productId: safeId(text(object(payload.variant), "productId")) };
  if (type === "move_variant") return { productId: safeId(text(payload, "toProductId")) };
  if (type === "split_variant") return { productId: safeId(text(object(payload.newProduct, "newProduct"), "id")) };
  if (type === "apply_inventory") return { shelfId: safeId(text(object(payload.session), "shelfId")) };
  if (type === "soft_delete" || type === "restore") {
    const aggregateType = text(payload, "targetType", 1, 30);
    const id = safeId(text(payload, "id"));
    if (aggregateType === "PRODUCT") return { productId: id };
    if (aggregateType === "SHELF") return { shelfId: id };
  }
  return {};
}

function registeredDeviceName(value: unknown): string {
  if (typeof value !== "string" || value.trim().length < 2 || value.trim().length > 40) {
    throw new HttpsError("failed-precondition", "Registrirani uređaj nema ispravan naziv.");
  }
  return value.trim();
}

function openFoodFactsImageUrl(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value !== "string" || value.length > 2048) {
    throw new HttpsError("invalid-argument", "Javna fotografija nije ispravna.");
  }
  let parsed: URL;
  try { parsed = new URL(value); } catch {
    throw new HttpsError("invalid-argument", "Javna fotografija nije ispravna.");
  }
  if (parsed.protocol !== "https:" || parsed.hostname !== "images.openfoodfacts.org" ||
      parsed.username !== "" || parsed.password !== "" ||
      (parsed.port !== "" && parsed.port !== "443") || !parsed.pathname.startsWith("/images/products/")) {
    throw new HttpsError("invalid-argument", "Dopuštene su samo fotografije s Open Food Facts poslužitelja.");
  }
  return parsed.toString();
}

function serverActivityLabel(value: unknown, fallback: string): string {
  return typeof value === "string" && value.trim() ? value.trim().slice(0, 100) : fallback;
}

function productVariantActivityLabel(productName: unknown, variantName: unknown): string {
  const product = serverActivityLabel(productName, "Artikl");
  const variant = serverActivityLabel(variantName, "Varijanta");
  return normalizedName(product) === normalizedName(variant) ? product : `${product} — ${variant}`;
}

function quantityDelta(payload: Data, type: string): number | null {
  if (type === "adjust_stock") return Number(payload.delta || 0);
  if (type === "move_stock") return Number(payload.quantity || 0);
  if (type === "upsert_shopping" && Number.isSafeInteger(payload.quantityDelta)) return Number(payload.quantityDelta);
  return null;
}
