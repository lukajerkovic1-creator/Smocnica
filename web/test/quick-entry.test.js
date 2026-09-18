import test from "node:test";
import assert from "node:assert/strict";
import { recentEntries, entryShelf, mergePhotoSuggestion, stockDelta } from "../src/quick-entry.js";

test("recent packages keep exact variant identity, include zero stock, exclude trash", () => {
  const rows = recentEntries({ products: [{id:"p"}, {id:"trash",deletedAt:1}],
    variants: [{id:"large",productId:"p",createdAt:10},{id:"small",productId:"p",createdAt:20},{id:"gone",productId:"trash"},{id:"deleted",productId:"p",deletedAt:1}],
    stocks: [{variantId:"large",quantity:0,updatedAt:30}] });
  assert.deepEqual(rows.map(r=>r.variant.id), ["large","small"]);
  const added = stockDelta(rows[0],"s1",1);
  assert.deepEqual(stockDelta(rows[0],"s1",-1), {...added,delta:-1});
  assert.throws(()=>stockDelta(rows[0],"",1));
});
test("shelf defaults validate explicit and remembered shelves", () => {
  const shelves=[{id:"s1"},{id:"s2"}];
  assert.equal(entryShelf(shelves,"s2","s1"),"s2");
  assert.equal(entryShelf(shelves,"deleted","s2"),"s2");
  assert.equal(entryShelf(shelves,"","deleted"),"s1");
  assert.equal(entryShelf([],"",""),"");
});
test("back label fills size but preserves front identity and manual changes", () => {
  const front={name:"Mlijeko",manufacturer:"Dukat",amount:"",unit:"UNKNOWN"};
  const suggestion={name:"Mliječni proizvod",manufacturer:"",packageAmount:"1",packageUnit:"L"};
  assert.deepEqual(mergePhotoSuggestion(front,front,suggestion,true),{...front,amount:"1",unit:"L"});
  const edited={...front,name:"Moje mlijeko",amount:"500",unit:"ML"};
  assert.deepEqual(mergePhotoSuggestion(edited,front,suggestion,true),edited);
  assert.deepEqual(mergePhotoSuggestion(edited,edited,suggestion,true),edited);
});
