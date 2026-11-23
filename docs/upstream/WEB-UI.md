# AgentScope Web UI 对照核查

下文记录升级前的 UI 对照。当前聊天页面的发送、订阅与审批入口见[前端交互协议](../ag-ui.md)。当前登录、频道、规划、委派、Skill 与记忆的实现和验收见 [SDK 与 Harness 审计](SDK-HARNESS.md)。

界面基线是本机下载的 `agentscope-main/examples/web_ui/frontend`。2026-11-21 逐文件比对了 `src` 下全部 229 个源文件，项目包含全部文件，其中 197 个逐字节相同。侧栏组件、侧栏 SVG、标签页图标、全局样式、计划和团队面板都直接复用本地版本。其余差异集中在 Java 登录、资源适配、聊天状态、对话框和能力提示。[上游前端](https://github.com/agentscope-ai/agentscope/tree/main/examples/web_ui/frontend) · [许可证](../../web/public/LICENSE-agentscope.txt)

## 对照方法

独立启动本地源码的 `examples/agent_service/main.py` 与编译后的官方前端，在独立 Redis 中创建对照 Agent 和会话。官方示例的八个一级入口都由 Chromium 实际打开，记录加载时的 HTTP 请求。对照服务的 OpenAPI 有 21 个一级路径分组、117 个操作。Java 项目的 OpenAPI 有 38 个操作。两个数字用于界定检查范围，操作间存在一对多映射。[Java OpenAPI](../api/openapi.json)

在两端都用 1440 × 880 的中文浅色桌面视口打开空会话。在相同位置截取侧栏标志，两个 32 × 32 像素 PNG 文件完全相同。红框内的黑底白色 AS 来自 `AppSidebar` 内的 `agentscope_mono.svg`，`public/agentscope.svg` 用于浏览器标签页。聊天区使用上游的 48rem 内容宽度与输入框工具栏。[侧栏源码](../../web/src/components/layout/AppSidebar.tsx) · [聊天布局](../../web/src/pages/chat/ChatViewport.tsx)

## 四个演示场景

| 场景 | Java 执行和验证 | 仍有差异的行为 |
|---|---|---|
| 计划 | `TaskCreate`、`TaskGet`、`TaskList`、`TaskUpdate` 操作真实 SDK 会话状态。模型创建任务触发面板自动打开，支持跨轮更新、依赖、负责人、元数据、完成和删除。写入事件可经 SSE 重播，刷新可从 MySQL 历史重建 | 两端工具返回给模型的文本格式有差异。可见计划由模型调用工具产生，默认回显模型只返回输入 |
| 权限 | 使用原版模式选择器，五种模式保存到 Java 会话，每轮提交时固定模式，恢复时沿用该轮快照。浏览器验证 Bypass 切换和刷新回读、发布操作仍需要服务端审批 | Java 发布审批、租户角色、配额、沙箱及 MCP 配置由平台执行。尚未逐工具验证五种模式的所有交互式询问与临时授权组合 |
| 团队 | 原版团队面板可打开，单 Agent 会话有对应提示 | 缺少创建团队、委派子会话、成员消息和成员审批 |
| 后台工具 | 聊天右侧面板保留独立后台工具入口，并标出当前执行器的能力 | 缺少将单次工具调用转入后台、独立状态查询和完成后唤醒模型。Java 的运行、追踪、恢复和产物在「运行记录」查看 |

## 页面与入口

| 官方入口 | 上游核心请求 | Java 版本的行为 |
|---|---|---|
| 首次设置、聊天 | 用户、Agent、会话配置和消息流 | 首次进入设置页验证同源 Bearer 令牌，随后返回原深链接。首次新手引导、Agent 创建和编辑、会话切换、主模型选择、文本对话与纯文本附件、重连、确认卡、桌面停靠面板和窄屏面板已接入。输入框的工作目录与浏览器端工具控件当前禁用 |
| 日程 | `/schedule/` | 原版日历、翻月和列表视图。调度和创建入口待接入 |
| 频道 | `/channels/`、`/channels/types` | 原版页面，尚无外部平台绑定或消息投递 |
| 凭证 | `/credential/`、`/credential/schemas` | 原版页面提示凭证由服务端配置。聊天顶部从 Java 读取租户允许的模型，支持切换主模型。供应商凭证 CRUD、备用模型、逐模型参数待接入 |
| MCP 中心 | `/hub/mcp`、`/mcp` | 「我的」显示本租户配置的 MCP 及允许的工具，服务地址与密钥引用保存在服务端。外部 Hub 安装和工作区动态注册待接入 |
| 技能中心 | `/hub/skill`、`/skill` | 租户技能库支持搜索、详情、引用修订查询与撤销确认。「管理技能版本」支持手工发布及导入 SKILL.md 和 references/ 文本目录。外部 Hub 安装和会话内覆盖待接入 |
| 知识库 | `/knowledge_bases/`、分块和嵌入模型 | 原版知识库页面可访问。个人记忆有单独的管理入口。文档集合、索引与检索待接入 |
| 语音配置 | `/voice-profile/`、可用引擎 | 原版页面，尚无实时语音、音色、ASR 或 TTS 执行协议 |

完整行为一致性尚未通过。其余协议差异包括运行中输入的排队、排序和插入，会话内 Git 与目录操作，以及图片、音频和二进制附件。Java 额外提供发布版本固定、幂等运行、租户审批和故障恢复。上述差异在验收时分别记录。[Java 请求适配](../../web/src/api/harness.ts) · [历史与 SSE](../../web/src/hooks/useAguiThread.ts) · [计划工具](../../modules/sh-runtime-agentscope/src/main/java/io/superharness/runtime/PlanTools.java)

## 复验

`./mvnw clean verify` 运行构建和数据库、运行时测试。`./scripts/acceptance.mjs --browser` 在一次性数据库运行 Java API、Worker 与浏览器，使用确定性供应商响应验证模型工具调用，无需外部 API Key。浏览器回归覆盖首次登录、模型菜单、权限持久化、多轮对话、断线回放、审批、面板布局、产物下载、窄屏，以及八个一级路由的导航和深链接刷新。[浏览器回归](../../web/test/official-chat.spec.ts) · [服务验收](../../scripts/acceptance.mjs)

本次全量验收的三个 Chromium 用例全部通过。计划用例提交两条消息，实际观察到任务自动展开、完成后划线、刷新后保持完成。对照环境还分别执行了两端的任务依赖、元数据合并、完成解锁与删除清理。侧栏截图 SHA-256 为 `8a7d87400d29af42c2f42cf45b4cfeec6d86f1288ab3deabf8adba578c279d11`。线上供应商调用与团队、后台工具的执行链均未纳入通过项。
