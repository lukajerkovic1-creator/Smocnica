import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";
import {
  RulesTestEnvironment,
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import { ref, uploadBytes, getBytes } from "firebase/storage";

const emulatorAvailable = Boolean(process.env.FIRESTORE_EMULATOR_HOST && process.env.FIREBASE_STORAGE_EMULATOR_HOST);
describe.skipIf(!emulatorAvailable)("Firestore and Storage security rules", () => {
  let environment: RulesTestEnvironment;

  beforeAll(async () => {
    environment = await initializeTestEnvironment({
      projectId: "demo-smocnica",
      firestore: { rules: readFileSync(resolve(__dirname, "../../firestore.rules"), "utf8") },
      storage: { rules: readFileSync(resolve(__dirname, "../../storage.rules"), "utf8") },
    });
  });

  beforeEach(async () => {
    await environment.clearFirestore();
    await environment.withSecurityRulesDisabled(async (context) => {
      const firestore = context.firestore();
      await setDoc(doc(firestore, "pantries/p1"), { name: "Test", ownerUid: "owner", memberUids: ["owner", "member"] });
      await setDoc(doc(firestore, "pantries/p1/members/owner"), { role: "OWNER", active: true });
      await setDoc(doc(firestore, "pantries/p1/members/member"), { role: "MEMBER", active: true });
      await setDoc(doc(firestore, "pantries/p1/members/removed"), { role: "MEMBER", active: false });
      await setDoc(doc(firestore, "pantries/p2"), { name: "Tuđa", ownerUid: "other", memberUids: ["other"] });
      await setDoc(doc(firestore, "pantries/p2/members/other"), { role: "OWNER", active: true });
      await setDoc(doc(firestore, "pantries/p1/products/a"), { name: "Riža" });
      await setDoc(doc(firestore, "pantries/p1/products/deleted"), { name: "Staro", deletedAt: new Date() });
    });
  });

  afterAll(async () => environment.cleanup());

  it("denies unauthenticated and non-member reads", async () => {
    await assertFails(getDoc(doc(environment.unauthenticatedContext().firestore(), "pantries/p1")));
    await assertFails(getDoc(doc(environment.authenticatedContext("intruder").firestore(), "pantries/p1")));
    await assertFails(getDoc(doc(environment.authenticatedContext("removed").firestore(), "pantries/p1")));
  });

  it("allows an active member to read pantry data but not another pantry", async () => {
    const firestore = environment.authenticatedContext("member").firestore();
    await assertSucceeds(getDoc(doc(firestore, "pantries/p1")));
    await assertSucceeds(getDoc(doc(firestore, "pantries/p1/products/a")));
    await assertFails(getDoc(doc(firestore, "pantries/p2")));
  });

  it("denies all direct client writes, including forged owner and quantity", async () => {
    const owner = environment.authenticatedContext("owner").firestore();
    await assertFails(setDoc(doc(owner, "pantries/p1"), { ownerUid: "owner", memberUids: ["intruder"] }, { merge: true }));
    await assertFails(setDoc(doc(owner, "pantries/p1/stocks/a_s1"), { productId: "a", shelfId: "s1", quantity: -99 }));
    await assertFails(setDoc(doc(owner, "pantries/p1/members/intruder"), { role: "OWNER", active: true }));
  });

  it("permits only a bounded JPEG in the member pantry", async () => {
    const memberStorage = environment.authenticatedContext("member").storage();
    const valid = ref(memberStorage, "pantries/p1/products/a/main.jpg");
    await assertSucceeds(uploadBytes(valid, new Uint8Array([0xff, 0xd8, 0xff]), { contentType: "image/jpeg", customMetadata: { productId: "a" } }));
    await assertSucceeds(getBytes(valid));
    await assertFails(uploadBytes(ref(memberStorage, "pantries/p1/products/a/other.jpg"), new Uint8Array([1]), { contentType: "image/jpeg", customMetadata: { productId: "a" } }));
    await assertFails(uploadBytes(ref(memberStorage, "pantries/p2/products/a/main.jpg"), new Uint8Array([0xff, 0xd8]), { contentType: "image/jpeg", customMetadata: { productId: "a" } }));
    await assertFails(uploadBytes(ref(memberStorage, "pantries/p1/products/a/main.jpg"), new Uint8Array([1]), { contentType: "text/plain", customMetadata: { productId: "a" } }));
    await assertFails(uploadBytes(ref(memberStorage, "pantries/p1/products/missing/main.jpg"), new Uint8Array([0xff, 0xd8]), { contentType: "image/jpeg", customMetadata: { productId: "missing" } }));
    await assertFails(uploadBytes(ref(memberStorage, "pantries/p1/products/deleted/main.jpg"), new Uint8Array([0xff, 0xd8]), { contentType: "image/jpeg", customMetadata: { productId: "deleted" } }));
  });
});
