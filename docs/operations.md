# 运维手册

## 配置与部署

平台使用同一个 MySQL/InnoDB 主库完成任务状态、事件序号和 outbox 的事务提交。SDK 状态库另配数据源。生产部署先创建两库，使用各自的应用账号或工作负载身份，预发布阶段先演练迁移。生产 API 从 `SH_OIDC_ISSUER` 发现签名密钥，验证 `SH_OIDC_AUDIENCE`、有效期、`sub` 和 `tenant_id`。SSO 管理员在平台库中建立租户和首位 OWNER，此后该 OWNER 可通过租户 API 管理成员。停用成员会撤销其后续 API 和事件读取权限。

```sql
START TRANSACTION;
INSERT INTO tenants(id) VALUES (?);
INSERT INTO memberships(tenant_id,user_id,role) VALUES (?,?,'OWNER');
COMMIT;
```

上面是带参数的初始化 SQL，两处租户参数应相同，用户参数是身份提供方的稳定 subject。OIDC 客户端和平台应用应使用同一受众设置。浏览器令牌保存在当前标签页的 sessionStorage 中，本地签名只在 local profile 启用。部署时将模型密钥与 MCP 凭据配置给 Worker。本地启动脚本会向子进程导出环境文件中的变量。模型价格使用 API 的 SH_MODEL_PRICING 环境变量配置，例如 `{"openai:example":{"currency":"USD","inputPerMillion":1,"outputPerMillion":2}}`。数值是每百万输入或输出 Token 的计价示例。发布版本时固定费率，运行追踪按供应商返回量计算费用；未配置费率或未返回用量时费用为空。每日 Token 配额使用数据库 UTC 日界线。模型调用前预留额度，有供应商回报时以其用量结算，调用结果未知时保留预留量。

先运行 `./mvnw clean verify` 打包，再分别为三个角色制作镜像。将标记替换为本次发布号和私有仓库地址。

```bash
for role in api worker reconciler; do
  docker build -f deploy/Dockerfile --build-arg ROLE="$role" -t "super-harness-$role:0.1.0" .
done
helm lint deploy/helm/super-harness
helm template harness deploy/helm/super-harness --namespace super-harness
```

Chart 默认暴露一个集群内 API Service，Worker、Reconciler 各自部署。为 `harness-api`、`harness-worker`、`harness-reconciler` 分别提供现有的 Kubernetes Secret，包括 `MYSQL_HOST`、`MYSQL_USER`、`MYSQL_PASSWORD`。生产 API 必须提供 OIDC issuer 和 audience。API 和 Worker 同时设置 `SH_S3_BUCKET`、`AWS_REGION` 及可访问同一私有桶的凭据，可设置 `SH_S3_ENDPOINT`。本机的文件产物目录适用于单机；多 Pod 使用同一对象存储。Worker 若使用独立 SDK 账号，另配 `SH_RUNTIME_JDBC_USER` 和 `SH_RUNTIME_JDBC_PASSWORD`。API、Worker 的 profile/version 和 MCP 白名单来自同一份部署配置。

| SDK 后端 | Worker 参数 |
|---|---|
| JDBC | `SH_RUNTIME_JDBC_URL`、`SH_RUNTIME_JDBC_USER`、`SH_RUNTIME_JDBC_PASSWORD` |
| SQLite | 上述 URL 指向单机文件，状态及工作区记录写入 SQLite；快照和执行锁使用单独的 `SH_COORDINATION_JDBC_URL/USER/PASSWORD`，默认连接运行时 MySQL |
| Redis | `SH_REDIS_URL`，可设 `SH_STORE_PREFIX` |
| MongoDB | `SH_MONGODB_URL`、`SH_MONGODB_DATABASE` |
| OSS | `SH_OSS_ENDPOINT`、`SH_OSS_BUCKET`、`SH_OSS_KEY_ID`、`SH_OSS_KEY_SECRET`，配置 JDBC 状态与锁 |
| COS | `SH_COS_REGION`、`SH_COS_BUCKET`、`SH_COS_KEY_ID`、`SH_COS_KEY_SECRET`，配置 JDBC 状态与锁 |

