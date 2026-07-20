import { DocumentSnapshot, FieldValue } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { db, daysFromNow, now } from "./firebase";
import { PANTRY_LIMITS, assertResourceLimit } from "./limits";
import { authUid, boolean, invitationCode, object, optionalText, requireMember, requireOwner, safeId, sha256, text } from "./validation";

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
  const requestId = safeId(text(data, "requestId", 8, 128), "requestId");
  const deviceDisplayName = trustedDeviceName(await db.doc(`users/${uid}/devices/${deviceId}`).get());
  const pantryRef = db.collection("pantries").doc();
  const accessRef = db.doc(`userPantryAccess/${uid}`);
  const timestamp = now();
  const displayName = request.auth?.token.name?.toString() || "Korisnik";
  const photoUrl = request.auth?.token.picture?.toString() || null;
  return db.runTransaction(async (transaction) => {
    const access = await transaction.get(accessRef);
    if (access.exists) {
      const activePantryId = safeId(String(access.get("pantryId")), "pantryId");
      const existingPantryRef = db.doc(`pantries/${activePantryId}`);
      const [existingPantry, existingMember] = await Promise.all([
        transaction.get(existingPantryRef),
        transaction.get(existingPantryRef.collection("members").doc(uid)),
      ]);
      if (existingPantry.exists && !existingPantry.get("deletedAt") && existingMember.exists && existingMember.get("active") === true) {
        if (access.get("createRequestId") === requestId) return pantryResponse(existingPantry, existingMember);
        throw new HttpsError("already-exists", "Korisnik već ima aktivnu smočnicu.");
      }
    } else {
      const legacy = await transaction.get(db.collection("pantries").where("memberUids", "array-contains", uid).limit(10));
      const activeLegacy = legacy.docs.filter((document) => !document.get("deletedAt"));
      if (activeLegacy.length > 0) throw new HttpsError("already-exists", "Korisnik već ima aktivnu smočnicu.");
    }

    transaction.create(pantryRef, {
      name, ownerUid: uid, memberUids: [uid], revision: 0,
      createdAt: timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
    });
    transaction.create(pantryRef.collection("members").doc(uid), {
      uid, displayName, photoUrl, role: "OWNER", joinedAt: timestamp, invitedBy: uid, active: true,
    });
    transaction.set(accessRef, { uid, pantryId: pantryRef.id, createRequestId: requestId, active: true, updatedAt: timestamp });
    transaction.set(db.doc(`users/${uid}`), { displayName, photoUrl, createdAt: timestamp, lastSeenAt: timestamp }, { merge: true });
    for (let index = 0; index < 3; index++) {
      const shelf = pantryRef.collection("shelves").doc();
      transaction.create(shelf, {
        name: `Polica ${index + 1}`, sortOrder: index, revision: 0,
        createdAt: timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
      });
    }
    defaultCategories.forEach((categoryName, index) => {
      const category = pantryRef.collection("categories").doc();
      transaction.create(category, {
        name: categoryName, sortOrder: index, isDefault: categoryName === "Ostalo", revision: 0,
        createdAt: timestamp, updatedAt: timestamp, deletedAt: null, purgeAfter: null,
      });
    });
    transaction.create(pantryRef.collection("activities").doc(), {
      type: "PANTRY_CREATED", aggregateId: pantryRef.id, displayLabel: name,
      actorUid: uid, deviceId, deviceDisplayName,
      createdAt: timestamp, expiresAt: daysFromNow(365),
    });
    return {
      pantry: { id: pantryRef.id, name, ownerUid: uid, revision: 0, createdAt: timestamp.toMillis(), updatedAt: timestamp.toMillis() },
      member: { uid, displayName, photoUrl, role: "OWNER", joinedAt: timestamp.toMillis() },
    };
  });
});

export const listMyPantries = onCall(callable, async (request) => {
  const uid = authUid(request);
  const accessRef = db.doc(`userPantryAccess/${uid}`);
  const access = await accessRef.get();
  if (access.exists) {
    const pantryId = safeId(String(access.get("pantryId")), "pantryId");
    const pantry = await db.doc(`pantries/${pantryId}`).get();
    const member = await pantry.ref.collection("members").doc(uid).get();
    if (pantry.exists && !pantry.get("deletedAt") && member.exists && member.get("active") === true) {
      return { pantries: [pantryResponse(pantry, member)] };
    }
    await accessRef.delete();
  }
  const pantries = await db.collection("pantries")
    .where("memberUids", "array-contains", uid)
    .limit(10)
    .get();
  const available = await Promise.all(pantries.docs.map(async (pantry) => {
    if (pantry.get("deletedAt")) return null;
    const member = await pantry.ref.collection("members").doc(uid).get();
    if (!member.exists || member.get("active") !== true) return null;
    return pantryResponse(pantry, member);
  }));
  const active = available.filter((value) => value !== null);
  if (active.length > 1) {
    throw new HttpsError("failed-precondition", "Korisnik ima više aktivnih smočnica. Potrebna je administratorska provjera.");
  }
  if (active.length === 1) {
    await accessRef.set({ uid, pantryId: active[0]!.pantry.id, createRequestId: null, active: true, updatedAt: now() });
  }
  return { pantries: active };
});

