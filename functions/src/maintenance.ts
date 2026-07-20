import { getStorage } from "firebase-admin/storage";
import { getMessaging } from "firebase-admin/messaging";
import { FieldValue, Timestamp, QuerySnapshot } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { db } from "./firebase";
import { normalizedName, sha256 } from "./validation";

export const notifyLowStock = onDocumentCreated(
  { region: "europe-west1", document: "pantries/{pantryId}/notifications/{notificationId}" },
  async (event) => {
    const pantryId = event.params.pantryId;
    const notification = event.data?.data();
    if (!notification) return;
    const members = await db.collection(`pantries/${pantryId}/members`).where("active", "==", true).get();
    const deviceSnapshots = await Promise.all(members.docs.map((member) => db.collection(`users/${member.id}/devices`).where("active", "==", true).get()));
    const audiences = groupNotificationTokens(
      deviceSnapshots.flatMap((snapshot) => snapshot.docs.map((doc) => ({
        fcmToken: doc.get("fcmToken"),
        detailedNotifications: doc.get("detailedNotifications"),
      }))),
    );
    const tokens = [...audiences.privateTokens, ...audiences.detailedTokens];
    if (tokens.length === 0) return;
    for (const audience of [
      { tokens: audiences.privateTokens, detailed: false },
      { tokens: audiences.detailedTokens, detailed: true },
    ]) {
      for (const tokenChunk of chunk(audience.tokens, 500)) {
        const result = await getMessaging().sendEachForMulticast({
          tokens: tokenChunk,
          notification: lowStockNotificationContent(notification, audience.detailed),
          data: { pantryId, productId: String(notification.productId), destination: "shopping" },
          android: { priority: "high", notification: { channelId: "low_stock" } },
        });
        const invalidTokens = result.responses.flatMap((response, index) => {
          const code = response.error?.code;
          return code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token"
            ? [tokenChunk[index]!]
            : [];
        });
        if (invalidTokens.length > 0) await deactivateTokens(invalidTokens, deviceSnapshots);
      }
    }
    await event.data?.ref.update({ sentAt: FieldValue.serverTimestamp(), recipientCount: tokens.length });
  },
);

type NotificationDevice = { fcmToken?: unknown; detailedNotifications?: unknown };

export function groupNotificationTokens(devices: NotificationDevice[]): { privateTokens: string[]; detailedTokens: string[] } {
  const preferencesByToken = new Map<string, boolean>();
  for (const device of devices) {
    if (typeof device.fcmToken !== "string" || device.fcmToken.length === 0) continue;
    const detailed = device.detailedNotifications === true;
    preferencesByToken.set(device.fcmToken, (preferencesByToken.get(device.fcmToken) ?? true) && detailed);
  }
  const privateTokens: string[] = [];
  const detailedTokens: string[] = [];
  preferencesByToken.forEach((detailed, token) => (detailed ? detailedTokens : privateTokens).push(token));
  return { privateTokens, detailedTokens };
}

export function lowStockNotificationContent(
  notification: Record<string, unknown>,
  detailed: boolean,
): { title: string; body: string } {
  if (!detailed) {
    return { title: "Smočnica", body: "Jedan artikl je ispod minimalne zalihe." };
  }
  return {
    title: "Artikl je ispod minimuma",
    body: `${String(notification.name).slice(0, 100)}: preostalo ${Number(notification.remaining)} kom, na popis dodano ${Number(notification.required)} kom.`,
  };
}

