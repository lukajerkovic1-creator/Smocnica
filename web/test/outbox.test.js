import "fake-indexeddb/auto";
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { Outbox, database } from "../src/outbox.js";
beforeEach(async () => {
  const db = await database();
  await db.clear("outbox");
});
const request = (id) => ({
  operationId: id,
  deviceName: "iPhone",
  deviceId: "d",
  baseRevision: 0,
  payload: { type: "adjust_stock", delta: 1 },
});
test("operation is durable before sending and removed only on confirmation", async () => {
  let sent;
  const q = new Outbox(
    async (name, r) => {
      const db = await database();
      sent = await db.get("outbox", r.operationId);
      return { status: "APPLIED" };
    },
    () => ({ uid: "a" }),
  );
  await q.enqueue("applyOperation", request("one"));
  assert.equal(sent.operationId, "one");
  assert.deepEqual(await q.pending(), []);
});
test("network retry reuses operation id and payload", async () => {
  let first = true;
  const sent = [];
  const q = new Outbox(
    async (name, r) => {
      sent.push(r);
      if (first) {
        first = false;
        throw new Error("network");
      }
      return { status: "ALREADY_APPLIED" };
    },
    () => ({ uid: "a" }),
  );
  await assert.rejects(q.enqueue("applyOperation", request("retry")));
  await q.flush();
  assert.deepEqual(sent[0], sent[1]);
  assert.deepEqual(await q.pending(), []);
});
test("conflict blocks dependent operations and another account cannot replay them", async () => {
  let uid = "a",
    calls = 0;
  const q = new Outbox(
    async () => {
      calls++;
      return { status: "CONFLICT" };
    },
    () => ({ uid }),
  );
  await assert.rejects(q.enqueue("applyOperation", request("conflict")));
  await assert.rejects(q.enqueue("applyOperation", request("later")));
  assert.equal(calls, 1);
  uid = "b";
  await q.flush();
  assert.equal(calls, 1);
  await assert.rejects(q.discard("conflict"));
});
test("uncertain operations cannot be discarded and silently lost", async () => {
  const q = new Outbox(
    async () => {
      throw new Error("timeout");
    },
    () => ({ uid: "a" }),
  );
  await assert.rejects(q.enqueue("applyOperation", request("uncertain")));
  await assert.rejects(q.discard("uncertain"));
  assert.equal((await q.pending()).length, 1);
});
