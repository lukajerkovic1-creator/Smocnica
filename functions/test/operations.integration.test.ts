import { afterAll, beforeEach, describe, expect, it } from "vitest";
import firebaseFunctionsTest from "firebase-functions-test";
import { applyOperation } from "../src/operations";
import { createInvitation, joinPantry, listMyPantries, manageMember, transferOwnership, unregisterDevice } from "../src/pantry";
import { db } from "../src/firebase";
import { sha256 } from "../src/validation";

const emulatorAvailable = Boolean(process.env.FIRESTORE_EMULATOR_HOST);
const testEnvironment = firebaseFunctionsTest();
const invoke = testEnvironment.wrap(applyOperation);
const invokeManageMember = testEnvironment.wrap(manageMember);
const invokeListMyPantries = testEnvironment.wrap(listMyPantries);
const invokeUnregisterDevice = testEnvironment.wrap(unregisterDevice);
const invokeCreateInvitation = testEnvironment.wrap(createInvitation);
const invokeJoinPantry = testEnvironment.wrap(joinPantry);
const invokeTransferOwnership = testEnvironment.wrap(transferOwnership);

describe.skipIf(!emulatorAvailable)("applyOperation transaction integration", () => {
  afterAll(() => testEnvironment.cleanup());
  beforeEach(async () => {
    await db.recursiveDelete(db.doc("pantries/p1"));
    const batch = db.batch();
    batch.set(db.doc("pantries/p1"), { name: "Test", ownerUid: "u1", memberUids: ["u1", "u2"], revision: 1, createdAt: new Date(), updatedAt: new Date() });
    batch.set(db.doc("pantries/p1/members/u1"), { role: "OWNER", active: true, joinedAt: new Date() });
    batch.set(db.doc("pantries/p1/members/u2"), { role: "MEMBER", active: true, joinedAt: new Date() });
    batch.set(db.doc("pantries/p1/shelves/s1"), { name: "Polica 1", sortOrder: 0, revision: 1, createdAt: new Date(), updatedAt: new Date() });
    batch.set(db.doc("pantries/p1/products/a"), { name: "Riža", category: "Namirnice", minimumQuantity: 5, autoShopping: true, totalQuantity: 5, revision: 1, createdAt: new Date(), updatedAt: new Date() });
    batch.set(db.doc("pantries/p1/stocks/a_s1"), { productId: "a", shelfId: "s1", quantity: 5, revision: 1, updatedAt: new Date() });
    await batch.commit();
  });

  it("is idempotent and sends a low-stock event only on the threshold crossing", async () => {
    const request = operation("op-00000001", -1);
    const first = await invoke(request as never);
    const second = await invoke(request as never);
    expect(first.status).toBe("APPLIED");
    expect(second.status).toBe("ALREADY_APPLIED");
    expect((await db.doc("pantries/p1/stocks/a_s1").get()).get("quantity")).toBe(4);
    expect((await db.collection("pantries/p1/notifications").get()).size).toBe(1);

    await invoke(operation("op-00000002", -1) as never);
    expect((await db.doc("pantries/p1/stocks/a_s1").get()).get("quantity")).toBe(3);
    expect((await db.collection("pantries/p1/notifications").get()).size).toBe(1);
    expect((await db.doc("pantries/p1/shoppingItems/auto_a").get()).get("requiredQuantity")).toBe(2);

    await invoke({
      ...operation("op-check-01", 1),
      data: {
        ...operation("op-check-01", 1).data,
        aggregateType: "SHOPPING", aggregateId: "auto_a", baseRevision: 2,
        payload: { type: "upsert_shopping", item: { id: "auto_a", productId: "a", name: "krivotvoreno", category: "X", requiredQuantity: 999, checked: true, manual: false } },
      },
    } as never);
    expect((await db.doc("pantries/p1/shoppingItems/auto_a").get()).get("requiredQuantity")).toBe(2);
    expect((await db.doc("pantries/p1/shoppingItems/auto_a").get()).get("checked")).toBe(true);

    await invoke(operation("op-00000004", 2) as never);
    expect((await db.doc("pantries/p1/shoppingItems/auto_a").get()).get("deletedAt")).toBeTruthy();
    await invoke(operation("op-00000005", -1) as never);
    expect((await db.doc("pantries/p1/shoppingItems/auto_a").get()).get("checked")).toBe(false);
    expect((await db.collection("pantries/p1/notifications").get()).size).toBe(2);
  });

  it("never permits concurrent-style removal beyond available stock", async () => {
    await expect(invoke(operation("op-00000003", -6) as never)).rejects.toMatchObject({ code: "failed-precondition" });
    expect((await db.doc("pantries/p1/stocks/a_s1").get()).get("quantity")).toBe(5);
  });

  it("notifies once when editing the minimum crosses the threshold and rearms above it", async () => {
    const updateMinimum = (operationId: string, baseRevision: number, minimumQuantity: number) => callable({
      operationId, pantryId: "p1", aggregateType: "PRODUCT", aggregateId: "a", baseRevision,
      payload: { type: "upsert_product", product: {
        id: "a", name: "Riža", barcode: null, description: "", category: "Namirnice",
        photoUri: null, photoSource: "NONE", minimumQuantity, autoShopping: true,
      } },
      deviceId: "device-0001", deviceDisplayName: "Testni uređaj",
    }, "u1");
    await invoke(updateMinimum("op-minimum-up-1", 1, 6) as never);
    expect((await db.collection("pantries/p1/notifications").get()).size).toBe(1);
    await invoke(updateMinimum("op-minimum-down", 2, 4) as never);
    expect((await db.doc("pantries/p1/shoppingItems/auto_a").get()).get("deletedAt")).toBeTruthy();
    await invoke(updateMinimum("op-minimum-up-2", 3, 6) as never);
    expect((await db.collection("pantries/p1/notifications").get()).size).toBe(2);
  });

  it("imports stock totals and reserves imported barcodes atomically", async () => {
    const code = "4006381333931";
    await invoke({
      ...operation("op-import-01", 1),
      data: {
        ...operation("op-import-01", 1).data,
        aggregateType: "PANTRY",
        aggregateId: "p1",
        payload: {
          type: "import_snapshot",
          replaceExisting: false,
          snapshot: {
            shelves: [], categories: [], shoppingItems: [],
            products: [{ id: "b", name: "Tjestenina", barcode: code, description: "500 g", category: "Namirnice", photoSource: "NONE", minimumQuantity: 2, autoShopping: true, revision: 0, createdAt: 1, updatedAt: 1 }],
            stocks: [{ productId: "b", shelfId: "s1", quantity: 4, revision: 0, updatedAt: 1 }],
          },
        },
      },
    } as never);
    expect((await db.doc("pantries/p1/products/b").get()).get("totalQuantity")).toBe(4);
    expect((await db.doc(`barcodes/${sha256(`p1:${code}`)}`).get()).get("productId")).toBe("b");
  });

  it("rejects an inconsistent automatic shopping item without partial import", async () => {
    await expect(invoke({
      ...operation("op-import-invalid", 1),
      data: {
        ...operation("op-import-invalid", 1).data,
        aggregateType: "PANTRY", aggregateId: "p1",
        payload: {
          type: "import_snapshot", replaceExisting: false,
          snapshot: {
            shelves: [], categories: [],
            products: [{ id: "bad", name: "Ulje", barcode: null, description: "", category: "Namirnice", photoSource: "NONE", minimumQuantity: 5, autoShopping: true }],
            stocks: [{ productId: "bad", shelfId: "s1", quantity: 1 }],
            shoppingItems: [{ id: "auto_bad", productId: "bad", name: "Ulje", category: "Namirnice", requiredQuantity: 999, checked: false, manual: false }],
          },
        },
      },
    } as never)).rejects.toMatchObject({ code: "invalid-argument" });
    expect((await db.doc("pantries/p1/products/bad").get()).exists).toBe(false);
  });

  it("rejects stale inventory snapshots instead of overwriting another device", async () => {
    const { createHash } = await import("node:crypto");
    const version = Number.parseInt(createHash("sha256").update("a:5").digest("hex").slice(0, 12), 16);
    await invoke(operation("op-before-inventory", -1) as never);
    await expect(invoke({
      ...operation("op-inventory-stale", 1),
      data: {
        ...operation("op-inventory-stale", 1).data,
        aggregateType: "INVENTORY", aggregateId: "inventory1", baseRevision: version,
        payload: {
          type: "apply_inventory",
          session: { id: "inventory1", shelfId: "s1", differences: [{ productId: "a", actualQuantity: 3 }] },
        },
      },
    } as never)).rejects.toMatchObject({ code: "aborted", message: expect.stringContaining("REVISION_CONFLICT:") });
    expect((await db.doc("pantries/p1/stocks/a_s1").get()).get("quantity")).toBe(4);
  });

  it("rejects inventory for a deleted shelf without changing stock", async () => {
    const { createHash } = await import("node:crypto");
    const version = Number.parseInt(createHash("sha256").update("a:5").digest("hex").slice(0, 12), 16);
    await db.doc("pantries/p1/shelves/s1").update({ deletedAt: new Date() });
    await expect(invoke({
      ...operation("op-inventory-deleted-shelf", 1),
      data: {
        ...operation("op-inventory-deleted-shelf", 1).data,
        aggregateType: "INVENTORY", aggregateId: "inventory2", baseRevision: version,
        payload: { type: "apply_inventory", session: { id: "inventory2", shelfId: "s1", differences: [{ productId: "a", actualQuantity: 4 }] } },
      },
    } as never)).rejects.toMatchObject({ code: "failed-precondition" });
    expect((await db.doc("pantries/p1/stocks/a_s1").get()).get("quantity")).toBe(5);
  });

  it("restores only active memberships and deactivates the signed-out device", async () => {
    await db.doc("users/u1/devices/d1").set({ active: true, fcmToken: "token-that-must-not-survive-signout" });
    const listed = await invokeListMyPantries(callable({}, "u1") as never);
    expect(listed.pantries).toHaveLength(1);
    expect(listed.pantries[0].pantry.id).toBe("p1");
    await invokeUnregisterDevice(callable({ deviceId: "d1" }, "u1") as never);
    const device = await db.doc("users/u1/devices/d1").get();
    expect(device.get("active")).toBe(false);
    expect(device.get("fcmToken")).toBeUndefined();
    await db.doc("pantries/p1/members/u1").update({ active: false });
    const afterRemoval = await invokeListMyPantries(callable({}, "u1") as never);
    expect(afterRemoval.pantries).toHaveLength(0);
  });

  it("enforces owner-only member management", async () => {
    const device = { deviceId: "device-0001", deviceDisplayName: "Testni uređaj" };
    await expect(invokeManageMember(callable({ pantryId: "p1", memberUid: "u1", action: "REMOVE", ...device }, "u2") as never)).rejects.toMatchObject({ code: "permission-denied" });
    await invokeManageMember(callable({ pantryId: "p1", memberUid: "u2", action: "REMOVE", ...device }, "u1") as never);
    expect((await db.doc("pantries/p1/members/u2").get()).get("active")).toBe(false);
    const activity = (await db.collection("pantries/p1/activities").where("type", "==", "MEMBER_REMOVED").get()).docs[0];
    expect(activity.get("deviceDisplayName")).toBe("Testni uređaj");
  });

  it("consumes a hashed invitation exactly once", async () => {
    const invitation = await invokeCreateInvitation(callable({ pantryId: "p1" }, "u1") as never);
    expect(invitation.code).toMatch(/^[A-Z2-9]{16}$/);
    expect((await db.doc(`inviteCodes/${sha256(invitation.code)}`).get()).exists).toBe(true);
    expect((await db.doc(`inviteCodes/${invitation.code}`).get()).exists).toBe(false);
    await invokeJoinPantry(callable({ code: invitation.code, deviceDisplayName: "Telefon u3" }, "u3") as never);
    expect((await db.doc("pantries/p1/members/u3").get()).get("active")).toBe(true);
    await expect(invokeJoinPantry(callable({ code: invitation.code, deviceDisplayName: "Telefon u4" }, "u4") as never))
      .rejects.toMatchObject({ code: "failed-precondition" });
  });

  it("transfers ownership atomically and attributes the activity to the device", async () => {
    const device = { deviceId: "device-0001", deviceDisplayName: "Vlasnikov telefon" };
    await expect(invokeTransferOwnership(callable({ pantryId: "p1", newOwnerUid: "u1", ...device }, "u2") as never))
      .rejects.toMatchObject({ code: "permission-denied" });
    await invokeTransferOwnership(callable({ pantryId: "p1", newOwnerUid: "u2", ...device }, "u1") as never);
    expect((await db.doc("pantries/p1").get()).get("ownerUid")).toBe("u2");
    expect((await db.doc("pantries/p1/members/u1").get()).get("role")).toBe("MEMBER");
    expect((await db.doc("pantries/p1/members/u2").get()).get("role")).toBe("OWNER");
    const activity = (await db.collection("pantries/p1/activities").where("type", "==", "OWNERSHIP_TRANSFERRED").get()).docs[0];
    expect(activity.get("deviceDisplayName")).toBe("Vlasnikov telefon");
  });
});

function operation(operationId: string, delta: number) {
  return callable({
      operationId,
      pantryId: "p1",
      aggregateType: "STOCK",
      aggregateId: "a_s1",
      baseRevision: 1,
      payload: { type: "adjust_stock", productId: "a", shelfId: "s1", delta },
      deviceId: "device-0001",
      deviceDisplayName: "Testni uređaj",
    }, "u1");
}

function callable(data: Record<string, unknown>, uid: string) {
  return {
    data,
    auth: { uid, token: { sub: uid } },
    app: undefined,
    instanceIdToken: undefined,
    rawRequest: {},
  };
}