只读 MCP 注册使用 `SH_MCP_CATALOG`，例如以下测试配置。URL、工具名和认证变量引用由部署管理员填写。API 固定配置到版本，Worker 运行时检查租户、地址、版本和白名单是否仍匹配。服务迁移时使用新 profile ID 并保留旧配置。沙箱版本需要能运行受限 Docker 容器的专用 Worker；常规集群 Chart 适用于无沙箱任务。本机沙箱镜像没有网络，工作目录为独立有界 tmpfs，进程以 65532 运行。发布状态指针前先确认快照可恢复。

```json
[{"id":"catalog-v1","tenant":"example","version":"1","url":"https://tools.example.org/mcp","readOnlyTools":["lookup"],"bearerEnv":"SH_MCP_SECRET_CATALOG"}]
```

## 告警和调查

Prometheus 从 API 的 `/actuator/prometheus` 抓取指标，使用独立采集身份签发的令牌，scope 为 `metrics.read`。API 将携带此 scope 的令牌限定于指标采集，业务接口和 OpenAPI 对其返回 403。生产 OIDC 应将此 scope 仅分配给采集客户端。本地使用 `./scripts/dev-token.sh --metrics` 签发一小时有效的 `prometheus` 令牌，普通登录令牌用于业务访问。部署目录包含 Grafana dashboard 和告警规则。多个 API 副本都从同一主库读取状态，跨副本用 `max` 聚合。`SH_TRACING_ENABLED=true` 开启 OpenTelemetry，`SH_OTLP_TRACES_ENDPOINT` 指向接收 OTLP/HTTP 的 traces 端点。可从 `GET /api/v1/runs/{id}/trace` 查看队列等待、执行时间、尝试边界和供应商 Token；已采样的 HTTP 链路有 trace ID。采集和日志中避免存储提示词、令牌与产物正文。

- **队列堆积**。检查 Worker 的 profile、版本、可用容量和数据库连接。处于审批或恢复中的同一会话会继续持有会话门闩，处理首个阻塞任务后再观察队列。
- **Worker 丢失**。租约 60 秒，默认心跳及巡检间隔均为 5 秒。到期后 Reconciler 记录恢复事件并废弃旧 fence。检查事件和工具回执，通过任务详情恢复，无效旧 Worker 无法发布其状态槽。
- **待审批发文**。核对工具、参数摘要和内容。重复提交同一决定只产生一个回执。对状态未知的外部动作，先向原服务核对权威回执，保留证据，再决定人工处置。
- **API 切换**。聊天客户端向同一 AG-UI 地址重发未确认命令，或读取会话快照并观察活动任务。运维订阅者可携带最后确认的账本事件 ID 访问任一 API 副本。数据库轮询支持丢失通知后的追赶。中断浏览器请求不取消任务。
- **模型或 MCP 异常**。先核对模型白名单、密钥绑定、TLS、请求限额，再检查任务的尝试与审计记录。未知用量先计入预留。用户删除记忆后，新执行读取最新可见记录，已提交的事件和审计仍按保留策略处理。

## 备份、迁移、回滚

1. 在维护窗口停止接受写入，等待或暂停 Worker，记录平台库和 SDK 库的备份坐标、产物对象版本及检查点。生产启用 MySQL 全备和 binlog。使用不同存储服务时也需保全对应快照和版本。
2. 先在独立实例还原平台库、SDK 库、产物，再启动相同上游版本的服务。核对租户、记录数、水位、已批准产物 SHA-256，并用已有会话提交一轮任务。随附验收脚本的 `--restore` 在临时实例中执行这条流程。
3. MySQL DDL 可部分提交。迁移失败先停写，核对 `flyway_schema_history` 与实际表结构，再逐项核对失败版本已经生效的 DDL。保留已有数据，完成有证据的修复并验证后执行 Flyway repair/migrate。回归测试覆盖了 V2 中途失败且存在冲突旧表的情形。Flyway clean 已禁用。
4. 升级保持旧 profile 和 Worker 池直至旧任务排空。仅当旧版本兼容新 schema 时回滚应用镜像。涉及数据格式变化时恢复到独立实例，核验切换之后的增量，再切流。共享的本地 MySQL 容器及其他业务库不参与项目故障注入。
