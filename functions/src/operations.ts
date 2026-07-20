import { FieldValue, Transaction, DocumentReference, DocumentSnapshot, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { db, daysFromNow, now } from "./firebase";
import { PANTRY_LIMITS, assertFinalResourceLimit, assertResourceLimit } from "./limits";
import { Data, authUid, barcode, boolean, integer, object, safeId, sha256, stringArray, text } from "./validation";

const callable = { region: "europe-west1", enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== "true", timeoutSeconds: 60, memory: "512MiB" as const };
const metadataTypes = new Set(["rename_shelf", "reorder_shelves", "reorder_categories", "delete_shelf", "upsert_category", "delete_category", "upsert_product", "upsert_shopping", "delete_shopping", "soft_delete", "restore"]);

type HandlerContext = {
  tx: Transaction;
  pantryRef: DocumentReference;
  pantryId: string;
  payload: Data;
  baseRevision: number;
  operationId: string;
  uid: string;
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
};

type HandlerResult = number | { revision: number; activity: ActivityMetadata };

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
    if (!device.exists || device.get("active") !== true) throw new HttpsError("permission-denied", "Uređaj nije aktivno registriran za prijavljenog korisnika.");
    const deviceDisplayName = registeredDeviceName(device.get("name"));
    if (metadataTypes.has(type) && (type === "reorder_shelves" || type === "reorder_categories") && Number(pantry.get("revision") || 0) !== baseRevision) {
      return { status: "CONFLICT", revision: Number(pantry.get("revision") || 0) };
    }

    const handled = await handle({ tx, pantryRef, pantryId, payload, baseRevision, operationId, uid, deviceId, deviceDisplayName, timestamp });
    const revision = typeof handled === "number" ? handled : handled.revision;
    const metadata = typeof handled === "number" ? {} : handled.activity;
    tx.create(operationRef, {
      actorUid: uid, aggregateType, aggregateId, payloadType: type,
      resultRevision: revision, appliedAt: timestamp,
      resultDigest: sha256(JSON.stringify({ operationId, revision })),
    });
    const values = activityValues(metadata);
    const references = activityReferences(payload, type, metadata);
    tx.create(pantryRef.collection("activities").doc(operationId), {
      type: activityType(type, payload), aggregateId,
      displayLabel: metadata.displayLabel || activityLabel(payload, type),
      quantityDelta: quantityDelta(payload, type),
      oldValue: values.oldValue, newValue: values.newValue,
      productId: references.productId, shelfId: references.shelfId,
      fromShelfId: references.fromShelfId, toShelfId: references.toShelfId,
      actorUid: uid, deviceId, deviceDisplayName,
      createdAt: timestamp, expiresAt: daysFromNow(365),
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
    case "adjust_stock": return adjustStock(context);
    case "move_stock": return moveStock(context);
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
  const ref = pantryRef.collection("shelves").doc(id);
  const [existing, active] = await Promise.all([
    tx.get(ref),
    tx.get(pantryRef.collection("shelves").where("deletedAt", "==", null).limit(PANTRY_LIMITS.shelves + 1)),
  ]);
  if (existing.exists) throw new HttpsError("already-exists", "Polica već postoji.");
  assertResourceLimit(active.size, PANTRY_LIMITS.shelves, "polica");
  tx.create(ref, {
    name, sortOrder: integer(shelf, "sortOrder", 0, 10_000),
    revision: 1, createdAt: timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
  });
  return { revision: 1, activity: { shelfId: id, displayLabel: name } };
}

async function renameShelf({ tx, pantryRef, payload, baseRevision, timestamp }: HandlerContext): Promise<HandlerResult> {
  const id = safeId(text(payload, "shelfId"));
  const name = text(payload, "name", 1, 100);
  const ref = pantryRef.collection("shelves").doc(id);
  const shelf = await tx.get(ref);
  assertRevision(shelf, baseRevision, "Polica");
  const revision = baseRevision + 1;
  tx.update(ref, { name, revision, updatedAt: timestamp });
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
  if (!existing.exists || existing.get("deletedAt")) {
    const active = await tx.get(pantryRef.collection("categories").where("deletedAt", "==", null).limit(PANTRY_LIMITS.categories + 1));
    assertResourceLimit(active.size, PANTRY_LIMITS.categories, "kategorija");
  }
  const name = text(category, "name", 1, 100);
  if (existing.exists && existing.get("name") !== name) {
    const [products, shopping] = await Promise.all([
      tx.get(pantryRef.collection("products").where("category", "==", existing.get("name"))),
      tx.get(pantryRef.collection("shoppingItems").where("category", "==", existing.get("name"))),
    ]);
    if (products.size + shopping.size > 400) throw new HttpsError("resource-exhausted", "Previše zapisa za atomarno preimenovanje kategorije.");
    products.docs.forEach((product) => tx.update(product.ref, {
      category: name, revision: FieldValue.increment(1), updatedAt: timestamp,
    }));
    shopping.docs.forEach((item) => tx.update(item.ref, {
      category: name, revision: FieldValue.increment(1), updatedAt: timestamp,
    }));
  }
  const revision = existing.exists ? baseRevision + 1 : 1;
  tx.set(ref, {
    name, sortOrder: integer(category, "sortOrder", 0, 10_000),
    isDefault: boolean(category, "isDefault", false), revision,
    createdAt: existing.get("createdAt") || timestamp, updatedAt: timestamp,
    deletedAt: null, purgeAfter: null,
  }, { merge: true });
  return revision;
}

async function deleteCategory({ tx, pantryRef, payload, baseRevision, timestamp }: HandlerContext): Promise<number> {
  const id = safeId(text(payload, "categoryId"));
  const replacementId = safeId(text(payload, "replacementCategoryId"));
  const categoryRef = pantryRef.collection("categories").doc(id);
  const replacementRef = pantryRef.collection("categories").doc(replacementId);
  const [category, replacement] = await Promise.all([tx.get(categoryRef), tx.get(replacementRef)]);
  assertRevision(category, baseRevision, "Kategorija");
  if (category.get("isDefault") === true) throw new HttpsError("failed-precondition", "Zadana kategorija ne može se obrisati.");
  if (!replacement.exists || replacement.get("deletedAt")) throw new HttpsError("failed-precondition", "Zamjenska kategorija nije dostupna.");
  const [products, shopping] = await Promise.all([
    tx.get(pantryRef.collection("products").where("category", "==", category.get("name"))),
    tx.get(pantryRef.collection("shoppingItems").where("category", "==", category.get("name"))),
  ]);
  if (products.size + shopping.size > 400) throw new HttpsError("resource-exhausted", "Previše zapisa za atomarno brisanje kategorije.");
  products.docs.forEach((product) => tx.update(product.ref, {
    categoryId: replacementId, category: replacement.get("name"), revision: FieldValue.increment(1), updatedAt: timestamp,
  }));
  shopping.docs.forEach((item) => tx.update(item.ref, {
    category: replacement.get("name"), revision: FieldValue.increment(1), updatedAt: timestamp,
  }));
  const revision = baseRevision + 1;
  tx.update(categoryRef, { deletedAt: timestamp, purgeAfter: daysFromNow(30), revision, updatedAt: timestamp });
  return revision;
}

async function upsertProduct(context: HandlerContext): Promise<HandlerResult> {
  const { tx, pantryRef, pantryId, payload, baseRevision, operationId, timestamp } = context;
  const product = object(payload.product, "product");
  const id = safeId(text(product, "id"));
  const ref = pantryRef.collection("products").doc(id);
  const shoppingRef = pantryRef.collection("shoppingItems").doc(`auto_${id}`);
  const [existing, currentShopping] = await Promise.all([tx.get(ref), tx.get(shoppingRef)]);
  if (existing.exists && Number(existing.get("revision") || 0) !== baseRevision) return conflict(Number(existing.get("revision") || 0));
  if (!existing.exists || existing.get("deletedAt")) {
    const active = await tx.get(pantryRef.collection("products").where("deletedAt", "==", null).limit(PANTRY_LIMITS.products + 1));
    assertResourceLimit(active.size, PANTRY_LIMITS.products, "artikala");
  }
  let categoryId: string;
  let categoryName: string;
  if (typeof product.categoryId === "string" && product.categoryId.trim() !== "") {
    categoryId = safeId(product.categoryId, "categoryId");
    const categoryDocument = await tx.get(pantryRef.collection("categories").doc(categoryId));
    if (!categoryDocument.exists || categoryDocument.get("deletedAt")) {
      throw new HttpsError("failed-precondition", "Kategorija nije dostupna.");
    }
    categoryName = text({ name: categoryDocument.get("name") }, "name", 1, 100);
  } else {
    const legacyName = text(product, "category", 1, 100);
    const matches = await tx.get(pantryRef.collection("categories").where("name", "==", legacyName).limit(2));
    const active = matches.docs.filter((document) => !document.get("deletedAt"));
    if (active.length !== 1) throw new HttpsError("failed-precondition", "Kategorija nije dostupna.");
    categoryId = active[0]!.id;
    categoryName = text({ name: active[0]!.get("name") }, "name", 1, 100);
  }
  const code = barcode(product.barcode);
  const oldCode = existing.exists ? (existing.get("barcode") as string | null) : null;
  const newReservation = code ? db.doc(`barcodes/${sha256(`${pantryId}:${code}`)}`) : null;
  const oldReservation = oldCode && oldCode !== code ? db.doc(`barcodes/${sha256(`${pantryId}:${oldCode}`)}`) : null;
  if (newReservation && code !== oldCode) {
    const reservation = await tx.get(newReservation);
    if (reservation.exists && reservation.get("productId") !== id) throw new HttpsError("already-exists", "Barkod je već povezan s drugim artiklom.");
  }
  const minimum = integer(product, "minimumQuantity", 0, 1_000_000);
  const autoShopping = boolean(product, "autoShopping", true);
  const photoSource = typeof product.photoSource === "string" ? product.photoSource : "NONE";
  const photoUri = typeof product.photoUri === "string" ? product.photoUri : null;
  if (!["NONE", "OPEN_FOOD_FACTS", "CAMERA", "GALLERY"].includes(photoSource)) {
    throw new HttpsError("invalid-argument", "Izvor fotografije nije ispravan.");
  }
  const publicPhotoUrl = photoSource === "OPEN_FOOD_FACTS" ? openFoodFactsImageUrl(photoUri) : null;
  if (photoSource === "NONE" && photoUri !== null) {
    throw new HttpsError("invalid-argument", "Artikl bez fotografije ne smije sadržavati URL.");
  }
  if ((photoSource === "CAMERA" || photoSource === "GALLERY") &&
      (photoUri === null || !new RegExp(`^gs://[^/]+/pantries/${pantryId}/products/${id}/main\\.jpg$`).test(photoUri))) {
    throw new HttpsError("invalid-argument", "Privatna fotografija nema dopuštenu Storage putanju.");
  }
  const name = text(product, "name", 1, 100);
  const totalQuantity = existing.exists ? Number(existing.get("totalQuantity") || 0) : 0;
  const revision = existing.exists ? baseRevision + 1 : 1;
  tx.set(ref, {
    name, normalizedName: name.toLocaleLowerCase("hr"),
    barcode: code, description: typeof product.description === "string" ? product.description.slice(0, 500) : "",
    category: categoryName, categoryId,
    photoUrl: publicPhotoUrl ?? photoUri,
    photoSource,
    minimumQuantity: minimum, autoShopping, totalQuantity, revision,
    createdAt: existing.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
  }, { merge: true });
  if (newReservation) tx.set(newReservation, { pantryId, productId: id, barcode: code, updatedAt: timestamp });
  if (oldReservation) tx.delete(oldReservation);
  reconcileShopping(tx, shoppingRef, currentShopping, { id, name, category: categoryName }, minimum, totalQuantity, autoShopping, timestamp);
  const wasBelow = existing.exists && existing.get("autoShopping") !== false &&
    totalQuantity < Number(existing.get("minimumQuantity") || 0);
  if (autoShopping && !wasBelow && totalQuantity < minimum) {
    tx.create(pantryRef.collection("notifications").doc(operationId), {
      productId: id, name: text(product, "name"), remaining: totalQuantity,
      required: Math.max(minimum - totalQuantity, 0), createdAt: timestamp,
    });
  }
  return { revision, activity: { productId: id, displayLabel: name } };
}

async function adjustStock(context: HandlerContext): Promise<HandlerResult> {
  const { tx, pantryRef, payload, operationId, timestamp } = context;
  const productId = safeId(text(payload, "productId"));
  const shelfId = safeId(text(payload, "shelfId"));
  const delta = integerSigned(payload, "delta", -1_000_000, 1_000_000);
  if (delta === 0) throw new HttpsError("invalid-argument", "Promjena količine ne može biti nula.");
  const productRef = pantryRef.collection("products").doc(productId);
  const shelfRef = pantryRef.collection("shelves").doc(shelfId);
  const stockRef = pantryRef.collection("stocks").doc(`${productId}_${shelfId}`);
  const shoppingRef = pantryRef.collection("shoppingItems").doc(`auto_${productId}`);
  const [product, shelf, stock, shopping] = await Promise.all([
    tx.get(productRef), tx.get(shelfRef), tx.get(stockRef), tx.get(shoppingRef),
  ]);
  if (!product.exists || product.get("deletedAt")) throw new HttpsError("not-found", "Artikl nije dostupan.");
  if (!shelf.exists || shelf.get("deletedAt")) throw new HttpsError("not-found", "Polica nije dostupna.");
  const previousOnShelf = Number(stock.get("quantity") || 0);
  const previousTotal = Number(product.get("totalQuantity") || 0);
  if (previousOnShelf + delta < 0 || previousTotal + delta < 0) throw new HttpsError("failed-precondition", "Nije moguće izvaditi više od dostupne količine.");
  const revision = Number(stock.get("revision") || 0) + 1;
  const total = previousTotal + delta;
  tx.set(stockRef, { productId, shelfId, quantity: previousOnShelf + delta, revision, updatedAt: timestamp }, { merge: true });
  tx.update(productRef, { totalQuantity: total, revision: FieldValue.increment(1), updatedAt: timestamp });
  const minimum = Number(product.get("minimumQuantity") || 0);
  const autoShopping = product.get("autoShopping") !== false;
  reconcileShopping(tx, shoppingRef, shopping, { id: productId, name: product.get("name"), category: product.get("category") }, minimum, total, autoShopping, timestamp);
  if (autoShopping && previousTotal >= minimum && total < minimum) {
    tx.create(pantryRef.collection("notifications").doc(operationId), {
      productId, name: product.get("name"), remaining: total, required: Math.max(minimum - total, 0), createdAt: timestamp,
    });
  }
  return {
    revision,
    activity: {
      productId,
      shelfId,
      displayLabel: serverActivityLabel(product.get("name"), "Artikl"),
      oldValue: serverActivityLabel(shelf.get("name"), "Polica"),
      newValue: serverActivityLabel(shelf.get("name"), "Polica"),
    },
  };
}

async function moveStock({ tx, pantryRef, payload, timestamp }: HandlerContext): Promise<HandlerResult> {
  const productId = safeId(text(payload, "productId"));
  const fromShelfId = safeId(text(payload, "fromShelfId"));
  const toShelfId = safeId(text(payload, "toShelfId"));
  const quantity = integer(payload, "quantity", 1, 1_000_000);
  if (fromShelfId === toShelfId) throw new HttpsError("invalid-argument", "Police moraju biti različite.");
  const productRef = pantryRef.collection("products").doc(productId);
  const sourceShelfRef = pantryRef.collection("shelves").doc(fromShelfId);
  const targetShelfRef = pantryRef.collection("shelves").doc(toShelfId);
  const fromRef = pantryRef.collection("stocks").doc(`${productId}_${fromShelfId}`);
  const toRef = pantryRef.collection("stocks").doc(`${productId}_${toShelfId}`);
  const [product, sourceShelf, targetShelf, from, to] = await Promise.all([
    tx.get(productRef), tx.get(sourceShelfRef), tx.get(targetShelfRef), tx.get(fromRef), tx.get(toRef),
  ]);
  if (!product.exists || product.get("deletedAt")) throw new HttpsError("not-found", "Artikl nije dostupan.");
  if (!sourceShelf.exists || sourceShelf.get("deletedAt")) throw new HttpsError("not-found", "Izvorna polica nije dostupna.");
  if (!targetShelf.exists || targetShelf.get("deletedAt")) throw new HttpsError("not-found", "Odredišna polica nije dostupna.");
  if (!from.exists || Number(from.get("quantity") || 0) < quantity) throw new HttpsError("failed-precondition", "Nema dovoljno zalihe na izvornoj polici.");
  const revision = Math.max(Number(from.get("revision") || 0), Number(to.get("revision") || 0)) + 1;
  tx.update(fromRef, { quantity: Number(from.get("quantity")) - quantity, revision, updatedAt: timestamp });
  tx.set(toRef, { productId, shelfId: toShelfId, quantity: Number(to.get("quantity") || 0) + quantity, revision, updatedAt: timestamp }, { merge: true });
  return {
    revision,
    activity: {
      productId,
      fromShelfId,
      toShelfId,
      displayLabel: serverActivityLabel(product.get("name"), "Artikl"),
      oldValue: serverActivityLabel(sourceShelf.get("name"), "Izvorna polica"),
      newValue: serverActivityLabel(targetShelf.get("name"), "Odredišna polica"),
    },
  };
}

async function upsertShopping({ tx, pantryRef, payload, baseRevision, timestamp }: HandlerContext): Promise<number> {
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
  if (existing.exists && Number(existing.get("revision") || 0) !== baseRevision) return conflict(Number(existing.get("revision") || 0));
  const revision = existing.exists ? baseRevision + 1 : 1;
  if (item.productId !== null && item.productId !== undefined) {
    throw new HttpsError("invalid-argument", "Ručna stavka ne smije biti povezana s artiklom.");
  }
  const requestedCategory = text(item, "category", 1, 100);
  const categoryMatches = await tx.get(pantryRef.collection("categories").where("name", "==", requestedCategory).limit(2));
  const activeCategory = categoryMatches.docs.find((category) => !category.get("deletedAt"));
  if (!activeCategory) throw new HttpsError("failed-precondition", "Odabrana kategorija nije aktivna.");
  tx.set(ref, {
    productId: null,
    name: text(item, "name", 1, 100), category: activeCategory.get("name"),
    requiredQuantity: integer(item, "requiredQuantity", 1, 1_000_000),
    checked: boolean(item, "checked"), manual: true, revision,
    createdAt: existing.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null,
  }, { merge: true });
  return revision;
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
  const productIds = differences.map((value) => safeId(text(object(value, "difference"), "productId")));
  if (new Set(productIds).size !== productIds.length) throw new HttpsError("invalid-argument", "Artikl je u inventuri naveden više puta.");
  const stockRefs = productIds.map((id) => pantryRef.collection("stocks").doc(`${id}_${shelfId}`));
  const productRefs = productIds.map((id) => pantryRef.collection("products").doc(id));
  const shoppingRefs = productIds.map((id) => pantryRef.collection("shoppingItems").doc(`auto_${id}`));
  const [shelf, shelfStocks, stockDocs, productDocs, shoppingDocs] = await Promise.all([
    tx.get(pantryRef.collection("shelves").doc(shelfId)),
    tx.get(pantryRef.collection("stocks").where("shelfId", "==", shelfId)),
    tx.getAll(...stockRefs), tx.getAll(...productRefs), tx.getAll(...shoppingRefs),
  ]);
  if (!shelf.exists || shelf.get("deletedAt")) throw new HttpsError("failed-precondition", "Polica inventure više nije dostupna.");
  const currentRevision = inventorySnapshotVersion(shelfStocks.docs.map((doc) => ({
    productId: String(doc.get("productId")), quantity: Number(doc.get("quantity") || 0),
  })));
  if (currentRevision !== baseRevision) return conflict(currentRevision);
  for (let index = 0; index < differences.length; index++) {
    const difference = object(differences[index], "difference");
    const actual = integer(difference, "actualQuantity", 0, 1_000_000);
    const stock = stockDocs[index]!;
    const product = productDocs[index]!;
    if (!product.exists || product.get("deletedAt")) throw new HttpsError("failed-precondition", "Artikl u inventuri više nije dostupan.");
    const previous = Number(stock.get("quantity") || 0);
    const previousTotal = Number(product.get("totalQuantity") || 0);
    const total = previousTotal - previous + actual;
    tx.set(stockRefs[index]!, {
      productId: productIds[index], shelfId, quantity: actual,
      revision: Number(stock.get("revision") || 0) + 1, updatedAt: timestamp,
    }, { merge: true });
    tx.update(productRefs[index]!, { totalQuantity: total, revision: FieldValue.increment(1), updatedAt: timestamp });
    const minimum = Number(product.get("minimumQuantity") || 0);
    const auto = product.get("autoShopping") !== false;
    reconcileShopping(tx, shoppingRefs[index]!, shoppingDocs[index]!, { id: productIds[index]!, name: product.get("name"), category: product.get("category") }, minimum, total, auto, timestamp);
    if (auto && previousTotal >= minimum && total < minimum) {
      tx.set(pantryRef.collection("notifications").doc(`${operationId}_${index}`), {
        productId: productIds[index], name: product.get("name"), remaining: total,
        required: Math.max(minimum - total, 0), createdAt: timestamp,
      });
    }
  }
  tx.set(pantryRef.collection("inventorySessions").doc(safeId(text(session, "id"))), {
    shelfId, expectedRevision: baseRevision, status: "APPLIED",
    differences: differences.map((value) => {
      const difference = object(value, "difference");
      return { productId: safeId(text(difference, "productId")), actualQuantity: integer(difference, "actualQuantity", 0, 1_000_000) };
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
  const [doc, shopping] = await Promise.all([
    tx.get(ref),
    shoppingRef ? tx.get(shoppingRef) : Promise.resolve(null),
  ]);
  assertRevision(doc, baseRevision, "Zapis");
  const revision = baseRevision + 1;
  tx.update(ref, { deletedAt: timestamp, purgeAfter: daysFromNow(30), revision, updatedAt: timestamp });
  if (shoppingRef && shopping?.exists) {
    tx.update(shoppingRef, { deletedAt: timestamp, updatedAt: timestamp });
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

async function restore({ tx, pantryRef, payload, timestamp }: HandlerContext): Promise<HandlerResult> {
  const kind = text(payload, "targetType", 1, 30);
  const id = safeId(text(payload, "id"));
  const ref = pantryRef.collection(collectionForAggregate(kind)).doc(id);
  const shoppingRef = kind === "PRODUCT" ? pantryRef.collection("shoppingItems").doc(`auto_${id}`) : null;
  const [doc, shopping] = await Promise.all([tx.get(ref), shoppingRef ? tx.get(shoppingRef) : Promise.resolve(null)]);
  if (!doc.exists || !doc.get("deletedAt")) throw new HttpsError("not-found", "Zapis nije pronađen u košu.");
  if (doc.get("purgeAfter")?.toMillis() <= Date.now()) throw new HttpsError("failed-precondition", "Rok za vraćanje je istekao.");
  const limit = kind === "PRODUCT" ? PANTRY_LIMITS.products : kind === "SHELF" ? PANTRY_LIMITS.shelves : PANTRY_LIMITS.categories;
  const label = kind === "PRODUCT" ? "artikala" : kind === "SHELF" ? "polica" : "kategorija";
  const active = await tx.get(pantryRef.collection(collectionForAggregate(kind)).where("deletedAt", "==", null).limit(limit + 1));
  assertResourceLimit(active.size, limit, label);
  if (kind === "PRODUCT" && typeof doc.get("barcode") === "string") {
    const code = String(doc.get("barcode"));
    const reservationRef = db.doc(`barcodes/${sha256(`${pantryRef.id}:${code}`)}`);
    const reservation = await tx.get(reservationRef);
    if (reservation.exists && reservation.get("productId") !== id) throw new HttpsError("already-exists", "Barkod sada pripada drugom artiklu.");
    tx.set(reservationRef, { pantryId: pantryRef.id, productId: id, barcode: code, updatedAt: timestamp });
  }
  const revision = Number(doc.get("revision") || 0) + 1;
  tx.update(ref, { deletedAt: null, purgeAfter: null, revision, updatedAt: timestamp });
  if (kind === "PRODUCT" && shoppingRef && shopping) {
    reconcileShopping(
      tx, shoppingRef, shopping,
      { id, name: doc.get("name"), category: doc.get("category") },
      Number(doc.get("minimumQuantity") || 0), Number(doc.get("totalQuantity") || 0),
      doc.get("autoShopping") !== false, timestamp,
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
    return { value, id, name: text(value, "name", 1, 100), sortOrder: integer(value, "sortOrder", 0, 10_000) };
  });
  const categoryEntries = categories.map((raw) => {
    const value = object(raw); const id = safeId(text(value, "id"));
    return { value, id, name: text(value, "name", 1, 100), sortOrder: integer(value, "sortOrder", 0, 10_000), isDefault: boolean(value, "isDefault", false) };
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
    return {
      value, id, manual, productId,
      name: text(value, "name", 1, 100), category: text(value, "category", 1, 100),
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
  const incoming = {
    shelves: new Set(shelfEntries.map((entry) => entry.id)),
    categories: new Set(categoryEntries.map((entry) => entry.id)),
    products: new Set(productEntries.map((entry) => entry.id)),
    stocks: new Set(stockEntries.map((entry) => entry.id)),
    shoppingItems: new Set(shoppingEntries.map((entry) => entry.id)),
  };
  const reservationRefs = codes.map((code) => db.doc(`barcodes/${sha256(`${pantryRef.id}:${code}`)}`));
  const reservationRefByCode = new Map(codes.map((code, index) => [code, reservationRefs[index]!]));
  const [existing, reservations] = await Promise.all([
    Promise.all([
      tx.get(pantryRef.collection("shelves")), tx.get(pantryRef.collection("categories")),
      tx.get(pantryRef.collection("products")), tx.get(pantryRef.collection("stocks")),
      tx.get(pantryRef.collection("shoppingItems")),
    ]),
    reservationRefs.length > 0 ? tx.getAll(...reservationRefs) : Promise.resolve([]),
  ]);
  const existingProducts = new Map(existing[2].docs.map((document) => [document.id, document]));
  const existingShelves = new Map(existing[0].docs.map((document) => [document.id, document]));
  const existingCategories = new Map(existing[1].docs.map((document) => [document.id, document]));
  const existingStocks = new Map(existing[3].docs.map((document) => [document.id, document]));
  const existingShopping = new Map(existing[4].docs.map((document) => [document.id, document]));
  const finalCategories = new Map<string, string>();
  if (!replaceExisting) existing[1].docs.forEach((document) => {
    if (!document.get("deletedAt")) finalCategories.set(document.id, text({ name: document.get("name") }, "name", 1, 100));
  });
  categoryEntries.forEach((entry) => finalCategories.set(entry.id, entry.name));
  const resolvedCategoryIds = new Map<string, string>();
  productEntries.forEach((entry) => {
    if (entry.categoryId && finalCategories.has(entry.categoryId)) {
      resolvedCategoryIds.set(entry.id, entry.categoryId);
      return;
    }
    if (entry.categoryId) throw new HttpsError("failed-precondition", "Artikl upućuje na nepostojeću kategoriju.");
    const matches = [...finalCategories.entries()].filter(([, name]) => name === entry.legacyCategory);
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
  const finalStocks = new Map<string, { productId: string; quantity: number }>();
  if (!replaceExisting) existing[3].docs.forEach((document) => finalStocks.set(document.id, { productId: String(document.get("productId")), quantity: Number(document.get("quantity") || 0) }));
  stockEntries.forEach((entry) => finalStocks.set(entry.id, { productId: entry.productId, quantity: entry.quantity }));
  const totals = new Map<string, number>();
  finalStocks.forEach((stock) => totals.set(stock.productId, (totals.get(stock.productId) || 0) + stock.quantity));
  const productById = new Map(productEntries.map((entry) => [entry.id, {
    name: text(entry.value, "name", 1, 100), category: finalCategories.get(resolvedCategoryIds.get(entry.id)!)!,
    minimum: integer(entry.value, "minimumQuantity", 0, 1_000_000), autoShopping: boolean(entry.value, "autoShopping", true),
  }]));
  shoppingEntries.filter((entry) => !entry.manual).forEach((entry) => {
    const product = entry.productId ? productById.get(entry.productId) : undefined;
    if (!product) throw new HttpsError("failed-precondition", "Automatska stavka nema uvezeni artikl.");
    const expected = product.autoShopping ? Math.max(product.minimum - (totals.get(entry.productId!) || 0), 0) : 0;
    if (expected === 0 || entry.requiredQuantity !== expected || entry.name !== product.name || entry.category !== product.category) {
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
      }
    }));
  }
  shelfEntries.forEach(({ id, name, sortOrder }) => {
    const existing = existingShelves.get(id);
    tx.set(pantryRef.collection("shelves").doc(id), {
      name, sortOrder, revision: Number(existing?.get("revision") || 0) + 1,
      createdAt: existing?.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
    }, { merge: true });
  });
  categoryEntries.forEach(({ id, name, sortOrder, isDefault }) => {
    const existing = existingCategories.get(id);
    tx.set(pantryRef.collection("categories").doc(id), {
      name, sortOrder, isDefault, revision: Number(existing?.get("revision") || 0) + 1,
      createdAt: existing?.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
    }, { merge: true });
  });
  productEntries.forEach(({ value, id, code }) => {
    const existing = existingProducts.get(id);
    const name = text(value, "name", 1, 100);
    const categoryId = resolvedCategoryIds.get(id)!;
    const imported = {
      name, normalizedName: name.toLocaleLowerCase("hr"), barcode: code,
      description: typeof value.description === "string" ? value.description : "",
      category: finalCategories.get(categoryId)!, categoryId,
      photoSource: value.photoSource === "OPEN_FOOD_FACTS" && value.photoUri != null ? "OPEN_FOOD_FACTS" : "NONE",
      minimumQuantity: integer(value, "minimumQuantity", 0, 1_000_000),
      autoShopping: boolean(value, "autoShopping", true), totalQuantity: totals.get(id) || 0,
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
  shoppingEntries.forEach(({ id, productId, name, category, requiredQuantity, checked, manual }) => {
    const existing = existingShopping.get(id);
    tx.set(pantryRef.collection("shoppingItems").doc(id), {
      productId, name, category, requiredQuantity, checked, manual,
      revision: Number(existing?.get("revision") || 0) + 1,
      createdAt: existing?.get("createdAt") || timestamp, updatedAt: timestamp, deletedAt: null,
    }, { merge: true });
  });
  const nextRevision = Date.now();
  tx.update(pantryRef, { revision: nextRevision, updatedAt: timestamp });
  return nextRevision;
}

function reconcileShopping(
  tx: Transaction,
  ref: DocumentReference,
  existing: DocumentSnapshot,
  product: { id: string; name: unknown; category: unknown },
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
    productId: product.id, name: product.name, category: product.category,
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

function conflict(revision: number): never {
  throw new HttpsError("aborted", `REVISION_CONFLICT:${revision}`);
}

function integerSigned(data: Data, key: string, min: number, max: number): number {
  const value = data[key];
  if (!Number.isSafeInteger(value) || Number(value) < min || Number(value) > max) throw new HttpsError("invalid-argument", `${key} nije ispravan.`);
  return Number(value);
}

function inventorySnapshotVersion(stocks: Array<{ productId: string; quantity: number }>): number {
  const canonical = stocks.sort((a, b) => a.productId.localeCompare(b.productId))
    .map((stock) => `${stock.productId}:${stock.quantity}`).join("|");
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
    default: throw new HttpsError("invalid-argument", "Ova vrsta zapisa ne podržava koš.");
  }
}

function activityType(type: string, payload: Data): string {
  const map: Record<string, string> = {
    create_shelf: "SHELF_CREATED", rename_shelf: "SHELF_RENAMED", reorder_shelves: "SHELF_REORDERED",
    delete_shelf: "SHELF_DELETED", upsert_category: "CATEGORY_UPDATED", reorder_categories: "CATEGORY_REORDERED",
    delete_category: "CATEGORY_DELETED", upsert_shopping: "SHOPPING_UPDATED", delete_shopping: "ITEM_DELETED",
    upsert_product: "PRODUCT_UPDATED", adjust_stock: "STOCK_ADDED", move_stock: "STOCK_MOVED",
    apply_inventory: "INVENTORY_APPLIED", soft_delete: "ITEM_DELETED", restore: "ITEM_RESTORED",
    import_snapshot: "IMPORT_APPLIED",
  };
  if (type === "adjust_stock" && Number(payload.delta) < 0) return "STOCK_REMOVED";
  return map[type] || "UNKNOWN";
}

function activityLabel(payload: Data, type: string): string {
  if (type === "upsert_product") return String(object(payload.product).name || "Artikl").slice(0, 100);
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

function quantityDelta(payload: Data, type: string): number | null {
  if (type === "adjust_stock") return Number(payload.delta || 0);
  if (type === "move_stock") return Number(payload.quantity || 0);
  return null;
}
