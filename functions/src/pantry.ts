import { FieldValue } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { db, daysFromNow, now } from "./firebase";
import { authUid, invitationCode, object, optionalText, requireMember, requireOwner, safeId, sha256, text } from "./validation";

const callable = { region: "europe-west1", enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== "true" } as const;

const defaultCategories = [
  "Voće i povrće", "Mlijeko i jaja", "Meso i riba", "Žitarice i tjestenina",
  "Konzerve", "Začini", "Pića", "Grickalice", "Kućne potrepštine", "Ostalo",
];

export const createPantry = onCall(callable, async (request) => {
  const uid = authUid(request);
  const data = object(request.data);
  const name = text(data, "name", 1, 60);
  const deviceId = safeId(text(data, "deviceId", 1, 128), "deviceId");
  const deviceDisplayName = trustedDeviceName(await db.doc(`users/${uid}/devices/${deviceId}`).get());
  const pantryRef = db.collection("pantries").doc();
  const timestamp = now();
  const batch = db.batch();
  batch.create(pantryRef, {
    name, ownerUid: uid, memberUids: [uid], revision: 0,
    createdAt: timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
  });
  const displayName = request.auth?.token.name?.toString() || "Korisnik";
  const photoUrl = request.auth?.token.picture?.toString() || null;
  batch.create(pantryRef.collection("members").doc(uid), {
    uid, displayName, photoUrl, role: "OWNER", joinedAt: timestamp, invitedBy: uid, active: true,
  });
  batch.set(db.doc(`users/${uid}`), { displayName, photoUrl, createdAt: timestamp, lastSeenAt: timestamp }, { merge: true });
  for (let index = 0; index < 3; index++) {
    const shelf = pantryRef.collection("shelves").doc();
    batch.create(shelf, {
      name: `Polica ${index + 1}`, sortOrder: index, revision: 0,
      createdAt: timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
    });
  }
  defaultCategories.forEach((categoryName, index) => {
    const category = pantryRef.collection("categories").doc();
    batch.create(category, {
      name: categoryName, sortOrder: index, isDefault: categoryName === "Ostalo", revision: 0,
      createdAt: timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
    });
  });
  batch.create(pantryRef.collection("activities").doc(), {
    type: "PANTRY_CREATED", aggregateId: pantryRef.id, displayLabel: name,
    actorUid: uid, deviceId, deviceDisplayName,
    createdAt: timestamp, expiresAt: daysFromNow(365),
  });
  await batch.commit();
  return {
    pantry: { id: pantryRef.id, name, ownerUid: uid, revision: 0, createdAt: timestamp.toMillis(), updatedAt: timestamp.toMillis() },
    member: { uid, displayName, photoUrl, role: "OWNER", joinedAt: timestamp.toMillis() },
  };
});

export const listMyPantries = onCall(callable, async (request) => {
  const uid = authUid(request);
  const pantries = await db.collection("pantries")
    .where("memberUids", "array-contains", uid)
    .limit(10)
    .get();
  const available = await Promise.all(pantries.docs.map(async (pantry) => {
    if (pantry.get("deletedAt")) return null;
    const member = await pantry.ref.collection("members").doc(uid).get();
    if (!member.exists || member.get("active") !== true) return null;
    const createdAt = pantry.get("createdAt");
    const updatedAt = pantry.get("updatedAt");
    return {
      pantry: {
        id: pantry.id,
        name: pantry.get("name"),
        ownerUid: pantry.get("ownerUid"),
        revision: Number(pantry.get("revision") || 0),
        createdAt: createdAt?.toMillis?.() ?? Number(createdAt || Date.now()),
        updatedAt: updatedAt?.toMillis?.() ?? Number(updatedAt || Date.now()),
      },
      member: {
        uid,
        displayName: member.get("displayName") || "Korisnik",
        photoUrl: member.get("photoUrl") || null,
        role: member.get("role") || "MEMBER",
        joinedAt: member.get("joinedAt")?.toMillis?.() ?? Date.now(),
      },
    };
  }));
  return { pantries: available.filter((value) => value !== null) };
});

