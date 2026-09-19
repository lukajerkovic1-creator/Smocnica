import { test } from "node:test";
import assert from "node:assert/strict";
import { inventoryPackageLabel } from "../src/inventory-label.js";

test("inventory distinguishes same-name packages by size before the manufacturer", () => {
  const milk = { displayName: "Mlijeko", manufacturer: "Testna mljekara", packageUnit: "L", packageAmountBase: 500 };
  assert.equal(inventoryPackageLabel("Mlijeko", milk), "Mlijeko · 0,5 l · Testna mljekara");
  assert.equal(inventoryPackageLabel("Mlijeko", { ...milk, packageAmountBase: 1000 }), "Mlijeko · 1 l · Testna mljekara");
});
test("inventory uses actual size even if package labels are identical", () => {
  const flour = { displayName: "Brašno", packageLabel: "Vrećica", packageUnit: "G", packageAmountBase: 500000 };
  assert.equal(inventoryPackageLabel("Brašno", flour), "Brašno · 500 g");
  assert.equal(inventoryPackageLabel("Brašno", { ...flour, packageAmountBase: 1000000 }), "Brašno · 1.000 g");
});
test("inventory retains distinctive variant names without repeating equal names", () => {
  assert.equal(inventoryPackageLabel(" MLIJEKO ", { displayName: "Mlijeko", manufacturer: "Mlijeko", packageLabel: "500 ml" }), "MLIJEKO · 500 ml");
  assert.equal(inventoryPackageLabel("Mlijeko", { displayName: "Bez laktoze", manufacturer: "Test", packageLabel: "1 l" }), "Mlijeko · 1 l · Test · Bez laktoze");
});
test("legacy packages preserve a label or explicitly state unknown size and barcode", () => {
  assert.equal(inventoryPackageLabel("Mlijeko", { displayName: "Mlijeko", packageLabel: " 500 ml " }), "Mlijeko · 500 ml");
  assert.equal(inventoryPackageLabel("Mlijeko", { displayName: "Mlijeko", barcode: "12345678" }), "Mlijeko · Nepoznata veličina · Barkod: 12345678");
  assert.equal(inventoryPackageLabel("", { displayName: "Riža" }), "Riža · Nepoznata veličina");
});
