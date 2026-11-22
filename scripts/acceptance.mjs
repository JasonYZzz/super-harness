#!/usr/bin/env node
// Exercise packaged Java services on private loopback ports and disposable databases.
import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { once } from "node:events";
import {
  mkdtempSync,
  readFileSync,
  writeFileSync,
  copyFileSync,
  cpSync,
  rmSync,
  openSync,
  closeSync,
} from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { client, until } from "./test-client.mjs";
import { fixtures } from "./acceptance-fixtures.mjs";

const { values: options } = parseArgs({
  options: {
    browser: { type: "boolean" },
    load: { type: "boolean" },
    restore: { type: "boolean" },
  },
});
const root = fileURLToPath(new URL("../", import.meta.url));
const dir = mkdtempSync(join(tmpdir(), "sh-acceptance-"));
const env = Object.fromEntries(
  Object.entries(process.env).filter(
    ([k]) => !/^(MYSQL_|SH_|SPRING_|AGENTSCOPE_)/.test(k),
  ),
);
const children = [],
  cleanup = [];
function command(name, args, extra = {}) {
  return execFileSync(name, args, {
    env,
    cwd: root,
    encoding: "utf8",
    ...extra,
  })?.trim();
}
async function run(name, args, extra = {}) {
  const proc = spawn(name, args, {
    env,
    cwd: root,
    stdio: "inherit",
    ...extra,
  });
  children.push(proc);
  const [code] = await once(proc, "exit");
  assert.equal(code, 0, name + " exited with " + code);
}
async function stop(proc, signal = "SIGTERM") {
  if (proc.exitCode !== null || proc.signalCode !== null) return;
  const done = once(proc, "exit");
  proc.kill(signal);
  const timer = setTimeout(() => proc.kill("SIGKILL"), 6000);
  try {
    await done;
  } finally {
    clearTimeout(timer);
  }
}
async function stopAll() {
  for (const proc of children.toReversed()) await stop(proc);
}
async function database() {
  const id = command("docker", [
    "run",
    "--rm",
    "-d",
    "-p",
    "127.0.0.1::3306",
    "-e",
    "MYSQL_ROOT_PASSWORD",
    "-e",
    "MYSQL_ROOT_HOST",
    "mysql:8.4.13",
  ]);
  cleanup.push(() => command("docker", ["rm", "-f", id], { stdio: "ignore" }));
  const query = (sql) =>
    command(
      "docker",
      ["exec", "-e", "MYSQL_PWD", id, "mysql", "-uroot", "-Nse", sql],
      { stdio: "ignore" },
    );
  await until(() => {
    try {
      query("SELECT 1");
      return true;
    } catch {
      return false;
    }
  });
  env.MYSQL_PORT = command("docker", ["port", id, "3306/tcp"])
    .split(":")
    .at(-1);
  return { id, query };
}
const path = (name) => join(dir, name);
function start(role, delay = 0) {
  const log = openSync(path(role + ".log"), "a");
  try {
    const proc = spawn(
      "java",
      [
        "-Dsh.local-model-delay-ms=" + delay,
        "-Djavax.net.ssl.trustStore=" + path("trust.p12"),
        "-Djavax.net.ssl.trustStorePassword=changeit",
        "-jar",
        path(role + ".jar"),
      ],
      {
        cwd: dir,
        env: {
          ...env,
          ...(role === "api" && env.SPRING_PROFILES_ACTIVE === "local"
            ? { SH_DEV_KEY: readFileSync(env.SH_DEV_KEY_FILE, "utf8") }
            : {}),
        },
        stdio: ["ignore", log, log],
      },
    );
    children.push(proc);
    return proc;
  } finally {
    closeSync(log);
  }
}
async function healthy() {
  await until(async () => {
    try {
      return (
        (
          await (
            await fetch(env.SH_API_URL + "/actuator/health", {
              signal: AbortSignal.timeout(2000),
            })
          ).json()
        ).status === "UP"
      );
    } catch {
      return false;
    }
  });
}
async function runSuite() {
  const pwd = randomBytes(24).toString("hex");
  Object.assign(env, {
    MYSQL_ROOT_PASSWORD: pwd,
    MYSQL_ROOT_HOST: "%",
    MYSQL_PWD: pwd,
    MYSQL_USER: "root",
    MYSQL_PASSWORD: pwd,
    MYSQL_HOST: "127.0.0.1",
    MYSQL_DATABASE: "platform",
    AGENTSCOPE_MYSQL_DATABASE: "runtime",
    SPRING_PROFILES_ACTIVE: "local",
    SH_DEV_KEY_FILE: path("jwt.key"),
    SH_WORKSPACE_ROOT: path("workspace"),
    SH_ARTIFACT_PATH: path("objects"),
  });
  const db = await database();
  db.query("CREATE DATABASE platform; CREATE DATABASE runtime");
  const portProbe = createServer();
  portProbe.listen(0, "127.0.0.1");
  await once(portProbe, "listening");
  env.SERVER_PORT = String(portProbe.address().port);
  await new Promise((resolve) => portProbe.close(resolve));
  env.SH_API_URL = "http://127.0.0.1:" + env.SERVER_PORT;
  writeFileSync(env.SH_DEV_KEY_FILE, randomBytes(48).toString("base64"), {
    mode: 0o600,
  });
  command(
    "openssl",
    [
      "req",
      "-x509",
      "-newkey",
      "rsa:2048",
      "-nodes",
      "-days",
      "1",
      "-subj",
      "/CN=localhost",
      "-addext",
      "subjectAltName=DNS:localhost,IP:127.0.0.1",
      "-keyout",
      path("mcp.key"),
      "-out",
      path("mcp.pem"),
    ],
    { stdio: "ignore" },
  );
  command(
    "keytool",
    [
      "-importcert",
      "-noprompt",
      "-alias",
      "local-mcp",
      "-file",
      path("mcp.pem"),
      "-keystore",
      path("trust.p12"),
      "-storepass",
      "changeit",
    ],
    { stdio: "ignore" },
  );
  const mock = await fixtures(
    env,
    { key: readFileSync(path("mcp.key")), cert: readFileSync(path("mcp.pem")) },
    cleanup,
  );
  for (const role of ["api", "worker", "reconciler"])
    copyFileSync(
      join(
        root,
        "apps/sh-" + role + "/target/sh-" + role + "-0.1.0-SNAPSHOT.jar",
      ),
      path(role + ".jar"),
    );
  const token = (...args) =>
    command("java", ["-jar", path("api.jar"), "--dev-token", ...args]);
  const { api, fetchApi, conversation, submit, waitStatus, events } = client(
    env.SH_API_URL,
    token(),
  );
  const firstApi = start("api");
  await healthy();
  start("reconciler");
  const worker = start("worker", 10000);
  const { agentId } = await api("/agents", { name: "chaos" });
  const echo = { prompt: "test", model: "local-echo" };
  const runId = await submit(await conversation(agentId, echo), "chaos");
  const uri = "/runs/" + runId;
  await waitStatus(runId, "RUNNING");
  await stop(worker, "SIGKILL");
  const killed = Date.now(),
    before = (await api(uri)).lastSeq;
  await stop(firstApi, "SIGKILL");
  start("api");
  await healthy();
  await waitStatus(runId, "RECOVERY_REQUIRED", 75);
  const elapsed = (Date.now() - killed) / 1000;
  assert.ok(elapsed <= 75);
  start("worker");
  await api(uri + "/resume", {});
  await waitStatus(runId, "SUCCEEDED");
  const replay = await events(runId, before);
  assert.deepEqual(
    replay.map((e) => e.seq),
    Array.from(
      { length: (await api(uri)).lastSeq - before },
      (_, i) => before + i + 1,
    ),
  );
  assert.equal(replay.filter((e) => e.type === "run.completed").length, 1);
  assert.equal(replay.at(-1).attempt, 2);
  console.log(
    "PASS fenced recovery after Worker and API replacement in",
    elapsed,
    "s",
  );
  await run(process.execPath, ["scripts/smoke.mjs"]);
  const provider = { prompt: "test", model: "openai:fixture" };
  const modelConversation = await conversation(agentId, echo);
  const modelUrl = env.SH_API_URL + "/api/v1/conversations/" + modelConversation + "/model";
  const headers = {Authorization: "Bearer " + token(), "Content-Type":"application/json"};
  const selectModel = (model) => fetch(modelUrl, {method: "PUT", headers, body: JSON.stringify({model})});
  assert.equal((await selectModel("unlisted-model")).status, 400);
  assert.ok((await selectModel(provider.model)).ok);
  const external = await submit(modelConversation, "test");
  await waitStatus(external, "SUCCEEDED");
  const trace = await api("/runs/" + external + "/trace");
  assert.equal(mock.calls.length, 1);
  assert.equal(trace.modelCalls[0].model, provider.model);
  assert.equal(trace.modelCalls[0].inputTokens, 12);
  assert.equal(trace.modelCalls[0].outputTokens, 2);
  assert.equal(Number(trace.modelCalls[0].cost), 0.000016);
  assert.ok((await selectModel(echo.model)).ok);
  const switched = await submit(modelConversation, "local follow-up");
  await waitStatus(switched, "SUCCEEDED");
  const localTrace = await api("/runs/" + switched + "/trace");
  assert.equal(localTrace.modelCalls[0].model, echo.model);
  assert.equal((await api("/runs/" + external + "/trace")).modelCalls[0].model, provider.model);
  console.log("PASS two server-selected models in one conversation, billing and historical run snapshots");
  assert.equal(mock.calls[0].enable_search, undefined);
  const channelMessage = {agentId, source:"fixture", threadId:"weather", messageId:"1", text:"weather-fixture"};
  await api("/agents/" + agentId + "/revisions", {prompt:"test search", model:"token-plan:qwen4-max"});
  const [delivery, duplicate] = await Promise.all([1,2].map(() => api("/channels/http/messages", channelMessage)));
  assert.equal(delivery.runId, duplicate.runId);
  await waitStatus(delivery.runId, "SUCCEEDED");
  assert.equal(mock.calls.at(-1).enable_search, true);
  assert.ok((await events(delivery.runId)).some(e => e.type === "model.started" && e.payload.webSearchRequested));
  const permission = (mode) => fetch(env.SH_API_URL + "/api/v1/conversations/" + delivery.conversationId + "/permission",
      {method:"PUT",headers,body:JSON.stringify({mode})});
  assert.ok((await permission("plan")).ok);
  const draftRun = await submit(delivery.conversationId, "plan-file-fixture");
  await waitStatus(draftRun, "SUCCEEDED");
  assert.ok(mock.calls.at(-1).messages.some(m => m.role === "tool" && JSON.stringify(m.content).includes("Plan saved")));
  assert.ok((await api("/conversations/" + delivery.conversationId + "/messages")).at(-1).response.includes("Approved sample steps"));
  const blocked = await submit(delivery.conversationId, "blocked-task-fixture");
  await waitStatus(blocked, "SUCCEEDED");
  assert.match(JSON.stringify(mock.calls.at(-1).messages.at(-1).content), /plan mode|PLAN mode/i);
  assert.ok(!(await events(blocked)).some(e => e.type === "plan.updated"));
  assert.ok((await permission("default")).ok);
  const review = await submit(delivery.conversationId, "read-draft-fixture");
  await waitStatus(review, "SUCCEEDED");
  assert.ok(JSON.stringify(mock.calls.at(-1).messages.at(-1).content).includes("Approved sample steps"));
  const delegated = await submit(delivery.conversationId, "delegate-fixture");
  await waitStatus(delegated, "SUCCEEDED");
  assert.equal((await api("/runs/" + delegated + "/trace")).modelCalls.length, 3);
  console.log("PASS channel deduplication, provider web search request, plan writing and metered child analysis");
  const {id: skillId} = await api("/skills", {name:"fixture skill",version:"1",content:"pinned instructions for this test"});
  const memoryConversation = await conversation(agentId, {...provider, useMemory:true, skills:[skillId]});
  for (const [prompt,expected] of [
    ["memory-save-fixture", "Saved 1 memory"],
    ["memory-read-fixture", "remember-fixture-value"],
    ["skill-load-fixture", "pinned instructions for this test"],
  ]) {
    await waitStatus(await submit(memoryConversation, prompt), "SUCCEEDED");
    const toolResult = mock.calls.at(-1).messages.at(-1);
    assert.equal(toolResult.role, "tool");
    assert.ok(JSON.stringify(toolResult.content).includes(expected), JSON.stringify(toolResult.content));
  }
  console.log("PASS native skill load and cross-turn memory save/read");
  const toolRun = await submit(
    await conversation(agentId, {
      ...provider,
      prompt: "use lookup for lookup-fixture",
      mcpProfile: "lookup",
    }),
    "lookup-fixture",
  );
  await waitStatus(toolRun, "SUCCEEDED");
  const tool = await api("/runs/" + toolRun + "/trace");
  assert.deepEqual(mock.invoked, ["lookup"]);
  assert.equal(tool.events.filter((e) => e.type === "tool.started").length, 1);
  assert.equal(tool.modelCalls.length, 2);
  assert.ok(tool.timing.traceId);
  await until(() =>
    mock.telemetry.some((packet) => packet.includes("harness.attempt")),
  );
  assert.match(
    await (
      await fetchApi("/actuator/prometheus", undefined, {
        Authorization: "Bearer " + token("--metrics"),
      })
    ).text(),
    /harness_runs/,
  );
  console.log("PASS provider usage and cost, TLS MCP, traces and metrics");
  const planConversation = await conversation(agentId, provider);
  const planRun = await submit(planConversation, "plan-fixture");
  await waitStatus(planRun, "SUCCEEDED");
  const planEvent = (await events(planRun, 0)).find(e=>e.type==="plan.updated" && e.payload.tasks.length);
  assert.match(planEvent?.createdAt, /Z$/);
  assert.equal(planEvent?.payload.tasks[0].subject, "Inspect reference behavior");
  assert.equal(planEvent?.payload.tasks[0].state, "pending");
  const planHistory=await api("/conversations/"+planConversation+"/messages");
  assert.deepEqual(planHistory[0].toolEvents.find(e=>e.type==="plan.updated").payload,planEvent.payload);
  const updateRun = await submit(planConversation, "update-plan-fixture");
  await waitStatus(updateRun, "SUCCEEDED");
  const updated = (await events(updateRun,0)).find(e=>e.type==="plan.updated");
  assert.equal(updated?.payload.tasks[0].id,"1");
  assert.equal(updated?.payload.tasks[0].state,"completed");
  console.log("PASS SDK model-produced plan, cross-turn update, stream and durable history");
  if (options.browser) {
    const browserPlan = await conversation(agentId, provider);
    await run("npm", ["test"], { cwd: join(root, "web"), env: {...env, SH_TEST_PLAN_PATH: "/chat/"+agentId+"/"+browserPlan} });
  }
  if (options.load)
    await run(process.execPath, ["scripts/load.mjs"], {
      env: { ...env, SH_ACCEPTANCE_LOAD: "isolated" },
    });
  if (options.restore) {
    const conv = await conversation(agentId, {
      ...echo,
      outputMode: "APPROVED_ARTIFACT",
    });
    const publish = await submit(conv, "backup");
    await waitStatus(publish, "WAITING_APPROVAL");
    const approval = (await api("/approvals")).find((a) => a.runId === publish);
    await api("/approvals/" + approval.id + "/decision", {
      approve: true,
      argsHash: approval.argsHash,
    });
    const file = (await api("/runs/" + publish + "/artifacts"))[0].id;
    const bytes = async () =>
      Buffer.from(
        await (await fetchApi("/api/v1/artifacts/" + file)).arrayBuffer(),
      );
    const expected = await bytes();
    await stopAll();
    const out = openSync(path("dump.sql"), "w", 0o600);
    try {
      command(
        "docker",
        [
          "exec",
          "-e",
          "MYSQL_PWD",
          db.id,
          "mysqldump",
          "--user=root",
          "--single-transaction",
          "--hex-blob",
          "--set-gtid-purged=OFF",
          "--databases",
          "platform",
          "runtime",
        ],
        { stdio: ["ignore", out, "inherit"] },
      );
    } finally {
      closeSync(out);
    }
    const restored = await database(),
      input = openSync(path("dump.sql"), "r");
    try {
      command(
        "docker",
        ["exec", "-i", "-e", "MYSQL_PWD", restored.id, "mysql", "-uroot"],
        { stdio: [input, "ignore", "inherit"] },
      );
    } finally {
      closeSync(input);
    }
    cpSync(env.SH_ARTIFACT_PATH, path("restored-objects"), { recursive: true });
    env.SH_ARTIFACT_PATH = path("restored-objects");
    start("api");
    await healthy();
    start("worker");
    assert.deepEqual(await bytes(), expected);
    const recovered = await api(uri);
    assert.equal(recovered.status, "SUCCEEDED");
    assert.equal(recovered.attempt, 2);
    await waitStatus(await submit(conv, "after restore"), "WAITING_APPROVAL");
    console.log(
      "PASS independent database restore, artifact bytes and continued conversation",
    );
  }
  await stopAll();
  delete env.SPRING_PROFILES_ACTIVE;
  Object.assign(env, {
    SH_OIDC_ISSUER: mock.issuer,
    SH_OIDC_AUDIENCE: "super-harness",
    SERVER_ADDRESS: "127.0.0.1",
  });
  start("api");
  await healthy();
  const unauthLogin = await fetch(env.SH_API_URL + "/api/v1/auth/login", {method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({username:"admin",password:"admin"})});
  assert.equal(unauthLogin.status,401);
  const tenant = (aud, id) => api("/tenant", undefined, mock.signed(aud, id));
  assert.equal((await tenant("super-harness", "local")).role, "OWNER");
  await assert.rejects(tenant("wrong", "local"), { cause: 401 });
  await assert.rejects(tenant("super-harness", "another"), { cause: 403 });
  console.log("PASS OIDC discovery, signature, audience and tenant membership");
}
try {
  await runSuite();
} finally {
  try {
    await stopAll();
  } finally {
    for (const close of cleanup.reverse()) await close();
    rmSync(dir, { recursive: true, force: true });
  }
}
