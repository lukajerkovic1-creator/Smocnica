import { test } from "node:test";
import assert from "node:assert/strict";
import {
  packageBase,
  totals,
  manualId,
  integer,
  csvCell,
  validateSnapshot,
  groupingMatches,
  belowMinimum,
} from "../src/domain.js";
test("grouping honors shared rules, synonyms and opted-out products", () => {
  const products = [{ id: "p", name: "Glatko brašno" }, { id: "x", name: "Posebno brašno", doNotGroup: true }];
  assert.equal(groupingMatches("Brašno glatko", products)[0].id, "p");
  assert.equal(groupingMatches("Glatko brašno 1 kg", products)[0].id, "p");
  assert.equal(groupingMatches("TIP 550", products, [{ sourceNormalized: "tip 550", genericName: "Glatko brašno", productId: "p" }])[0].id, "p");
  assert.deepEqual(groupingMatches("Posebno brašno", products), []);
  assert.deepEqual(groupingMatches("", products), []);
});
test("minimum respects physical quantity and per-variant thresholds", () => {
  const p = { id: "p", minimumMode: "MASS_MG", minimumAmountBase: 750000 };
  const variants = [{ id: "v", productId: "p", packageUnit: "G", packageAmountBase: 500000 }];
  assert.equal(belowMinimum(p, variants, [{ variantId: "v", quantity: 1 }]), true);
  assert.equal(belowMinimum(p, variants, [{ variantId: "v", quantity: 2 }]), false);
  assert.equal(belowMinimum(p, [{ ...variants[0], minimumPackages: 3 }], [{ variantId: "v", quantity: 2 }]), true);
});
test("package quantities use integer base units", () => {
  assert.equal(packageBase("0,5", "KG"), 500000);
  assert.equal(packageBase("1.25", "L"), 1250);
  assert.throws(() => packageBase("-1", "G"));
  assert.throws(() => integer("1.5"));
  assert.throws(() => integer(""));
});
test("totals exclude trashed variants and expose unknown sizes", () => {
  const t = totals(
    { id: "p" },
    [
      { id: "v", productId: "p", packageUnit: "G", packageAmountBase: 500000 },
      { id: "u", productId: "p", packageUnit: "UNKNOWN" },
      { id: "d", productId: "p", deletedAt: 1 },
    ],
    [
      { variantId: "v", quantity: 2 },
      { variantId: "u", quantity: 3 },
      { variantId: "d", quantity: 10 },
    ],
  );
  assert.equal(t.packages, 5);
  assert.equal(t.amounts.mg, 1000000);
  assert.equal(t.unknown, 3);
});
test("shopping identity is stable across normalized Croatian names", async () => {
  assert.equal(
    await manualId("p", "c", "  ŠEĆER   bijeli "),
    await manualId("p", "c", "šećer bijeli"),
  );
  assert.notEqual(await manualId("p", "c", "a"), await manualId("p", "d", "a"));
});
test("CSV protects spreadsheets from formula injection", () => {
  assert.equal(csvCell("=SUM(1)"), `"'=SUM(1)"`);
  assert.equal(csvCell('"a"'), '"""a"""');
});
test("import rejects dangling stock and duplicate ids", () => {
  const data = {
    schemaVersion: 2,
    shelves: [{ id: "s" }],
    categories: [{ id: "c" }],
    products: [{ id: "p", categoryId: "c" }],
    variants: [{ id: "v", productId: "p" }],
    stocks: [
      { id: "v_s", variantId: "v", productId: "p", shelfId: "s", quantity: 1 },
    ],
    shoppingItems: [],
    synonymRules: [],
  };
  assert.equal(validateSnapshot(data), data);
  assert.throws(() =>
    validateSnapshot({
      ...data,
      stocks: [{ ...data.stocks[0], quantity: -1 }],
    }),
  );
  assert.throws(() => validateSnapshot({ ...data, shelves: [] }));
  assert.throws(() =>
    validateSnapshot({
      ...data,
      products: [...data.products, ...data.products],
    }),
  );
});
