// This isolated UI fixture is never included in the production build.
import React from "react";
import { createRoot } from "react-dom/client";
import App from "../src/App";
import "../src/styles.css";
const pantry = {
  id: "test-pantry",
  name: "Testna smočnica",
  ownerUid: "test-owner",
  revision: 1,
  createdAt: 1000,
  updatedAt: 1000,
};
let change;
const data = {
  shelves: [
    { id: "s1", name: "Polica 1", sortOrder: 0, revision: 1 },
    { id: "s2", name: "Polica 2", sortOrder: 1, revision: 1 },
  ],
  categories: [
    { id: "c1", name: "Ostalo", isDefault: true, sortOrder: 0, revision: 1 },
  ],
  products: ["Glatko brašno", "Fusilli", "Mlijeko", "Riža"].map((name, i) => ({
    id: `p${i}`,
    name,
    categoryId: "c1",
    category: "Ostalo",
    revision: 1,
    minimumMode: "PACKAGES",
    minimumAmountBase: 0,
    autoShopping: true,
  })),
  variants: ["Glatko brašno", "Fusilli", "Mlijeko", "Riža"].map(
    (displayName, i) => ({
      id: `v${i}`,
      productId: `p${i}`,
      displayName,
      packageUnit: i === 2 ? "L" : "G",
      packageAmountBase: i === 2 ? 1000 : 500000,
      packageLabel: i === 2 ? "1 l" : "500 g",
      revision: 1,
    }),
  ),
  stocks: [2, 1, 3, 2].map((quantity, i) => ({
    id: `v${i}_s1`,
    variantId: `v${i}`,
    productId: `p${i}`,
    shelfId: "s1",
    quantity,
    revision: 1,
  })),
  shoppingItems: [],
  members: [
    {
      id: "test-owner",
      uid: "test-owner",
      displayName: "Testni vlasnik",
      role: "OWNER",
      active: true,
    },
  ],
  synonymRules: [],
  activities: [],
};
if (new URLSearchParams(location.search).has("inventory-packages")) {
  data.variants.push(
    { id: "milk-small", productId: "p2", displayName: "Mlijeko", manufacturer: "Testna mljekara", packageUnit: "ML", packageAmountBase: 500, revision: 1 },
    { id: "milk-unknown", productId: "p2", displayName: "Mlijeko", packageUnit: "UNKNOWN", barcode: "12345678", revision: 1 },
  );
  data.variants.find((v) => v.id === "v2").manufacturer = "Testna mljekara";
  data.stocks.push({ id: "milk-small_s1", variantId: "milk-small", productId: "p2", shelfId: "s1", quantity: 1, revision: 1 });
}
function emit() {
  for (const [name, rows] of Object.entries(data)) change?.(name, [...rows]);
}
const api = {
  uid: () => "test-owner",
  deviceName: () => "Testni iPhone",
  observeAuth(fn) {
    queueMicrotask(() => fn({ uid: "test-owner" }));
    return () => {};
  },
  initialize: async () => ({ pantries: [{ pantry }] }),
  subscribe(id, fn) {
    change = fn;
    queueMicrotask(emit);
    return () => {
      change = null;
    };
  },
  outbox: { pending: async () => [], flush: async () => {} },
  photo: async (url) => url,
  upload: async (_id, blob) => URL.createObjectURL(blob),
  call: async (name, payload) => {
    if (name === "recognizeProductPhoto") {
      // Deterministic local fixture; never calls an AI provider or a real pantry.
      await new Promise(resolve => setTimeout(resolve, 150));
      if (new URLSearchParams(location.search).has("recognition-timeout")) {
        throw Object.assign(new Error("Private provider details"), { code: "functions/deadline-exceeded" });
      }
      const sizeVisible = payload.additionalPhotoBase64 || new URLSearchParams(location.search).has("known-size");
      return { name: "Testna zobena kaša", manufacturer: "Test", packageAmount: sizeVisible ? "500" : "", packageUnit: sizeVisible ? "G" : "UNKNOWN" };
    }
    throw new Error("External operations are disabled in the UI fixture");
  },
  register: async () => {},
  record: async (kind, id) => data[kind].find((r) => r.id === id),
  mutate: async (type, id, payload) => {
    if (type === "adjust_stock") {
      const s = data.stocks.find(
        (s) =>
          s.variantId === payload.variantId && s.shelfId === payload.shelfId,
      );
      if (!s) {
        data.stocks.push({ id: `${payload.variantId}_${payload.shelfId}`, variantId: payload.variantId, productId: payload.productId, shelfId: payload.shelfId, quantity: payload.delta, revision: 1 });
        emit();
        return { status: "APPLIED" };
      }
      if (s.quantity + payload.delta < 0)
        throw new Error("Nema dovoljno zalihe.");
      s.quantity += payload.delta;
    } else if (type === "upsert_shopping") {
      const row = data.shoppingItems.find((r) => r.id === id);
      if (row) Object.assign(row, payload.item);
      else data.shoppingItems.push({ ...payload.item, revision: 1 });
    } else if (type === "delete_shopping")
      data.shoppingItems = data.shoppingItems.filter((i) => i.id !== id);
    else if (type === "create_shelf")
      data.shelves.push({ ...payload.shelf, revision: 1 });
    else if (type === "upsert_product") {
      const p = data.products.find((p) => p.id === id);
      if (p) Object.assign(p, payload.product);
      else {
        data.products.push({ ...payload.product, revision: 1 });
        data.variants.push({ ...payload.initialVariant, photoUrl: payload.initialVariant.photoUri, revision: 1 });
      }
    } else if (type === "upsert_variant") {
      const variant = data.variants.find((v) => v.id === id);
      const value = { ...payload.variant, photoUrl: payload.variant.photoUri, revision: (variant?.revision || 0) + 1 };
      if (variant) Object.assign(variant, value);
      else data.variants.push(value);
    } else throw new Error(`Unsupported test operation: ${type}`);
    emit();
    return { status: "APPLIED" };
  },
};
createRoot(document.getElementById("root")).render(<App api={api} />);
