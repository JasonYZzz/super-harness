# AgentScope Java 2.0.5 能力审计

核对范围为 SDK 文档导航中的 7 项核心组件和 10 项 Harness 能力。下表中的“已有”对应修改前工作代码；“现状”对应聊天控制台、Java API 或 Worker 中可调用的路径。上游同一章节往往介绍多种部署方式，实际可用的实现和测试范围分别注明。[官方目录](https://java.agentscope.io/v2/zh/docs) · [SDK 版本与存储契约](API-COMPATIBILITY.md)

| 能力 | 修改前 | 现状与证据 |
|---|---|---|
| 智能体 | 已有 | 控制台创建 Agent、发布固定修订。Worker 为每个执行尝试构建 HarnessAgent，使用 SDK 推理循环和取消句柄。浏览器与运行时测试覆盖多轮聊天。 |
| 消息与事件 | 已有文本链路 | 用户消息、增量、终态、工具和计划事件经 Java SSE 送达，支持按序号重播以及从历史恢复。平台选择性映射 SDK 事件。 |
| Middleware | 已接入 | 使用 SDK 的上下文压缩和沙箱生命周期中间件；增加只读规划与固定技能中间件。工具计量和计划变更使用 Hook。当前配置由 Worker 组装。 |
| Model | 已有供应商调用与计量，缺搜索参数 | 按会话选择模型，按运行固定模型，支持本地回显、OpenAI 兼容协议和 DashScope。两个本地 Token Plan 模型启用百炼原生搜索。协议测试检查请求体和运行记录。 |
| Permission System | 已有五种 SDK 模式与平台权限 | 会话持久化模式，提交时固定；租户角色、所有权、工具预算、发布审批在服务端执行。增加由用户控制的 Plan 模式。不同 SDK 工具的 ASK 交互组合尚未逐一验收。 |
| Tool | 已有 | 本地工具、只读 HTTPS MCP 白名单和沙箱工具可以执行。新增分析委派、已保存计划读取。工具的使用情况可从运行事件查询。 |
| 上下文与 AgentState | 已有 | 从成功发布的上一执行槽复制对话、摘要、任务和权限，现也复制规划状态。使用版本化存储与会话门闩；故障测试覆盖恢复后的单一终态。 |
| Harness 架构 | 已有 | API 持久化任务与审批，Worker 执行固定版本，Reconciler 处理租约和超时。API 与 Worker 可独立重启。 |
| 上下文压缩 | 已接入 SDK 默认中间件 | 新增长历史回归，向已提交执行槽写入 120 条历史消息，运行下一轮，断言新槽保留 SDK 压缩摘要且消息数下降。真实模型的摘要保真度需业务样本评估。 |
| 工作区 | 已有执行槽与快照 | 每次尝试使用独立工作区。SDK 受管路径承载计划、技能、记忆，沙箱工作区从已发布快照恢复。聊天内任意切换宿主机目录未开放。 |
| 记忆 | 已有个人记忆库与最近四条注入 | Agent 设置可启用记忆，SDK 的 memory_save、memory_search、memory_get 管理会话记忆。记忆文件随执行槽持久化，已实测跨轮保存、读回。个人记忆按用户管理，会话文件按会话隔离。后台自动归纳未开启。 |
| 文件系统 | 已有 | 沙箱版本具备 SDK 读写与精确编辑，支持跨轮恢复及会话隔离。普通聊天只开放受管路径上的专用工具。 |
| 沙箱 | 已有 Docker 路径 | 固定镜像、只读根、资源限制、无网络、独立工作区。SandboxIT 验证创建、写入、编辑、恢复、跨会话隔离。 |
| 子 Agent | 之前关闭 | 模型通过 delegate_analysis 将自包含任务委派给独立 ReActAgent。子 Agent 仅收到传入的任务，共用父运行的模型计量，结果回到父 Agent。验收断言父、子、父共三次模型调用。此实现覆盖同步分析委派，远程子 Agent、后台回推与团队协作尚未覆盖。 |
| Skill | 已有版本发布、固定、撤销与提示注入 | 控制台可以选择具体版本；每个尝试从该修订生成 SKILL.md，SDK 按冻结目录提供 load_skill_through_path。测试实际读取固定正文。外部市场、自动学习与提升审批尚未覆盖。 |
| 计划模式 | 已有 TaskCreate 等清单工具 | 聊天的 Plan 模式运行 SDK 只读中间件，允许专用 plan_write，阻止普通修改。保存的正文随回答返回，可在后续调用 read_plan。用户审阅后在空闲会话切换模式，下一轮才能执行。此项目使用会话模式切换作为人工放行入口。[官方 Plan Mode](https://java.agentscope.io/v2/en/docs/harness/plan-mode.md) |
| Channel | 原频道页无后端 | HTTP 入站支持按租户、用户、Agent、来源和线程路由。首次消息绑定已发布修订，重投相同消息返回同一运行。响应带 SSE 地址，频道页列出可回到聊天的会话。具体验收覆盖并发首投。外部 IM 的专有鉴权和回调需各自适配器。 |

## 北京天气案例

聊天页显示的模型来自会话选择。原 Worker 将 Token Plan ID 解析为兼容模型，只传基础地址和密钥；SDK 工具配置同时显式关闭 Web 工具。模型有可能调用计划或其他工具，单凭截图中的调用次数无法还原当次工具名。历史运行可查看 trace 中对应的 tool.started 事件。

新 Worker 按完整模型 ID 决定是否传供应商扩展字段。对时效性回答提供北京时间日期并要求注明资料来源。搜索由供应商执行，计入供应商侧用量。webSearchRequested 记录应用提出搜索请求，工具调用数记录应用侧工具执行。它们衡量两件独立的事。本地通过正常的频道入站、会话、Worker、真实 qwen4-max 验证了截图中的原问题，收到覆盖 2026-11-21 至 11-27 的回答和注明日期的来源。另一次直接针对相同 Token Plan 端点的 qwen4-max 请求开启 include_tool_usage，百炼返回 usage.x_tools.search.count = 1、strategy = turbo。该统计属于独立请求，不能归到先前聊天。DeepSeek 经 Worker 也返回带日期的天气资料，运行事件记录 webSearchRequested = true。逐日气象精度未独立校验。[百炼 Chat Completions 搜索协议](https://help.aliyun.com/zh/model-studio/web-search/)

## 复验入口

- 构建与数据库、状态和压缩测试使用 `./mvnw clean verify`。
- 独立数据库、模拟供应商、真实 API 与 Worker、浏览器、恢复及压力测试使用 `./scripts/acceptance.mjs --browser --restore --load`。协议验收检查 Token Plan 请求带搜索参数、普通 OpenAI 请求省略搜索参数、频道幂等、只读规划、委派计量、技能正文与会话记忆。
- 真实 Docker 与替代存储测试使用 `SH_SANDBOX_IMAGE=super-harness-sandbox:local ./mvnw -pl modules/sh-runtime-agentscope -am test -Dtest=SandboxIT,AlternativeStoresIT -Dsurefire.failIfNoSpecifiedTests=false`。镜像构建方法见项目 README。
- 登录页依据当前服务 profile 显示本地账号入口。本地 JWT 与开发 CLI 共用签名和既有租户成员，错误密码返回 401。生产使用 OIDC；验收检查生产拒绝无令牌的本地密码登录。
