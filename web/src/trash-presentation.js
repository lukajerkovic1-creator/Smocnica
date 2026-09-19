import { inventoryPackageLabel } from "./inventory-label.js";
import { timestamp } from "./domain.js";

export function trashRows(data) {
  const types = { shelves: ["SHELF", "Polica"], categories: ["CATEGORY", "Kategorija"], products: ["PRODUCT", "Artikl"], variants: ["VARIANT", "Pakiranje"] };
  return Object.entries(types).flatMap(([kind, [type, typeLabel]]) => data[kind]
    .filter(r => r.deletedAt && (kind !== "variants" || !data.products.some(p => p.id === r.productId && p.deletedAt)))
    .map(r => {
      const product = data.products.find(p => p.id === r.productId);
      const packages = kind === "products" ? data.variants.filter(v => v.productId === r.id && v.deletedAt) : [];
      const details = kind === "variants"
        ? [inventoryPackageLabel(product?.name, r), `Pripada artiklu: ${product?.name || r.displayName}`]
        : packages.map(v => inventoryPackageLabel(r.name, v));
      return { ...r, kind, type, typeLabel, details, title: r.name || r.displayName,
        restoresTogether: packages.length > 0 };
    })).sort((a, b) => timestamp(b.deletedAt) - timestamp(a.deletedAt));
}