export const createInvitation = onCall(callable, async (request) => {
  const uid = authUid(request);
  const data = object(request.data);
  const pantryId = safeId(text(data, "pantryId"), "pantryId");
  await requireOwner(pantryId, uid);

  const existing = await db.collection("inviteCodes").where("pantryId", "==", pantryId).where("revokedAt", "==", null).limit(20).get();
  const code = invitationCode();
  const hash = sha256(code);
  const timestamp = now();
  const expiresAt = daysFromNow(1);
  const batch = db.batch();
  existing.docs.forEach((doc) => batch.update(doc.ref, { revokedAt: timestamp }));
  batch.create(db.doc(`inviteCodes/${hash}`), {
    pantryId, createdBy: uid, createdAt: timestamp, expiresAt, usesRemaining: 1, revokedAt: null,
  });
  await batch.commit();
  return { code, expiresAt: expiresAt.toMillis() };
});

export const joinPantry = onCall(callable, async (request) => {
  const uid = authUid(request);
  const data = object(request.data);
  const code = text(data, "code", 6, 32).toUpperCase();
  const deviceId = safeId(text(data, "deviceId", 1, 128), "deviceId");
  const inviteRef = db.doc(`inviteCodes/${sha256(code)}`);
  const timestamp = now();
  const displayName = request.auth?.token.name?.toString() || "Korisnik";
  const photoUrl = request.auth?.token.picture?.toString() || null;
  let pantryResult: Record<string, unknown> | undefined;

  await db.runTransaction(async (transaction) => {
    const invite = await transaction.get(inviteRef);
    if (!invite.exists) throw new HttpsError("not-found", "Pozivni kod ne postoji.");
    const inviteData = invite.data()!;
    if (inviteData.revokedAt || inviteData.expiresAt.toMillis() <= Date.now() || inviteData.usesRemaining < 1) {
      throw new HttpsError("failed-precondition", "Pozivni kod je iskorišten, poništen ili istekao.");
    }
    const pantryId = safeId(inviteData.pantryId, "pantryId");
    const pantryRef = db.doc(`pantries/${pantryId}`);
    const memberRef = pantryRef.collection("members").doc(uid);
    const deviceRef = db.doc(`users/${uid}/devices/${deviceId}`);
    const [pantry, existingMember, device] = await Promise.all([
      transaction.get(pantryRef), transaction.get(memberRef), transaction.get(deviceRef),
    ]);
    if (!pantry.exists || pantry.get("deletedAt")) throw new HttpsError("not-found", "Smočnica više nije dostupna.");
    const deviceDisplayName = trustedDeviceName(device);
    if (existingMember.exists && existingMember.get("active") === true) {
      throw new HttpsError("already-exists", "Već ste član ove smočnice.");
    }
    transaction.update(inviteRef, { usesRemaining: FieldValue.increment(-1), usedAt: timestamp, usedBy: uid });
    transaction.set(memberRef, {
      uid, displayName, photoUrl, role: "MEMBER", joinedAt: timestamp, invitedBy: inviteData.createdBy, active: true,
    });
    transaction.update(pantryRef, { memberUids: FieldValue.arrayUnion(uid), updatedAt: timestamp, revision: FieldValue.increment(1) });
    transaction.set(db.doc(`users/${uid}`), { displayName, photoUrl, createdAt: timestamp, lastSeenAt: timestamp }, { merge: true });
    transaction.create(pantryRef.collection("activities").doc(), {
      type: "MEMBER_JOINED", aggregateId: uid, displayLabel: "Novi uređaj pridružen",
      actorUid: uid, deviceId, deviceDisplayName, createdAt: timestamp, expiresAt: daysFromNow(365),
    });
    pantryResult = {
      id: pantry.id, name: pantry.get("name"), ownerUid: pantry.get("ownerUid"),
      revision: Number(pantry.get("revision") || 0) + 1,
      createdAt: pantry.get("createdAt").toMillis(), updatedAt: timestamp.toMillis(),
    };
  });
  return {
    pantry: pantryResult,
    member: { uid, displayName, photoUrl, role: "MEMBER", joinedAt: timestamp.toMillis() },
  };
});

