# AgentScope Java 兼容性

Java 21，SDK 2.0.5。HelloHarnessTest 覆盖两轮上下文、事件类型、中断与硬取消。JDBC 契约测试覆盖状态读写和过期 CAS。快照及锁的构造与实际恢复、跨进程互斥分别验收。

| 类型 | v2.0.5 源码 |
|---|---|
| HarnessAgent | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java) |
| ReActAgent | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java) |
| RuntimeContext | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-core/src/main/java/io/agentscope/core/agent/RuntimeContext.java) |
| AgentRun | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-core/src/main/java/io/agentscope/core/agent/AgentRun.java) |
| AgentEvent | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-core/src/main/java/io/agentscope/core/event/AgentEvent.java) |
| AgentStateStore | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-core/src/main/java/io/agentscope/core/state/AgentStateStore.java) |
| DistributedStore | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/DistributedStore.java) |
| JdbcDistributedStore | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-extensions/agentscope-extensions-jdbc/src/main/java/io/agentscope/extensions/jdbc/JdbcDistributedStore.java) |
| CompactionConfig | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/CompactionConfig.java) |
| DockerFilesystemSpec | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/sandbox/impl/docker/DockerFilesystemSpec.java) |
| RemoteFilesystemSpec | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/filesystem/spec/RemoteFilesystemSpec.java) |
| RedisDistributedStore | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-extensions/agentscope-extensions-redis/src/main/java/io/agentscope/extensions/redis/RedisDistributedStore.java) |
| MongoDistributedStore | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-extensions/agentscope-extensions-mongodb/src/main/java/io/agentscope/extensions/mongodb/MongoDistributedStore.java) |
| CosDistributedStore | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-extensions/agentscope-extensions-cos/src/main/java/io/agentscope/extensions/cos/CosDistributedStore.java) |
| OssDistributedStore | [源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-extensions/agentscope-extensions-oss/src/main/java/io/agentscope/extensions/oss/OssDistributedStore.java) |

兼容矩阵与 main 的 Durable Turn 映射见 [ADR-001](../../super-harness_codex_研发启动包/docs/ADR-001-UPSTREAM-BASELINE.md)。

## 运行时存储验收

使用同一组断言分别检查版本化状态读写、旧版本 CAS 拒绝、工作区 KV、快照字节还原、释放执行锁后重新获取。平台会话并发门闩和过期写入另由 MySQL 上的并发、故障测试覆盖。

| 实际配置 | 状态 CAS | 工作区 KV | 快照 | 执行锁 |
|---|---|---|---|---|
| JDBC MySQL 8.4.13 / PostgreSQL 16 / H2 | 通过 | 通过 | 通过 | 释放和重获通过 |
| SQLite + JDBC 协调库 | SQLite 通过 | SQLite 通过 | 协调库通过 | 协调库释放和重获通过 |
| Redis 7 + Jedis 7.4.1 | 通过 | 通过 | 通过 | 释放和重获通过 |
| MongoDB 7 | 通过 | 通过 | 通过 | 释放和重获通过 |
| 阿里云 OSS / 腾讯云 COS + JDBC | JDBC 实现 | 官方扩展已接入 | 官方扩展已接入 | JDBC 实现 |

SQLite 的 Xerial JDBC 驱动不支持 SDK 快照上传使用的无长度 `setBinaryStream` 重载。SQLite 配置因此通过组合 DistributedStore 将快照和执行锁交给独立协调库，回归测试使用 H2 模拟该库。本机默认协调库为运行时 MySQL。Redis 使用与上游扩展一致的 Jedis 主版本。云账号验收需在目标桶验证上传、校验、恢复及权限。

Docker 沙箱使用 SDK 自带的容器内文件传输路径，在只读根文件系统中以容器 UID 写入工作区 tmpfs。回归测试通过真实文件工具写入标记，在同会话下一次执行中读回，并验证另一个会话读不到该标记。对外模型和 MCP 均经过本地协议服务端验收，SDK 和平台控制面之间只通过运行时 SPI 传递任务。
