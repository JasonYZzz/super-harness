# Super Harness 接入 AG-UI 实施计划

**定稿日期**　2026-11-22　　**代码基线**　`98a17bf`　　**状态**　三个里程碑已实现并通过本地隔离验收。见[交付记录](release-readiness.md#ag-ui-开发交付)。

本文保留首版设计与验收依据。现行版本统一为一个聊天页面和一个 AG-UI POST 入口。开发新客户端请以[当前接入协议](ag-ui.md)为准。

## 1. 决策与交付边界

在 `sh-api` 增加一个 AG-UI POST/SSE 入口。一个使用官方 `HttpAgent` 的独立客户端，可以在已有 Harness 会话上完成多轮文本对话、查看计划、断线恢复，并对 `publish_text` 作出审批决定。首个对外可用版本即支持标准审批中断。用一个最小客户端完成端到端验收，现有控制台承担管理、历史查询和独立审批人的工作。

```text
HttpAgent → sh-api → Runs.submit → 平台任务 → Worker → AgentScope
               ↑                                ↓
               └──────── 已提交的 run_events ←────┘

HttpAgent 的审批决定 → sh-api → Approvals.decide → 产物回执与任务终态
```

**必要边界。** HTTP 连接可以随时消失，平台任务继续执行。AgentScope 一次尝试的输出可能在 Worker 失租后成为旧草稿。批准前的模型结果也可能因拒绝发布而使任务失败。因此由 Run Engine 决定终态、由事件账本提供回放、由 Worker 持有执行租约和 SDK 状态槽。这三项直接决定用户会不会看到重复执行、串接的回答或错误的成功状态。[Worker](../apps/sh-worker/src/main/java/io/superharness/worker/WorkerLoop.java) · [运行生命周期](../modules/sh-run-engine/src/main/java/io/superharness/runengine/RunLifecycle.java) · [账本](../modules/sh-persistence/src/main/java/io/superharness/persistence/EventJournal.java)

## 2. 冗余审查与最终取舍

| 原计划中的设计 | 最终选型 | 取舍依据 |
|---|---|---|
| 协议绑定表，独立请求摘要，跨表提交事务 | 普通提交使用现有 `agent_runs.request_key`、`request_sha256` 和唯一索引；审批通过已有外键找到任务 | 平台已对同一租户串行准入，并能返回相同请求的原任务。频道适配已采用派生幂等键。两处都已有持久身份。[Runs](../modules/sh-run-engine/src/main/java/io/superharness/runengine/Runs.java) · [ChannelApi](../apps/sh-api/src/main/java/io/superharness/api/ChannelApi.java) · [表结构](../modules/sh-persistence/src/main/resources/db/migration/V2__run_ledger.sql) |
| 全量回放、精确续传、帧序号和投影版本并行建设 | AG-UI 只交付**当前协议 run 从头回放** | 原生接口的 seq 指向账本行，一行可以展开多个协议帧。独立客户端重建当前轮次就能恢复；要求逐帧续接会多出一套客户端状态契约。代价是断线重读当轮事件。 |
| 临时自定义审批事件，再迁移到标准中断，按 run 持久化开关 | 仅实现标准 interrupt/resume | 同一发布动作只维护一种 UI 生命周期和一套测试；开发里程碑之间无需公开临时协议。[中断契约](https://docs.ag-ui.com/concepts/interrupts) |
| Starter、官方 Java 适配器、自建协议对象三者留待试验 | 后端使用 Spring MVC、Jackson 和 API 包内的少量请求/事件类型。客户端固定 `@ag-ui/client@1.0.5` | Starter 会启动 Agent。项目 API 只需把持久事件编码为 JSON。使用实际客户端解码约束字段和事件顺序，免去 Java 侧执行依赖。[AgentScope 集成](https://java.agentscope.io/v2/zh/integration/protocol/agui) · [客户端版本](https://www.npmjs.com/package/@ag-ui/client/v/1.0.5) |
| 客户端裁剪历史、清空 state、自定义 transport | 使用官方 HttpAgent 默认 POST，服务端提取当前命令 | 核对 1.0.5 发布包，`prepareRunAgentInput` 会携带消息历史、state 和可选 resume。要求客户端删字段，会让后续每个接入端重复适配。[HttpAgent](https://docs.ag-ui.com/sdk/js/client/http-agent) |
| 分租户灰度、旧投影兼容、双模式回退、预建整套工具/子 Agent 轨迹 | 当前以一套协议、一个开发发布和既有日志验收；只实现已有数据能支撑的展示 | 开发期尚无已发布 AG-UI 流需要迁移。工具轨迹进入范围的前提是有具体界面需要参数、结果或来源；届时再扩展 Runtime SPI。 |

**改动预算。** 首版新增一个路由、一套从账本生成事件的转换逻辑、一个官方客户端验收入口。复用现有任务、审批、账本及数据库索引，计划数据库 DDL 为零。接口层可为授权读取、审批关联和 SSE 写出补窄方法。平台原生 API 有真实调用方，其回归属于必要工作。

### 官方示例和文档的适用边界

逐文件核对了 v2.0.4 示例目录的全部 8 个文件，包含 POM、启动类、Agent 配置、工具实现、应用配置、日志配置、静态页面和手写 SSE 客户端。GitHub 两个标签下的 POM 与整个 src 子树具有相同的 Git 对象 ID，因此示例源码也适用于本项目锁定的 v2.0.5。另核对了 2.0.5 扩展发布源码中的消息内容类型和用量转换器。以下是对本计划有影响的差异。[v2.0.4 完整示例](https://github.com/agentscope-ai/agentscope-java/tree/v2.0.4/agentscope-examples/agui) · [v2.0.5 对应目录](https://github.com/agentscope-ai/agentscope-java/tree/v2.0.5/agentscope-examples/agui) · [官方集成文档](https://java.agentscope.io/v2/zh/integration/protocol/agui) · [2.0.5 扩展源码包](https://repo.maven.apache.org/maven2/io/agentscope/agentscope-extensions-agui/2.0.5/agentscope-extensions-agui-2.0.5-sources.jar)

| 上游的实际做法 | Harness 的决定及理由 |
|---|---|
| WebFlux Starter 为 default、chat、calculator 注册工厂，可按路径、请求头或 forwardedProps 路由，示例开启服务端会话缓存 | 会话在创建时已固定 Agent revision。只接一个受认证的 MVC 路由；客户端提供的 agentId 或身份头无法切换执行主体。对话历史与恢复继续用持久状态槽。开发客户端走同源或开发代理，避免引入示例的通配 CORS。[Agent 配置](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-examples/agui/src/main/java/io/agentscope/examples/agui/config/AgentConfiguration.java) · [示例配置](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-examples/agui/src/main/resources/application.yml) |
| 示例页面注册浏览器工具 request_approval，在工具调用结束后收集决定，附上 assistant.toolCalls 和 role=tool 消息再发一轮。文档另外描述了服务端工具挂起与权限确认的 interrupt/resume | 首版的 publish_text 由平台执行，只有第 5 节的持久审批能发布产物。拒绝入站前端工具注册与单独提交的 tool 结果；随普通请求回传的旧 tool 消息只作历史。原样运行该演示页面不属于本版兼容目标。[页面的审批处理](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-examples/agui/src/main/resources/static/index.html) |
| 示例 converter 改写自定义事件，enricher 补时间和部分 rawEvent；天气工具用 ToolEmitter 报进度。手写客户端显示文本、推理和工具片段，其事件分派没有 RUN_ERROR 分支 | 由账本转换器生成已知事件，时间来自已提交记录（epoch 毫秒）。普通工具只展示已采集的开始信息；推理内容、子 Agent 原始流及 RAW 载荷不对外投影。发布工具的参数与回执有持久依据，使用标准工具事件。验收以 HttpAgent 的错误回调和 reducer 为准。[工具实现](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-examples/agui/src/main/java/io/agentscope/examples/agui/tools/ExampleTools.java) · [示例 SSE 客户端](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-examples/agui/src/main/resources/static/js/agui-client.js) |

## 3. 请求、身份与幂等

入口为 `POST /api/v1/ag-ui/runs`。客户端传 Bearer JWT、JSON 请求体及 `Accept: text/event-stream`。使用现有会话创建入口获得 `threadId`。成功返回 HTTP 200，以 SSE `data` 写出含 `type` 的 AG-UI JSON。沿用当前心跳、禁缓存及代理缓冲设置。[原生任务 API](../apps/sh-api/src/main/java/io/superharness/api/RunApi.java) · [现有 SSE](../apps/sh-api/src/main/java/io/superharness/api/RunStream.java)

| 输入 | 约定 |
|---|---|
| `threadId`、`runId` | threadId 为 conversation UUID，鉴权检查租户与所有者。runId 为该次客户端交互的非空字符串，上限 180 字符。平台任务 UUID 用于状态查询、取消和产物下载；协议帧回显客户端 runId。 |
| 普通 `messages` | 客户端每次提交一个新回合，数组末条必须为非空 user 纯文本，沿用 32,000 字符上限。content 接受字符串和仅含 text part 的数组。数组按顺序原样拼接 text，不插入分隔符，随后统一校验非空与长度，并以归一化正文计算幂等摘要。混入非文本 part 时整条拒绝。前缀作为展示历史处理，不送入模型；当前轮唯一命令是末条消息。同一文本配新 runId 是新一轮，配原 runId 是重试。拒绝不支持的当前轮角色、附件和多模态内容。[输入验证](../modules/sh-contract/src/main/java/io/superharness/contract/AguiInput.java) |
| `state`、`protocolVersion`、可选参数 | 接受锁定客户端实际发送的协议元数据和展示状态；服务端从平台事实重建执行上下文。非空的前端 tools、context、行为性 forwardedProps 及分支请求返回明确的字段错误。身份、模型、权限和工具以认证主体及固定的会话配置为准。 |
| `resume` | 优先解析为审批命令，历史消息可照常随请求传回。允许 messages 为空；客户端无需回传服务端的 interrupt metadata。校验同 thread、当前审批、原工具、角色和参数摘要。一个发布动作对应一个 interrupt；批量、缺失、未知或过期的答复有确定的错误结果。 |

**本次审查的客户端验证。** 在临时目录安装官方客户端 1.0.5，以内存中的 SSE 响应驱动真实 `HttpAgent.runAgent` 三次。首轮捕获 1 条输入消息；第二轮捕获 3 条消息及上一轮的 tasks 状态。第二轮在消息/状态快照后产生工具中断；第三次请求自动携带 4 条历史消息并发送 1 条 resume，客户端把返回的工具结果关联到原 toolCallId。定稿复核还把模拟 SSE 切为 13 字节的小块，验证了 token_usage、同 ID 快照纠正草稿、工具拒绝回执及其后的 RUN_ERROR 均被锁定客户端正确消费。此试验验证了客户端收发形状；Harness 的事务、鉴权和重启恢复仍由第 7 节验收。

**普通提交。** 按仓库频道入口的方式构造 `agui-<SHA256([threadId,runId])>`，传给 `Runs.submit`；原生调用方提交的幂等键保留此前缀供适配器使用，在公共原生请求边界校验。平台现有摘要负责判定实际执行命令是否相同。客户端附带的历史、显示状态、消息 ID 均不参与执行决策。同一键同一命令复用原任务，正文冲突返回 409。打开流前处理 JWT、请求结构、所有权、配额和幂等冲突。已授权且进入协议生命周期的业务失败用 `RUN_ERROR` 表达。

**等待审批时的提交顺序。** 在现有准入事务中先查幂等命中，再检查此会话是否有待决 interrupt，最后插入新任务。这样 A 的网络重试能重播 A，另一个新 run 的普通输入收到协议错误。对待决状态的检查与审批状态转换使用一致的锁或事务可见性；并发用例覆盖。首版将此限制施加在 AG-UI 入站命令上。现行版本统一从 AG-UI 受理聊天命令。

## 4. 输出与断线恢复

`sh-api` 按现有 `Runs.events` 的授权和分页规则消费账本，只向外输出已定义的字段。每个协议 run 先发 RUN_STARTED，再发自身事件；RUN_ERROR 包含 message 与 code，排他于 RUN_FINISHED。心跳采用 SSE 注释。编码器以一次请求为生命周期，在读到协议终点时立即停读，即使同一批里已有后续事件。模型调用或 SDK 流关闭本身都没有发布终态的权限。

| 来源 | 客户端可见行为 |
|---|---|
| `run.accepted`、`run.started`、恢复事件 | accepted 发一次 `RUN_STARTED`。用一个 `CUSTOM` 名称 `harness.run.status` 表示平台任务 ID、排队、运行中、需要人工恢复等进度。每个 attempt 使用独立且稳定的 assistant messageId。|
| `text.delta` | 首个非空片段打开 `TEXT_MESSAGE_START`，逐段发 `TEXT_MESSAGE_CONTENT`。切换 attempt、审批中断和业务终态前发 `TEXT_MESSAGE_END`，保留旧草稿的尝试归属。 |
| `run.completed` | 最终正文来自账本。增量是前缀时仅补缺失部分；只有最终文本时生成完整消息。若增量与最终正文分歧，先结束打开的文本消息，再用 `MESSAGES_SNAPSHOT` 发送截至该轮边界的完整可见消息列表，同一 assistant messageId 纠正正文。文本和快照处理结束后发唯一的 `RUN_FINISHED`。 |
| `plan.updated`、`usage.updated`、`tool.started` | 给 tasks 发 `STATE_SNAPSHOT`；用量对齐 AgentScope 约定 `CUSTOM(name="token_usage")`，value.delta 与 value.cumulative 均含 inputTokens、outputTokens、totalTokens。按该协议 run 覆盖的已提交调用累加，以 callId 去重，total 为输入加输出。重播从零重建，B 没有模型调用就不发用量。实际计费读 model_usage。普通工具发 `CUSTOM(name="harness.tool.started")`，value 只含 name、mode。当前工具记录没有普通工具参数和结果。 |
| `approval.required` | 执行第 5 节的标准中断。普通 run A 的输出在该点封闭，历史回放也在同一点停。 |
| `run.cancel_requested`、`run.cancelled`、`run.failed` | 请求取消仅显示进度；已取消与失败分别以明确的 code 发一次 `RUN_ERROR`。失败后流结束。 |

**快照的消息身份。** 接收普通提交时把当前 user 消息的 id 一并保存到已有 input_json，用于流、历史与快照之间的关联。幂等重发沿用首次保存的 id。原生历史缺少该字段时，沿用控制台的稳定输入消息 ID。assistant 消息按任务和 attempt 命名，工具结果按执行回执命名。用服务器已授权的数据组装快照，并保持用户消息在对应回复之前。官方文档将 ASJ 出站消息转换的维护责任交给应用，快照顺序、字段和 ID 因此纳入本项目的客户端契约测试。

**首版的命令重放。** 客户端在发送前保存当前 thread、协议 runId 和足以重发的本轮命令。网络中断、页面刷新或 JWT 到期后，以原身份和原命令重新 POST。同一次普通提交重读该平台任务的账本；审批续接按第 5 节重读对应结果。客户端恢复到本轮开始前的消息、计划和待决状态，再交给官方客户端消费回放。前序会话可取既有已授权历史，按平台任务/attempt 去掉当前轮的临时内容；刷新前连首帧也未收到时，用原幂等命令取回平台任务 ID。已完成任务直接查看历史即可。读历史时按目标轮次及中断 seq 截断，防止把后来的轮次或最终批准结果带进早期快照。

全量回放的成本是当轮事件条数。开发验收测一次超过 128 行的两页账本，记录恢复时间和查询分页数。只有出现实际不可接受的恢复耗时，才有证据重新讨论精确续传。

## 5. 发布审批的唯一流程

平台已经把 approval、tool execution、run 和 artifact 串在同一个事务体系里。使用 `approval.id` 作为 interruptId、`tool_executions.id` 作为 toolCallId。由任务 ID 和审批关联行读取安全预览、argsHash、expiresAt；现有外键可恢复这组身份。可在现有 `tool.requested` 和 `approval.required` 载荷补上关联 ID，免去新事件的反查。历史审批也通过同一组外键解析。[持久审批与产物](../modules/sh-run-engine/src/main/java/io/superharness/runengine/Approvals.java) · [原生审批入口](../apps/sh-api/src/main/java/io/superharness/api/ApprovalApi.java)

1. **请求 A 等待决定。** 输出 `publish_text` 的工具调用 START、ARGS、END，以及恢复所需的 `MESSAGES_SNAPSHOT` 和 `STATE_SNAPSHOT`。快照保留原工具调用、当前回答和计划。发出 `RUN_FINISHED`，其 outcome 为 interrupt，reason 为 tool_call，附真实 toolCallId、interruptId、过期时间、展示摘要和响应 schema。metadata 声明服务端执行工具（`ag-ui:tool-execution=server`），客户端仅提交决定。平台任务停在 WAITING_APPROVAL。
2. **请求 B 提交决定。** 同一 thread 下使用新的协议 runId；以 interruptId 找回原任务。先按原任务的可见性鉴权，再调用 `Approvals.decide` 校验 OWNER/APPROVER、argsHash 与有效期。resolved 的答复必须带布尔 approved 和已展示的 argsHash；响应 schema 只允许这两个字段。对 editedArgs、客户端工具结果和未支持的决定字段返回协议错误。产物已按原参数暂存，批准时始终核验原摘要。B 发自己的 `RUN_STARTED`，从原审批边界之后的账本生成原 toolCallId 的 `TOOL_CALL_RESULT`，批准时用真实回执收尾，拒绝时以 APPROVAL_REJECTED 收尾。B 仅关联本次响应，已有审批行承担持久幂等；由批准或拒绝的已提交结果生成回执，不因换用另一个客户端 runId 再次执行动作。
3. **重发、过期与放弃。** 相同决定且摘要一致可重复读取原回执，包括服务已提交而首帧丢失的情况。相反决定、首次答复过期、错误摘要或未覆盖待决项，授权后返回协议错误。明确选择“放弃本次发布”时，客户端发送 cancelled 且省略 payload，服务端按任务发起人权限在同一锁定事务中核对该待决审批并走平台取消；若决定已先提交，呈现真实已决结果。关闭窗口只断开连接。沿用平台取消事务写入的终态，重复取消复用同一结果。界面把“放弃发布”作为显式操作。
4. **其他人审批。** 发起人有审批角色时可在 AG-UI 直接决定；其他获授权的审批人使用现有审批台。发起人通过已有任务状态、历史和产物接口看到外部决定，客户端按该结果清除本地待决视图，再开始下一轮。历史 A 仍重现中断时的输出。故障恢复继续由原生任务 resume 处理，审批命令不会触发 Worker 重跑。[AG-UI interrupt/resume](https://docs.ag-ui.com/concepts/interrupts)

**审批卡的线协议样例。** A 的下列终帧在工具调用及快照之后发送。B 使用同一 threadId、新 runId，提交示例中的 resume。标识与摘要均为示意，实际值取自该次持久审批。两个 JSON 样例已用 @ag-ui/core 1.0.5 的公开 schema 校验，并交叉校验了 threadId、interruptId 和 argsHash。响应 schema 由后端生成，UI 负责回传卡片展示时的摘要。

```json
{
  "type": "RUN_FINISHED",
  "threadId": "11111111-1111-4111-8111-111111111111",
  "runId": "run-a",
  "outcome": {
    "type": "interrupt",
    "interrupts": [{
      "id": "22222222-2222-4222-8222-222222222222",
      "reason": "tool_call",
      "toolCallId": "33333333-3333-4333-8333-333333333333",
      "message": "发布 response.txt 到本次任务的产物列表？",
      "expiresAt": "2026-11-23T04:00:00Z",
      "metadata": {
        "ag-ui:tool-execution": "server",
        "argsHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      },
      "responseSchema": {
        "type": "object",
        "properties": {"approved": {"type":"boolean"}, "argsHash": {"type":"string","pattern":"^[a-f0-9]{64}$"}},
        "required": ["approved", "argsHash"],
        "additionalProperties": false
      }
    }]
  }
}
```

```json
{
  "threadId": "11111111-1111-4111-8111-111111111111",
  "runId": "run-b",
  "messages": [],
  "tools": [],
  "context": [],
  "state": {},
  "forwardedProps": {},
  "resume": [{
    "interruptId": "22222222-2222-4222-8222-222222222222",
    "status": "resolved",
    "payload": {"approved": false, "argsHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
  }]
}
```

此例的 B 依次发送 RUN_STARTED、对应原调用的 TOOL_CALL_RESULT（含稳定 messageId、toolCallId、字符串 content）和 RUN_ERROR（code 为 APPROVAL_REJECTED）。同意发布则返回真实产物回执并以 RUN_FINISHED 成功结束。前端从已提交的决定更新卡片，避免在用户点击时提前显示成功。

## 6. 开发顺序、负责人和完成条件

按三个可合并的改动交付。前两步用于开发集成；三步通过后发布同一个功能版本。下表为总人日估算，假设一名熟悉 Java/MySQL 的开发者主责，前端或测试工程师参与 SDK 联调。

| 顺序 | 代码与负责人 | 可验收的结果 | 估算 |
|---|---|---|---|
| 1　打通真实客户端 | API 开发在 `apps/sh-api` 增加 POST、请求解析、幂等准入及文本投影；前端在测试入口锁定 HttpAgent 依赖 | 从已有会话连续发送两轮；同键并发仅产生一个平台任务；错误主体无法提交或读取。产出真实请求/响应样例。 | 2–3 人日 |
| 2　交付恢复与状态 | API 开发补齐恢复、终态、计划及事件白名单；前端完成最小客户端的刷新、计划展示和故障恢复 | 断线或 API 重启后同轮回放收敛；两次 attempt 各自保留；回放正文、计划与平台历史一致。 | 2–3 人日 |
| 3　完成审批并整体验收 | Run Engine 开发接入审批关联、待决定检查和授权命令；前端接审批卡；测试把真实客户端用例接入既有脚本 | 标准中断、批准、拒绝、放弃、外部决定、超时与竞争通过；产物唯一，现有控制台通过回归。更新接口样例与验收记录。 | 3–5 人日 |

**总估算为 7–11 人日，包含标准审批和测试。** 关键依赖是本地 Java/MySQL 环境、可启动的 API/Worker/Reconciler，以及已有浏览器和确定性模型夹具。生产灰度、全控制台迁移、前端执行工具、多模态与子 Agent 时间线均需独立的产品需求。

## 7. 合并验收清单

| 用例 | 通过条件 |
|---|---|
| **提交与身份** | 并发同键、首帧丢失后重发都只生成一笔任务；同键改正文冲突；会话所有权、跨租户访问、配额和固定版本与原生 API 一致。 |
| **真实客户端与历史** | HttpAgent 发送两轮含历史和 state 的真实请求；第二轮模型只收到一个新增输入。覆盖等价的字符串/纯文本分块输入、混合媒体拒绝、计划快照、仅最终文本及增量分歧。逐项比对最终消息的顺序、ID、正文和数量。用两个 callId 验证 delta、cumulative 与重放后的总量。 |
| **连接与故障** | 构造零帧过滤、一行多帧和跨分页事件；中途断线、JWT 更新、替换 API 后均恢复同一最终视图。Worker 失租后按现有故障恢复流程产生第二次 attempt，旧草稿与最终正文分开。 |
| **终态与取消** | 排队取消、运行中取消、模型失败、截止时间、浏览器断开各自表现正确。每次完整协议交互恰有一个终态。 |
| **审批事务** | 用户看到等待状态；授权后才能决定。原生审批与协议决定竞争、重复同决定、相反决定、摘要错误、过期、放弃、跨 thread 的结果可区分。另用示例的前端工具注册、伪造的 role=tool 结果以及带 editedArgs 的 resume 作反例，核对任务与产物均未因这些输入改变。核验模型未因审批续接重跑，批准仅一份产物，拒绝或放弃没有发布产物。 |
| **发布证据** | 样例经锁定的真实客户端解码。覆盖转换器单测、现有 MySQL 事务集成和跨进程测试；执行 `./mvnw clean verify` 与 `./scripts/acceptance.mjs --browser --load --restore`。记录命令、结果、一条中断/续接事件序列和跨页恢复耗时。 |

排障复用现有 run ID、attempt、seq、trace 和审批审计，协议错误日志附客户端 runId。验收脚本、OpenAPI 样例和原生浏览器回归一起随实现提交。[跨进程验收](../scripts/acceptance.mjs) · [浏览器回归](../web/test/official-chat.spec.ts) · [发布记录](release-readiness.md)
