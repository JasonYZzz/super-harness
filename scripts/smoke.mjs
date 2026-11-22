#!/usr/bin/env node
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { client } from "./test-client.mjs";

const token = execFileSync(
  fileURLToPath(new URL("./dev-token.sh", import.meta.url)),
  { encoding: "utf8" },
).trim();
const { api, conversation, submit, waitStatus, events } = client(
  process.env.SH_API_URL || "http://127.0.0.1:8080",
  token,
);
const { agentId } = await api("/agents", { name: "本地验收" });
const id = await conversation(agentId, {
  prompt: "用中文回答。",
  model: "local-echo",
});
for (const text of ["第一轮", "第二轮"]) {
  const key = randomUUID(),
    run = await submit(id, text, key);
  assert.equal(await submit(id, text, key), run);
  await waitStatus(run, "SUCCEEDED", 30);
  const records = await events(run);
  const { lastSeq } = await api("/runs/" + run);
  assert.deepEqual(
    records.map((e) => e.seq),
    Array.from({ length: lastSeq }, (_, i) => i + 1),
  );
  assert.equal(records.at(-1).payload.text, "收到：" + text);
  assert.deepEqual(await events(run, 1), records.slice(1));
}
const messages = await api("/conversations/" + id + "/messages");
assert.equal(messages.length, 2);
assert.equal(messages.at(-1).response, "收到：第二轮");
console.log(
  "PASS two completed turns, idempotency, conversation history and SSE replay",
);
