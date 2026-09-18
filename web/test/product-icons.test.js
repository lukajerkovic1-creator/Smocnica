import { test } from "node:test";
import assert from "node:assert/strict";
import { suggestIcon, iconDataUrl, productIcons, findIcons } from "../src/product-icons.js";
test("expanded catalogue covers everyday groceries and specific compound names", () => {
  const cases = {
    "Voda za piće": "water", "Jana 1,5 l": "water", "Negazirana voda": "water",
    "Gazirana mineralna voda": "sparkling-water", "Jamnica": "sparkling-water",
    "Sok od naranče": "juice", "Coca Cola": "soda", "Energetski napitak": "energy",
    "Pivo": "beer", "Crno vino": "wine", "Rakija": "spirits",
    "Bademov napitak": "plant-milk", "Kokosovo mlijeko": "plant-milk",
    "Čokoladno mlijeko": "milk", "Čokoladni keksi": "cookies",
    "Kakao u prahu": "cocoa", "Zeleni čaj": "tea",
    "Tekući jogurt": "yogurt", "Vrhnje za kuhanje": "cream", "Maslac": "butter",
    "Maslac od kikirikija": "nut-spread", "Kikiriki maslac": "nut-spread",
    "Svježi sir": "fresh-cheese", "Jaja": "eggs", "Sladoled": "ice-cream",
    "Smrznuto povrće": "frozen-veg", "Smrznute jagode": "frozen-fruit",
    "Pizza": "pizza", "Njoki": "dumplings", "Juha od gljiva": "soup",
    "Pasirana rajčica": "tomato-sauce", "Svježa rajčica": "tomato", "Majoneza": "mayo",
    "Senf": "mustard", "Pesto": "pesto", "Sojin umak": "soy-sauce", "Ajvar": "ajvar",
    "Med": "honey", "Džem od jagoda": "jam", "Čokoladni namaz": "nut-spread",
    "Zobene pahuljice": "cereal", "Palenta": "cornmeal", "Kvinoja": "couscous",
    "Crveni grah": "beans", "Leća": "lentils", "Slanutak": "chickpeas",
    "Grašak": "peas", "Kukuruz": "corn", "Bademi": "nuts", "Kikiriki": "peanuts",
    "Chia sjemenke": "seeds", "Čips": "chips", "Slani štapići": "crackers",
    "Kokice": "popcorn", "Grožđice": "dried-fruit", "Šećer": "sugar", "Sol": "salt",
    "Crni papar": "pepper", "Mljevena paprika": "spices", "Svježa paprika": "pepper-veg",
    "Jabučni ocat": "vinegar", "Kvasac": "yeast", "Prašak za pecivo": "baking",
    "Vanilija": "vanilla", "Puding": "pudding", "Bomboni": "candy", "Kolači": "cake",
    "Banane": "banana", "Naranče": "citrus", "Limun": "lemon", "Kruške": "pear",
    "Jagode": "berries", "Grožđe": "grapes", "Breskve": "stone-fruit", "Trešnje": "cherries",
    "Lubenica": "melon", "Ananas": "tropical", "Avokado": "avocado", "Kokos": "coconut",
    "Tikvice": "cucumber", "Krumpir": "potato", "Luk": "onion", "Češnjak": "garlic",
    "Šampinjoni": "mushrooms", "Špinat": "greens", "Brokula": "broccoli",
    "Piletina": "chicken", "Hrenovke": "sausages", "Šunka": "ham", "Losos": "fresh-fish",
    "Kozice": "seafood", "Tofu": "tofu", "Tortilje": "tortilla", "Kroasani": "croissant",
    "Instant rezanci": "noodles", "Dječja kašica": "baby-food",
  };
  assert.ok(productIcons.length >= 100);
  for (const [name, expected] of Object.entries(cases)) assert.equal(suggestIcon(name), expected, name);
});
test("icon search supports accents, aliases, groups and empty results", () => {
  assert.ok(findIcons("voda").some((i) => i.id === "water"));
  assert.ok(findIcons("Jamnica").some((i) => i.id === "sparkling-water"));
  assert.ok(findIcons("povrce").some((i) => i.id === "broccoli"));
  assert.deepEqual(findIcons("qzxqzx"), []);
  assert.equal(findIcons("").length, productIcons.length);
});
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
