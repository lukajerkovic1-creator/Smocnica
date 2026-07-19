import { afterAll, beforeEach, describe, expect, it } from "vitest";
import firebaseFunctionsTest from "firebase-functions-test";
import { applyOperation } from "../src/operations";
import { createInvitation, createPantry, joinPantry, listMyPantries, manageMember, registerDevice, transferOwnership, unregisterDevice } from "../src/pantry";
import { db } from "../src/firebase";
import { sha256 } from "../src/validation";

const emulatorAvailable = Boolean(process.env.FIRESTORE_EMULATOR_HOST);
const testEnvironment = firebaseFunctionsTest();
const invoke = testEnvironment.wrap(applyOperation);
const invokeManageMember = testEnvironment.wrap(manageMember);
const invokeListMyPantries = testEnvironment.wrap(listMyPantries);
const invokeUnregisterDevice = testEnvironment.wrap(unregisterDevice);
const invokeRegisterDevice = testEnvironment.wrap(registerDevice);
const invokeCreateInvitation = testEnvironment.wrap(createInvitation);
const invokeJoinPantry = testEnvironment.wrap(joinPantry);
const invokeTransferOwnership = testEnvironment.wrap(transferOwnership);
const invokeCreatePantry = testEnvironment.wrap(createPantry);

describe.skipIf(!emulatorAvailable)("applyOperation transaction integration", () => {
  afterAll(() => testEnvironment.cleanup());
  beforeEach(async () => {
    await db.recursiveDelete(db.doc("pantries/p1"));
    const batch = db.batch();
    batch.set(db.doc("pantries/p1"), { name: "Test", ownerUid: "u1", memberUids: ["u1", "u2"], revision: 1, createdAt: new Date(), updatedAt: new Date() });
    batch.set(db.doc("pantries/p1/members/u1"), { role: "OWNER", active: true, joinedAt: new Date() });
    batch.set(db.doc("pantries/p1/members/u2"), { role: "MEMBER", active: true, joinedAt: new Date() });
    batch.set(db.doc("users/u1/devices/device-0001"), { name: "Poslužiteljski telefon", active: true, platform: "ANDROID", updatedAt: new Date() });
    batch.set(db.doc("pantries/p1/shelves/s1"), { name: "Polica 1", sortOrder: 0, revision: 1, createdAt: new Date(), updatedAt: new Date() });
    batch.set(db.doc("pantries/p1/shelves/s2"), { name: "Polica 2", sortOrder: 1, revision: 1, createdAt: new Date(), updatedAt: new Date() });
    batch.set(db.doc("pantries/p1/categories/c1"), { name: "Namirnice", sortOrder: 0, isDefault: true, revision: 1, createdAt: new Date(), updatedAt: new Date() });
    batch.set(db.doc("pantries/p1/products/a"), { name: "Riža", category: "Namirnice", categoryId: "c1", minimumQuantity: 5, autoShopping: true, totalQuantity: 5, revision: 1, createdAt: new Date(), updatedAt: new Date() });
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
    const activity = await db.doc("pantries/p1/activities/op-00000001").get();
    expect(activity.get("displayLabel")).toBe("Riža");
    expect(activity.get("oldValue")).toBe("Polica 1");
    expect(activity.get("newValue")).toBe("Polica 1");
    expect(activity.get("productId")).toBe("a");
    expect(activity.get("shelfId")).toBe("s1");
    expect(activity.get("deviceDisplayName")).toBe("Poslužiteljski telefon");

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

  it("rejects unknown or inactive devices before applying a new operation", async () => {
    const unknown = operation("op-device-unknown", 1);
    unknown.data.deviceId = "unknown-device";
    await expect(invoke(unknown as never)).rejects.toMatchObject({ code: "permission-denied" });

    await db.doc("users/u1/devices/device-0001").update({ active: false });
    await expect(invoke(operation("op-device-inactive", 1) as never)).rejects.toMatchObject({ code: "permission-denied" });

    await db.doc("users/u2/devices/foreign-device").set({ name: "Tuđi uređaj", active: true });
    const foreign = operation("op-device-foreign", 1);
    foreign.data.deviceId = "foreign-device";
    await expect(invoke(foreign as never)).rejects.toMatchObject({ code: "permission-denied" });
    expect((await db.doc("pantries/p1/stocks/a_s1").get()).get("quantity")).toBe(5);
  });

  it("registers an active device even when an FCM token is temporarily unavailable", async () => {
    await invokeRegisterDevice(callable({
      deviceId: "device-no-token",
      deviceDisplayName: "Telefon bez tokena",
      platform: "ANDROID",
    }, "u1") as never);
    const device = await db.doc("users/u1/devices/device-no-token").get();
    expect(device.get("active")).toBe(true);
    expect(device.get("name")).toBe("Telefon bez tokena");
    expect(device.get("fcmToken")).toBeUndefined();
  });

  it("moves stock only while the product and both shelves are active", async () => {
    await invoke(moveOperation("op-move-active") as never);
    expect((await db.doc("pantries/p1/stocks/a_s1").get()).get("quantity")).toBe(3);
    expect((await db.doc("pantries/p1/stocks/a_s2").get()).get("quantity")).toBe(2);
    const activity = await db.doc("pantries/p1/activities/op-move-active").get();
    expect(activity.get("displayLabel")).toBe("Riža");
    expect(activity.get("oldValue")).toBe("Polica 1");
    expect(activity.get("newValue")).toBe("Polica 2");
    expect(activity.get("productId")).toBe("a");
    expect(activity.get("fromShelfId")).toBe("s1");
    expect(activity.get("toShelfId")).toBe("s2");
  });

  it("records a deleted product with its server name and structured product id", async () => {
    const request = operation("op-delete-product", 1);
    request.data.aggregateType = "PRODUCT";
    request.data.aggregateId = "a";
    request.data.payload = {
      type: "soft_delete", targetType: "PRODUCT", id: "a", displayLabel: "Krivotvoreni naziv",
    };
    await invoke(request as never);
    const activity = await db.doc("pantries/p1/activities/op-delete-product").get();
    expect(activity.get("displayLabel")).toBe("Riža");
    expect(activity.get("productId")).toBe("a");
  });

  it.each([
    ["missing product", async () => db.doc("pantries/p1/products/a").delete()],
    ["deleted product", async () => db.doc("pantries/p1/products/a").update({ deletedAt: new Date() })],
    ["missing source shelf", async () => db.doc("pantries/p1/shelves/s1").delete()],
    ["deleted source shelf", async () => db.doc("pantries/p1/shelves/s1").update({ deletedAt: new Date() })],
    ["missing target shelf", async () => db.doc("pantries/p1/shelves/s2").delete()],
    ["deleted target shelf", async () => db.doc("pantries/p1/shelves/s2").update({ deletedAt: new Date() })],
  ])("rejects move_stock for a %s without partial writes", async (_caseName, makeUnavailable) => {
    await makeUnavailable();
    await expect(invoke(moveOperation("op-move-unavailable") as never)).rejects.toMatchObject({ code: "not-found" });
    expect((await db.doc("pantries/p1/stocks/a_s1").get()).get("quantity")).toBe(5);
    expect((await db.doc("pantries/p1/stocks/a_s2").get()).exists).toBe(false);
    expect((await db.doc("pantries/p1/operations/op-move-unavailable").get()).exists).toBe(false);
    expect((await db.doc("pantries/p1/activities/op-move-unavailable").get()).exists).toBe(false);
  });

  it("notifies once when editing the minimum crosses the threshold and rearms above it", async () => {
    const updateMinimum = (operationId: string, baseRevision: number, minimumQuantity: number) => callable({
      operationId, pantryId: "p1", aggregateType: "PRODUCT", aggregateId: "a", baseRevision,
      payload: { type: "upsert_product", product: {
        id: "a", name: "Riža", barcode: null, description: "", category: "Lažirani naziv", categoryId: "c1",
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

  it("uses the active server category and rejects missing or deleted category ids", async () => {
    await invoke(upsertProductOperation("op-category-ok", "c1", "Klijentska kategorija") as never);
    const product = await db.doc("pantries/p1/products/a").get();
    expect(product.get("categoryId")).toBe("c1");
    expect(product.get("category")).toBe("Namirnice");

    const legacy = upsertProductOperation("op-category-legacy", "c1", "Namirnice", 2);
    delete (legacy.data.payload as any).product.categoryId;
    await invoke(legacy as never);
    expect((await db.doc("pantries/p1/products/a").get()).get("categoryId")).toBe("c1");

    await expect(invoke(upsertProductOperation("op-category-missing", "missing", "Namirnice", 3) as never))
      .rejects.toMatchObject({ code: "failed-precondition" });
    await db.doc("pantries/p1/categories/c1").update({ deletedAt: new Date() });
    await expect(invoke(upsertProductOperation("op-category-deleted", "c1", "Namirnice", 3) as never))
      .rejects.toMatchObject({ code: "failed-precondition" });
  });

  it("accepts only canonical Open Food Facts image URLs", async () => {
    const allowed = upsertProductOperation("op-photo-allowed", "c1", "Namirnice");
    (allowed.data.payload as any).product.photoSource = "OPEN_FOOD_FACTS";
    (allowed.data.payload as any).product.photoUri = "https://images.openfoodfacts.org/images/products/400/638/133/3931/front_en.3.400.jpg";
    await invoke(allowed as never);

    const foreign = upsertProductOperation("op-photo-foreign", "c1", "Namirnice", 2);
    (foreign.data.payload as any).product.photoSource = "OPEN_FOOD_FACTS";
    (foreign.data.payload as any).product.photoUri = "https://evil.example/images/products/a.jpg";
    await expect(invoke(foreign as never)).rejects.toMatchObject({ code: "invalid-argument" });
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
            products: [{ id: "b", name: "Tjestenina", barcode: code, description: "500 g", category: "Lažno", categoryId: "c1", photoSource: "NONE", minimumQuantity: 2, autoShopping: true, revision: 0, createdAt: 1, updatedAt: 1 }],
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
            products: [{ id: "bad", name: "Ulje", barcode: null, description: "", category: "Namirnice", categoryId: "c1", photoSource: "NONE", minimumQuantity: 5, autoShopping: true }],
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
    expect(activity.get("deviceDisplayName")).toBe("Poslužiteljski telefon");
  });

  it("consumes a hashed invitation exactly once", async () => {
    const invitation = await invokeCreateInvitation(callable({ pantryId: "p1" }, "u1") as never);
    expect(invitation.code).toMatch(/^[A-Z2-9]{16}$/);
    expect((await db.doc(`inviteCodes/${sha256(invitation.code)}`).get()).exists).toBe(true);
    expect((await db.doc(`inviteCodes/${invitation.code}`).get()).exists).toBe(false);
    await db.doc("users/u3/devices/device-u3").set({ name: "Telefon u3", active: true });
    await invokeJoinPantry(callable({ code: invitation.code, deviceId: "device-u3", deviceDisplayName: "Lažni telefon" }, "u3") as never);
    expect((await db.doc("pantries/p1/members/u3").get()).get("active")).toBe(true);
    const activity = (await db.collection("pantries/p1/activities").where("type", "==", "MEMBER_JOINED").get()).docs[0];
    expect(activity.get("deviceDisplayName")).toBe("Telefon u3");
    expect(activity.get("deviceId")).toBe("device-u3");
    await expect(invokeJoinPantry(callable({ code: invitation.code, deviceId: "missing-u4" }, "u4") as never))
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
    expect(activity.get("deviceDisplayName")).toBe("Poslužiteljski telefon");
  });

  it("attributes pantry creation to the registered device instead of the client label", async () => {
    const result = await invokeCreatePantry(callable({
      name: "Nova smočnica", deviceId: "device-0001", deviceDisplayName: "Lažni telefon",
    }, "u1") as never);
    const activity = (await db.collection(`pantries/${result.pantry.id}/activities`).get()).docs[0];
    expect(activity.get("deviceId")).toBe("device-0001");
    expect(activity.get("deviceDisplayName")).toBe("Poslužiteljski telefon");
  });
});

function operation(operationId: string, delta: number) {
  return callable({
      operationId,
      pantryId: "p1",
      aggregateType: "STOCK",
      aggregateId: "a_s1",
      baseRevision: 1,
      payload: { type: "adjust_stock", productId: "a", shelfId: "s1", delta, productName: "Lažni artikl", shelfName: "Lažna polica" },
      deviceId: "device-0001",
      deviceDisplayName: "Testni uređaj",
    }, "u1");
}

function moveOperation(operationId: string) {
  return callable({
    operationId,
    pantryId: "p1",
    aggregateType: "STOCK",
    aggregateId: "a_s1",
    baseRevision: 1,
    payload: {
      type: "move_stock", productId: "a", fromShelfId: "s1", toShelfId: "s2", quantity: 2,
      productName: "Lažni artikl", fromShelfName: "Lažna izvorna", toShelfName: "Lažna odredišna",
    },
    deviceId: "device-0001",
    deviceDisplayName: "Testni uredaj",
  }, "u1");
}

function upsertProductOperation(operationId: string, categoryId: string, category: string, baseRevision = 1) {
  return callable({
    operationId, pantryId: "p1", aggregateType: "PRODUCT", aggregateId: "a", baseRevision,
    payload: { type: "upsert_product", product: {
      id: "a", name: "Riža", barcode: null, description: "", category, categoryId,
      photoUri: null, photoSource: "NONE", minimumQuantity: 5, autoShopping: true,
    } },
    deviceId: "device-0001", deviceDisplayName: "Klijentski naziv",
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