export const registerDevice = onCall(callable, async (request) => {
  const uid = authUid(request);
  const data = object(request.data);
  const deviceId = safeId(text(data, "deviceId", 1, 128), "deviceId");
  const deviceDisplayName = text(data, "deviceDisplayName", 2, 40);
  const fcmToken = optionalText(data, "fcmToken", 4096);
  const update: Record<string, unknown> = {
    name: deviceDisplayName, platform: "ANDROID", updatedAt: now(), active: true,
  };
  if (fcmToken) update.fcmToken = fcmToken;
  await db.doc(`users/${uid}/devices/${deviceId}`).set(update, { merge: true });
  return { status: "OK" };
});

export const unregisterDevice = onCall(callable, async (request) => {
  const uid = authUid(request);
  const data = object(request.data);
  const deviceId = safeId(text(data, "deviceId", 1, 128), "deviceId");
  await db.doc(`users/${uid}/devices/${deviceId}`).set({
    active: false,
    fcmToken: FieldValue.delete(),
    invalidatedAt: now(),
  }, { merge: true });
  return { status: "OK" };
});

export const manageMember = onCall(callable, async (request) => {
  const uid = authUid(request);
  const data = object(request.data);
  const pantryId = safeId(text(data, "pantryId"), "pantryId");
  const memberUid = safeId(text(data, "memberUid"), "memberUid");
  const deviceId = safeId(text(data, "deviceId", 1, 128), "deviceId");
  if (data.action !== "REMOVE") throw new HttpsError("invalid-argument", "Nepodržana članska radnja.");
  await requireOwner(pantryId, uid);
  if (memberUid === uid) throw new HttpsError("failed-precondition", "Vlasnik prvo mora prenijeti vlasništvo.");
  const pantryRef = db.doc(`pantries/${pantryId}`);
  await db.runTransaction(async (transaction) => {
    const memberRef = pantryRef.collection("members").doc(memberUid);
    const deviceRef = db.doc(`users/${uid}/devices/${deviceId}`);
    const [member, device] = await Promise.all([transaction.get(memberRef), transaction.get(deviceRef)]);
    if (!member.exists || member.get("active") !== true) throw new HttpsError("not-found", "Član nije pronađen.");
    const deviceDisplayName = trustedDeviceName(device);
    const timestamp = now();
    transaction.update(memberRef, { active: false, removedAt: timestamp, removedBy: uid });
    transaction.update(pantryRef, { memberUids: FieldValue.arrayRemove(memberUid), revision: FieldValue.increment(1), updatedAt: timestamp });
    transaction.create(pantryRef.collection("activities").doc(), {
      type: "MEMBER_REMOVED", aggregateId: memberUid,
      displayLabel: String(member.get("displayName") || "Član").slice(0, 100),
      actorUid: uid, deviceId, deviceDisplayName, createdAt: timestamp, expiresAt: daysFromNow(365),
    });
  });
  return { status: "OK" };
});

export const transferOwnership = onCall(callable, async (request) => {
  const uid = authUid(request);
  const data = object(request.data);
  const pantryId = safeId(text(data, "pantryId"), "pantryId");
  const newOwnerUid = safeId(text(data, "newOwnerUid"), "newOwnerUid");
  const deviceId = safeId(text(data, "deviceId", 1, 128), "deviceId");
  await requireOwner(pantryId, uid);
  if (uid === newOwnerUid) throw new HttpsError("failed-precondition", "Već ste vlasnik.");
  const pantryRef = db.doc(`pantries/${pantryId}`);
  await db.runTransaction(async (transaction) => {
    const targetRef = pantryRef.collection("members").doc(newOwnerUid);
    const deviceRef = db.doc(`users/${uid}/devices/${deviceId}`);
    const [target, device] = await Promise.all([transaction.get(targetRef), transaction.get(deviceRef)]);
    if (!target.exists || target.get("active") !== true) throw new HttpsError("not-found", "Novi vlasnik mora biti aktivan član.");
    const deviceDisplayName = trustedDeviceName(device);
    const timestamp = now();
    transaction.update(targetRef, { role: "OWNER" });
    transaction.update(pantryRef.collection("members").doc(uid), { role: "MEMBER" });
    transaction.update(pantryRef, { ownerUid: newOwnerUid, revision: FieldValue.increment(1), updatedAt: timestamp });
    transaction.create(pantryRef.collection("activities").doc(), {
      type: "OWNERSHIP_TRANSFERRED", aggregateId: newOwnerUid,
      displayLabel: String(target.get("displayName") || "Novi vlasnik").slice(0, 100),
      actorUid: uid, deviceId, deviceDisplayName, createdAt: timestamp, expiresAt: daysFromNow(365),
    });
  });
  return { status: "OK" };
});

