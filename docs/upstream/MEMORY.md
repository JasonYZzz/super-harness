# AgentScope Java 2.0.5 的上下文压缩与工具结果卸载

以下行为依据 `v2.0.5`（`5df96ea7262e9d2e50953cdb1ac01061ff6e6002`）。会话摘要属于 `AgentState.context`。模型提取的长期事实、会话 JSONL 和超大工具结果各有自己的写入入口。

## 长对话摘要与事实提取

| 写入 | SDK 入口 | 控制方式 |
|---|---|---|
| 会话上下文中的滚动摘要 | `ConversationCompactor.compactIfNeeded` | 保留压缩。SDK 汇总旧摘要和待压缩前缀，保留最近消息，并避免切断工具调用与结果。有效且更短的摘要以 `__compaction_summary__` 消息进入上下文。 |
| 阈值触发的压缩前提取及会话归档 | `ConversationCompactor` 使用 `MemoryFlushManager` | 显式设定 `flushBeforeCompact(false)` 和 `offloadBeforeCompact(false)`。两个开关默认均为 true，动态解析阈值时 SDK 保留它们。 |
| 自动记忆提取及后台整合 | `MemoryFlushMiddleware`、`MemoryMaintenanceMiddleware` | `disableMemoryHooks()` 阻止注册这两个中间件。 |
| 每轮转录 | `TranscriptMiddleware` | `disableTranscript()` 阻止独立的会话转录写入。 |
| 模型显式调用记忆工具 | `HarnessAgent.Builder.disableMemoryTools()` | 单独决定是否允许显式调用；打开工具不会要求打开自动提取。 |

常规压缩默认在 50 条消息或达到 token 阈值时触发。SDK 根据模型报告的窗口计算 token 阈值和保留量。关闭压缩前归档后，生成的摘要省略全文文件位置。若摘要为空、不是有效文本或未缩短输入，SDK 可以仅返回工具结果裁剪等轻量处理结果。[压缩配置](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/CompactionConfig.java) · [摘要与轻量裁剪实现](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/ConversationCompactor.java) · [中间件注册](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java#L2483-L2558)

## 上下文溢出重试

`HarnessAgent.call` 在每次订阅的 Reactor Context 中放入一次恢复预算。模型明确报上下文过长且尚未产生局部输出时，内建中间件尝试紧急压缩。紧急分支重新构造 `CompactionConfig.builder().triggerMessages(1).build()`，因而恢复两个默认写入开关。传给 builder 的常规压缩配置不能覆盖这条分支。已中断的调用、缺少可压缩前缀或重试再度失败时，调用仍可失败。[紧急恢复及配置构造](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/CompactionMiddleware.java#L174-L311)

平台的流式入口 `prepareRun(...).stream()` 经 `streamEvents` 和 `wrappedStreamEvents` 执行。v2.0.5 仅在 `wrappedCall` 安装内建紧急恢复标志；上游测试专门断言流式入口传播溢出错误。这意味着给常规压缩配置两个 false 足以控制该入口的压缩写入，但流式溢出重试需要另行接入。[调用与流式包装](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java#L850-L851) · [恢复预算注入位置](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java#L946-L1006) · [上游回归用例](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/test/java/io/agentscope/harness/agent/HarnessAgentOverflowRecoveryTest.java#L453-L482)

平台通过 `TurnCompaction` 使用 SDK 三参数压缩中间件；常规和紧急摘要均关闭预提取和 JSONL。流式上下文溢出只允许一次紧急摘要并重试当前模型推理，已产生局部结果或中断后会传播错误。[运行时实现](../../modules/sh-runtime-agentscope/src/main/java/io/superharness/runtime/TurnCompaction.java)

## 大工具结果与跨执行槽读回

`ToolResultEvictionMiddleware` 默认在单次工具结果的文本拼接后超过 80,000 个 Java 字符时，写入工作区相对路径 `large_tool_results/<agent>/<toolCallId>-<hash>`。末尾哈希是 UTF-8 正文 SHA-256 的前 8 字节。写入成功后，中间件替换状态和本轮模型输入中的结果，保留首尾预览，各最多 2,000 字符。占位符直接提示调用 `read_file`，该工具默认排除在再卸载范围外。写入失败时 SDK 保留原结果继续推理。[配置默认值](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/ToolResultEvictionConfig.java) · [写入与读回提示](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/ToolResultEvictionMiddleware.java#L189-L300)

沙箱模式由已发布工作区快照传递正文。远程模式将 `large_tool_results/` 路由到执行槽的 SDK 分布式存储。占位路径包含写入槽，`read_file` 每次最多回读 4096 个字符。除本槽外，Worker 只允许读取同一租户、同一会话已成功发布的运行槽；文件名须符合 SDK 生成的格式。[只读工具](../../modules/sh-runtime-agentscope/src/main/java/io/superharness/runtime/ToolOutputReader.java) · [SDK 卸载实现](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/ToolResultEvictionMiddleware.java)

## 平台长期记忆

| 来源 | 召回范围 | 发布与保留 |
|---|---|---|
| 管理页笔记、聊天中显式保存 | 同一租户和用户下所有启用记忆的 Agent | 手工笔记立即可用；聊天事实成功发布后可用，同一用户重复保存相同聊天事实会去重。用户可删除。 |
| 自动摘要 | 同一租户、用户和 Agent | 每个来源会话维护一条摘要，当前原始用户消息与旧摘要交由已计量模型合并。更新后保留 90 天，查询实时排除到期项，Reconciler 每分钟清理一批。 |
| V12 及之前的工作区记忆 | 仅原会话 | 从上次成功槽读取，下一次成功发布时导入管理库，可逐条删除。历史运行槽仍遵守运行数据保留策略。 |

每轮最多预取四条，并作为临时推理输入提供。模型可通过 `memory_search` 按关键词搜索完整可见范围，通过 `session_search` 查当前会话已完成的问答。管理页查询为服务端搜索，每页 100 条。删除对后续领取的运行生效；已开始的运行可以继续使用领取时读到的内容，旧聊天、工具结果、审计和备份保留既有历史。

显式保存优先于该轮的自动归纳。真实模型启用记忆时，未显式保存的轮次最多追加一次计量的摘要请求，输入只包含当前用户原文和该来源会话旧摘要。模型返回无变化、格式无效、预算不足或超时时保留旧摘要。本地回显模型只用于显式记忆测试。工作槽先暂存变更，成功或审批通过时在更新会话状态槽的同一事务中发布；失败、取消、审批拒绝和失租回收均丢弃暂存数据。用户并发删除旧摘要后，基于旧记录的更新不会重建该摘要。

[平台记忆存储](../../modules/sh-persistence/src/main/java/io/superharness/persistence/Knowledge.java) · [运行时记忆工具](../../modules/sh-runtime-agentscope/src/main/java/io/superharness/runtime/PersonalMemoryTools.java) · [官方长期记忆机制](https://java.agentscope.io/v2/zh/docs/harness/memory)
