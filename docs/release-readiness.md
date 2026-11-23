# 研发验收记录

## 单一 AG-UI 入口（2026-11-23）

部署的 OpenAPI 仅注册 `POST /api/v1/ag-ui/runs` 一个 AG-UI 操作。文本、同键重放、审批、快照、只读观察、取消与故障恢复均使用同一地址；原来的运行提交、消息历史、审批决定和 AG-UI 快照及连接路径均已移除。[当前契约与迁移表](ag-ui.md) · [OpenAPI](api/openapi.json)

| 验证 | 覆盖范围 |
|---|---|
| 构建与 Java | 打包 API、Worker、Reconciler；3 个事件投影测试、2 个模块边界测试通过。另在隔离 MySQL 上运行 8 个 AccessTest，覆盖租户隔离、恢复、历史、审批及过期。 |
| 客户端 | 真实官方 HttpAgent 验证正常请求、断线重放、快照游标、观察任务、拒绝混合读写、回执及审批幂等。 Chromium 覆盖同一聊天地址跨标签页审批、提交冲突、计划恢复、导航和产物下载。 |
| 独立审批人 | 使用 OIDC 中不同的会话所有者与 APPROVER。审批人可重放自己的决定，收到开始、工具回执和终态三个事件；请求他人会话快照返回 404。 |
| 部署演练 | 强制替换 API 和 Worker 后，任务在 61.195 秒进入可恢复状态，重发同一尝试的恢复命令后只有一个完成事件。100 个会话的 500 条原生账本订阅全部完成，接单 p95 为 947 ms。双库还原后校验产物内容并继续对话。 |

前端生产构建及 TypeScript 检查通过，ESLint 为 0 个错误、12 个既有警告。本轮使用独立端口和一次性本机 MySQL 26.7，Docker MySQL 8.4 的运行记录见下方历史批次。[端到端验收](../scripts/acceptance.mjs) · [协议回归](../web/test/agui-acceptance.mjs) · [聊天页面回归](../web/test/official-chat.spec.ts)

## 双页面过渡阶段验收（历史记录，2026-11-23）

从两个页面路由追踪到同一处 `HttpAgent.runAgent` 调用。发送和只读连接共用 Java 事件投影，历史快照共用账本读取逻辑。已对照 OpenAPI 检查三个 HTTP 入口、两条 SSE 响应及快照的 204 响应。[当前接入协议](ag-ui.md)

复核时复现了一个状态差异：审批中断时客户端已显示本轮计划，拒绝或放弃审批的续接流可能只报告失败，刷新后计划才回滚。修复后，续接流在终止前发送与服务端快照一致的 `state.tasks`。3 个 Java 投影单测通过，新增用例分别覆盖拒绝和放弃。[回归用例](../apps/sh-api/src/test/java/io/superharness/api/AguiProjectionTest.java)

使用隔离的本机 MySQL、打包的 API、Worker 和 Reconciler 运行真实 `HttpAgent` 验收及 5 个 Chromium 用例，全部通过。覆盖跨页审批和回执、断线重放、提交冲突后的输入框恢复，以及多轮计划刷新。前端 TypeScript 检查通过。此次验证未重跑 Docker MySQL 8.4、负载和备份还原。[协议验收](../web/test/agui-acceptance.mjs) · [跨页审批](../web/test/agui.spec.ts) · [主聊天](../web/test/official-chat.spec.ts)

## AG-UI 开发交付

以下为首版独立客户端的验收记录。当前单一聊天入口的 HTTP 契约、恢复规则和维护入口见[当前接入协议](ag-ui.md)。

会话页面通过官方 `@ag-ui/client@1.0.5` 连接 `POST /api/v1/ag-ui/runs`。开发验收从打包后的 API、Worker、Reconciler 启动，用临时 MySQL 双库、确定性模型与本地 OpenAI 兼容夹具完成。项目沿用既有任务、审批与账本，本次数据库迁移数为零。[实施计划](ag-ui-implementation-plan.md) · [接口样例](api/openapi.json)

