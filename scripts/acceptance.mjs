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
import { aguiAcceptance } from "../web/test/agui-acceptance.mjs";
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
      [
        "exec",
        "-e",
        "MYSQL_PWD",
        id,
        "mysql",
        "--protocol=TCP",
        "-h127.0.0.1",
        "-uroot",
        "-Nse",
        sql,
      ],
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
  const { api, fetchApi, conversation, submit, snapshot, decide, waitStatus, events } = client(
    env.SH_API_URL,
    token(),
  );
  const firstApi = start("api");
  await healthy();
  const login = password => fetch(env.SH_API_URL + "/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password }),
  });
  assert.equal((await login("incorrect")).status, 401);
  const localLogin = await login("admin");
  assert.equal(localLogin.status, 200);
  const localUser = { Authorization: "Bearer " + (await localLogin.json()).accessToken };
  assert.equal((await api("/tenant", undefined, localUser)).role, "OWNER");
  await assert.rejects(fetchApi("/actuator/prometheus", undefined, localUser), { cause: 403 });
  // A second active membership checks resource scoping even when both tenants authorize the user.
  db.query("USE platform; INSERT INTO tenants(id) VALUES('release-second'); " +
    "INSERT INTO memberships(tenant_id,user_id,role) VALUES('release-second','developer','MEMBER')");

  start("reconciler");
  const worker = start("worker", 10000);
  const { agentId } = await api("/agents", { name: "chaos" });
  const echo = { prompt: "test", model: "local-echo" };
  const chaosThread = await conversation(agentId, echo);
  const chaosInput = {
    threadId: chaosThread,
    runId: "chaos-interaction",
    messages: [{ role: "user", id: "chaos-user", content: "chaos" }],
  };
  const openInteraction = () =>
    fetchApi("/api/v1/ag-ui/runs", chaosInput, { Accept: "text/event-stream" });
  const firstStream = await openInteraction();
  const runId = (await snapshot(chaosThread)).turns[0]
    .runId;
  await firstStream.body.cancel();
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
  const recover = { threadId: chaosThread, runId: "recover-attempt-1", messages: [],
    forwardedProps: { "harness.recover": { taskId: runId, attempt: 1 } } };
  const recoverRequest = () => fetchApi("/api/v1/ag-ui/runs", recover);
  assert.match(await (await recoverRequest()).text(), /"RUN_FINISHED"/);
  await waitStatus(runId, "SUCCEEDED");
  assert.match(await (await recoverRequest()).text(), /"RUN_FINISHED"/);
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
  const interactionReplay = (await (await openInteraction()).text())
    .split("\n")
    .filter((line) => line.startsWith("data: "))
    .map((line) => JSON.parse(line.slice(6)));
  assert.equal(
    interactionReplay.filter((e) => e.type === "RUN_STARTED").length,
    1,
  );
  assert.equal(
    interactionReplay.filter((e) => e.type === "RUN_FINISHED").length,
    1,
  );
  assert.deepEqual(
    interactionReplay
      .filter(
        (e) => e.name === "harness.run.status" && e.value.status === "started",
      )
      .map((e) => e.value.attempt),
    [1, 2],
  );
  assert.equal(
    interactionReplay
      .filter((e) => e.type === "TEXT_MESSAGE_CONTENT")
      .map((e) => e.delta)
      .join(""),
    "收到：chaos",
  );
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
  assert.ok((await snapshot(delivery.conversationId)).messages.at(-1).content.includes("Approved sample steps"));
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
  const document = "---\nname: fixture-skill\ndescription: Fixture reference lookup\n---\npinned instructions for this test";
  const {id: skillId} = await api("/skills", {
    version: "1", content: document, resources: {"references/guide.md": "pinned reference text"},
  });
  const {id: otherId} = await api("/skills", {
    name: "other", version: "1", description: "Another skill purpose", content: "unselected skill body",
  });
  const {id: nextId} = await api("/skills", {version: "2", content: document});
  await assert.rejects(conversation(agentId, {...provider, skills:[skillId, nextId]}), {cause:400});
  await assert.rejects(api("/skills", {version:"bad", content:document, resources:{"../bad":"text"}}), {cause:400});
  const memoryConversation = await conversation(agentId, {...provider, useMemory:true, skills:[skillId, otherId]});
  assert.ok((await api("/skills/" + skillId)).usedBy.length);
  for (const [prompt,expected] of [
    ["skill-load-fixture", "pinned instructions for this test"],
    ["skill-reference-fixture", "pinned reference text"],
    ["memory-save-fixture", "Saved 1 memory"],
    ["memory-read-fixture", "remember-fixture-value"],
  ]) {
    const before = mock.calls.length;
    await waitStatus(await submit(memoryConversation, prompt), "SUCCEEDED");
    if (prompt === "skill-load-fixture") {
      const first = JSON.stringify(mock.calls[before].messages);
      for (const purpose of ["Fixture reference lookup", "Another skill purpose"]) assert.ok(first.includes(purpose));
      for (const body of ["pinned instructions for this test", "unselected skill body", "pinned reference text"]) assert.ok(!first.includes(body));
    }
    const toolResult = mock.calls.at(-1).messages.at(-1);
    assert.equal(toolResult.role, "tool");
    assert.ok(JSON.stringify(toolResult.content).includes(expected), JSON.stringify(toolResult.content));
  }
  console.log("PASS frozen skill catalog, on-demand document/reference loading and cross-turn memory");
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
  const metrics = { Authorization: "Bearer " + token("--metrics") };
  assert.match(await (await fetchApi("/actuator/prometheus", undefined, metrics)).text(), /harness_runs/);
  await assert.rejects(fetchApi("/actuator/prometheus"), { cause: 403 });
  await assert.rejects(api("/tenant", undefined, metrics), { cause: 403 });
  console.log("PASS provider usage and cost, TLS MCP, traces and metrics");
  const planConversation = await conversation(agentId, provider);
  const planRun = await submit(planConversation, "plan-fixture");
  await waitStatus(planRun, "SUCCEEDED");
  const planEvent = (await events(planRun, 0)).find(e=>e.type==="plan.updated" && e.payload.tasks.length);
  assert.match(planEvent?.createdAt, /Z$/);
  assert.equal(planEvent?.payload.tasks[0].subject, "Inspect reference behavior");
  assert.equal(planEvent?.payload.tasks[0].state, "pending");
  const planHistory=await snapshot(planConversation);
  assert.deepEqual(planHistory.state.tasks,planEvent.payload.tasks);
  const updateRun = await submit(planConversation, "update-plan-fixture");
  await waitStatus(updateRun, "SUCCEEDED");
  const updated = (await events(updateRun,0)).find(e=>e.type==="plan.updated");
  assert.equal(updated?.payload.tasks[0].id,"1");
  assert.equal(updated?.payload.tasks[0].state,"completed");
  console.log("PASS SDK model-produced plan, cross-turn update, stream and durable history");
  await aguiAcceptance({
    base: env.SH_API_URL,
    token: token(),
    api,
    conversation,
    agentId,
  });
  if (options.browser) {
    const browserPlan = await conversation(agentId, provider);
    await run("npm", ["test"], {
      cwd: join(root, "web"),
      env: {
        ...env,
        SH_TEST_PUBLISH_PATH: "/chat/" + agentId + "/" + await conversation(agentId, {
          ...echo,
          outputMode: "APPROVED_ARTIFACT",
        }),
        SH_TEST_PLAN_PATH: "/chat/" + agentId + "/" + browserPlan,
      },
    });
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
    await decide(approval, true);
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
  const reviewThread = await conversation(agentId, { ...echo, outputMode: "APPROVED_ARTIFACT" });
  const reviewTask = await submit(reviewThread, "reviewer scope check");
  await waitStatus(reviewTask, "WAITING_APPROVAL");
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
  const secondTenant = mock.signed("super-harness", "release-second");
  assert.equal((await api("/tenant", undefined, secondTenant)).role, "MEMBER");
  await assert.rejects(api("/agents/" + agentId, undefined, secondTenant), { cause: 404 });
  const reviewer = "release-reviewer";
  const identity = subject => mock.signed("super-harness", "local", subject);
  async function setMember(subject, role, active = true) {
    const result = await fetch(env.SH_API_URL + "/api/v1/tenant/members", {
      method: "PUT",
      headers: { ...identity("developer"), "Content-Type": "application/json" },
      body: JSON.stringify({ subject, role, active }),
    });
    assert.ok(result.ok, "Membership update HTTP " + result.status);
  }

  // Even an existing owner's subject in a monitoring token has no product API access.
  const oidcMetrics = mock.signed("super-harness", "local", "developer", "metrics.read");
  await fetchApi("/actuator/prometheus", undefined, oidcMetrics);
  await assert.rejects(api("/agents", { name: "metrics write" }, oidcMetrics), { cause: 403 });
  await assert.rejects(fetchApi("/v3/api-docs", undefined, oidcMetrics), { cause: 403 });
  await assert.rejects(fetchApi("/actuator/prometheus", undefined, identity("developer")), { cause: 403 });

  await setMember("release-editor", "EDITOR");
  const editor = identity("release-editor");
  const edited = await api("/agents", { name: "release editor agent" }, editor);
  const { agentRevisionId } = await api("/agents/" + edited.agentId + "/revisions", echo, editor);
  await assert.rejects(api("/agents/" + agentId + "/revisions", echo, editor), { cause: 403 });
  await assert.rejects(api("/tenant/members", undefined, editor), { cause: 403 });

  const member = identity("release-member"), peer = identity("release-peer");
  for (const subject of ["release-member", "release-peer"]) await setMember(subject, "MEMBER");
  const thread = await api("/conversations", { agentId: edited.agentId, agentRevisionId }, member);
  const memberView = { threadId: thread.conversationId, runId: "role-read", messages: [],
    forwardedProps: { "harness.snapshot": {} } };
  await (await fetchApi("/api/v1/ag-ui/runs", memberView, member)).text();
  await assert.rejects(fetchApi("/api/v1/ag-ui/runs", memberView, peer), { cause: 404 });
  await assert.rejects(api("/agents", { name: "member write" }, member), { cause: 403 });
  await setMember("release-member", "MEMBER", false);
  await assert.rejects(api("/tenant", undefined, member), { cause: 403 });

  await setMember(reviewer, "APPROVER");
  const reviewHeaders = mock.signed("super-harness", "local", reviewer);
  await assert.rejects(api("/agents", { name: "reviewer write" }, reviewHeaders), { cause: 403 });
  const pending = (await api("/approvals", undefined, reviewHeaders)).find(a => a.runId === reviewTask);
  assert.equal(pending.threadId, reviewThread);
  const read = { threadId: reviewThread, runId: "reader", messages: [], forwardedProps: { "harness.snapshot": {} } };
  await assert.rejects(fetchApi("/api/v1/ag-ui/runs", read, reviewHeaders), { cause: 404 });
  const resume = { threadId: reviewThread, runId: "review", messages: [], resume: [{
    interruptId: pending.id, status: "resolved", payload: { approved: true, argsHash: pending.argsHash },
  }] };
  const receipt = async () => (await (await fetchApi("/api/v1/ag-ui/runs", resume, reviewHeaders)).text())
    .split("\n").filter(line => line.startsWith("data: ")).map(line => JSON.parse(line.slice(6)));
  const result = await receipt();
  assert.deepEqual(result.map(event => event.type), ["RUN_STARTED", "TOOL_CALL_RESULT", "RUN_FINISHED"]);
  assert.deepEqual(await receipt(), result);
  assert.equal(result[2].outcome.type, "success");
  console.log("PASS OIDC authentication, tenant isolation and receipt-only reviewer resume");
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
