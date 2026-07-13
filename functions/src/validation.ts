import { createHash, randomBytes } from "node:crypto";
import { HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { db } from "./firebase";

export type Data = Record<string, unknown>;

export function authUid(request: CallableRequest): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Prijava je obvezna.");
  return uid;
}

export function object(value: unknown, name = "data"): Data {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpsError("invalid-argument", `${name} mora biti objekt.`);
  }
  return value as Data;
}

export function text(data: Data, key: string, min = 1, max = 100): string {
  const value = data[key];
  if (typeof value !== "string" || value.trim().length < min || value.trim().length > max) {
    throw new HttpsError("invalid-argument", `${key} mora imati ${min}-${max} znakova.`);
  }
  return value.trim();
}

export function optionalText(data: Data, key: string, max = 500): string | null {
  const value = data[key];
  if (value === null || value === undefined || value === "") return null;
  if (typeof value !== "string" || value.length > max) throw new HttpsError("invalid-argument", `${key} nije ispravan.`);
  return value.trim();
}

export function integer(data: Data, key: string, min = 0, max = Number.MAX_SAFE_INTEGER): number {
  const value = data[key];
  if (!Number.isSafeInteger(value) || (value as number) < min || (value as number) > max) {
    throw new HttpsError("invalid-argument", `${key} mora biti cijeli broj ${min}-${max}.`);
  }
  return value as number;
}

export function boolean(data: Data, key: string, fallback = false): boolean {
  const value = data[key];
  if (value === undefined) return fallback;
  if (typeof value !== "boolean") throw new HttpsError("invalid-argument", `${key} mora biti true ili false.`);
  return value;
}

export function stringArray(data: Data, key: string, max = 500): string[] {
  const value = data[key];
  if (!Array.isArray(value) || value.length > max || value.some((item) => typeof item !== "string")) {
    throw new HttpsError("invalid-argument", `${key} mora biti polje stringova.`);
  }
  return value as string[];
}

export async function requireMember(pantryId: string, uid: string): Promise<Data> {
  const member = await db.doc(`pantries/${pantryId}/members/${uid}`).get();
  if (!member.exists || member.get("active") !== true) throw new HttpsError("permission-denied", "Korisnik nije član smočnice.");
  return member.data() as Data;
}

export async function requireOwner(pantryId: string, uid: string): Promise<void> {
  const member = await requireMember(pantryId, uid);
  if (member.role !== "OWNER") throw new HttpsError("permission-denied", "Samo vlasnik može izvršiti ovu radnju.");
}

export function sha256(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export function invitationCode(length = 16): string {
  const bytes = randomBytes(length);
  return Array.from(bytes, (byte) => alphabet[byte % alphabet.length]).join("");
}

export function safeId(value: string, label = "id"): string {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(value)) throw new HttpsError("invalid-argument", `${label} nije ispravan.`);
  return value;
}

export function barcode(value: unknown): string | null {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value !== "string" || !/^\d{8}$|^\d{12}$|^\d{13}$/.test(value)) {
    throw new HttpsError("invalid-argument", "Barkod mora biti EAN-8, EAN-13, UPC-A ili UPC-E.");
  }
  if (!validGtin(value) && !(value.length === 8 && validUpcE(value))) throw new HttpsError("invalid-argument", "Kontrolna znamenka barkoda nije ispravna.");
  return value;
}

function validGtin(value: string): boolean {
  const digits = [...value].map(Number);
  const expected = digits.pop();
  const sum = digits.reverse().reduce((total, digit, index) => total + digit * (index % 2 === 0 ? 3 : 1), 0);
  return (10 - sum % 10) % 10 === expected;
}

function validUpcE(value: string): boolean {
  if (value[0] !== "0" && value[0] !== "1") return false;
  const ns = value[0]; const d = value.slice(1, 7); const last = d[5];
  let body: string;
  if (last === "0" || last === "1" || last === "2") body = `${ns}${d[0]}${d[1]}${last}0000${d[2]}${d[3]}${d[4]}`;
  else if (last === "3") body = `${ns}${d[0]}${d[1]}${d[2]}00000${d[3]}${d[4]}`;
  else if (last === "4") body = `${ns}${d[0]}${d[1]}${d[2]}${d[3]}00000${d[4]}`;
  else body = `${ns}${d[0]}${d[1]}${d[2]}${d[3]}${d[4]}0000${last}`;
  return validGtin(body + value[7]);
}
