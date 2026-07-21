import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("Firestore retention configuration", () => {
  it("enables TTL for activities, trash, applied operations and processed notifications", () => {
    const indexes = JSON.parse(readFileSync(resolve(process.cwd(), "../firestore.indexes.json"), "utf8"));
    const ttlGroups = new Set(
      indexes.fieldOverrides
        .filter((override: { ttl?: boolean }) => override.ttl === true)
        .map((override: { collectionGroup: string; fieldPath: string }) => `${override.collectionGroup}.${override.fieldPath}`),
    );

    expect(ttlGroups).toEqual(new Set([
      "activities.expiresAt",
      "operations.expiresAt",
      "notifications.expiresAt",
      "trash.purgeAfter",
    ]));
  });
});
