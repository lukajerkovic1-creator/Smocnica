import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { db, daysFromNow, now } from "./firebase";
import { PANTRY_LIMITS, assertFinalResourceLimit } from "./limits";
import { Data, authUid, barcode, boolean, integer, normalizedName, object, safeId, sha256, text } from "./validation";

const options = { region: "europe-west1", enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== "true", timeoutSeconds: 540, memory: "1GiB" as const };
const CHUNK_SIZE = 200;
type CollectionName = "shelves" | "categories" | "products" | "variants" | "synonymRules" | "stocks" | "shoppingItems";
type ImportWrite = { collection: CollectionName; id: string; data: Data };

/**
 * Validates the complete snapshot before the first content write, then applies deterministic
 * chunks. The persisted cursor and operation digest make retries after timeout/force-stop safe.
 * A failed chunk may be repeated, but never double-counts because every write is an absolute set.
 */
export const importSnapshotJob = onCall(options, async (request) => {
  const uid = authUid(request);
  const data = object(request.data);
  const operationId = safeId(text(data, "operationId"), "operationId");
  const pantryId = safeId(text(data, "pantryId"), "pantryId");
  const deviceId = safeId(text(data, "deviceId", 1, 128), "deviceId");
  const payload = object(data.payload, "payload");
  if (text(payload, "type") !== "import_snapshot") throw new HttpsError("invalid-argument", "Ova funkcija prihvaća samo uvoz sigurnosne kopije.");
  const snapshot = object(payload.snapshot, "snapshot");
  const replaceExisting = payload.replaceExisting === true;
  const digest = sha256(JSON.stringify({ snapshot, replaceExisting }));
  const pantryRef = db.doc(`pantries/${pantryId}`);
  const jobRef = pantryRef.collection("importJobs").doc(operationId);
  const operationRef = pantryRef.collection("operations").doc(operationId);
  const [pantry, member, device, existingOperation, existingJob] = await Promise.all([
    pantryRef.get(), pantryRef.collection("members").doc(uid).get(), db.doc(`users/${uid}/devices/${deviceId}`).get(),
    operationRef.get(), jobRef.get(),
  ]);
  if (existingOperation.exists) return { status: "ALREADY_APPLIED", revision: Number(existingOperation.get("resultRevision") || 0) };
  if (!pantry.exists || pantry.get("deletedAt")) throw new HttpsError("not-found", "Smočnica nije dostupna.");
  if (!member.exists || member.get("active") !== true) throw new HttpsError("permission-denied", "Korisnik nije aktivan član smočnice.");
  if (!device.exists || device.get("active") !== true) throw new HttpsError("permission-denied", "Uređaj nije aktivno registriran.");
  if (existingJob.exists && existingJob.get("digest") !== digest) throw new HttpsError("already-exists", "Isti ID uvoza već pripada drugoj sigurnosnoj kopiji.");
  const writes = validateAndNormalize(snapshot, pantryId, member.get("role") === "OWNER");
  const revision = existingJob.exists ? Number(existingJob.get("revision")) : Date.now();

  if (!existingJob.exists) {
    await db.runTransaction(async (tx) => {
      const [currentPantry, currentJob] = await Promise.all([tx.get(pantryRef), tx.get(jobRef)]);
      if (currentJob.exists) return;
      const activeImport = currentPantry.get("importInProgress");
      if (activeImport && activeImport !== operationId) throw new HttpsError("aborted", "Drugi uvoz je već u tijeku.");
      tx.create(jobRef, { digest, revision, cursor: 0, phase: "APPLYING", replaceExisting, createdAt: now(), updatedAt: now(), expiresAt: daysFromNow(30) });
      tx.update(pantryRef, { importInProgress: operationId, updatedAt: now() });
    });
  }

  let cursor = existingJob.exists ? Number(existingJob.get("cursor") || 0) : 0;
  while (cursor < writes.length) {
    const chunk = writes.slice(cursor, cursor + CHUNK_SIZE);
    const writer = db.bulkWriter();
    chunk.forEach((entry) => writer.set(pantryRef.collection(entry.collection).doc(entry.id), {
      ...entry.data, revision, updatedAt: Timestamp.fromMillis(revision), deletedAt: null, purgeAfter: null,
    }, { merge: true }));
    await writer.close();
    cursor += chunk.length;
    await jobRef.set({ cursor, updatedAt: now() }, { merge: true });
  }

  if (replaceExisting) await removeMissingDocuments(pantryRef.path, writes, revision);
  await rebuildBarcodeReservations(pantryId, writes, revision);

  await db.runTransaction(async (tx) => {
    const [operation, job, currentPantry] = await Promise.all([tx.get(operationRef), tx.get(jobRef), tx.get(pantryRef)]);
    if (operation.exists) return;
    if (!job.exists || job.get("digest") !== digest || Number(job.get("cursor")) !== writes.length) {
      throw new HttpsError("aborted", "Uvoz nije dovršio sve dijelove.");
    }
    if (currentPantry.get("importInProgress") !== operationId) throw new HttpsError("aborted", "Uvoz više nije aktivan.");
    tx.create(operationRef, {
      actorUid: uid, aggregateType: "PANTRY", aggregateId: pantryId, payloadType: "import_snapshot",
      resultRevision: revision, appliedAt: now(), expiresAt: daysFromNow(365), resultDigest: digest,
    });
    tx.create(pantryRef.collection("activities").doc(operationId), {
      type: "IMPORT_APPLIED", aggregateId: pantryId, displayLabel: "Uvezena sigurnosna kopija",
      quantityDelta: null, oldValue: null, newValue: replaceExisting ? "REPLACE" : "MERGE",
      actorUid: uid, deviceId, deviceDisplayName: String(device.get("name") || "Android uređaj").slice(0, 100),
      createdAt: now(), expiresAt: daysFromNow(365),
    });
    tx.update(jobRef, { phase: "COMPLETED", completedAt: now(), updatedAt: now() });
    tx.update(pantryRef, { importInProgress: FieldValue.delete(), revision, updatedAt: now() });
  });
  return { status: "APPLIED", revision };
});

