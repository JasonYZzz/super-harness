# AgentScope Web 控制台对照

## 扫描范围

2026-11-23 对本机 `agentscope-main/examples/web_ui/frontend/src` 和本仓库 `web/src` 取相对路径并逐文件比较内容，排除 `.DS_Store`。同时检查两端路由表、前端 API 适配、服务注册、权限检查与运行入口。上游 Express 示例只有健康检查。产品页面实际依赖 `examples/agent_service/main.py` 创建的 FastAPI 应用。扫描 `src/agentscope/app/_router` 得到 20 个含操作的模块，117 个 HTTP 操作和 1 个 WebSocket 操作。上游示例还配置了 Redis、Qdrant、工作区、外部频道和 Hub。[本地官方前端](/Users/mac/ai-project/agentscope-main/examples/web_ui/frontend/src/App.tsx) · [示例服务](/Users/mac/ai-project/agentscope-main/examples/agent_service/main.py) · [Java API 快照](../api/openapi.json)

上游共 229 个源文件。184 个在本项目有同路径文件，45 个只出现在上游。文件数用来复查扫描范围，功能判断依据实际请求和数据流。

| 源码目录 | 字节相同 | 同路径有改动 | 仅上游 | 仅本项目 |
|---|---:|---:|---:|---:|
| 根目录文件 | 3 | 1 | 0 | 0 |
| api | 7 | 9 | 4 | 2 |
| assets | 4 | 1 | 0 | 0 |
| components | 80 | 16 | 18 | 2 |
| context | 3 | 0 | 0 | 0 |
| hooks | 21 | 5 | 11 | 3 |
| i18n | 2 | 2 | 0 | 0 |
| lib | 9 | 1 | 2 | 0 |
| pages | 7 | 10 | 9 | 4 |
| types、utils | 3 | 0 | 1 | 0 |

## 用户入口和执行行为

| 入口 | 本项目可用的流程 | 上游另有的能力和差异 |
|---|---|---|
| 设置、Agent、会话 | 同源登录，选择或新建 Agent，编辑和发布修订，新建、重命名、归档会话。创建后选中新 Agent。已归档 Agent 的入口回到可用 Agent。来源能区分用户、HTTP 频道和日程。 | Python 示例使用其用户和工作区模型。Java 会话保存已发布修订，模型和权限在提交时固定。 |
| 聊天、队列 | 文本、UTF-8 纯文本附件、流式响应、历史恢复、打断、审批和 AG-UI 重播。回答进行时可以追加消息，服务端回执返回队列任务 ID，支持刷新和撤回。 | 上游队列可以重排、改写和插入当前推理。还有图片、音频、浏览器端工具执行和子 Agent 询问。 |
| 计划、团队、后台任务 | 计划工具及状态面板支持跨轮更新。只读分析委派有独立计量。运行记录可跟踪尝试、恢复和产物。 | 委派以父运行内工具调用执行。持久团队成员、成员审批、独立后台工具进程及完成唤醒均有单独的上游协议。 |
| 日程 | 用户可创建一次、每日、每周和每月任务，设置时区、截止时间、模型、权限以及是否复用会话。支持日历、列表、暂停、恢复、删除和历史会话。Reconciler 向正常运行队列提交，事务保护触发时刻、会话和任务。积压的周期任务合并为一次补跑，随后从当前时间计算下一次。 | 当前编辑入口支持暂停和恢复。修改开始时间、Cron 表达式或提示需新建日程。上游还支持 Agent 生成日程。 |
| 频道 | 认证 HTTP 消息、线程绑定、入站去重、会话跳转。 | 钉钉、飞书、Discord 的绑定、OAuth、启停和外发适配器尚缺。 |
| 凭证、模型 | 页面显示当前租户可用模型和默认模型，聊天中可切换主模型。供应商凭证留在服务配置中。 | 用户级密钥管理、备用模型和逐模型参数编辑尚缺。 |
| MCP | 租户目录显示服务、版本和被允许的工具。Agent 表单可选择配置，聊天面板列出会话固定的版本和工具。 | 外部 Hub 安装、凭证表单和工作区临时注册尚缺。 |
| Skill | 上传 SKILL.md 或 ZIP、校验文本资源、发布、撤销、选择版本。聊天面板显示当前修订固定的用途与版本。 | 未移植 Claw Hub 或会话内替换已固定 Skill。 |
| 知识库 | 个人文档库支持创建、删除、UTF-8 文件导入、文档移除、关键词检索和结果片段。在聊天面板选择库后，后续运行固定其 ID，通过 `knowledge_search` 读取来源和片段。租户与所有者双重限定查询。 | 当前每个会话可挂载一个库，检索采用字面关键词。上游另有多库、嵌入模型、分块参数、向量相似度、多媒体解析与原件下载。个人长期记忆有独立入口。 |
| 语音、工作区 | 语音页显示引擎可用状态。沙箱工具作业和授权产物下载由 Java 运行时提供。 | 未接入 ASR/TTS、WebRTC、音色克隆、自由目录浏览或 Git 状态协议。 |

## 后端逐模块核查

括号内为本机 Python 路由源码中声明的操作数。HTML 深链接、Spring Actuator 和 AG-UI 扩展各有不同的操作粒度。

| 上游模块 | Java 对应入口 |
|---|---|
| agent (6)、session (8)、chat (6) | `/agents`、`/conversations`、`/ag-ui/runs`。同一 AG-UI 入口处理流、快照、排队、撤回、审批和恢复。 |
| schedule (5)、channel (14) | `/schedules` 和 `/channels/http`。 |
| credential (5)、model (1)、health (1) | `/tenant` 的模型目录及 `/actuator/health`。 |
| skill (3)、mcp (3)、hub (8)、workspace (12) | `/skills`、`/mcp-profiles`、`/conversations/{id}/resources`、授权运行产物。 |
| knowledge_base (16)、embedding_model (1) | `/libraries` 和会话挂载接口，关键词查询。 |
| asr (4，含 WebSocket)、realtime (3)、tts_model (1)、voice_profile (8) | 控制台展示服务能力；没有供应商语音代理。 |
| background_task (2)、sop (11) | 运行账本、计划及发布审批可独立使用。上游的后台工具和 SOP 资源协议尚未移植。 |

## 验证入口

`./mvnw clean verify` 构建 Java 与控制台，并运行隔离 MySQL 测试。`./scripts/acceptance.mjs --browser --load --restore` 在临时数据库运行 Java API、Worker、Reconciler 和 Chromium，用可重复的模型响应验证真实调用链。对照不把页面能打开、已复制组件、或禁用按钮计作执行成功。[端到端验收](../../scripts/acceptance.mjs) · [浏览器用例](../../web/test/official-chat.spec.ts) · [AG-UI 协议](../ag-ui.md)

本轮最终验收通过 9 个 Chromium 用例，包括聊天运行中排队及撤回、知识库上传检索、Skill 选用和一级入口刷新。服务验收覆盖定时任务暂停和恢复、再次触发复用会话、模型检索指定文档、跨租户隔离及独立数据库恢复。压力验收覆盖 100 个会话、500 条并发流，全部完成，准入耗时 P95 为 591 毫秒。