export const purgeExpiredData = onSchedule(
  { region: "europe-west1", schedule: "every day 03:15", timeZone: "Europe/Zagreb", timeoutSeconds: 540, memory: "512MiB" },
  async () => {
    const timestamp = Timestamp.now();
    for (const collection of ["products", "shelves", "categories", "shoppingItems"] as const) {
      const expired = await db.collectionGroup(collection).where("purgeAfter", "<=", timestamp).limit(350).get();
      const writer = db.bulkWriter();
      for (const document of expired.docs) {
        const pantryId = document.ref.parent.parent?.id;
        if (collection === "products") {
          if (pantryId) {
            await getStorage().bucket().file(`pantries/${pantryId}/products/${document.id}/main.jpg`).delete({ ignoreNotFound: true });
            const [stocks, shopping] = await Promise.all([
              db.collection(`pantries/${pantryId}/stocks`).where("productId", "==", document.id).get(),
              db.collection(`pantries/${pantryId}/shoppingItems`).where("productId", "==", document.id).get(),
            ]);
            stocks.docs.forEach((related) => writer.delete(related.ref));
            shopping.docs.forEach((related) => writer.delete(related.ref));
            const code = document.get("barcode");
            if (typeof code === "string") writer.delete(db.doc(`barcodes/${sha256(`${pantryId}:${code}`)}`));
          }
        } else if (collection === "shelves" && pantryId) {
          const stocks = await db.collection(`pantries/${pantryId}/stocks`).where("shelfId", "==", document.id).get();
          stocks.docs.forEach((related) => writer.delete(related.ref));
          const normalized = typeof document.get("normalizedName") === "string"
            ? normalizedName(document.get("normalizedName"))
            : normalizedName(String(document.get("name") || ""));
          writer.delete(db.doc(`pantries/${pantryId}/shelfNames/${sha256(normalized)}`));
        } else if (collection === "categories" && pantryId) {
          const normalized = typeof document.get("normalizedName") === "string"
            ? normalizedName(document.get("normalizedName"))
            : normalizedName(String(document.get("name") || ""));
          writer.delete(db.doc(`pantries/${pantryId}/categoryNames/${sha256(normalized)}`));
        }
        writer.delete(document.ref);
      }
      await writer.close();
    }
    const pantries = await db.collection("pantries").where("purgeAfter", "<=", timestamp).limit(20).get();
    for (const pantry of pantries.docs) {
      await db.recursiveDelete(pantry.ref);
      const [barcodes, invitations] = await Promise.all([
        db.collection("barcodes").where("pantryId", "==", pantry.id).get(),
        db.collection("inviteCodes").where("pantryId", "==", pantry.id).get(),
      ]);
      const cleanup = db.bulkWriter();
      barcodes.docs.forEach((document) => cleanup.delete(document.ref));
      invitations.docs.forEach((document) => cleanup.delete(document.ref));
      await cleanup.close();
      const files = await getStorage().bucket().getFiles({ prefix: `pantries/${pantry.id}/` });
      await Promise.all(files[0].map((file) => file.delete({ ignoreNotFound: true })));
    }
    logger.info("Expired pantry data purge complete", { products: "redacted", pantryCount: pantries.size });
  },
);

export const purgeOldActivities = onSchedule(
  { region: "europe-west1", schedule: "every day 04:15", timeZone: "Europe/Zagreb", timeoutSeconds: 300 },
  async () => {
    const before = Timestamp.fromMillis(Date.now() - 365 * 86_400_000);
    const expired = await db.collectionGroup("activities").where("createdAt", "<", before).limit(450).get();
    const batch = db.batch();
    expired.docs.forEach((document) => batch.delete(document.ref));
    if (!expired.empty) await batch.commit();
    logger.info("Old activity purge complete", { deletedCount: expired.size });
  },
);

async function deactivateTokens(tokens: string[], snapshots: QuerySnapshot[]): Promise<void> {
  const tokenSet = new Set(tokens);
  const batch = db.batch();
  snapshots.flatMap((snapshot) => snapshot.docs).forEach((document) => {
    if (tokenSet.has(document.get("fcmToken"))) batch.update(document.ref, { active: false, invalidatedAt: FieldValue.serverTimestamp() });
  });
  await batch.commit();
}

function chunk<T>(values: T[], size: number): T[][] {
  const result: T[][] = [];
  for (let index = 0; index < values.length; index += size) result.push(values.slice(index, index + size));
  return result;
}