function validateAndNormalize(snapshot: Data, pantryId: string, owner: boolean): ImportWrite[] {
  const rawShelves = values(snapshot, "shelves");
  const rawCategories = values(snapshot, "categories");
  const rawProducts = values(snapshot, "products");
  const rawVariants = values(snapshot, "variants");
  const rawStocks = values(snapshot, "stocks");
  const rawShopping = values(snapshot, "shoppingItems");
  const rawRules = values(snapshot, "synonymRules");
  assertFinalResourceLimit(rawShelves.length, PANTRY_LIMITS.shelves, "polica");
  assertFinalResourceLimit(rawCategories.length, PANTRY_LIMITS.categories, "kategorija");
  assertFinalResourceLimit(rawProducts.length, PANTRY_LIMITS.products, "artikala");
  assertFinalResourceLimit(rawVariants.length, PANTRY_LIMITS.products, "varijanti");
  if (rawRules.length > 0 && !owner) throw new HttpsError("permission-denied", "Samo vlasnik smije uvesti zajednički rječnik.");

  const shelfIds = uniqueIds(rawShelves, "polica");
  const categoryIds = uniqueIds(rawCategories, "kategorija");
  const productIds = uniqueIds(rawProducts, "artikala");
  const variantIds = uniqueIds(rawVariants, "varijanti");
  assertUniqueNames(rawShelves, "polica");
  assertUniqueNames(rawCategories, "kategorija");
  assertUniqueNames(rawProducts, "artikala");
  const categoryNames = new Map(rawCategories.map((raw) => {
    const value = object(raw); return [safeId(text(value, "id")), text(value, "name", 1, 100)];
  }));
  const variantProducts = new Map<string, string>();
  const usedBarcodes = new Set<string>();
  const writes: ImportWrite[] = [];
  rawShelves.forEach((raw, index) => {
    const value = object(raw); const id = safeId(text(value, "id")); const name = text(value, "name", 1, 100);
    writes.push({ collection: "shelves", id, data: { name, normalizedName: normalizedName(name), sortOrder: optionalInteger(value.sortOrder, index, 0, 10_000), createdAt: value.createdAt ?? now() } });
  });
  const requestedDefault = rawCategories.findIndex((raw) => object(raw).isDefault === true);
  rawCategories.forEach((raw, index) => {
    const value = object(raw); const id = safeId(text(value, "id")); const name = text(value, "name", 1, 100);
    writes.push({ collection: "categories", id, data: { name, normalizedName: normalizedName(name), sortOrder: optionalInteger(value.sortOrder, index, 0, 10_000), isDefault: index === (requestedDefault >= 0 ? requestedDefault : 0) } });
  });
  rawProducts.forEach((raw) => {
    const value = object(raw); const id = safeId(text(value, "id")); const name = capitalized(text(value, "name", 1, 100));
    const categoryId = safeId(text(value, "categoryId"), "categoryId");
    if (!categoryIds.has(categoryId)) throw new HttpsError("failed-precondition", "Artikl upućuje na nepostojeću kategoriju.");
    const minimumMode = allowed(String(value.minimumMode || "PACKAGES"), ["PACKAGES", "MASS_MG", "VOLUME_ML", "COUNT"], "minimumMode");
    const minimumAmountBase = optionalInteger(value.minimumAmountBase, optionalInteger(value.minimumQuantity, 0, 0, 1_000_000), 0, 1_000_000_000_000);
    writes.push({ collection: "products", id, data: {
      name, normalizedName: normalizedName(name), barcode: null, description: "", categoryId, category: categoryNames.get(categoryId)!,
      photoUrl: null, photoSource: "NONE", minimumQuantity: minimumMode === "PACKAGES" ? minimumAmountBase : 0,
      minimumMode, minimumAmountBase, preferredVariantId: typeof value.preferredVariantId === "string" ? safeId(value.preferredVariantId) : null,
      doNotGroup: value.doNotGroup === true, groupingRevision: optionalInteger(value.groupingRevision, 0, 0), autoShopping: boolean(value, "autoShopping", true),
      createdAt: value.createdAt ?? now(), totalQuantity: FieldValue.delete(),
    } });
  });
  rawVariants.forEach((raw) => {
    const value = object(raw); const id = safeId(text(value, "id")); const productId = safeId(text(value, "productId"));
    if (!productIds.has(productId)) throw new HttpsError("failed-precondition", "Varijanta upućuje na nepostojeći artikl.");
    variantProducts.set(id, productId);
    const code = barcode(value.barcode);
    if (code && usedBarcodes.has(code)) throw new HttpsError("already-exists", "Barkod je više puta naveden u sigurnosnoj kopiji.");
    if (code) usedBarcodes.add(code);
    const packageUnit = allowed(String(value.packageUnit || "UNKNOWN"), ["MG", "G", "KG", "ML", "L", "PIECE", "ROLL", "BAG", "CAPSULE", "UNKNOWN"], "packageUnit");
    const amount = value.packageAmountBase == null ? null : optionalInteger(value.packageAmountBase, 0, 1, 1_000_000_000_000);
    if ((packageUnit === "UNKNOWN") !== (amount === null)) throw new HttpsError("invalid-argument", "Veličina pakiranja i jedinica nisu usklađene.");
    writes.push({ collection: "variants", id, data: {
      productId, displayName: text(value, "displayName", 1, 100), manufacturer: typeof value.manufacturer === "string" ? value.manufacturer.trim().slice(0, 100) : "",
      barcode: code, packageAmountBase: amount, packageUnit, packageLabel: string(value.packageLabel, 100), description: string(value.description, 500),
      photoUrl: photo(value.photoUri, value.photoSource, pantryId, id), photoSource: allowed(String(value.photoSource || "NONE"), ["NONE", "OPEN_FOOD_FACTS", "CAMERA", "GALLERY"], "photoSource"),
      minimumPackages: value.minimumPackages == null ? null : optionalInteger(value.minimumPackages, 0, 0, 1_000_000),
      purchaseCount: optionalInteger(value.purchaseCount, 0, 0), createdAt: value.createdAt ?? now(),
    } });
  });
  rawProducts.forEach((raw) => {
    const value = object(raw); const preferred = value.preferredVariantId;
    if (typeof preferred === "string" && variantProducts.get(preferred) !== String(value.id)) throw new HttpsError("failed-precondition", "Preferirana varijanta ne pripada artiklu.");
  });
  rawStocks.forEach((raw) => {
    const value = object(raw); const variantId = safeId(typeof value.variantId === "string" ? value.variantId : text(value, "productId"), "variantId");
    const shelfId = safeId(text(value, "shelfId")); const productId = variantProducts.get(variantId);
    if (!productId || !variantIds.has(variantId) || !shelfIds.has(shelfId)) throw new HttpsError("failed-precondition", "Zaliha upućuje na nepostojeću varijantu ili policu.");
    writes.push({ collection: "stocks", id: `${variantId}_${shelfId}`, data: { productId, variantId, shelfId, quantity: integer(value, "quantity", 0, 1_000_000) } });
  });
  rawShopping.forEach((raw) => {
    const value = object(raw); const id = safeId(text(value, "id")); const productId = typeof value.productId === "string" ? safeId(value.productId) : null;
    const categoryId = safeId(text(value, "categoryId"), "categoryId");
    if (!categoryIds.has(categoryId) || (productId && !productIds.has(productId))) throw new HttpsError("failed-precondition", "Stavka kupnje ima nepostojeću vezu.");
    writes.push({ collection: "shoppingItems", id, data: {
      productId, name: text(value, "name", 1, 100), categoryId, category: categoryNames.get(categoryId)!,
      requiredQuantity: integer(value, "requiredQuantity", 0, 1_000_000), checked: boolean(value, "checked"), manual: boolean(value, "manual"),
      preferredVariantId: typeof value.preferredVariantId === "string" ? safeId(value.preferredVariantId) : null,
      createdAt: value.createdAt ?? now(),
    } });
  });
  rawRules.forEach((raw) => {
    const value = object(raw); const id = safeId(text(value, "id")); const genericName = capitalized(text(value, "genericName", 1, 100));
    const productId = typeof value.productId === "string" ? safeId(value.productId) : null;
    if (productId && !productIds.has(productId)) throw new HttpsError("failed-precondition", "Pravilo upućuje na nepostojeći artikl.");
    writes.push({ collection: "synonymRules", id, data: {
      sourceNormalized: normalizedName(text(value, "sourceNormalized", 1, 100)), genericName,
      genericNameNormalized: normalizedName(genericName), productId, ownerConfirmed: true,
    } });
  });
  return writes;
}

