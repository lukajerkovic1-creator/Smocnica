export const active = (rows) => rows.filter((row) => !row.deletedAt);
export const normalize = (value) =>
  value.normalize("NFKC").trim().replace(/\s+/gu, " ").toLocaleLowerCase("hr");
export function groupingMatches(name, products, rules = []) {
  const source = normalize(name);
  if (!source) return [];
  const rule = active(rules).find((r) => normalize(r.sourceNormalized) === source);
  const synonyms = {
    "pšenično brašno t-550": "Glatko brašno",
    "pšenično brašno t 550": "Glatko brašno",
    "brašno glatko": "Glatko brašno",
    "mlijeko trajno": "Trajno mlijeko",
    "paradajz pasiran": "Pasirana rajčica",
    "šećer kristal": "Kristal šećer",
    "ulje suncokretovo": "Suncokretovo ulje",
  };
  const target = normalize(rule?.genericName || synonyms[source] || source);
  const eligible = active(products).filter((p) => !p.doNotGroup);
  const exact = eligible.filter((p) => p.id === rule?.productId || normalize(p.name) === target);
  if (exact.length || rule) return exact;
  const words = new Set(source.replace(/\b\d+(?:[.,]\d+)?\s*(?:mg|g|kg|ml|l|kom|komada|rola|vrećica|kapsula)\b/gu, " ").split(/\s+/u).filter(Boolean));
  return eligible.map((p) => {
    const other = new Set(normalize(p.name).split(" "));
    const score = [...words].filter((w) => other.has(w)).length / new Set([...words, ...other]).size;
    return { p, score };
  }).filter(({ score }) => score >= 0.75).sort((a, b) => b.score - a.score).slice(0, 1).map(({ p }) => p);
}
export const quantity = (stocks, id, field = "productId") =>
  stocks.filter((s) => s[field] === id).reduce((n, s) => n + s.quantity, 0);
export const sorted = (rows) =>
  [...rows].sort(
    (a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name, "hr"),
  );
export const timestamp = (v) =>
  typeof v === "number"
    ? v
    : (v?.toMillis?.() ?? (v?.seconds ?? v?._seconds ?? 0) * 1000);
