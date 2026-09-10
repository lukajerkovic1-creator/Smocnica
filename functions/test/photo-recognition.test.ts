import { afterEach, describe, expect, it, vi } from "vitest";
import { parsePhotoSuggestion, recognizeWithGemini, reservePhotoRequest, validatePhoto } from "../src/photo-recognition";
import { db } from "../src/firebase";

afterEach(() => vi.unstubAllGlobals());
const product = { name: "Glatko brašno", manufacturer: "Čakovečki mlinovi", packageAmount: "1", packageUnit: "KG" };
const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xd9]).toString("base64");

describe("photo recognition", () => {
  it("accepts only bounded JPEG payloads", () => {
    expect(validatePhoto(jpeg)).toBe(jpeg);
    for (const input of [null, "garbage", "aGVsbG8=", "A".repeat(7_000_000)]) expect(() => validatePhoto(input)).toThrow();
  });
  it("preserves food identity and known package data without inventing missing values", () => {
    expect(parsePhotoSuggestion(product)).toEqual(product);
    expect(parsePhotoSuggestion({ ...product, manufacturer: "", packageAmount: "", packageUnit: "" }).manufacturer).toBe("");
    for (const override of [{ name: "" }, { packageAmount: "-1" }, { packageUnit: "" }, { packageUnit: "OZ" }, { packageAmount: "NaN" }]) {
      expect(() => parsePhotoSuggestion({ ...product, ...override })).toThrow();
    }
  });
  it("uses a structured image request and returns a proposal", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ candidates: [{ finishReason: "STOP", content: { parts: [{ text: JSON.stringify(product) }] } }] })));
    vi.stubGlobal("fetch", fetch);
    expect(await recognizeWithGemini(jpeg, "test-only-key")).toEqual(product);
    const request = JSON.parse(fetch.mock.calls[0][1].body);
    expect(request.contents[0].parts[0].inlineData.data).toBe(jpeg);
    expect(request.generationConfig.responseMimeType).toBe("application/json");
  });
  it("does not retry or switch to paid services after quota exhaustion", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response("private provider body", { status: 429 }));
    vi.stubGlobal("fetch", fetch);
    await expect(recognizeWithGemini(jpeg, "test-only-key")).rejects.toMatchObject({ code: "resource-exhausted" });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("never forwards raw provider errors or malformed suggestions", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("secret private product")));
    await expect(recognizeWithGemini(jpeg, "test-only-key")).rejects.toMatchObject({ code: "unavailable" });
    await expect(recognizeWithGemini(jpeg, "test-only-key")).rejects.not.toThrow("secret private product");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ candidates: [{ finishReason: "MAX_TOKENS" }] }))));
    await expect(recognizeWithGemini(jpeg, "test-only-key")).rejects.toMatchObject({ code: "unavailable" });
  });
});

describe.skipIf(!process.env.FIRESTORE_EMULATOR_HOST)("photo access and quota", () => {
  it("denies outsiders and inactive members, limits simultaneous requests", async () => {
    const pantryId = "photo-test-pantry";
    await db.doc(`pantries/${pantryId}`).set({ deletedAt: null });
    await db.doc(`pantries/${pantryId}/members/active`).set({ active: true });
    await db.doc(`pantries/${pantryId}/members/inactive`).set({ active: false });
    await expect(reservePhotoRequest(pantryId, "outsider")).rejects.toMatchObject({ code: "permission-denied" });
    await expect(reservePhotoRequest(pantryId, "inactive")).rejects.toMatchObject({ code: "permission-denied" });
    const time = Date.now() + 86_400_000;
    const results = await Promise.allSettled([reservePhotoRequest(pantryId, "active", time), reservePhotoRequest(pantryId, "active", time)]);
    expect(results.filter((r) => r.status === "fulfilled")).toHaveLength(1);
    await db.doc(`pantries/${pantryId}/members/active`).update({ active: false });
    await expect(reservePhotoRequest(pantryId, "active", time + 6000)).rejects.toMatchObject({ code: "permission-denied" });
  }, 30_000);
});