async function removeMissingDocuments(pantryPath: string, writes: ImportWrite[], revision: number): Promise<void> {
  const incoming = new Map<CollectionName, Set<string>>();
  writes.forEach((entry) => {
    const ids = incoming.get(entry.collection) || new Set<string>(); ids.add(entry.id); incoming.set(entry.collection, ids);
  });
  const pantryRef = db.doc(pantryPath);
  const collections: CollectionName[] = ["shelves", "categories", "products", "variants", "synonymRules", "stocks", "shoppingItems"];
  for (const collection of collections) {
    const existing = await pantryRef.collection(collection).get();
    const writer = db.bulkWriter();
    existing.docs.filter((doc) => !(incoming.get(collection)?.has(doc.id) ?? false)).forEach((doc) => {
      if (collection === "stocks") writer.delete(doc.ref);
      else writer.set(doc.ref, { deletedAt: Timestamp.fromMillis(revision), purgeAfter: daysFromNow(30), revision, updatedAt: Timestamp.fromMillis(revision) }, { merge: true });
    });
    await writer.close();
  }
}

async function rebuildBarcodeReservations(pantryId: string, writes: ImportWrite[], revision: number): Promise<void> {
  const variants = writes.filter((entry) => entry.collection === "variants");
  const existing = await db.collection("barcodes").where("pantryId", "==", pantryId).get();
  const desired = new Set(variants.map((entry) => entry.data.barcode).filter((value): value is string => typeof value === "string"));
  const writer = db.bulkWriter();
  existing.docs.filter((doc) => !desired.has(String(doc.get("barcode")))).forEach((doc) => writer.delete(doc.ref));
  variants.forEach((entry) => {
    const code = entry.data.barcode;
    if (typeof code === "string") writer.set(db.doc(`barcodes/${sha256(`${pantryId}:${code}`)}`), {
      pantryId, productId: entry.data.productId, variantId: entry.id, barcode: code, updatedAt: Timestamp.fromMillis(revision),
    }, { merge: true });
  });
  await writer.close();
}

