// Shared client for development and isolated release tests.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { setTimeout as sleep } from "node:timers/promises";

export async function until(check, seconds = 45) {
  const deadline = Date.now() + seconds * 1000;
  do {
    if (await check()) return;
    await sleep(250);
  } while (Date.now() < deadline);
  throw new Error(
    `Timed out after ${seconds}s waiting for ${check.name || "condition"}`,
  );
}

export function client(base, token) {
  async function fetchApi(path, body, headers = {}) {
    const result = await fetch(base + path, {
      method: body === undefined ? "GET" : "POST",
      headers: {
        Authorization: "Bearer " + token,
        "Content-Type": "application/json",
        ...headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(90000),
    });
    if (!result.ok)
      throw new Error(`${path}: HTTP ${result.status}`, {
        cause: result.status,
      });
    return result;
  }
  async function api(path, body, headers) {
    const text = await (await fetchApi("/api/v1" + path, body, headers)).text();
    return text ? JSON.parse(text) : undefined;
  }
  async function conversation(agentId, revision) {
    const { agentRevisionId } = await api(
      "/agents/" + agentId + "/revisions",
      revision,
    );
    return (await api("/conversations", { agentId, agentRevisionId }))
      .conversationId;
  }
  async function submit(conversationId, text, key = randomUUID()) {
    return (
      await api(
        "/runs",
        { conversationId, input: { text, type: "text" } },
        { "Idempotency-Key": key },
      )
    ).runId;
  }
  async function waitStatus(run, expected, seconds) {
    await until(async () => {
      const { status } = await api("/runs/" + run);
      if (status === expected) return true;
      assert.ok(
        !["FAILED", "CANCELLED", "SUCCEEDED"].includes(status),
        `${run}: unexpected ${status}`,
      );
      return false;
    }, seconds);
  }
  async function events(run, after = 0) {
    const response = await fetchApi(
      "/api/v1/runs/" + run + "/events",
      undefined,
      after ? { "Last-Event-ID": run + ":" + after } : {},
    );
    return (await response.text())
      .split("\n")
      .filter((line) => line.startsWith("data: "))
      .map((line) => JSON.parse(line.slice(6)));
  }
  return { api, fetchApi, conversation, submit, waitStatus, events };
}
