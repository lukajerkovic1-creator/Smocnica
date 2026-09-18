import { initializeApp } from "firebase/app";
import {
  initializeAppCheck,
  ReCaptchaEnterpriseProvider,
} from "firebase/app-check";
import {
  getAuth,
  GoogleAuthProvider,
  signInWithPopup,
  signOut,
  onAuthStateChanged,
} from "firebase/auth";
import {
  getFirestore,
  collection,
  doc,
  getDoc,
  getDocs,
  startAfter,
  onSnapshot,
  query,
  orderBy,
  limit,
} from "firebase/firestore";
import { getFunctions, httpsCallable } from "firebase/functions";
import { getStorage, ref, getBlob, uploadBytes } from "firebase/storage";
import { Outbox, database } from "./outbox";
const config = JSON.parse(import.meta.env.VITE_FIREBASE_CONFIG);
const app = initializeApp(config);
initializeAppCheck(app, {
  provider: new ReCaptchaEnterpriseProvider(
    import.meta.env.VITE_RECAPTCHA_SITE_KEY,
  ),
  isTokenAutoRefreshEnabled: true,
});
const auth = getAuth(app),
  db = getFirestore(app),
  functions = getFunctions(app, "europe-west1"),
  storage = getStorage(app);
const deviceId =
  localStorage.getItem("smocnica-device-id") || crypto.randomUUID();
localStorage.setItem("smocnica-device-id", deviceId);
let pantryId = null;
export const deviceName = () =>
  localStorage.getItem("smocnica-device-name") ||
  (/iPhone/i.test(navigator.userAgent) ? "iPhone" : "Web preglednik");
