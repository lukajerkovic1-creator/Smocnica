import { describe, expect, it } from "vitest";
import { PANTRY_LIMITS, assertFinalResourceLimit, assertResourceLimit } from "../src/limits";

describe("pantry resource limits", () => {
  it("keeps explicit production limits for every bounded aggregate", () => {
    expect(PANTRY_LIMITS).toEqual({
      members: 10,
      shelves: 50,
      categories: 50,
      products: 500,
      activeInvitations: 1,
    });
  });

  it("rejects a create at the limit and an import above the limit", () => {
    expect(() => assertResourceLimit(49, 50, "polica")).not.toThrow();
    expect(() => assertResourceLimit(50, 50, "polica")).toThrowError(expect.objectContaining({ code: "resource-exhausted" }));
    expect(() => assertFinalResourceLimit(500, 500, "artikala")).not.toThrow();
    expect(() => assertFinalResourceLimit(501, 500, "artikala")).toThrowError(expect.objectContaining({ code: "resource-exhausted" }));
  });
});