| 检查 | 结果 |
|---|---|
| 构建与事务 | `./mvnw clean verify` 通过，含前端生产构建、转换器单测及 MySQL 集成测试。覆盖重复 callId、不同 attempt、最终文本纠正、越权、过期审批、JWT 到期停止订阅和新令牌重放。 |
| 真实客户端 | `./scripts/acceptance.mjs --browser --load --restore` 覆盖两轮对话、同键并发提交、冲突请求、分块文本、计划与累计用量。批准、拒绝、放弃、原生审批台决定均按账本重放；原生审批与协议批准并发时仅发布一次，同决定重放返回相同回执，反向决定被拒绝。续接前后的模型调用记录相同。 |
| 浏览器 | 4 个 Playwright 用例通过。AG-UI 页面刷新后完成原审批，核对回答仅出现一次，并通过浏览器下载 `response.txt` 校验内容。其余用例覆盖原控制台。 |
| 恢复与负载 | AG-UI 的 135 行账本跨 2 页重放耗时 21 ms。断开客户端并替换 API、Worker 后，原任务在 63.514 秒进入可恢复状态，第二次 attempt 完成，原请求重放得到一个成功终态。原生 SSE 压测为 100 个会话，500/500 条连接完成，提交响应 p95 为 861 ms，用时 32 秒。 |
| 还原与身份 | 从备份还原平台库、运行时库及产物，在还原会话上继续执行。独立 OIDC issuer 的签名、受众和租户成员检查通过。 |

本轮完成于 2026-11-23。首次运行曾在容器初始化阶段误判 MySQL 已就绪，验收脚本改为通过 TCP 执行探测查询后，建库和独立还原均通过。全量 ESLint 为 0 个错误、12 个既有警告。

发布审批的事件顺序可按以下边界核对。A 从 `RUN_STARTED` 开始，文本结束后依次发 `TOOL_CALL_START → TOOL_CALL_ARGS → TOOL_CALL_END → MESSAGES_SNAPSHOT → STATE_SNAPSHOT → RUN_FINISHED(interrupt)`。B 以新的协议 runId 续接，批准产生 `RUN_STARTED → TOOL_CALL_RESULT → RUN_FINISHED(success)`，拒绝产生 `RUN_STARTED → TOOL_CALL_RESULT → RUN_ERROR(APPROVAL_REJECTED)`。两次交互共享同一平台任务和 toolCallId。批准可下载一份产物，拒绝和放弃均无产物。[协议验收](../web/test/agui-acceptance.mjs) · [浏览器验收](../web/test/agui.spec.ts)

## 首次源码发布复核（2026-11-22）

从 Git 暂存区导出源码，在独立目录运行 `./mvnw clean verify`。隔离验收首次在等待 Worker 租约恢复的 75 秒窗口超时，完整重跑通过故障恢复、3 个浏览器用例、100 个会话的 500 条 SSE 连接、备份还原和 OIDC 鉴权。并发轮次完成 500 条流，提交响应 p95 为 467 ms。前端生产构建通过，ESLint 返回 0 个错误和 12 个警告。npm audit 报告 6 个中危和 7 个低危问题。Git 导出内容的 Trivy 扫描未发现密钥。

## 初始研发验收

以下为 2026-11-21 的验收记录。后续能力与验证见 [SDK 与 Harness 审计](upstream/SDK-HARNESS.md)。

本次验收针对 Java 21、Spring Boot 4.1.3、AgentScope Java 2.0.5 和 MySQL 8.4.13。实现覆盖本地研发的任务执行及管理流程。下表的结果来自独立测试数据库或本机两个项目库。真实供应商计费、生产流量和人员审批另列在试点条件中。