export const api = {
  app,
  uid: () => auth.currentUser?.uid,
  deviceId,
  deviceName,
  observeAuth: (fn) => onAuthStateChanged(auth, fn),
  login: () => signInWithPopup(auth, new GoogleAuthProvider()),
  raw: async (name, data = {}) =>
    (await httpsCallable(functions, name, { timeout: 600000 })(data)).data,
  record: async (collectionName, id) => {
    if (!pantryId) throw new Error("Smočnica nije dostupna.");
    const d = await getDoc(doc(db, "pantries", pantryId, collectionName, id));
    if (!d.exists()) throw new Error("Zapis nije dostupan.");
    return { ...d.data(), id: d.id };
  },
  history: async (afterId) => {
    if (!pantryId) throw new Error("Smočnica nije dostupna.");
    const base = collection(db, "pantries", pantryId, "activities");
    const cursor = afterId ? await getDoc(doc(base, afterId)) : null;
    const result = await getDocs(
      query(
        base,
        orderBy("createdAt", "desc"),
        ...(cursor?.exists() ? [startAfter(cursor)] : []),
        limit(500),
      ),
    );
    return result.docs.map((d) => ({ ...d.data(), id: d.id }));
  },
  allHistory: async () => {
    let all = [],
      last;
    do {
      const part = await api.history(last);
      all.push(...part);
      if (part.length < 500) break;
      last = part.at(-1).id;
    } while (last);
    return all;
  },
  call: async (name, data = {}) =>
    api.raw(name, {
      deviceId,
      deviceName: deviceName(),
      operationId: crypto.randomUUID(),
      baseRevision: 0,
      ...data,
    }),
  register: async (extra = {}) =>
    api.call("registerDevice", {
      deviceDisplayName: deviceName(),
      platform: "WEB",
      detailedNotifications:
        localStorage.getItem("smocnica-detailed") === "true",
      ...extra,
    }),
  initialize: async () => {
    const capabilities = await api.raw("getBackendCapabilities");
    if (capabilities.backendApiVersion < 9)
      throw new Error("Potrebna je nadogradnja poslužitelja.");
    await api.register();
    return api.call("listMyPantries");
  },
  subscribe: (id, change, fail) => {
    pantryId = id;
    let live = true;
    const stops = [];
    const failure = (e) => {
      if (!live) return;
      live = false;
      stops.forEach((stop) => stop());
      pantryId = null;
      fail(e);
    };
    stops.push(
      onSnapshot(
        doc(db, "pantries", id),
        (snap) => {
          if (live) {
            if (!snap.exists() || snap.data().deletedAt)
              failure(new Error("Smočnica više nije dostupna."));
            else change("pantry", { ...snap.data(), id: snap.id });
          }
        },
        failure,
      ),
    );
    for (const name of [
      "shelves",
      "categories",
      "products",
      "variants",
      "stocks",
      "shoppingItems",
      "members",
      "synonymRules",
      "activities",
    ]) {
      const target =
        name === "activities"
          ? query(
              collection(db, "pantries", id, name),
              orderBy("createdAt", "desc"),
              limit(500),
            )
          : collection(db, "pantries", id, name);
      stops.push(
        onSnapshot(
          target,
          (snap) => {
            if (live)
              change(
                name,
                snap.docs.map((d) => ({ ...d.data(), id: d.id })),
              );
          },
          failure,
        ),
      );
    }
    return () => {
      live = false;
      stops.forEach((stop) => stop());
      pantryId = null;
    };
  },
  mutate: async (
    type,
    id,
    payload,
    baseRevision = 0,
    aggregateType = "PRODUCT",
  ) => {
    if (!navigator.onLine)
      throw new Error("Za spremanje promjena potrebna je internetska veza.");
    if (!pantryId) throw new Error("Smočnica nije dostupna.");
    if ((await api.outbox.pending()).length)
      throw new Error("Prvo razriješite prethodnu nepotvrđenu promjenu.");
    const request = {
      pantryId,
      aggregateId: id,
      aggregateType,
      operationId: crypto.randomUUID(),
      deviceId,
      deviceName: deviceName(),
      baseRevision,
      payload: { ...payload, type },
    };
    return api.outbox.enqueue(
      type === "import_snapshot" ? "importSnapshotJob" : "applyOperation",
      request,
    );
  },
  logout: async () => {
    if ((await api.outbox.pending()).length)
      throw new Error("Prvo potvrdite ili razriješite spremljene promjene.");
    await api.call("unregisterDevice");
    await signOut(auth);
    const local = await database();
    await local.clear("drafts");
  },
  photo: async (uri) => {
    if (uri?.startsWith("gs://"))
      return URL.createObjectURL(await getBlob(ref(storage, uri)));
    const u = new URL(uri);
    if (u.protocol === "https:" && u.hostname === "images.openfoodfacts.org")
      return u.href;
    throw new Error("Fotografija nije dostupna.");
  },
  upload: async (variantId, blob) => {
    const path = `pantries/${pantryId}/variants/${variantId}/main.jpg`;
    await uploadBytes(ref(storage, path), blob, {
      contentType: "image/jpeg",
      customMetadata: { variantId },
    });
    return `gs://${config.storageBucket}/${path}`;
  },
  notifications: async (detailed) => {
    if (!("Notification" in window))
      throw new Error(
        "Dodajte Smočnicu na početni zaslon pa je otvorite putem njezine ikone.",
      );
    const permission = await Notification.requestPermission();
    if (permission !== "granted")
      throw new Error(
        "Obavijesti nisu dopuštene. Promijenite dopuštenje u postavkama iPhonea.",
      );
    if (!("PushManager" in window))
      throw new Error("Ovaj preglednik ne podržava obavijesti.");
    const registration = await navigator.serviceWorker.register(
      `${import.meta.env.BASE_URL}sw.js`,
    );
    await navigator.serviceWorker.ready;
    const key = import.meta.env.VITE_VAPID_KEY;
    if (!key) throw new Error("Obavijesti još nisu postavljene.");
    const bytes = Uint8Array.from(
      atob(key.replaceAll("-", "+").replaceAll("_", "/")),
      (c) => c.charCodeAt(0),
    );
    const subscription =
      (await registration.pushManager.getSubscription()) ||
      (await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: bytes,
      }));
    await api.register({
      webPush: subscription.toJSON(),
      detailedNotifications: detailed,
    });
    localStorage.setItem("smocnica-detailed", String(detailed));
  },
  disableNotifications: async () => {
    await api.register({ webPush: null });
    const registration = await navigator.serviceWorker?.getRegistration(
      import.meta.env.BASE_URL,
    );
    const subscription = await registration?.pushManager.getSubscription();
    if (subscription) await subscription.unsubscribe();
  },
  forget: async () => {
    await signOut(auth);
    const local = await database();
    await local.clear("drafts");
  },
};
api.outbox = new Outbox(
  api.raw,
  () => ({ uid: api.uid() }),
  () => window.dispatchEvent(new Event("smocnica-outbox")),
);
window.addEventListener("online", () => api.outbox.flush());
window.addEventListener("focus", () => api.outbox.flush());
