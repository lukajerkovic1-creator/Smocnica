import { HttpsError } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { sendNotification, PushSubscription } from "web-push";
export const webPushVapid = defineSecret("WEB_PUSH_VAPID");
const allowedHosts = new Set(["web.push.apple.com", "fcm.googleapis.com", "updates.push.services.mozilla.com"]);
export function validateWebPush(value: unknown): PushSubscription | null {
  if (value === null) return null;
  const data = value as Partial<PushSubscription> | undefined;
  if (!data || typeof data.endpoint !== "string" || data.endpoint.length > 4096) throw new HttpsError("invalid-argument", "Neispravna pretplata na obavijesti.");
  let url: URL;
  try { url = new URL(data.endpoint); } catch { throw new HttpsError("invalid-argument", "Neispravna adresa obavijesti."); }
  if (url.protocol !== "https:" || !allowedHosts.has(url.hostname) || url.port || url.username || url.password || url.hash) throw new HttpsError("invalid-argument", "Nepodržana adresa obavijesti.");
  const key = data.keys?.p256dh; const secret = data.keys?.auth;
  if (typeof key !== "string" || !/^[\w-]{87}={0,1}$/.test(key) || Buffer.from(key, "base64url").length !== 65 ||
      typeof secret !== "string" || !/^[\w-]{22}={0,2}$/.test(secret) || Buffer.from(secret, "base64url").length !== 16) throw new HttpsError("invalid-argument", "Neispravni podaci pretplate.");
  return { endpoint: url.href, keys: { p256dh: key, auth: secret } };
}
export async function sendWebNotification(subscription: PushSubscription, content: { title: string; body: string }): Promise<void> {
  const keys = JSON.parse(webPushVapid.value()) as { publicKey: string; privateKey: string };
  await sendNotification(subscription, JSON.stringify(content), { TTL: 86400, timeout: 10000,
    vapidDetails: { subject: "https://lukajerkovic1-creator.github.io/Smocnica/", ...keys } });
}
