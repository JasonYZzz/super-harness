# super-harness 研发启动包

本包是一套 **工程计划与 Codex 任务合同**，并非已经实现的生产代码。

- `docs/IMPLEMENTATION_PLAN.md`：完整研发技术实施方案（含源码证据、架构、API、核心 DDL、故障恢复、路线图、测试、验收）。
- `AGENTS.md`：Codex 必须遵守的工程约束。
- `.codex/TASKS.md`：第一阶段可直接分拆的研发任务（含验收、依赖与约束）。
- `docs/ADR-001-UPSTREAM-BASELINE.md`：技术基线、已验证的官方 API 与稳定版/主分支边界。

## 交给 Codex 的推荐方式

1. 新建空仓库 `super-harness`，解压本包放在仓库根目录。
2. 告诉 Codex：**先读 `AGENTS.md`、`docs/ADR-001-UPSTREAM-BASELINE.md`、`docs/IMPLEMENTATION_PLAN.md`，从 `.codex/TASKS.md` 的 `SH-001` 开始，完成一项并运行测试后再进入下一项。**
3. 首个交付 SH-001 只做 AgentScope v2.0.5 源码/SDK API 审计及最小 HelloHarness 测试工程；完整工程骨架归 SH-002。不得在没有真实库源码核验的情况下猜测 API。
4. 在 GitHub/本地 Java 环境验证上游稳定 tag 与 Maven 工件，最终以实际编译及测试结果为准。

## 本地开发数据库

本机复用已有的 唯一的 MySQL 容器 `mysql8`（MySQL 8.4.13 LTS）。宿主机连接 `localhost:3306`，平台使用 `super_harness`，AgentScope JDBC 使用 `super_harness_runtime`。两个开发库已创建。本机运行前执行 `source ~/.config/super-harness/mysql.env` 加载连接参数。容器内应用的主机名及 CI 数据库约定见 [实施方案 §17.1](docs/IMPLEMENTATION_PLAN.md)。

## 最重要的选择

- 平台默认关系数据库为 **MySQL 8.4 LTS / InnoDB**。Run、事件、审批和工具账本由同一 MySQL 写库提交。
- AgentScope 运行时默认使用独立的 JDBC/MySQL 数据源，可另选 PostgreSQL、H2、SQLite 或上游 Redis、MongoDB、OSS、COS 扩展。配置与验收范围见 [实施方案 §2.3](docs/IMPLEMENTATION_PLAN.md)。
- `AgentScope Java v2.0.5` 是生产开发基线，**不是** main 直接依赖。
- `AgentStateStore` 不等于任意时刻可恢复的 Durable Run；平台必须另行实现 Run/Events/Tool Ledger/Recovery。
- 客户端 SSE 断线不停止 Worker；Run 已提交事件可重播。
- 产生业务副作用的工具必须有业务幂等和账本；结果未知时禁止无条件重试。

## 设计评审结论（2026-11-21）

通过研发启动评审，可以进入 SH-001。Java 21 已验证 v2.0.5 工件和 Mock 模型两轮调用；MySQL 8.4.13 已验证核心 DDL、幂等约束、跨租户外键、同会话并发领取和事件序号计数器回滚。固定的 main 对照提交、本次实测范围及后续集成验收见 [ADR-001](docs/ADR-001-UPSTREAM-BASELINE.md)。
