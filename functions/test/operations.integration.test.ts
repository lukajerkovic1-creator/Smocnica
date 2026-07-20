import { afterAll, beforeEach, describe, expect, it } from "vitest";
import firebaseFunctionsTest from "firebase-functions-test";
import { applyOperation } from "../src/operations";
import { createInvitation, createPantry, deleteAccountData, joinPantry, listMyPantries, manageMember, registerDevice, transferOwnership, unregisterDevice } from "../src/pantry";
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
    await db.recursiveDelete(db.doc("pantries/p2"));
    await db.recursiveDelete(db.doc("pantries/p3"));
    const createdPantries = await db.collection("pantries").where("memberUids", "array-contains", "u-create").get();
    const parallelPantries = await db.collection("pantries").where("memberUids", "array-contains", "u-parallel").get();
    await Promise.all([...createdPantries.docs, ...parallelPantries.docs].map((pantry) => db.recursiveDelete(pantry.ref)));
    await Promise.all([
      db.doc("userPantryAccess/u1").delete(), db.doc("userPantryAccess/u2").delete(),
      db.doc("userPantryAccess/u3").delete(), db.doc("userPantryAccess/u-create").delete(),
      db.doc("userPantryAccess/u-parallel").delete(),
      db.recursiveDelete(db.doc("users/u1")), db.recursiveDelete(db.doc("users/u2")),
    ]);
    const batch = db.batch();
    batch.set(db.doc("pantries/p1"), { name: "Test", ownerUid: "u1", memberUids: ["u1", "u2"], revision: 1, createdAt: new Date(), updatedAt: new Date() });
    batch.set(db.doc("pantries/p1/members/u1"), { role: "OWNER", active: true, joinedAt: new Date() });
    batch.set(db.doc("pantries/p1/members/u2"), { role: "MEMBER", active: true, joinedAt: new Date() });
    batch.set(db.doc("userPantryAccess/u1"), { uid: "u1", pantryId: "p1", active: true, updatedAt: new Date() });
    batch.set(db.doc("userPantryAccess/u2"), { uid: "u2", pantryId: "p1", active: true, updatedAt: new Date() });
    batch.set(db.doc("users/u1/devices/device-0001"), { name: "Poslužiteljski telefon", active: true, platform: "ANDROID", updatedAt: new Date() });
    batch.set(db.doc("pantries/p1/shelves/s1"), { name: "Polica 1", sortOrder: 0, revision: 1, createdAt: new Date(), updatedAt: new Date(), deletedAt: null });
    batch.set(db.doc("pantries/p1/shelves/s2"), { name: "Polica 2", sortOrder: 1, revision: 1, createdAt: new Date(), updatedAt: new Date(), deletedAt: null });
    batch.set(db.doc("pantries/p1/categories/c1"), { name: "Namirnice", sortOrder: 0, isDefault: true, revision: 1, createdAt: new Date(), updatedAt: new Date(), deletedAt: null });
    batch.set(db.doc("pantries/p1/products/a"), { name: "Riža", category: "Namirnice", categoryId: "c1", minimumQuantity: 5, autoShopping: true, totalQuantity: 5, revision: 1, createdAt: new Date(), updatedAt: new Date(), deletedAt: null });
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

  it("reactivates a checked automatic item when the real shortage grows", async () => {
    await invoke(operation("op-shortage-1", -1) as never);
    await invoke(callable({
      operationId: "op-check-shortage", pantryId: "p1", aggregateType: "SHOPPING", aggregateId: "auto_a", baseRevision: 1,
      payload: { type: "upsert_shopping", item: {
        id: "auto_a", productId: "a", name: "Klijentski naziv", category: "Namirnice",
        requiredQuantity: 1, checked: true, manual: false,
      } },
      deviceId: "device-0001", deviceDisplayName: "Klijentski uređaj",
    }, "u1") as never);
    expect((await db.doc("pantries/p1/shoppingItems/auto_a").get()).get("checked")).toBe(true);

    await invoke(operation("op-shortage-2", -1) as never);
    const automatic = await db.doc("pantries/p1/shoppingItems/auto_a").get();
    expect(automatic.get("requiredQuantity")).toBe(2);
    expect(automatic.get("checked")).toBe(false);
  });

  it("deletes only manual shopping items and validates their active category", async () => {
    const upsertManual = (operationId: string, categoryId: string) => callable({
      operationId, pantryId: "p1", aggregateType: "SHOPPING", aggregateId: "manual-1", baseRevision: 0,
      payload: { type: "upsert_shopping", item: {
        id: "manual-1", productId: null, name: "Kruh", category: "Klijentski naziv", categoryId,
        requiredQuantity: 2, checked: false, manual: true,
      } },
      deviceId: "device-0001", deviceDisplayName: "Klijentski uređaj",
    }, "u1");
    await expect(invoke(upsertManual("op-manual-invalid-category", "missing") as never))
      .rejects.toMatchObject({ code: "failed-precondition" });
    await invoke(upsertManual("op-manual-create", "c1") as never);
    const created = await db.doc("pantries/p1/shoppingItems/manual-1").get();
    expect(created.get("categoryId")).toBe("c1");
    expect(created.get("category")).toBe("Namirnice");

    await invoke(callable({
      operationId: "op-manual-delete", pantryId: "p1", aggregateType: "SHOPPING", aggregateId: "manual-1", baseRevision: 1,
      payload: { type: "delete_shopping", itemId: "manual-1" },
      deviceId: "device-0001", deviceDisplayName: "Klijentski uređaj",
    }, "u1") as never);
    const deleted = await db.doc("pantries/p1/shoppingItems/manual-1").get();
    expect(deleted.get("deletedAt")).toBeTruthy();
    expect(deleted.get("purgeAfter")).toBeTruthy();
    expect((await db.doc("pantries/p1/activities/op-manual-delete").get()).get("displayLabel")).toBe("Kruh");

    await invoke(operation("op-auto-for-delete", -1) as never);
    await expect(invoke(callable({
      operationId: "op-auto-delete", pantryId: "p1", aggregateType: "SHOPPING", aggregateId: "auto_a", baseRevision: 1,
      payload: { type: "delete_shopping", itemId: "auto_a" },
      deviceId: "device-0001", deviceDisplayName: "Klijentski uređaj",
    }, "u1") as never)).rejects.toMatchObject({ code: "failed-precondition" });
    expect((await db.doc("pantries/p1/shoppingItems/auto_a").get()).get("deletedAt")).toBeFalsy();
  });

  it("never permits concurrent-style removal beyond available stock", async () => {
    await expect(invoke(operation("op-00000003", -6) as never)).rejects.toMatchObject({ code: "failed-precondition" });
    expect((await db.doc("pantries/p1/stocks/a_s1").get()).get("quantity")).toBe(5);
  });

  it("atomically merges the same manual item added offline on two devices", async () => {
    await db.doc("users/u2/devices/device-0002").set({
      name: "Drugi telefon", active: true, platform: "ANDROID", updatedAt: new Date(),
    });
    const normalized = "  KRUH   integralni ".normalize("NFKC").trim().replace(/\s+/gu, " ").toLocaleLowerCase("hr");
    const itemId = `manual_${sha256(`p1\u0000c1\u0000${normalized}`)}`;
    const offlineAdd = (operationId: string, uid: string, deviceId: string, name: string, quantity: number) => callable({
      operationId, pantryId: "p1", aggregateType: "SHOPPING", aggregateId: itemId, baseRevision: 0,
      payload: {
        type: "upsert_shopping", quantityDelta: quantity,
        item: {
          id: itemId, productId: null, name, category: "Klijentski naziv", categoryId: "c1",
          requiredQuantity: quantity, checked: true, manual: true,
        },
      },
      deviceId, deviceDisplayName: "Klijentski uređaj",
    }, uid);

    const firstRequest = offlineAdd("op-offline-manual-1", "u1", "device-0001", "  KRUH   integralni ", 2);
    await Promise.all([
      invoke(firstRequest as never),
      invoke(offlineAdd("op-offline-manual-2", "u2", "device-0002", "Kruh integralni", 3) as never),
    ]);
    await invoke(firstRequest as never);

    const activeManual = (await db.collection("pantries/p1/shoppingItems").get()).docs
      .filter((item) => item.get("manual") === true && !item.get("deletedAt"));
    expect(activeManual).toHaveLength(1);
    expect(activeManual[0]!.id).toBe(itemId);
    expect(activeManual[0]!.get("requiredQuantity")).toBe(5);
    expect(activeManual[0]!.get("checked")).toBe(false);
    expect(activeManual[0]!.get("category")).toBe("Namirnice");
    expect(activeManual[0]!.get("revision")).toBe(2);

    await expect(invoke(offlineAdd("op-offline-invalid-id", "u1", "device-0001", "Drugi proizvod", 1) as never))
      .rejects.toMatchObject({ code: "failed-precondition" });
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
    expect(device.get("detailedNotifications")).toBe(false);
  });

  it("stores an explicit detailed-notification preference and rejects invalid values", async () => {
    await invokeRegisterDevice(callable({
      deviceId: "device-detailed",
      deviceDisplayName: "Detaljni telefon",
      platform: "ANDROID",
      fcmToken: "token-detailed",
      detailedNotifications: true,
    }, "u1") as never);
    expect((await db.doc("users/u1/devices/device-detailed").get()).get("detailedNotifications")).toBe(true);

    await expect(invokeRegisterDevice(callable({
      deviceId: "device-invalid-privacy",
      deviceDisplayName: "Neispravan telefon",
      platform: "ANDROID",
      detailedNotifications: "true",
    }, "u1") as never)).rejects.toMatchObject({ code: "invalid-argument" });
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

  it("rejects every bulk action atomically when the middle product is unavailable", async () => {
    const setup = db.batch();
    setup.set(db.doc("pantries/p1/products/b"), {
      name: "Brašno", category: "Namirnice", categoryId: "c1", minimumQuantity: 0,
      autoShopping: true, totalQuantity: 3, revision: 1, createdAt: new Date(), updatedAt: new Date(), deletedAt: null,
    });
    setup.set(db.doc("pantries/p1/stocks/b_s1"), {
      productId: "b", shelfId: "s1", quantity: 3, revision: 1, updatedAt: new Date(),
    });
    await setup.commit();
    const bulk = (operationId: string, payload: Record<string, unknown>) => callable({
      operationId, pantryId: "p1", aggregateType: "PANTRY", aggregateId: "p1", baseRevision: 0,
      payload, deviceId: "device-0001", deviceDisplayName: "Klijentski uređaj",
    }, "u1");

    await expect(invoke(bulk("op-bulk-category-invalid", {
      type: "bulk_change_product_category", productIds: ["a", "missing-middle", "b"], categoryId: "c1",
    }) as never)).rejects.toMatchObject({ code: "failed-precondition" });
    expect((await db.doc("pantries/p1/products/a").get()).get("revision")).toBe(1);
    expect((await db.doc("pantries/p1/products/b").get()).get("revision")).toBe(1);

    await expect(invoke(bulk("op-bulk-move-invalid", {
      type: "bulk_move_stock",
      moves: [
        { productId: "a", fromShelfId: "s1", toShelfId: "s2", quantity: 2 },
        { productId: "missing-middle", fromShelfId: "s1", toShelfId: "s2", quantity: 1 },
        { productId: "b", fromShelfId: "s1", toShelfId: "s2", quantity: 3 },
      ],
    }) as never)).rejects.toMatchObject({ code: "failed-precondition" });
    expect((await db.doc("pantries/p1/stocks/a_s1").get()).get("quantity")).toBe(5);
    expect((await db.doc("pantries/p1/stocks/b_s1").get()).get("quantity")).toBe(3);
    expect((await db.doc("pantries/p1/stocks/a_s2").get()).exists).toBe(false);
    expect((await db.doc("pantries/p1/stocks/b_s2").get()).exists).toBe(false);

    await expect(invoke(bulk("op-bulk-delete-invalid", {
      type: "bulk_delete_products", productIds: ["a", "missing-middle", "b"],
    }) as never)).rejects.toMatchObject({ code: "failed-precondition" });
    expect((await db.doc("pantries/p1/products/a").get()).get("deletedAt")).toBeNull();
    expect((await db.doc("pantries/p1/products/b").get()).get("deletedAt")).toBeNull();
    expect((await db.collection("pantries/p1/operations").where("payloadType", ">=", "bulk_").get()).size).toBe(0);
    expect((await db.collection("pantries/p1/activities").get()).size).toBe(0);
  });

  it("applies a valid bulk move as one operation with per-product activities", async () => {
    const setup = db.batch();
    setup.set(db.doc("pantries/p1/products/b"), {
      name: "Brašno", category: "Namirnice", categoryId: "c1", minimumQuantity: 0,
      autoShopping: true, totalQuantity: 3, revision: 1, createdAt: new Date(), updatedAt: new Date(), deletedAt: null,
    });
    setup.set(db.doc("pantries/p1/stocks/b_s1"), {
      productId: "b", shelfId: "s1", quantity: 3, revision: 1, updatedAt: new Date(),
    });
    await setup.commit();

    const request = callable({
      operationId: "op-bulk-move-valid", pantryId: "p1", aggregateType: "PANTRY", aggregateId: "p1", baseRevision: 0,
      payload: {
        type: "bulk_move_stock",
        moves: [
          { productId: "a", fromShelfId: "s1", toShelfId: "s2", quantity: 2 },
          { productId: "b", fromShelfId: "s1", toShelfId: "s2", quantity: 3 },
        ],
      },
      deviceId: "device-0001", deviceDisplayName: "Klijentski uređaj",
    }, "u1");
    await invoke(request as never);
    await invoke(request as never);

    expect((await db.doc("pantries/p1/stocks/a_s1").get()).get("quantity")).toBe(3);
    expect((await db.doc("pantries/p1/stocks/a_s2").get()).get("quantity")).toBe(2);
    expect((await db.doc("pantries/p1/stocks/b_s1").get()).get("quantity")).toBe(0);
    expect((await db.doc("pantries/p1/stocks/b_s2").get()).get("quantity")).toBe(3);
    expect((await db.doc("pantries/p1/operations/op-bulk-move-valid").get()).exists).toBe(true);
    const activities = await db.collection("pantries/p1/activities").where("type", "==", "STOCK_MOVED").get();
    expect(activities.size).toBe(2);
    expect(activities.docs.map((activity) => activity.get("displayLabel")).sort()).toEqual(["Brašno", "Riža"]);
  });

  it("applies bulk category change and deletion atomically", async () => {
    const setup = db.batch();
    setup.set(db.doc("pantries/p1/categories/c2"), {
      name: "Pića", sortOrder: 1, isDefault: false, revision: 1,
      createdAt: new Date(), updatedAt: new Date(), deletedAt: null,
    });
    setup.set(db.doc("pantries/p1/products/b"), {
      name: "Brašno", category: "Namirnice", categoryId: "c1", minimumQuantity: 0,
      autoShopping: true, totalQuantity: 0, revision: 1, createdAt: new Date(), updatedAt: new Date(), deletedAt: null,
    });
    await setup.commit();
    const bulk = (operationId: string, payload: Record<string, unknown>) => callable({
      operationId, pantryId: "p1", aggregateType: "PANTRY", aggregateId: "p1", baseRevision: 0,
      payload, deviceId: "device-0001", deviceDisplayName: "Klijentski uređaj",
    }, "u1");

    await invoke(bulk("op-bulk-category-valid", {
      type: "bulk_change_product_category", productIds: ["a", "b"], categoryId: "c2",
    }) as never);
    expect((await db.doc("pantries/p1/products/a").get()).get("categoryId")).toBe("c2");
    expect((await db.doc("pantries/p1/products/a").get()).get("category")).toBe("Pića");
    expect((await db.doc("pantries/p1/products/b").get()).get("categoryId")).toBe("c2");
    expect((await db.collection("pantries/p1/activities").where("type", "==", "PRODUCT_UPDATED").get()).size).toBe(2);

    await invoke(bulk("op-bulk-delete-valid", {
      type: "bulk_delete_products", productIds: ["a", "b"],
    }) as never);
    expect((await db.doc("pantries/p1/products/a").get()).get("deletedAt")).toBeTruthy();
    expect((await db.doc("pantries/p1/products/b").get()).get("deletedAt")).toBeTruthy();
    expect((await db.collection("pantries/p1/activities").where("type", "==", "ITEM_DELETED").get()).size).toBe(2);
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

    const missingId = upsertProductOperation("op-category-missing-id", "c1", "Namirnice", 2);
    delete (missingId.data.payload as any).product.categoryId;
    await expect(invoke(missingId as never)).rejects.toMatchObject({ code: "invalid-argument" });

    await expect(invoke(upsertProductOperation("op-category-missing", "missing", "Namirnice", 2) as never))
      .rejects.toMatchObject({ code: "failed-precondition" });
    await db.doc("pantries/p1/categories/c1").update({ deletedAt: new Date() });
    await expect(invoke(upsertProductOperation("op-category-deleted", "c1", "Namirnice", 2) as never))
      .rejects.toMatchObject({ code: "failed-precondition" });
  });

  it("rejects duplicate shelf names regardless of case and whitespace", async () => {
    await invoke(callable({
      operationId: "op-shelf-create", pantryId: "p1", aggregateType: "SHELF", aggregateId: "s3", baseRevision: 0,
      payload: { type: "create_shelf", shelf: { id: "s3", name: "Nova   polica", sortOrder: 2 } },
      deviceId: "device-0001", deviceDisplayName: "Klijent",
    }, "u1") as never);
    expect((await db.doc("pantries/p1/shelves/s3").get()).get("normalizedName")).toBe("nova polica");
    await expect(invoke(callable({
      operationId: "op-shelf-duplicate", pantryId: "p1", aggregateType: "SHELF", aggregateId: "s4", baseRevision: 0,
      payload: { type: "create_shelf", shelf: { id: "s4", name: "  NOVA polica ", sortOrder: 3 } },
      deviceId: "device-0001", deviceDisplayName: "Klijent",
    }, "u1") as never)).rejects.toMatchObject({ code: "already-exists" });
    expect((await db.doc("pantries/p1/shelves/s4").get()).exists).toBe(false);
  });

  it("keeps one server-controlled default category and category links by id", async () => {
    await invoke(callable({
      operationId: "op-category-new", pantryId: "p1", aggregateType: "CATEGORY", aggregateId: "c2", baseRevision: 0,
      payload: { type: "upsert_category", category: { id: "c2", name: "Pića", sortOrder: 1, isDefault: true } },
      deviceId: "device-0001", deviceDisplayName: "Klijent",
    }, "u1") as never);
    expect((await db.doc("pantries/p1/categories/c1").get()).get("isDefault")).toBe(true);
    expect((await db.doc("pantries/p1/categories/c2").get()).get("isDefault")).toBe(false);

    await expect(invoke(callable({
      operationId: "op-category-duplicate", pantryId: "p1", aggregateType: "CATEGORY", aggregateId: "c3", baseRevision: 0,
      payload: { type: "upsert_category", category: { id: "c3", name: "  PIĆA  ", sortOrder: 2, isDefault: false } },
      deviceId: "device-0001", deviceDisplayName: "Klijent",
    }, "u1") as never)).rejects.toMatchObject({ code: "already-exists" });
  });

  it("migrates legacy canonical names, category ids, and the single default before listing", async () => {
    await db.doc("pantries/p1/categories/c2").set({ name: "Pića", sortOrder: 1, isDefault: true, deletedAt: null });
    await db.doc("pantries/p1/shoppingItems/manual-legacy").set({
      name: "Sok", category: "  piĆA ", requiredQuantity: 1, checked: false, manual: true, deletedAt: null,
    });
    const result = await invokeListMyPantries(callable({}, "u1") as never);
    expect(result.pantries).toHaveLength(1);
    const categories = await db.collection("pantries/p1/categories").where("deletedAt", "==", null).get();
    expect(categories.docs.filter((document) => document.get("isDefault") === true)).toHaveLength(1);
    expect((await db.doc("pantries/p1/categories/c2").get()).get("normalizedName")).toBe("pića");
    const item = await db.doc("pantries/p1/shoppingItems/manual-legacy").get();
    expect(item.get("categoryId")).toBe("c2");
    expect(item.get("category")).toBe("Pića");
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
    await db.doc("userPantryAccess/u1").delete();
    const listed = await invokeListMyPantries(callable({}, "u1") as never);
    expect(listed.pantries).toHaveLength(1);
    expect(listed.pantries[0].pantry.id).toBe("p1");
    expect((await db.doc("userPantryAccess/u1").get()).get("pantryId")).toBe("p1");
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
    expect((await db.doc("userPantryAccess/u2").get()).exists).toBe(false);
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

  it("deletes a member account, devices and access while anonymizing shared history", async () => {
    await db.doc("users/u2").set({ displayName: "Osobno ime", photoUrl: "https://example.test/avatar.jpg" });
    await db.doc("users/u2/devices/phone").set({ name: "Privatni telefon", active: true, fcmToken: "secret-token" });
    await db.doc("pantries/p1/activities/private-u2").set({
      type: "STOCK_ADDED", actorUid: "u2", aggregateId: "a", deviceId: "phone",
      deviceDisplayName: "Privatni telefon", displayLabel: "Riža", createdAt: new Date(),
    });
    await db.doc("pantries/p1/operations/private-u2").set({ actorUid: "u2", deviceId: "phone", deviceDisplayName: "Privatni telefon" });
    await db.doc("pantries/p3").set({ name: "Legacy", ownerUid: "legacy-owner", memberUids: ["legacy-owner"], revision: 0 });
    await db.doc("pantries/p3/members/u2").set({ uid: "u2", role: "MEMBER", active: true, joinedAt: new Date() });

    await deleteAccountData("u2");

    expect((await db.doc("users/u2").get()).exists).toBe(false);
    expect((await db.doc("users/u2/devices/phone").get()).exists).toBe(false);
    expect((await db.doc("userPantryAccess/u2").get()).exists).toBe(false);
    expect((await db.doc("pantries/p1/members/u2").get()).exists).toBe(false);
    expect((await db.doc("pantries/p3/members/u2").get()).exists).toBe(false);
    expect((await db.doc("pantries/p1").get()).get("memberUids")).toEqual(["u1"]);
    const activity = await db.doc("pantries/p1/activities/private-u2").get();
    expect(activity.get("actorUid")).toBe("deleted-user");
    expect(activity.get("deviceDisplayName")).toBe("Obrisani korisnik");
    expect((await db.doc("pantries/p1/operations/private-u2").get()).get("actorUid")).toBe("deleted-user");
  });

  it("transfers an owner's pantry before deleting the owner account", async () => {
    await db.doc("users/u1").set({ displayName: "Vlasnik" });
    await db.doc("users/u1/devices/device-0001").set({ name: "Telefon", active: true });

    await deleteAccountData("u1");

    const pantry = await db.doc("pantries/p1").get();
    expect(pantry.get("ownerUid")).toBe("u2");
    expect(pantry.get("memberUids")).toEqual(["u2"]);
    expect((await db.doc("pantries/p1/members/u1").get()).exists).toBe(false);
    expect((await db.doc("pantries/p1/members/u2").get()).get("role")).toBe("OWNER");
    expect((await db.doc("users/u1").get()).exists).toBe(false);
  });

  it("creates at most one pantry and returns the same pantry for an idempotent retry", async () => {
    await db.doc("users/u-create/devices/device-create").set({ name: "Telefon za stvaranje", active: true });
    const request = callable({
      name: "Nova smočnica", deviceId: "device-create", requestId: "create-request-0001",
      deviceDisplayName: "Lažni telefon",
    }, "u-create");
    const result = await invokeCreatePantry(request as never);
    const retry = await invokeCreatePantry(request as never);
    expect(retry.pantry.id).toBe(result.pantry.id);
    expect((await db.doc("userPantryAccess/u-create").get()).get("pantryId")).toBe(result.pantry.id);
    const activity = (await db.collection(`pantries/${result.pantry.id}/activities`).get()).docs[0];
    expect(activity.get("deviceId")).toBe("device-create");
    expect(activity.get("deviceDisplayName")).toBe("Telefon za stvaranje");
    await expect(invokeCreatePantry(callable({
      name: "Druga smočnica", deviceId: "device-create", requestId: "create-request-0002",
    }, "u-create") as never)).rejects.toMatchObject({ code: "already-exists" });
    await expect(invokeCreatePantry(callable({
      name: "Skrivena smočnica", deviceId: "device-0001", requestId: "create-request-u1",
    }, "u1") as never)).rejects.toMatchObject({ code: "already-exists" });
  });

  it("serializes simultaneous pantry creation from two devices", async () => {
    await Promise.all([
      db.doc("users/u-parallel/devices/device-a").set({ name: "Telefon A", active: true }),
      db.doc("users/u-parallel/devices/device-b").set({ name: "Telefon B", active: true }),
    ]);
    const attempts = await Promise.allSettled([
      invokeCreatePantry(callable({ name: "Prva", deviceId: "device-a", requestId: "parallel-request-a" }, "u-parallel") as never),
      invokeCreatePantry(callable({ name: "Druga", deviceId: "device-b", requestId: "parallel-request-b" }, "u-parallel") as never),
    ]);

    expect(attempts.filter((attempt) => attempt.status === "fulfilled")).toHaveLength(1);
    expect(attempts.filter((attempt) => attempt.status === "rejected")).toHaveLength(1);
    const pantries = await db.collection("pantries").where("memberUids", "array-contains", "u-parallel").get();
    expect(pantries.size).toBe(1);
    expect((await db.doc("userPantryAccess/u-parallel").get()).get("pantryId")).toBe(pantries.docs[0]!.id);
  }, 15_000);

  it("keeps only one active invitation under simultaneous creation", async () => {
    const attempts = await Promise.allSettled([
      invokeCreateInvitation(callable({ pantryId: "p1" }, "u1") as never),
      invokeCreateInvitation(callable({ pantryId: "p1" }, "u1") as never),
    ]);
    expect(attempts.some((attempt) => attempt.status === "fulfilled")).toBe(true);
    const active = await db.collection("inviteCodes").where("pantryId", "==", "p1").where("revokedAt", "==", null).get();
    expect(active.size).toBe(1);
    expect(active.docs[0]!.get("usesRemaining")).toBe(1);
  }, 15_000);

  it("rejects joining a second pantry without consuming its invitation", async () => {
    const secondPantry = db.doc("pantries/p2");
    const code = "SECOND-PANTRY-01";
    const inviteRef = db.doc(`inviteCodes/${sha256(code)}`);
    await secondPantry.set({ name: "Druga", ownerUid: "owner2", memberUids: ["owner2"], revision: 0, createdAt: new Date(), updatedAt: new Date(), deletedAt: null });
    await inviteRef.set({ pantryId: "p2", createdBy: "owner2", createdAt: new Date(), expiresAt: new Date(Date.now() + 60_000), usesRemaining: 1, revokedAt: null });
    await db.doc("users/u1/devices/device-0001").set({ name: "Poslužiteljski telefon", active: true });

    await expect(invokeJoinPantry(callable({ code, deviceId: "device-0001" }, "u1") as never))
      .rejects.toMatchObject({ code: "already-exists" });
    expect((await inviteRef.get()).get("usesRemaining")).toBe(1);
    expect((await secondPantry.collection("members").doc("u1").get()).exists).toBe(false);
  });

  it("rejects an eleventh active member without consuming the invitation", async () => {
    const additions = db.batch();
    for (let index = 3; index <= 10; index++) {
      additions.set(db.doc(`pantries/p1/members/u${index}`), { role: "MEMBER", active: true, joinedAt: new Date() });
    }
    await additions.commit();
    const invitation = await invokeCreateInvitation(callable({ pantryId: "p1" }, "u1") as never);
    const inviteRef = db.doc(`inviteCodes/${sha256(invitation.code)}`);
    await db.doc("users/u11/devices/device-u11").set({ name: "Telefon u11", active: true });

    await expect(invokeJoinPantry(callable({ code: invitation.code, deviceId: "device-u11" }, "u11") as never))
      .rejects.toMatchObject({ code: "resource-exhausted" });
    expect((await inviteRef.get()).get("usesRemaining")).toBe(1);
    expect((await db.doc("pantries/p1/members/u11").get()).exists).toBe(false);
  });

  it("rejects creating a shelf after the pantry limit is reached", async () => {
    const additions = db.batch();
    for (let index = 3; index <= 50; index++) {
      additions.set(db.doc(`pantries/p1/shelves/limit-${index}`), {
        name: `Polica ${index}`, sortOrder: index, revision: 1,
        createdAt: new Date(), updatedAt: new Date(), deletedAt: null,
      });
    }
    await additions.commit();
    await expect(invoke(callable({
      operationId: "op-shelf-limit", pantryId: "p1", aggregateType: "SHELF", aggregateId: "s51", baseRevision: 0,
      payload: { type: "create_shelf", shelf: { id: "s51", name: "Polica 51", sortOrder: 51 } },
      deviceId: "device-0001", deviceDisplayName: "Klijentski uređaj",
    }, "u1") as never)).rejects.toMatchObject({ code: "resource-exhausted" });
    expect((await db.doc("pantries/p1/shelves/s51").get()).exists).toBe(false);
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
