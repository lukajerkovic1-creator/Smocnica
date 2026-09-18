import { test } from "node:test";
import assert from "node:assert/strict";
import { saveVariantEdit } from "../src/variant-edit.js";
test("failed illustration upload preserves revision and can be retried without a preliminary mutation", async () => {
  const variant = { id: "v", photoUri: "old", photoSource: "CAMERA" };
  const events = [];
  let deny = true;
  const api = {
    upload: async () => { events.push("upload"); if (deny) throw new Error("denied"); return "new"; },
    mutate: async (...args) => { events.push(args); return { status: "APPLIED" }; },
  };
  await assert.rejects(saveVariantEdit(api, variant, 7, new Blob()), /denied/);
  assert.deepEqual(events, ["upload"]);
  deny = false;
  await saveVariantEdit(api, variant, 7, new Blob());
  assert.deepEqual(events[2], ["upsert_variant", "v", { variant: { id: "v", photoUri: "new", photoSource: "GALLERY" } }, 7, "VARIANT"]);
  assert.equal(variant.photoUri, "old");
});
test("metadata-only edits preserve the stored photo and still use conflict checks", async () => {
  const variant = { id: "v", photoUri: "old" };
  await assert.rejects(saveVariantEdit({ mutate: async (_type, _id, payload, revision) => {
    assert.equal(payload.variant.photoUri, "old"); assert.equal(revision, 3); throw new Error("conflict");
  } }, variant, 3, null), /conflict/);
});
