/* Network-only: private pantry data and photos are never cached by the worker. */
self.addEventListener("push", (event) => {
  let content = {
    title: "Smočnica",
    body: "Jedan artikl je ispod minimalne zalihe.",
  };
  try {
    const data = event.data?.json();
    if (typeof data?.title === "string" && typeof data?.body === "string")
      content = data;
  } catch {}
  event.waitUntil(
    self.registration.showNotification(content.title, {
      body: content.body,
      icon: "./icon-192.png",
      badge: "./icon-192.png",
    }),
  );
});
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(
    (async () => {
      const url = new URL("./#shopping", self.registration.scope).href;
      const windows = await self.clients.matchAll({
        type: "window",
        includeUncontrolled: true,
      });
      const existing = windows.find((w) =>
        w.url.startsWith(self.registration.scope),
      );
      if (existing) {
        await existing.navigate(url);
        await existing.focus();
      } else await self.clients.openWindow(url);
    })(),
  );
});
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) =>
  event.waitUntil(self.clients.claim()),
);