| 范围 | 验证入口 | 已验证行为 |
|---|---|---|
| 模块、数据库 | `./mvnw clean verify` | SDK 依赖边界，空库安装，已有库升级，部分 DDL 失败后的修复，重复迁移 |
| 身份、资源、事务 | `AccessTest` | 跨租户拒绝，撤销成员，个人记忆隔离，固定与撤销 Skill，服务端配额，同一会话争抢、过期写入拒绝、事件与 outbox 同步回滚 |
| 运行时 | `HelloHarnessTest`、`MySqlStoreTest` | AgentScope 真实多轮历史、类型化事件、订阅前中断、运行中协作中断与硬取消、MySQL 与 H2 契约 |
| 其他存储、沙箱 | `AlternativeStoresIT`、`SandboxIT` | PostgreSQL、SQLite 组合、Redis、MongoDB 契约，真实 Docker 工作区快照恢复及会话隔离 |
| 主链路 | `scripts/smoke.mjs` | 真实本地 MySQL 双库，HTTP 创建、重复提交、两轮会话、连续事件、从游标重播及终态 |
| 故障、协议 | `scripts/acceptance.mjs` | 强杀 Worker，替换 API，保留任务与会话门闩，恢复后单一终态；OpenAI 兼容流、返回用量、HTTPS MCP 白名单、工具回合、OTLP 上报、Prometheus 权限 |
| 控制台 | `cd web && npm test` | 从根路径打开设置页，连接 Java、创建 Agent 和会话，发送两轮消息，截断 SSE 后重连，通过会话确认卡审批，刷新深链接，验证计划空态、运行记录、产物下载及 390 像素布局 |
| 备份还原 | `scripts/acceptance.mjs --restore` | 联合导出两个库，在新 MySQL 还原，重读已提交任务与产物字节，从已还原的会话状态执行下一轮 |
| 交付物 | `deploy/`、`.github/workflows/ci.yml` | 三个角色镜像、隔离沙箱镜像、Helm Chart、Grafana dashboard、Prometheus 告警、CI 的依赖和机密扫描、SBOM |

## 会话升级实测

控制台以本地官方前端源码为基线。八个一级路由均可访问，各页面已接入的操作与差异见 [核查记录](upstream/WEB-UI.md)。浏览器回归在实际 Java 服务验证根路径、连接设置、创建 Agent 与会话、重连、多轮回答、确认卡、下载、深链接和窄屏布局。

浏览器回归使用确定性模型。Java 历史接口按尝试保留增量，MySQL 集成测试覆盖失败、取消、租约失效后恢复，以及最终回答替换当前草稿。会话确认卡和审批面板使用同一决策接口。

## 故障和并发实测

隔离环境使用真实 JVM、一次性 MySQL 8.4.13、确定性本地模型和同机 HTTP。强杀发生在首个尝试进入 RUNNING 后，期间替换 API。本轮观测到 64.3 秒进入 RECOVERY_REQUIRED，落在方案的 75 秒目标内。人工恢复入口在同一 runId 下启动第二次尝试。事件从旧游标连续追到唯一的 `run.completed`。

一组压测并行提交 100 个独立会话，每个任务进入审批后保持打开。每个任务建立五条 SSE 订阅，待 500 条连接都收到响应后执行审批。本轮提交响应 p95 为 597 ms，全部结束用时 32 秒。500 条订阅逐条核对了 runId、连续序号与最终状态。这是单机短测，长期可用性、多区域延迟和真实供应商吞吐需要独立测量。

## 安全与发布检查

使用与交付版本一致的 Maven 依赖图生成 CycloneDX SBOM，Trivy 0.76.2 按 2026-11-21 漏洞库扫描命中 0 项，替换为官方完整前端后，npm 审计报告 0 项高危或严重、6 项中危及 7 项低危。源码机密扫描命中 0 项。部署文件的本地静态规则检查发现 Dockerfile 缺少镜像内 HEALTHCHECK 等低级别提示，集群角色已配置 startup、readiness 和 liveness 探针。三个角色镜像均构建成功；Helm lint、模板渲染已通过。CI 工作流已交付，本次运行记录来自本机。生产 OIDC 解码器使用独立的临时 RSA issuer 和 JWKS 完成有效令牌、错误受众和跨租户拒绝验证。

## 试点条件

- 对接组织 IdP、真实用户目录、模型供应商、生产对象桶及云存储账号，验证真实权限、限流、费用与恢复。
- 在目标 Kubernetes 集群完成发布、滚动升级、容量和持续负载测试。普通 Chart 运行三个服务，Docker 沙箱任务依赖隔离的执行环境。
- 上线前按环境验证加密备份、binlog 时间点恢复、密钥轮换、审计保留周期、值班告警及租户资源策略。已提交的事件和 outbox 都需要纳入留存容量规划。
- 用经授权的业务样本建立质量和成本基线。真实业务质量、生产 SLO、生产安全签核和 Pilot 决策由技术负责人、SRE、安全方确认。
- 方案中的 P1 增强包括远程与后台多 Agent 委派、定时任务、Skill 市场、沙箱预热池和自动模型路由，本次交付聚焦 P0 开发主链路。

复核接口见 [OpenAPI](api/openapi.json)，部署与恢复步骤见 [运维手册](operations.md)。上游扩展的实测范围见 [兼容矩阵](upstream/API-COMPATIBILITY.md)。
