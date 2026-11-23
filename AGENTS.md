# Super Harness 维护指南

本仓库是 Java 21 的 Maven 多模块项目。运行时基线为 AgentScope Java 2.0.5，Web 控制台使用 React、TypeScript 和 npm，由 API 服务同源提供。开发环境和启动命令见 [README](README.md)，依赖版本见 [pom.xml](pom.xml) 与 [前端依赖清单](web/package.json)。

## 从改动找到入口

| 改动 | 入口及关联方 |
|---|---|
| HTTP、鉴权与聊天协议 | `apps/sh-api` 提供 Spring MVC 接口。`modules/sh-contract` 定义 AG-UI 入参。`web/src/hooks/useAguiThread.ts` 驱动聊天客户端。联动核对 [协议](docs/ag-ui.md) 和 [OpenAPI 快照](docs/api/openapi.json)。 |
| 运行、审批与恢复 | `modules/sh-run-engine` 处理提交、领取和状态转换；`apps/sh-worker` 执行任务，`apps/sh-reconciler` 处理过期租约等恢复事项。 |
| 平台数据与权限 | `modules/sh-domain` 定义领域类型；`modules/sh-persistence` 持有 SQL、事务和 Flyway 迁移。平台库与 SDK 运行时库独立配置。 |
| 模型、工具、Skill 与存储 | 通过 `modules/sh-runtime-spi` 传递运行参数；`modules/sh-runtime-agentscope` 对接官方实现。核对当前版本的 [SDK 接口](docs/upstream/API-COMPATIBILITY.md) 和 [Harness 能力](docs/upstream/SDK-HARNESS.md)。 |

## 实现约定

- 优先复用锁定版本的 SDK、Spring Security 和官方 AG-UI 客户端。领域类型和公共协议保持独立于 AgentScope，模块的 `BoundaryTest` 检查此约束。
- 会话绑定已发布的 Agent 修订，运行和重试使用已固定的配置与 Skill。改动调度或恢复时，保留会话串行、租约 fencing、执行尝试隔离和事件重播语义。状态转换、业务事件及 outbox 写入在同一事务提交。
- 从已验证的 JWT 取得租户和主体，结合数据库中的有效成员角色及资源归属授权。监控 scope、租户角色和会话工具模式分别在各自边界检查。
- 平台表结构通过 `modules/sh-persistence/src/main/resources/db/migration` 新增版本化迁移。涉及 REST 契约时，从运行服务的 `/v3/api-docs` 核对快照，同时检查控制台和验收脚本的调用。

## 验证入口

- Java 与集成测试在根目录运行 `./mvnw clean verify`。MySQL Testcontainers 测试需要可用的 Docker。Maven 构建 API 时也会编译并打包前端。
- 前端独立验证在 `web/` 执行 `npm ci`、`npm run lint` 和 `npm run build`。浏览器用例依赖运行中的服务，环境约定见 [前端 README](web/README.md)。
- 在已构建 jar 的基础上，`./scripts/acceptance.mjs --browser --load --restore` 使用临时数据库和端口验证跨服务行为。CI 的沙箱镜像、Playwright、Helm 及扫描步骤见 [验证工作流](.github/workflows/ci.yml)。
- `./scripts/smoke.mjs` 会向目标服务写入测试 Agent、修订、会话和运行。日常启动和部署环境的区别见 [运维手册](docs/operations.md)。
