import { test } from "node:test";
import assert from "node:assert/strict";
import { suggestIcon, iconDataUrl, productIcons } from "../src/product-icons.js";
test("recognised Croatian product names select meaningful illustrations", () => {
  assert.equal(suggestIcon("Čokoladne napolitanke"), "cookies");
  assert.equal(suggestIcon("Čokoladni keksi"), "cookies");
  assert.equal(suggestIcon("Mliječna čokolada"), "chocolate");
  assert.equal(suggestIcon("Trajno mlijeko"), "milk");
  assert.equal(suggestIcon("Riža"), "rice");
  assert.equal(suggestIcon("Glatko brašno"), "flour");
  assert.equal(suggestIcon("Fusilli"), "pasta");
  assert.equal(suggestIcon(""), "pantry");
});
test("manual icon selection is bounded to fixed self-contained artwork", () => {
  assert.equal(new Set(productIcons.map((i) => i.id)).size, productIcons.length);
  for (const icon of productIcons) {
    const svg = decodeURIComponent(iconDataUrl(icon.id).split(",")[1]);
    assert.match(svg, /viewBox="0 0 100 100"/);
    assert.doesNotMatch(svg, /script|href=|onload=/);
  }
  assert.equal(iconDataUrl('<script>'), iconDataUrl('pantry'));
  assert.notEqual(iconDataUrl('cookies'), iconDataUrl('milk'));
});
