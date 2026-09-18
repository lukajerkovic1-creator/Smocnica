import { openDB } from "idb";
export const database = () =>
  openDB("smocnica-web-v1", 1, {
    upgrade(db) {
      db.createObjectStore("outbox", { keyPath: "operationId" });
      db.createObjectStore("drafts");
    },
  });
export class Outbox {
  constructor(send, identity, changed) {
    this.send = send;
    this.identity = identity;
    this.changed = changed;
    this.running = null;
  }
  async pending() {
    const db = await database();
    const user = this.identity().uid;
    return (await db.getAll("outbox"))
      .filter((x) => x.uid === user)
      .sort((a, b) => a.createdAt - b.createdAt);
  }
  async enqueue(call, request) {
    const uid = this.identity().uid;
    if (!uid) throw new Error("Prijava je obvezna.");
    const db = await database();
    const entry = {
      uid,
      call,
      request,
      operationId: request.operationId,
      createdAt: Date.now(),
      state: "PENDING",
    };
    await db.add("outbox", entry);
    this.changed?.();
    await this.flush();
    const remaining = await db.get("outbox", entry.operationId);
    if (remaining)
      throw new Error(
        remaining.error || "Promjena je sačuvana i čeka potvrdu poslužitelja.",
      );
  }
  async flush() {
    if (this.running) return this.running;
    this.running = this.drain().finally(() => {
      this.running = null;
      this.changed?.();
    });
    return this.running;
  }
  async drain() {
    for (const entry of await this.pending()) {
      if (
        entry.uid !== this.identity().uid ||
        entry.state === "CONFLICT" ||
        entry.state === "FAILED"
      )
        break;
      try {
        const result = await this.send(entry.call, entry.request);
        if (result.status === "CONFLICT") {
          const e = new Error(
            "Podaci su izmijenjeni na drugom uređaju. Pregledajte promjenu.",
          );
          e.code = "functions/aborted";
          throw e;
        }
        if (!["APPLIED", "ALREADY_APPLIED"].includes(result.status))
          throw new Error("Nedostaje potvrda poslužitelja.");
        const db = await database();
        await db.delete("outbox", entry.operationId);
      } catch (e) {
        const code = e.code?.replace("functions/", "");
        entry.state =
          code === "aborted"
            ? "CONFLICT"
            : [
                  "permission-denied",
                  "invalid-argument",
                  "not-found",
                  "failed-precondition",
                  "already-exists",
                  "resource-exhausted",
                ].includes(code)
              ? "FAILED"
              : "PENDING";
        entry.error = errorText(e);
        const db = await database();
        await db.put("outbox", entry);
        break;
      }
    }
  }
  async discard(id) {
    const db = await database();
    const entry = await db.get("outbox", id);
    if (entry?.uid !== this.identity().uid)
      throw new Error("Promjena nije dostupna.");
    if (entry.state === "PENDING")
      throw new Error(
        "Prvo ponovno provjerite je li poslužitelj primio promjenu.",
      );
    await db.delete("outbox", id);
    this.changed?.();
  }
}
export function errorText(e) {
  const code = e?.code || "";
  if (code === "storage/unauthorized")
    return "Sliku nije moguće spremiti. Provjerite pristup smočnici i pokušajte ponovno.";
  if (code.includes("popup-closed-by-user"))
    return "Prijava je zatvorena. Pokušajte ponovno.";
  if (code.includes("popup-blocked"))
    return "Safari je blokirao prozor prijave. Dopustite skočne prozore pa pokušajte ponovno.";
  if (code.includes("unauthorized-domain"))
    return "Ova adresa još nije dopuštena za prijavu.";
  if (code.includes("permission-denied"))
    return "Nemate pristup ovoj radnji ili smočnici.";
  if (code.includes("network") || code.includes("unavailable"))
    return "Veza s poslužiteljem nije dostupna. Pokušajte ponovno.";
  return e?.message || "Radnja nije uspjela. Pokušajte ponovno.";
}