export const createInvitation = onCall(callable, async (request) => {
  const uid = authUid(request);
  const data = object(request.data);
  const pantryId = safeId(text(data, "pantryId"), "pantryId");
  await requireOwner(pantryId, uid);

  const code = invitationCode();
  const hash = sha256(code);
  const timestamp = now();
  const expiresAt = daysFromNow(1);
  await db.runTransaction(async (transaction) => {
    const pantryRef = db.doc(`pantries/${pantryId}`);
    const pantry = await transaction.get(pantryRef);
    const existing = await transaction.get(
      db.collection("inviteCodes").where("pantryId", "==", pantryId).where("revokedAt", "==", null).limit(PANTRY_LIMITS.activeInvitations + 1),
    );
    if (!pantry.exists || pantry.get("deletedAt")) throw new HttpsError("not-found", "Smočnica nije dostupna.");
    existing.docs.forEach((document) => transaction.update(document.ref, { revokedAt: timestamp }));
    transaction.create(db.doc(`inviteCodes/${hash}`), {
      pantryId, createdBy: uid, createdAt: timestamp, expiresAt, usesRemaining: 1, revokedAt: null,
    });
    transaction.update(pantryRef, { activeInvitationHash: hash, updatedAt: timestamp });
  });
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
    const accessRef = db.doc(`userPantryAccess/${uid}`);
    const [pantry, existingMember, device, access, activeMembers, legacyPantries] = await Promise.all([
      transaction.get(pantryRef), transaction.get(memberRef), transaction.get(deviceRef), transaction.get(accessRef),
      transaction.get(pantryRef.collection("members").where("active", "==", true).limit(PANTRY_LIMITS.members + 1)),
      transaction.get(db.collection("pantries").where("memberUids", "array-contains", uid).limit(10)),
    ]);
    if (!pantry.exists || pantry.get("deletedAt")) throw new HttpsError("not-found", "Smočnica više nije dostupna.");
    const deviceDisplayName = trustedDeviceName(device);
    if (access.exists && access.get("pantryId") !== pantryId) {
      throw new HttpsError("already-exists", "Korisnik već ima aktivnu smočnicu.");
    }
    if (!access.exists && legacyPantries.docs.some((document) => document.id !== pantryId && !document.get("deletedAt"))) {
      throw new HttpsError("already-exists", "Korisnik već ima aktivnu smočnicu.");
    }
    if (existingMember.exists && existingMember.get("active") === true) {
      transaction.set(accessRef, { uid, pantryId, createRequestId: null, active: true, updatedAt: timestamp });
      pantryResult = pantryResponse(pantry, existingMember).pantry;
      return;
    }
    assertResourceLimit(activeMembers.size, PANTRY_LIMITS.members, "članova");
    transaction.update(inviteRef, { usesRemaining: FieldValue.increment(-1), usedAt: timestamp, usedBy: uid });
    transaction.set(memberRef, {
      uid, displayName, photoUrl, role: "MEMBER", joinedAt: timestamp, invitedBy: inviteData.createdBy, active: true,
    });
    transaction.update(pantryRef, { memberUids: FieldValue.arrayUnion(uid), updatedAt: timestamp, revision: FieldValue.increment(1) });
    transaction.set(accessRef, { uid, pantryId, createRequestId: null, active: true, updatedAt: timestamp });
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
  const detailedNotifications = boolean(data, "detailedNotifications", false);
  const update: Record<string, unknown> = {
    name: deviceDisplayName, platform: "ANDROID", detailedNotifications, updatedAt: now(), active: true,
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
    const accessRef = db.doc(`userPantryAccess/${memberUid}`);
    const [member, device, access] = await Promise.all([
      transaction.get(memberRef), transaction.get(deviceRef), transaction.get(accessRef),
    ]);
    if (!member.exists || member.get("active") !== true) throw new HttpsError("not-found", "Član nije pronađen.");
    const deviceDisplayName = trustedDeviceName(device);
    const timestamp = now();
    transaction.update(memberRef, { active: false, removedAt: timestamp, removedBy: uid });
    transaction.update(pantryRef, { memberUids: FieldValue.arrayRemove(memberUid), revision: FieldValue.increment(1), updatedAt: timestamp });
    if (access.exists && access.get("pantryId") === pantryId) transaction.delete(accessRef);
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
  const [invitations, members] = await Promise.all([
    db.collection("inviteCodes").where("pantryId", "==", pantryId).limit(100).get(),
    db.collection(`pantries/${pantryId}/members`).where("active", "==", true).limit(PANTRY_LIMITS.members + 1).get(),
  ]);
  const accessRefs = members.docs.map((member) => db.doc(`userPantryAccess/${member.id}`));
  const accessDocuments = accessRefs.length > 0 ? await db.getAll(...accessRefs) : [];
  const batch = db.batch();
  batch.update(db.doc(`pantries/${pantryId}`), {
    deletedAt: timestamp, purgeAfter: daysFromNow(30), updatedAt: timestamp, revision: FieldValue.increment(1),
  });
  batch.create(db.collection(`pantries/${pantryId}/activities`).doc(), {
    type: "ITEM_DELETED", aggregateId: pantryId, displayLabel: "Smočnica",
    actorUid: uid, deviceId, deviceDisplayName, createdAt: timestamp, expiresAt: daysFromNow(365),
  });
  invitations.docs.forEach((invite) => batch.set(invite.ref, { revokedAt: timestamp }, { merge: true }));
  accessDocuments.forEach((access) => {
    if (access.exists && access.get("pantryId") === pantryId) batch.delete(access.ref);
  });
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

function pantryResponse(pantry: DocumentSnapshot, member: DocumentSnapshot) {
  const createdAt = pantry.get("createdAt");
  const updatedAt = pantry.get("updatedAt");
  const joinedAt = member.get("joinedAt");
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
      uid: member.id,
      displayName: member.get("displayName") || "Korisnik",
      photoUrl: member.get("photoUrl") || null,
      role: member.get("role") || "MEMBER",
      joinedAt: joinedAt?.toMillis?.() ?? Number(joinedAt || Date.now()),
    },
  };
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
