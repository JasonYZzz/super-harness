# 交互式 Agent 的前端协议

聊天页面统一使用 `/chat/:agentId/:sessionId`。所有交互请求都发往 **`POST /api/v1/ag-ui/runs`**，请求体是 AG-UI 的 `RunAgentInput`，响应是 `text/event-stream`。客户端携带 Bearer JWT。会话已固定 Agent revision，切换 Agent 通过切换会话完成。[OpenAPI 与请求示例](api/openapi.json) · [Java 入参契约](../modules/sh-contract/src/main/java/io/superharness/contract/AguiInput.java) · [官方 HttpAgent](https://docs.ag-ui.com/sdk/js/client/http-agent)

## 一次请求做什么

| 用途 | 发送内容 | 成功响应 |
|---|---|---|
| 发送或重试用户消息 | `threadId`、协议 `runId`、末条为用户文本的 `messages` | 文本增量、计划、工具活动，直至首次中断或任务终态。相同命令用原 ID 重放。 |
| 回复发布审批 | 新的协议 `runId` 与单项 `resume` | 决定关联原 `interruptId` 和 `toolCallId`。批准产生发布回执，拒绝或放弃以 `RUN_ERROR` 结束。 |
| 打开会话、刷新或检查变化 | 空消息与 `forwardedProps: {"harness.snapshot": {}}` | `MESSAGES_SNAPSHOT` 和 `STATE_SNAPSHOT` 提供消息与计划。`RUN_FINISHED.result` 提供 cursor、turns、tools、archived、revisionId 和 capabilities。待决审批在 `outcome.interrupts`。 |
| 跟随已存在的平台任务 | 空消息与 `forwardedProps: {"harness.observe": "<平台任务 UUID>"}` | 服务端校验会话与任务归属，从任务起点重放并追随新事件，至首次中断或终态。 |
| 停止平台任务 | 空消息与 `forwardedProps: {"harness.cancel":"<平台任务 UUID>"}` | 所有者收到 `result.taskId` 确认。正在执行的任务由 Worker 接受取消信号，最终状态从账本获取。 |
| 恢复故障尝试 | 空消息与 `forwardedProps: {"harness.recover":{"taskId":"<平台任务 UUID>","attempt":1}}` | 传入快照中的失败尝试编号。服务端核验归属、可恢复状态和期限；同一次恢复重试复用确认，后续尝试独立。 |

`harness.snapshot` 和 `harness.observe` 是 Harness 在标准 `forwardedProps` 中定义的只读扩展。另外两个扩展用于任务控制。每次只传一个扩展，且与发送消息或审批答复互斥。快照支持传入 `{"harness.snapshot":{"since":"<cursor>"}}`。视图未变化时返回标准的开始、结束事件，结束事件含 `result: {"unchanged":true}`，客户端保留已有视图。[服务端分发](../apps/sh-api/src/main/java/io/superharness/api/AguiApi.java) · [会话快照](../apps/sh-api/src/main/java/io/superharness/api/AguiThreads.java)

## 重连与审批

- **重试自己的命令。** 会话 UUID 是 `threadId`；协议 `runId` 由客户端生成。发送前保存本次命令、消息 ID 和协议 runId。若服务端已经接单却丢失响应，原样重发会重放同一次交互。审批续接本身也是一次可重试的命令。
- **在另一个标签页打开。** 先读取快照。有待决中断时展示审批；任务仍在执行时，取 `turns[].runId` 的平台任务 UUID 作为 `harness.observe`；已完成时直接显示快照。关闭标签页只断开 HTTP 连接。
- **批准、拒绝与放弃。** 批准或拒绝使用 `status: "resolved"`，payload 包含布尔值 `approved` 和原 `argsHash`。放弃使用 `status: "cancelled"` 并省略 payload。会话所有者拒绝或放弃后，续接流会在终止前发送回滚后的计划。具有审批角色的其他用户可从 `GET /api/v1/approvals` 获取 threadId 和审批摘要，向同一 AG-UI 入口提交决定；收到的内容限于决定回执和终态。[审批授权与事务](../modules/sh-run-engine/src/main/java/io/superharness/runengine/Approvals.java) · [官方中断语义](https://docs.ag-ui.com/concepts/interrupts)

## 事件与维护位置

共享会话服务使用 `@ag-ui/client@1.0.5` 的 `HttpAgent`。聊天组件从适配器取得消息、计划、工具卡和审批状态。新增交互能力应从这一入口贯穿到事件投影与客户端验收。[会话服务](../web/src/hooks/useAguiThread.ts) · [聊天渲染适配器](../web/src/hooks/useHarnessChat.ts) · [真实客户端验收](../web/test/agui-acceptance.mjs)

后端从已提交的账本生成标准文本、消息快照、状态和工具调用事件。三个 `CUSTOM` 事件分别承载用量（`token_usage`）、平台运行状态（`harness.run.status`）和普通服务端工具活动（`harness.tool.started`）。目前支持文本和服务端工具；前端工具注册、多模态输入、非空 context、分支和未声明的 forwardedProps 会收到错误。[事件投影](../apps/sh-api/src/main/java/io/superharness/api/AguiProjection.java) · [历史重建](../apps/sh-api/src/main/java/io/superharness/api/AguiHistory.java)

会话管理、任务追踪和产物下载继续使用平台 REST。频道收件的 `eventsUrl` 与运维消费者使用按账本序号续传的任务事件流。故障恢复用 `harness.recover`，审批续接用标准 `resume`。[平台接口](api/openapi.json) · [运维手册](operations.md)

## 调用方升级

本次是破坏性变更。部署后通过聊天页面重新打开已有会话，已入账的运行、历史和待决审批可从快照恢复。旧标签页中只保存在本地、尚未提交成功的草稿或命令需要重新输入。

| 已移除的入口 | 现行调用 |
|---|---|
| 独立页面 `/ag-ui/{threadId}` | 打开会话的 `/chat/{agentId}/{sessionId}`，其他标签页使用相同地址。 |
| `POST /api/v1/runs` | 在 AG-UI 请求中发送用户文本。将客户端重试标识作为协议 runId；服务端依据 threadId 选择已固定的版本，执行超时为 1,200 秒。 |
| `GET /api/v1/conversations/{id}/messages`、`GET /api/v1/ag-ui/threads/{thread}` | 发送 `harness.snapshot`，分别消费消息、计划和结束事件中的元数据。 |
| `POST /api/v1/ag-ui/threads/{thread}/runs/{run}/connect` | 发送 `harness.observe`，值为平台任务 UUID。 |
| `POST /api/v1/approvals/{id}/decision` | 用审批记录返回的 threadId、interruptId（记录中的 id）和 argsHash 发起标准 resume。 |
| `POST /api/v1/runs/{id}/cancel`、`POST /api/v1/runs/{id}/resume` | 在 AG-UI 请求中发送 `harness.cancel` 或带尝试编号的 `harness.recover`。 |
