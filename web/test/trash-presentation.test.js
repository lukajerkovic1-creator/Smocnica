import { test } from "node:test";
import assert from "node:assert/strict";
import { trashRows } from "../src/trash-presentation.js";

const deleted = { deletedAt: 1000, purgeAfter: 2000 };
const milk = (id, productId, size) => ({ id, productId, displayName: "Mlijeko", packageAmountBase: size, packageUnit: "ML", ...deleted });
test("trash shows one restorable product with its sizes instead of duplicate child records", () => {
  const rows = trashRows({ products: [{ id: "p", name: "Mlijeko", ...deleted }],
    variants: [milk("small", "p", 500), milk("large", "p", 1000)], shelves: [], categories: [] });
  assert.equal(rows.length, 1);
  assert.equal(rows[0].type, "PRODUCT");
  assert.equal(rows[0].id, "p");
  assert.deepEqual(rows[0].details, ["Mlijeko · 500 ml", "Mlijeko · 1.000 ml"]);
  assert.equal(rows[0].restoresTogether, true);
});
test("separately deleted packages retain their own restore identities and parent name", () => {
  const rows = trashRows({ products: [{ id: "p", name: "Mlijeko" }],
    variants: [milk("small", "p", 500), milk("large", "p", 1000)],
    shelves: [{ id: "s", name: "Mlijeko", ...deleted }], categories: [] });
  assert.equal(rows.length, 3);
  const packages = rows.filter(r => r.type === "VARIANT");
  assert.deepEqual(packages.map(r => r.id), ["small", "large"]);
  assert.notEqual(packages[0].details[0], packages[1].details[0]);
  assert.equal(packages[0].details[1], "Pripada artiklu: Mlijeko");
  assert.equal(rows.find(r => r.id === "s").typeLabel, "Polica");
});
