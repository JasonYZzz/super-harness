# Super Harness

使用 AgentScope Java 2.0.5 执行有状态的 Agent 任务。API 接受任务并持久化事件，Worker 运行 Agent，Reconciler 检查租约、超时和恢复状态。Web 控制台与 API 由同一个服务提供。

## 本地启动

首次从仓库启动需要 JDK 21、Node.js 22.12+、npm 和 MySQL 8.4。先在 MySQL 中创建 `super_harness` 和 `super_harness_runtime` 两个数据库，为开发账号授予这两个库的建表和读写权限。Flyway 管理平台库迁移，AgentScope 管理运行时库。

将下列配置写入 `~/.config/super-harness/mysql.env`，替换账号和密码，文件权限设为 `600`。也可以通过 `SH_ENV_FILE` 指定其他文件。启动脚本使用 Bash 加载配置，含特殊字符的值需加引号。

```bash
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_USER=super_harness
MYSQL_PASSWORD='替换为开发库密码'
MYSQL_DATABASE=super_harness
AGENTSCOPE_MYSQL_DATABASE=super_harness_runtime
```

在仓库根目录执行构建并启动三个服务。Maven 将 React 页面打入 API jar。之后可使用 `./scripts/dev-up.sh` 启动已构建的版本。

```bash
./scripts/dev-up.sh --build
```

