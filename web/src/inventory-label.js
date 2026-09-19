import { normalize, packageDisplay } from "./domain.js";

// Keep the size near the start, including when the name and brand are identical.
export function inventoryPackageLabel(productName, variant) {
  const hasSize = variant.packageAmountBase > 0 &&
    variant.packageUnit && variant.packageUnit !== "UNKNOWN";
  const size = hasSize
    ? packageDisplay({ ...variant, packageLabel: "" })
    : variant.packageLabel?.trim() || "Nepoznata veličina";
  const parts = [
    productName?.trim() || variant.displayName?.trim() || "Artikl",
    size,
    variant.manufacturer,
    variant.displayName,
    !hasSize && variant.barcode ? `Barkod: ${variant.barcode}` : "",
  ].map((part) => part?.trim()).filter(Boolean);
  const seen = new Set();
  return parts.filter((part) => {
    const key = normalize(part);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  }).join(" · ");
}
