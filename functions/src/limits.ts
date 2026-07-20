import { HttpsError } from "firebase-functions/v2/https";

export const PANTRY_LIMITS = Object.freeze({
  members: 10,
  shelves: 50,
  categories: 50,
  products: 500,
  activeInvitations: 1,
});

export function assertResourceLimit(currentActive: number, maximum: number, label: string): void {
  if (currentActive >= maximum) {
    throw new HttpsError("resource-exhausted", `Dosegnut je najveći dopušteni broj ${label} (${maximum}).`);
  }
}

export function assertFinalResourceLimit(finalActive: number, maximum: number, label: string): void {
  if (finalActive > maximum) {
    throw new HttpsError("resource-exhausted", `Broj ${label} prelazi dopušteni maksimum (${maximum}).`);
  }
}
