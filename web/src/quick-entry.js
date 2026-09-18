import { active, timestamp } from "./domain.js";

export function packageFields(label) {
  // Only unambiguous single-package quantities; multipacks need confirmation.
  const match = String(label || "").trim().match(/^(\d+(?:[.,]\d{1,3})?)\s*(kg|g|ml|l)$/i);
  if (!match || Number(match[1].replace(",", ".")) <= 0 || Number(match[1].replace(",", ".")) > 1_000_000)
    return { amount: "", unit: "UNKNOWN" };
  return { amount: match[1].replace(",", "."), unit: match[2].toUpperCase() };
}

export function photoRecognitionError(error) {
  const code = String(error?.code || "").replace(/^functions\//, "");
  const messages = {
    "deadline-exceeded": "Prepoznavanje traje predugo. Pokušajte ponovno.",
    "resource-exhausted": "Prepoznavanje je privremeno ograničeno. Pokušajte kasnije.",
    "unavailable": "Usluga prepoznavanja trenutačno nije dostupna. Pokušajte ponovno.",
    "failed-precondition": "Usluga prepoznavanja još nije postavljena.",
    "unauthenticated": "Prijava je istekla. Ponovno se prijavite.",
    "permission-denied": "Nemate dopuštenje za prepoznavanje u ovoj smočnici.",
  };
  return `${messages[code] || "Prepoznavanje nije uspjelo. Pokušajte ponovno."} Fotografija je sačuvana; podatke možete unijeti ručno.`;
}

export function recentEntries(data) {
  const products = new Map(active(data.products).map(p => [p.id, p]));
  return active(data.variants).filter(v => products.has(v.productId)).map(variant => ({
    variant, product: products.get(variant.productId),
    usedAt: Math.max(timestamp(variant.createdAt), ...data.stocks.filter(s => s.variantId === variant.id).map(s => timestamp(s.updatedAt)), 0),
  })).sort((a, b) => b.usedAt - a.usedAt || a.variant.id.localeCompare(b.variant.id)).slice(0, 8);
}

export function entryShelf(shelves, explicit, remembered) {
  return shelves.find(s => s.id === explicit)?.id || shelves.find(s => s.id === remembered)?.id || shelves[0]?.id || "";
}

export function mergePhotoSuggestion(current, starting, suggestion, additional) {
  const result = { ...current };
  for (const field of ["name", "manufacturer"]) {
    if (current[field] === starting[field] && (!additional || !current[field]) && suggestion[field]) result[field] = suggestion[field];
  }
  if (current.amount === starting.amount && current.unit === starting.unit && (!additional || !current.amount) && suggestion.packageAmount) {
    result.amount = suggestion.packageAmount;
    result.unit = suggestion.packageUnit || "UNKNOWN";
  }
  return result;
}

export function stockDelta(entry, shelfId, delta) {
  if (!shelfId) throw new Error("Najprije dodajte policu.");
  return { productId: entry.product.id, variantId: entry.variant.id, shelfId, delta };
}

export function photoBase64(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result.split(",")[1]);
    reader.onerror = () => reject(new Error("Fotografiju nije moguće pročitati."));
    reader.readAsDataURL(blob);
  });
}