打开 [AgentScope 聊天页](http://127.0.0.1:8080/)，在本地设置页输入用户名 `admin` 和密码 `admin`，点击「开始使用」。本地密码登录和普通开发令牌使用同一身份 `local/developer`，首次初始化为租户 OWNER。修改登录名后仍使用该身份，实际权限由成员表决定。设置页支持退出和开发令牌登录，命令行可继续使用 `./scripts/dev-token.sh`。本地 JWT 有效期一小时，账号密码可用 `SH_LOCAL_USERNAME` 和 `SH_LOCAL_PASSWORD` 覆盖。生产 profile 使用 OIDC。在左侧 Agent 栏点加号创建并发布 Agent，点「新会话」后即可发送消息。右上角「面板 → 计划」展示模型创建的任务。「运行记录」展示每轮运行、追踪和产物。启用审批的 Agent 会在输入框上方展示官方确认卡。

开发签名密钥在首次启动时生成，默认保存在 `~/.config/super-harness/dev-jwt.key`。使用 `./scripts/dev-down.sh` 停止服务。日志位于 `.local/{api,worker,reconciler}.log`。运行集成测试和 Docker 沙箱需要可用的 Docker 服务。

### 使用 AG-UI 客户端

聊天统一使用 `/chat`。所有交互式 Agent 通过 `POST /api/v1/ag-ui/runs` 发送消息、恢复会话、只读跟随已有任务、答复审批、取消及故障恢复。携带 Bearer JWT 的官方 `HttpAgent` 可以直接使用该入口。新标签页打开同一聊天地址即可继续处理待决审批。关闭页面只断开订阅，显式取消才停止平台任务。[协议与接入示例](docs/ag-ui.md) · [OpenAPI](docs/api/openapi.json)

### 设置聊天模型

在聊天页右上角点击「选择模型」或当前模型名称。可用模型从 Java 服务读取。在空闲会话切换后，下一条消息使用新模型，历史和重试保留各自提交时的模型。创建 Agent 时可以设置初始模型，从已有会话新建时会继承当前选择。

本地默认用 Token Plan 的 `qwen4-max`。新发布的 Agent 版本未指定模型时会使用后端默认值。已有会话继续使用已保存的模型，可从聊天工具栏切换。模型菜单同时提供 `deepseek-v4.1-flash-1017` 和回显测试模型 `local-echo`。

- 在 [API application.yaml](apps/sh-api/src/main/resources/application.yaml) 的 `local` 配置段编辑 `sh.models.allowed` 列表和 `sh.models.default-model`。默认模型必须存在于列表中。
- 在 [Worker application.yaml](apps/sh-worker/src/main/resources/application.yaml) 配置模型供应商。Worker 使用 Token Plan 的 OpenAI 兼容地址，并从 `~/.config/super-harness/mysql.env` 加载的 `SH_TOKEN_PLAN_API_KEY` 或既有 `DASHSCOPE_API_KEY` 读取套餐密钥。
- 其他 OpenAI 兼容服务使用 `openai:<模型 ID>`、`OPENAI_API_KEY` 和 `sh.models.openai.base-url`（也支持原有 `SH_OPENAI_BASE_URL`）。DashScope 的普通 API 使用 `dashscope:<模型 ID>`。部署也可用原有 `SH_ALLOWED_MODELS` 替换列表，此时首项作为默认值，或用 `SH_DEFAULT_MODEL` 指定默认值。

改动源代码里的 YAML 后，运行 `./scripts/dev-down.sh && ./scripts/dev-up.sh --build` 并刷新聊天页。只更新环境文件时重启服务即可。

### 联网搜索

本地 Worker 为 `token-plan:qwen4-max` 和 `token-plan:deepseek-v4.1-flash-1017` 的 Chat Completions 请求发送 `enable_search: true`。模型根据问题决定是否检索。已有会话在 Worker 更新后即可使用。要调整名单，在 Worker 环境设置 `SH_WEB_SEARCH_MODELS`（逗号分隔的完整模型 ID，空字符串关闭），重启 Worker。运行记录的 `model.started` 事件中，`webSearchRequested` 表示本次发送了搜索参数；实际搜索次数与费用以供应商记录为准。[百炼联网搜索](https://docs.bailian.console.aliyun.com/zh/model-studio/web-search)

## 能力和入口

[17 项 SDK 与 Harness 能力逐项审计](docs/upstream/SDK-HARNESS.md)包含改动前状态、验证方法和功能边界。聊天权限菜单的 Plan 进入只读规划，模型可写入计划。查看保存的计划后，切换回执行模式并发送下一条消息。Agent 设置可以固定最多四个 Skill 版本、启用记忆；频道页提供 HTTP 消息格式和已绑定会话。

| 入口 | 行为 |
|---|---|
| Agent 配置 | 发布不可变版本，固定指令、模型、存储配置版本、Skill、MCP 与沙箱选项 |
| 任务 | 提交、查看状态、流式输出、事件重播、取消、恢复、产物下载、查看模型调用与 Token |
| 审批 | 查看文本预览、大小和动作摘要。服务端核验角色、有效期与摘要，重复决定复用同一回执 |
| 资源与租户 | 技能中心和知识库分别提供技能版本与个人记忆管理入口。成员、配额和审计使用 Java 租户 API |
| HTTP | 已认证的 `/v3/api-docs` 和 `/v3/api-docs.yaml` 提供 OpenAPI。平台任务事件流接受 `Last-Event-ID: runId:seq`。AG-UI 客户端使用协议 runId 重放 |

`SH_STORE_BACKEND` 决定 SDK 存储。默认 `jdbc` 使用独立连接池，也能用 `SH_RUNTIME_JDBC_URL`、`SH_RUNTIME_JDBC_USER`、`SH_RUNTIME_JDBC_PASSWORD` 指向 MySQL、PostgreSQL、SQLite 或 H2。其他后端有 `redis`、`mongodb`、`oss`、`cos`。OSS/COS 使用对象存储承担工作区与快照，由 JDBC 承担带版本状态和执行锁。API 与 Worker 的 `SH_STORE_PROFILE`、`SH_STORE_VERSION` 必须对应同一份后端配置。更换存储时发布新 profile 版本，并保留旧 Worker 直到旧会话退出。[存储兼容记录](docs/upstream/API-COMPATIBILITY.md)

Docker 沙箱为显式启用的版本能力。完成 Maven 构建后，从项目根目录构建镜像，然后用相同的 `SH_SANDBOX_IMAGE` 环境变量重启服务，再发布勾选沙箱的版本。

```bash
docker build -f deploy/sandbox/Dockerfile -t super-harness-sandbox:local .
./scripts/dev-down.sh
SH_SANDBOX_IMAGE=super-harness-sandbox:local ./scripts/dev-up.sh
```

## 验证与部署

```bash
./mvnw clean verify
./scripts/smoke.mjs
./scripts/acceptance.mjs --browser --load --restore
```

`smoke.mjs` 在目标服务创建测试 Agent、修订和会话，并提交两轮运行。`acceptance.mjs` 使用已构建的 jar、自有临时 MySQL、独立端口和真实 JVM，结束时清理。首次运行浏览器测试时，在 `web` 下执行 `npx playwright install chromium`。依赖合同、迁移、故障与发布结果见 [验收记录](docs/release-readiness.md)。部署配置、恢复及审计操作见 [运维手册](docs/operations.md)。项目维护入口见 [AGENTS.md](AGENTS.md)。最初的方案可查阅[历史实施计划](https://github.com/JasonYZzz/super-harness/blob/b771adf78d50286f247935cd3cf59d24374a5e64/super-harness_codex_研发启动包/docs/IMPLEMENTATION_PLAN.md)。

Web UI 的上游组件、流式协议与复用实现见 [官方前端组件对照评审](docs/upstream/WEB-UI.md)。

项目采用 [MIT 许可证](LICENSE)。前端复用的 AgentScope 代码和资源保留其[上游许可声明](web/public/LICENSE-agentscope.txt)。