export function integer(value, min = 0, max = 1000000) {
  const n = Number(value);
  if (!String(value).trim() || !Number.isSafeInteger(n) || n < min || n > max)
    throw new Error(`Upišite cijeli broj od ${min} do ${max}.`);
  return n;
}
export async function hash(text) {
  return [
    ...new Uint8Array(
      await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)),
    ),
  ]
    .map((n) => n.toString(16).padStart(2, "0"))
    .join("");
}
export async function manualId(pantryId, categoryId, name) {
  return `manual_${await hash(`${pantryId}\0${categoryId}\0${normalize(name)}`)}`;
}
export function packageBase(amount, unit) {
  if (unit === "UNKNOWN" || amount === "") return null;
  const n = Number(String(amount).replace(",", "."));
  const v = Math.round(
    n * ({ KG: 1000000, G: 1000, MG: 1, L: 1000, ML: 1 }[unit] || 1),
  );
  if (!Number.isFinite(n) || n <= 0 || v < 1 || !Number.isSafeInteger(v))
    throw new Error("Provjerite veličinu pakiranja.");
  return v;
}
export function packageDisplay(v) {
  if (v.packageLabel) return v.packageLabel;
  if (!v.packageAmountBase) return "Nepoznata veličina";
  const unit = v.packageUnit;
  return `${(v.packageAmountBase / ({ KG: 1000000, G: 1000, MG: 1, L: 1000, ML: 1 }[unit] || 1)).toLocaleString("hr")} ${{ KG: "kg", G: "g", MG: "mg", L: "l", ML: "ml", PIECE: "kom", ROLL: "rola", BAG: "vrećica", CAPSULE: "kapsula" }[unit] || ""}`;
}
export function totals(product, variants, stocks) {
  const vs = active(variants).filter((v) => v.productId === product.id);
  const ids = new Set(vs.map((v) => v.id));
  const ss = stocks.filter((s) => ids.has(s.variantId));
  const packages = ss.reduce((n, s) => n + s.quantity, 0);
  const amounts = {};
  let unknown = 0;
  for (const v of vs) {
    const q = quantity(ss, v.id, "variantId");
    const kind = ["MG", "G", "KG"].includes(v.packageUnit)
      ? "mg"
      : ["ML", "L"].includes(v.packageUnit)
        ? "ml"
        : v.packageUnit;
    if (v.packageAmountBase)
      amounts[kind] = (amounts[kind] || 0) + q * v.packageAmountBase;
    else unknown += q;
  }
  return { packages, amounts, unknown };
}
export function csvCell(value) {
  let v = String(value ?? "");
  if (/^[=+@\-\t\r]/.test(v)) v = "'" + v;
  return '"' + v.replaceAll('"', '""') + '"';
}
export function belowMinimum(product, variants, stocks) {
  const sum = totals(product, variants, stocks);
  const current =
    product.minimumMode === "MASS_MG"
      ? sum.amounts.mg || 0
      : product.minimumMode === "VOLUME_ML"
        ? sum.amounts.ml || 0
        : product.minimumMode === "COUNT"
          ? Object.entries(sum.amounts)
              .filter(([k]) => !["mg", "ml"].includes(k))
              .reduce((n, [, v]) => n + v, 0)
          : sum.packages;
  return (
    current < (product.minimumAmountBase ?? product.minimumQuantity ?? 0) ||
    active(variants).some(
      (v) =>
        v.productId === product.id &&
        v.minimumPackages != null &&
        quantity(stocks, v.id, "variantId") < v.minimumPackages,
    )
  );
}
export function snapshot(data, pantry, includeHistory = false) {
  return {
    schemaVersion: 2,
    exportedAt: Date.now(),
    pantry,
    settings: {},
    ...Object.fromEntries(
      [
        "shelves",
        "categories",
        "products",
        "variants",
        "stocks",
        "shoppingItems",
        "synonymRules",
      ].map((k) => [
        k,
        active(data[k] || []).map(({ photoUrl, ...row }) => ({
          ...row,
          ...(photoUrl ? { photoUri: photoUrl } : {}),
        })),
      ]),
    ),
    ...(includeHistory ? { activities: data.activities } : {}),
  };
}
export function validateSnapshot(value) {
  if (!value || value.schemaVersion !== 2)
    throw new Error("Podržan je JSON izvoz verzije 2.");
  for (const name of [
    "shelves",
    "categories",
    "products",
    "variants",
    "stocks",
    "shoppingItems",
    "synonymRules",
  ]) {
    if (!Array.isArray(value[name]))
      throw new Error(`Nedostaje popis: ${name}.`);
    const ids = new Set();
    for (const row of value[name]) {
      if (
        !row ||
        typeof row.id !== "string" ||
        !/^[\w-]{1,128}$/.test(row.id) ||
        ids.has(row.id)
      )
        throw new Error("Neispravni ili ponovljeni identifikatori.");
      ids.add(row.id);
    }
  }
  const products = new Set(value.products.map((p) => p.id)),
    shelves = new Set(value.shelves.map((s) => s.id)),
    variants = new Map(value.variants.map((v) => [v.id, v.productId])),
    categories = new Set(value.categories.map((c) => c.id));
  for (const p of value.products)
    if (!categories.has(p.categoryId))
      throw new Error("Artikl nema valjanu kategoriju.");
  for (const v of value.variants)
    if (!products.has(v.productId))
      throw new Error("Pakiranje nema valjani artikl.");
  for (const s of value.stocks) {
    integer(s.quantity);
    if (!shelves.has(s.shelfId) || variants.get(s.variantId) !== s.productId)
      throw new Error("Zaliha ima neispravne veze.");
  }
  return value;
}