function values(data: Data, key: string): unknown[] {
  const value = data[key];
  if (value === undefined) return [];
  if (!Array.isArray(value)) throw new HttpsError("invalid-argument", `${key} mora biti polje.`);
  return value;
}
function uniqueIds(raw: unknown[], label: string): Set<string> {
  const ids = raw.map((value) => safeId(text(object(value), "id")));
  if (new Set(ids).size !== ids.length) throw new HttpsError("invalid-argument", `Dupli ID ${label}.`);
  return new Set(ids);
}
function assertUniqueNames(raw: unknown[], label: string): void {
  const names = raw.map((value) => normalizedName(text(object(value), "name", 1, 100)));
  if (new Set(names).size !== names.length) throw new HttpsError("already-exists", `Dupli naziv ${label}.`);
}
function optionalInteger(value: unknown, fallback: number, min: number, max = Number.MAX_SAFE_INTEGER): number {
  if (value === undefined || value === null) return fallback;
  if (!Number.isSafeInteger(value) || Number(value) < min || Number(value) > max) throw new HttpsError("invalid-argument", "Broj nije u dopuštenom rasponu.");
  return Number(value);
}
function allowed(value: string, choices: string[], label: string): string {
  if (!choices.includes(value)) throw new HttpsError("invalid-argument", `${label} nije ispravan.`); return value;
}
function string(value: unknown, max: number): string {
  if (value == null) return ""; if (typeof value !== "string" || value.length > max) throw new HttpsError("invalid-argument", "Tekst je predug."); return value.trim();
}
function capitalized(value: string): string { return value.charAt(0).toLocaleUpperCase("hr-HR") + value.slice(1); }
function photo(uri: unknown, source: unknown, pantryId: string, variantId: string): string | null {
  if (uri == null || uri === "") return null;
  if (typeof uri !== "string") throw new HttpsError("invalid-argument", "Fotografija nije ispravna.");
  if (source === "OPEN_FOOD_FACTS") {
    const url = new URL(uri); const host = url.hostname.toLowerCase();
    if (url.protocol !== "https:" || !(host === "images.openfoodfacts.org" || host.endsWith(".openfoodfacts.org"))) throw new HttpsError("invalid-argument", "Javna fotografija nije s dopuštene domene.");
    return uri;
  }
  if ((source === "CAMERA" || source === "GALLERY") && new RegExp(`^gs://[^/]+/pantries/${pantryId}/variants/${variantId}/main\\.jpg$`).test(uri)) return uri;
  throw new HttpsError("invalid-argument", "Fotografija nema dopuštenu putanju.");
}
