import { describe, expect, it } from "vitest";
import { barcode, invitationCode, normalizedName, sha256 } from "../src/validation";

describe("security validation", () => {
  it("creates high-entropy invitation codes without ambiguous characters", () => {
    const values = new Set(Array.from({ length: 200 }, () => invitationCode()));
    expect(values.size).toBe(200);
    for (const value of values) expect(value).toMatch(/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{16}$/);
  });

  it("hashes invitations deterministically without storing the clear code", () => {
    expect(sha256("ABC123")).toBe(sha256("ABC123"));
    expect(sha256("ABC123")).not.toContain("ABC123");
  });

  it("accepts valid GTIN and rejects invalid checksum", () => {
    expect(barcode("4006381333931")).toBe("4006381333931");
    expect(barcode("04252614")).toBe("04252614");
    expect(() => barcode("4006381333932")).toThrow();
    expect(() => barcode("123")).toThrow();
  });

  it("normalizes names independently of case, Unicode form, and repeated whitespace", () => {
    expect(normalizedName("  POLICA   Čaj  ")).toBe(normalizedName("polica čaj"));
    expect(normalizedName("Mlijeko\t i\n jaja")).toBe("mlijeko i jaja");
  });
});
