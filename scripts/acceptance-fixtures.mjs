// Loopback test doubles for external OIDC, model, OTLP and MCP protocols.
import { createServer as http } from "node:http";
import { createServer as https } from "node:https";
import { generateKeyPairSync, sign } from "node:crypto";
import { gunzipSync } from "node:zlib";
import { once } from "node:events";

export async function listen(server, cleanup) {
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  cleanup.push(
    () =>
      new Promise((resolve) => {
        server.close(resolve);
        server.closeAllConnections();
      }),
  );
  return server.address().port;
}
function handler(route) {
  return async (req, res) => {
    try {
      const chunks = [];
      for await (const part of req) chunks.push(part);
      await route(req, res, Buffer.concat(chunks));
    } catch {
      res.writeHead(400).end();
    }
  };
}
function json(res, data) {
  res
    .writeHead(200, { "Content-Type": "application/json" })
    .end(JSON.stringify(data));
}
export async function fixtures(env, tls, cleanup) {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", {
    modulusLength: 2048,
  });
  const jwk = {
    ...publicKey.export({ format: "jwk" }),
    alg: "RS256",
    use: "sig",
    kid: "fixture",
  };
  const calls = [],
    invoked = [],
    telemetry = [];
  const port = await listen(
    http(
      handler((req, res, body) => {
        if (req.method === "GET" && req.url === "/keys")
          return json(res, { keys: [jwk] });
        if (req.method === "GET" && req.url.includes("well-known"))
          return json(res, {
            issuer,
            jwks_uri: issuer + "/keys",
            authorization_endpoint: issuer + "/authorize",
            token_endpoint: issuer + "/token",
            subject_types_supported: ["public"],
            response_types_supported: ["code"],
            id_token_signing_alg_values_supported: ["RS256"],
          });
        if (req.url === "/v1/traces") {
          telemetry.push(
            req.headers["content-encoding"] === "gzip"
              ? gunzipSync(body)
              : body,
          );
          return res
            .writeHead(200, { "Content-Type": "application/x-protobuf" })
            .end();
        }
        if (
          req.url !== "/v1/chat/completions" ||
          req.headers.authorization !== "Bearer fixture"
        )
          return res.writeHead(400).end();
        const request = JSON.parse(body);
        calls.push(request);
        const lookup =
          request.messages.some((m) =>
            JSON.stringify(m.content)?.includes("lookup-fixture"),
          ) && !request.messages.some((m) => m.role === "tool");
        const lastUser = request.messages.findLastIndex(m=>m.role==='user' && typeof m.content === 'string' && m.content.endsWith('plan-fixture'));
        const pending = request.messages.slice(lastUser+1).every(m=>m.role!=='tool');
        const prompt = JSON.stringify(request.messages[lastUser]?.content);
        const plan = pending && (prompt?.includes("plan-fixture") || prompt?.includes("update-plan-fixture"));
        const planCall = prompt?.includes("update-plan-fixture")
          ? {name:"TaskUpdate",arguments:JSON.stringify({task_id:"1",status:"completed"})}
          : {name:"TaskCreate",arguments:JSON.stringify({subject:"Inspect reference behavior",description:"Compare with the downloaded source"})};
        const name =
          lookup &&
          request.tools
            .map((t) => t.function.name)
            .find((n) => n === "lookup" || n.endsWith("_lookup"));
        const lastMessage = request.messages.at(-1);
        const current = lastMessage.role === "user" ? JSON.stringify(lastMessage.content) : "";
        const skillId = JSON.stringify(request.messages).match(/<skill-id>(skill-[^<]+)<\/skill-id>/)?.[1];
        const requests = [
          ["delegate-fixture", "delegate_analysis", {task:"Analyze the supplied example."}],
          ["read-draft-fixture", "read_plan", {}],
          ["blocked-task-fixture", "TaskCreate", {subject:"must be blocked", description:"check plan write boundary"}],
          ["plan-file-fixture", "plan_write", {content:"Approved sample steps"}],
          ["memory-save-fixture", "memory_save", {content:"- remember-fixture-value"}],
          ["memory-read-fixture", "memory_get", {path:"MEMORY.md", startLine:1, endLine:10}],
          ["skill-load-fixture", "load_skill_through_path", {skillId, path:"SKILL.md"}],
        ];
        const requested = requests.find(([marker]) => current.includes(marker));
        const fixtureTool = requested && {name:requested[1], arguments:JSON.stringify(requested[2])};
        const delta = (lookup || plan || fixtureTool)
          ? {
              role: "assistant",
              tool_calls: [
                {
                  index: 0,
                  id: "fixture-call-" + calls.length,
                  type: "function",
                  function: fixtureTool ?? (plan ? planCall : { name, arguments: "{}" }),
                },
              ],
            }
          : { role: "assistant", content: "provider fixture" };
        const chunks = [
          { choices: [{ index: 0, delta, finish_reason: null }] },
          {
            choices: [
              {
                index: 0,
                delta: {},
                finish_reason: lookup || plan || fixtureTool ? "tool_calls" : "stop",
              },
            ],
          },
          {
            choices: [],
            usage: {
              prompt_tokens: lookup ? 15 : 12,
              completion_tokens: lookup ? 3 : 2,
              total_tokens: lookup ? 18 : 14,
            },
          },
        ];
        res.writeHead(200, { "Content-Type": "text/event-stream" });
        for (const chunk of chunks)
          res.write(
            "data: " +
              JSON.stringify({
                id: "fixture",
                object: "chat.completion.chunk",
                created: 1,
                model: "fixture",
                ...chunk,
              }) +
              "\n\n",
          );
        res.end("data: [DONE]\n\n");
      }),
    ),
    cleanup,
  );
  const issuer = "http://127.0.0.1:" + port;
  const mcp = await listen(
    https(
      tls,
      handler((req, res, body) => {
        if (req.method === "GET") return res.writeHead(405).end();
        if (req.headers.authorization !== "Bearer fixture-mcp")
          return res.writeHead(401).end();
        if (req.method === "DELETE") return res.writeHead(200).end();
        const rpc = JSON.parse(body);
        if (rpc.id === undefined) return res.writeHead(202).end();
        let result = {};
        if (rpc.method === "initialize")
          result = {
            protocolVersion: rpc.params.protocolVersion,
            capabilities: { tools: {} },
            serverInfo: { name: "fixture", version: "1" },
          };
        if (rpc.method === "tools/list")
          result = {
            tools: ["lookup", "unlisted"].map((name) => ({
              name,
              description: "test",
              inputSchema: { type: "object", properties: {} },
            })),
          };
        if (rpc.method === "tools/call") {
          invoked.push(rpc.params.name);
          result = {
            content: [{ type: "text", text: "fixture lookup completed" }],
            isError: false,
          };
        }
        json(res, { jsonrpc: "2.0", id: rpc.id, result });
      }),
    ),
    cleanup,
  );
  Object.assign(env, {
    SH_OPENAI_BASE_URL: issuer + "/v1",
    SH_ALLOWED_MODELS: "local-echo,openai:fixture,token-plan:qwen4-max",
    SH_TOKEN_PLAN_BASE_URL: issuer + "/v1",
    SH_TOKEN_PLAN_API_KEY: "fixture",
    OPENAI_API_KEY: "fixture",
    SH_MODEL_PRICING: JSON.stringify({
      "openai:fixture": {
        currency: "USD",
        inputPerMillion: 1,
        outputPerMillion: 2,
      },
    }),
    SH_TRACING_ENABLED: "true",
    SH_OTLP_TRACES_ENDPOINT: issuer + "/v1/traces",
    MANAGEMENT_TRACING_SAMPLING_PROBABILITY: "1",
    SH_MCP_CATALOG: JSON.stringify([
      {
        id: "lookup",
        tenant: "local",
        version: "1",
        url: "https://127.0.0.1:" + mcp + "/mcp",
        readOnlyTools: ["lookup"],
        bearerEnv: "SH_MCP_SECRET_LOOKUP",
      },
    ]),
    SH_MCP_SECRET_LOOKUP: "fixture-mcp",
  });
  function signed(aud, tenant) {
    const now = Math.floor(Date.now() / 1000);
    const encode = (data) =>
      Buffer.from(JSON.stringify(data)).toString("base64url");
    const body =
      encode({ alg: "RS256", kid: "fixture" }) +
      "." +
      encode({
        iss: issuer,
        sub: "developer",
        tenant_id: tenant,
        aud: [aud],
        iat: now,
        exp: now + 120,
      });
    return {
      Authorization:
        "Bearer " +
        body +
        "." +
        sign("sha256", Buffer.from(body), privateKey).toString("base64url"),
    };
  }
  return { calls, invoked, telemetry, issuer, signed };
}
