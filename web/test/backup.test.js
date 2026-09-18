import { test } from "node:test";
import assert from "node:assert/strict";
import { canonicalSnapshot, exportBackup, readBackup } from "../src/backup.js";
const pantry = {
  id: "p",
  name: "Smočnica",
  ownerUid: "u",
  createdAt: 1000,
  updatedAt: 2000,
};
const data = {
  members: [],
  shelves: [{ id: "s", name: "Polica", sortOrder: 0 }],
  categories: [{ id: "c", name: "Ostalo", sortOrder: 0, isDefault: true }],
  products: [{ id: "p1", name: "Brašno", categoryId: "c" }],
  variants: [
    {
      id: "v",
      productId: "p1",
      displayName: "500 g",
      packageUnit: "G",
      packageAmountBase: 500000,
    },
  ],
  stocks: [
    { id: "v_s", productId: "p1", variantId: "v", shelfId: "s", quantity: 2 },
  ],
  shoppingItems: [],
  synonymRules: [],
  activities: [],
};
test("backup uses Android envelope, defaults, field order and no nulls", async () => {
  const s = canonicalSnapshot(data, pantry);
  assert.deepEqual(Object.keys(s), [
    "pantry",
    "members",
    "shelves",
    "categories",
    "products",
    "stocks",
    "shoppingItems",
    "activities",
    "variants",
    "synonymRules",
  ]);
  assert.equal(s.variants[0].pantryId, "p");
  assert.equal(s.variants[0].syncState, "SYNCED");
  assert.ok(!Object.hasOwn(s.stocks[0], "id"));
  assert.ok(!Object.hasOwn(s.variants[0], "photoUri"));
  const exported = await exportBackup(data, pantry);
  assert.equal(exported.schemaVersion, 3);
  const restored = await readBackup(JSON.stringify(exported, null, 2));
  assert.equal(restored.stocks[0].id, "v_s");
  assert.equal(restored.stocks[0].quantity, 2);
});
test("backup detects edited content before importing", async () => {
  const e = await exportBackup(data, pantry);
  e.snapshot.stocks[0].quantity = 99;
  await assert.rejects(readBackup(JSON.stringify(e)), /sažetak/);
});
test("backup does not export orphan stock from trashed variants", () => {
  const s = canonicalSnapshot(
    { ...data, variants: [{ ...data.variants[0], deletedAt: 1000 }] },
    pantry,
  );
  assert.deepEqual(s.stocks, []);
});
