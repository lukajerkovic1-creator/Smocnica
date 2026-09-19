import { active, hash, timestamp, validateSnapshot, packageBase } from "./domain.js";
// Field order and defaults mirror Kotlin's PantrySnapshot serializer. This keeps
// JSON checksums and exported files compatible with the Android application.
const compact = (object) =>
  Object.fromEntries(
    Object.entries(object).filter(([, v]) => v !== null && v !== undefined),
  );
const time = (v) => (v == null ? null : timestamp(v));
export function canonicalSnapshot(data, pantry, includeHistory = false) {
  const id = pantry.id;
  const common = (r) => ({
    revision: r.revision || 0,
    createdAt: timestamp(r.createdAt),
    updatedAt: timestamp(r.updatedAt),
    deletedAt: time(r.deletedAt),
    purgeAfter: time(r.purgeAfter),
    syncState: "SYNCED",
  });
  const products = active(data.products),
    productIds = new Set(products.map((p) => p.id));
  const variants = active(data.variants).filter((v) =>
      productIds.has(v.productId),
    ),
    variantIds = new Set(variants.map((v) => v.id));
  return {
    pantry: compact({
      id,
      name: pantry.name,
      ownerUid: pantry.ownerUid,
      ...common(pantry),
      contentSchemaVersion: 2,
      groupingReviewCompletedAt: time(pantry.groupingReviewCompletedAt),
    }),
    members: data.members
      .filter((m) => m.active)
      .map((m) =>
        compact({
          pantryId: id,
          uid: m.uid || m.id,
          displayName: m.displayName || "Korisnik",
          photoUrl: m.photoUrl,
          role: m.role,
          joinedAt: timestamp(m.joinedAt),
          active: true,
        }),
      ),
    shelves: active(data.shelves).map((s) =>
      compact({
        id: s.id,
        pantryId: id,
        name: s.name,
        sortOrder: s.sortOrder,
        ...common(s),
      }),
    ),
    categories: active(data.categories).map((c) =>
      compact({
        id: c.id,
        pantryId: id,
        name: c.name,
        sortOrder: c.sortOrder,
        isDefault: c.isDefault || false,
        revision: c.revision || 0,
        deletedAt: time(c.deletedAt),
        purgeAfter: time(c.purgeAfter),
        syncState: "SYNCED",
      }),
    ),
    products: products.map((p) =>
      compact({
        id: p.id,
        pantryId: id,
        name: p.name,
        barcode: null,
        description: "",
        category: p.category || "Ostalo",
        photoUri: null,
        photoSource: "NONE",
        minimumQuantity: p.minimumQuantity || 0,
        autoShopping: p.autoShopping !== false,
        ...common(p),
        categoryId: p.categoryId,
        minimumMode: p.minimumMode || "PACKAGES",
        minimumAmountBase: p.minimumAmountBase ?? p.minimumQuantity ?? 0,
        preferredVariantId: p.preferredVariantId,
        doNotGroup: p.doNotGroup || false,
        groupingRevision: p.groupingRevision || 0,
      }),
    ),
    stocks: data.stocks
      .filter((s) => variantIds.has(s.variantId))
      .map((s) => ({
        pantryId: id,
        productId: s.productId,
        shelfId: s.shelfId,
        quantity: s.quantity,
        revision: s.revision || 0,
        updatedAt: timestamp(s.updatedAt),
        syncState: "SYNCED",
        variantId: s.variantId,
      })),
    shoppingItems: active(data.shoppingItems).map((i) =>
      compact({
        id: i.id,
        pantryId: id,
        productId: i.productId,
        name: i.name,
        category: i.category || "Ostalo",
        requiredQuantity: i.requiredQuantity,
        checked: i.checked || false,
        manual: i.manual,
        revision: i.revision || 0,
        createdAt: timestamp(i.createdAt),
        updatedAt: timestamp(i.updatedAt),
        deletedAt: null,
        syncState: "SYNCED",
        categoryId: i.categoryId,
        preferredVariantId: i.preferredVariantId,
      }),
    ),
    activities: includeHistory
      ? data.activities.map((a) =>
          compact({
            id: a.id,
            pantryId: id,
            type: a.type || "UNKNOWN",
            aggregateId: a.aggregateId,
            displayLabel: a.displayLabel || "",
            quantityDelta: a.quantityDelta,
            actorUid: a.actorUid,
            deviceId: a.deviceId,
            deviceName: a.deviceDisplayName || a.deviceName || "",
            oldValue: a.oldValue,
            newValue: a.newValue,
            createdAt: timestamp(a.createdAt),
            productId: a.productId,
            shelfId: a.shelfId,
            fromShelfId: a.fromShelfId,
            toShelfId: a.toShelfId,
          }),
        )
      : [],
    variants: variants.map((v) =>
      compact({
        id: v.id,
        pantryId: id,
        productId: v.productId,
        displayName: v.displayName,
        manufacturer: v.manufacturer || "",
        barcode: v.barcode,
        packageAmountBase: v.packageAmountBase,
        packageUnit: v.packageUnit || "UNKNOWN",
        packageLabel: v.packageLabel || "",
        description: v.description || "",
        photoUri: v.photoUrl || v.photoUri,
        photoSource: v.photoSource || "NONE",
        minimumPackages: v.minimumPackages,
        purchaseCount: v.purchaseCount || 0,
        ...common(v),
      }),
    ),
    synonymRules: active(data.synonymRules).map((r) =>
      compact({
        id: r.id,
        pantryId: id,
        sourceNormalized: r.sourceNormalized,
        genericName: r.genericName,
        genericNameNormalized: r.genericNameNormalized,
        productId: r.productId,
        ownerConfirmed: r.ownerConfirmed || false,
        revision: r.revision || 0,
        updatedAt: timestamp(r.updatedAt),
        deletedAt: null,
        syncState: "SYNCED",
      }),
    ),
  };
}
export async function exportBackup(data, pantry, history) {
  const snapshot = canonicalSnapshot(data, pantry, history);
  return {
    schemaVersion: 3,
    exportedAt: Date.now(),
    checksumSha256: await hash(JSON.stringify(snapshot)),
    snapshot,
  };
}
export async function readBackup(text) {
  const envelope = JSON.parse(text.replace(/^\uFEFF/, ""));
  if (
    ![1, 2, 3].includes(envelope.schemaVersion) ||
    !envelope.snapshot ||
    typeof envelope.checksumSha256 !== "string"
  )
    throw new Error("Odaberite sigurnosnu kopiju Smočnice verzije 1, 2 ili 3.");
  if (
    (await hash(JSON.stringify(envelope.snapshot))) !==
    envelope.checksumSha256.toLowerCase()
  )
    throw new Error(
      "Kontrolni sažetak nije ispravan. Datoteka je oštećena ili izmijenjena.",
    );
  const s = envelope.snapshot;
  if (envelope.schemaVersion < 3 && !s.variants?.length) {
    s.variants = s.products.map((p) => {
      const match = (p.description || "").match(/(?:^|\s)(\d+(?:[.,]\d+)?)\s*(mg|kg|g|ml|l|kom(?:ad(?:a)?)?|rola|role|vrećica|vrećice|kapsula|kapsule)(?:\s|$)/iu);
      const units = { mg: "MG", kg: "KG", g: "G", ml: "ML", l: "L", rola: "ROLL", role: "ROLL", vrećica: "BAG", vrećice: "BAG", kapsula: "CAPSULE", kapsule: "CAPSULE" };
      const unit = match ? units[match[2].toLocaleLowerCase("hr")] || "PIECE" : "UNKNOWN";
      return { ...p, productId: p.id, displayName: p.name, manufacturer: "", packageUnit: unit, packageAmountBase: match ? packageBase(match[1], unit) : null, packageLabel: match?.[0].trim() || "" };
    });
    s.products = s.products.map((p) => ({ ...p, barcode: null, description: "", photoUri: null, photoSource: "NONE", minimumMode: "PACKAGES", minimumAmountBase: p.minimumQuantity || 0, preferredVariantId: p.id }));
    s.stocks = s.stocks.map((row) => ({ ...row, variantId: row.productId }));
    s.shoppingItems = s.shoppingItems.map((row) => ({ ...row, preferredVariantId: row.productId || null }));
    s.synonymRules ||= [];
  }
  return validateSnapshot({
    schemaVersion: 2,
    ...s,
    stocks: s.stocks.map((row) => ({
      ...row,
      id: `${row.variantId || row.productId}_${row.shelfId}`,
    })),
  });
}
export function mergeConflicts(current, incoming) {
  const conflicts = [];
  for (const kind of ["shelves", "categories", "products"])
    for (const item of incoming[kind]) {
      const other = active(current[kind]).find(
        (row) =>
          row.id !== item.id &&
          row.name.normalize("NFKC").trim().toLocaleLowerCase("hr") ===
            item.name.normalize("NFKC").trim().toLocaleLowerCase("hr"),
      );
      if (other)
        conflicts.push(`Naziv „${item.name}” već pripada drugom zapisu.`);
    }
  for (const variant of incoming.variants)
    if (
      variant.barcode &&
      active(current.variants).some(
        (row) => row.id !== variant.id && row.barcode === variant.barcode,
      )
    )
      conflicts.push(
        `Barkod pakiranja „${variant.displayName}” već je zauzet.`,
      );
  return conflicts;
}