export const deletePantry = onCall(callable, async (request) => {
  const uid = authUid(request);
  const data = object(request.data);
  const pantryId = safeId(text(data, "pantryId"), "pantryId");
  const deviceId = safeId(text(data, "deviceId", 1, 128), "deviceId");
  await requireOwner(pantryId, uid);
  const deviceDisplayName = trustedDeviceName(await db.doc(`users/${uid}/devices/${deviceId}`).get());
  const timestamp = now();
  const invitations = await db.collection("inviteCodes").where("pantryId", "==", pantryId).limit(100).get();
  const batch = db.batch();
  batch.update(db.doc(`pantries/${pantryId}`), {
    deletedAt: timestamp, purgeAfter: daysFromNow(30), updatedAt: timestamp, revision: FieldValue.increment(1),
  });
  batch.create(db.collection(`pantries/${pantryId}/activities`).doc(), {
    type: "ITEM_DELETED", aggregateId: pantryId, displayLabel: "Smočnica",
    actorUid: uid, deviceId, deviceDisplayName, createdAt: timestamp, expiresAt: daysFromNow(365),
  });
  invitations.docs.forEach((invite) => batch.set(invite.ref, { revokedAt: timestamp }, { merge: true }));
  await batch.commit();
  return { status: "OK", purgeAfter: daysFromNow(30).toMillis() };
});

function trustedDeviceName(device: { exists: boolean; get(field: string): unknown }): string {
  if (!device.exists || device.get("active") !== true) {
    throw new HttpsError("permission-denied", "Uređaj nije registriran ili više nije aktivan.");
  }
  const name = device.get("name");
  if (typeof name !== "string" || name.trim().length < 2 || name.trim().length > 40) {
    throw new HttpsError("failed-precondition", "Registrirani uređaj nema ispravan naziv.");
  }
  return name.trim();
}

export const purgeTrash = onCall(callable, async (request) => {
  const uid = authUid(request);
  const data = object(request.data);
  const pantryId = safeId(text(data, "pantryId"), "pantryId");
  const id = safeId(text(data, "id"), "id");
  const type = text(data, "type", 1, 30);
  await requireMember(pantryId, uid);
  const collection = type === "PRODUCT" ? "products" : type === "SHELF" ? "shelves" : type === "CATEGORY" ? "categories" : null;
  if (!collection) throw new HttpsError("invalid-argument", "Nepodržana vrsta zapisa u košu.");
  const ref = db.doc(`pantries/${pantryId}/${collection}/${id}`);
  await db.runTransaction(async (transaction) => {
    const document = await transaction.get(ref);
    if (document.exists) {
      if (!document.get("deletedAt")) throw new HttpsError("failed-precondition", "Zapis nije u košu.");
      transaction.delete(ref);
      if (type === "PRODUCT" && document.get("barcode")) {
        transaction.delete(db.doc(`barcodes/${sha256(`${pantryId}:${document.get("barcode")}`)}`));
      }
    }
  });
  if (type === "PRODUCT") {
    await getStorage().bucket().file(`pantries/${pantryId}/products/${id}/main.jpg`).delete({ ignoreNotFound: true });
  }
  const related = type === "PRODUCT"
    ? await Promise.all([
      db.collection(`pantries/${pantryId}/stocks`).where("productId", "==", id).get(),
      db.collection(`pantries/${pantryId}/shoppingItems`).where("productId", "==", id).get(),
    ])
    : type === "SHELF"
      ? [await db.collection(`pantries/${pantryId}/stocks`).where("shelfId", "==", id).get()]
      : [];
  if (related.length > 0) {
    const writer = db.bulkWriter();
    related.flatMap((query) => query.docs).forEach((document) => writer.delete(document.ref));
    await writer.close();
  }
  return { status: "OK" };
});
