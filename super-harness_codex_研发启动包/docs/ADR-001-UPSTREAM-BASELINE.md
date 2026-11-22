# ADR-001: Use AgentScope Java 2.0.5 as the production SDK baseline

**Status:** Accepted for engineering bootstrap. Artifact/API smoke checked on Java 21. Distributed operation remains subject to SH-001 and SH-007.  
**Date:** 2026-11-21.

## Context

The production tag [v2.0.5](https://github.com/agentscope-ai/agentscope-java/tree/5df96ea7262e9d2e50953cdb1ac01061ff6e6002) resolves to `5df96ea7262e9d2e50953cdb1ac01061ff6e6002`. Its source tree has no `agentscope-durable` module. The reviewed [main snapshot](https://github.com/agentscope-ai/agentscope-java/tree/21be74a8a3d584c54531ee04a58e91831c2d57f6) resolves to `21be74a8a3d584c54531ee04a58e91831c2d57f6`.

| Platform contract | Equivalent in the main snapshot | Integration decision |
|---|---|---|
| Conversation / user-visible Run / attempt | Thread / Turn / native Run | Preserve platform IDs across retries. Map a platform Run to a Durable Turn when evaluating migration. |
| Claim, lease, checkpoint, SSE replay | DurableRuntime and SessionBackend | Pilot a single backend with the existing recovery and cursor tests before changing ownership of writes. |
| Tool execution and receipt | Executor, optionally an independent Hands worker | Verify uncertain external outcomes with T-08/T-10. Keep tenant policy and authorization in the platform. |

The upstream README describes Durable as a development source API. Its integrated HTTP/SSE and restart examples provide concrete comparison cases for SH-001 and future upgrades. [Durable contracts](https://github.com/agentscope-ai/agentscope-java/blob/21be74a8a3d584c54531ee04a58e91831c2d57f6/agentscope-durable/README.md) · [Examples](https://github.com/agentscope-ai/agentscope-java/tree/21be74a8a3d584c54531ee04a58e91831c2d57f6/agentscope-examples/durable)

## Decision

- Integrate published `io.agentscope:agentscope-harness:2.0.5` and the exact same-version provider/store extensions.
- Verify code against pinned GitHub tag and actual Maven artifacts.
- Use `HarnessAgent` with `RuntimeContext.userId/sessionId`, `streamEvents(...)`, `prepareRun(...)` and `DistributedStore` through an isolating runtime SPI.
- Build own durable run registry, append-only event store and safe recovery manager because v2.0.5 `AgentStateStore` is primarily persisted at call boundaries.
- Follow upstream new event-log/durable work and reevaluate when stable and tested.

## Validated source links

- [v2.0.5 HarnessAgent](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java)
- [v2.0.5 RuntimeContext](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-core/src/main/java/io/agentscope/core/agent/RuntimeContext.java)
- [v2.0.5 DistributedStore](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-harness/src/main/java/io/agentscope/harness/agent/DistributedStore.java)
- [v2.0.5 Quickstart](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/docs/v2/zh/docs/quickstart.md)
- [v2.0.5 Production](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/docs/v2/zh/docs/others/going-to-production.md)
- [v2.0.5 Release](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.5)

## Review evidence (2026-11-21)

- Resolved the Harness, Redis and DashScope 2.0.5 dependencies from Maven Central and compiled the original Redis wiring with Java 21. The Redis and DashScope wiring was compile-checked.
- With a deterministic model, explicit in-memory state store and memory hooks disabled, two calls in one session produced typed AgentResultEvent instances. The second model input contained the first user message. Interrupting a prepared handle before subscription produced CANCELLED. Running-tool interruption, Redis failover and cross-JVM state publication require integration tests.
- During the earlier PostgreSQL design review, executed that revision’s DDL and claim statement on PostgreSQL 16.16. Two connections contended for different queued runs in one conversation. Exactly one claimed a run, and releasing its gate in the terminal transaction admitted the other. Also checked same-caller duplicate keys, independent callers reusing a key, and rejection of an event referencing a run in another tenant. This is historical evidence for the previous platform design.
- The default SDK conflict policy permits overwriting a concurrent update. Select FAIL, isolate writable state per attempt and fence publication as specified in §7.3. [ConflictPolicy](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-core/src/main/java/io/agentscope/core/state/ConflictPolicy.java) · [ReActAgent](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.5/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java)

## Revised storage decision (2026-11-21)

MySQL 8.4 LTS / InnoDB is the default platform ledger. The runtime uses a separately configured AgentScope 2.0.5 DistributedStore, with JDBC/MySQL as the default. PostgreSQL remains an optional runtime backend. The control-plane schema and scheduling transaction target MySQL. See implementation plan §§2.3, 5.3 and 7.2.

- Ran the revised DDL and claim transaction on MySQL 8.4.13 and 8.0.45. Separate connections selected two runs in the same conversation. Only one acquired it. Another conversation could proceed, and releasing the gate admitted the waiting run. Duplicate-key rejection, case-sensitive caller/key scope, cross-tenant FK rejection and rollback of the sequence counter also passed.
- Verified the published JDBC extension on Java 21 with MySQL 8.4.13, PostgreSQL 16.14, H2 2.3.232 and SQLite (Xerial 3.47.1.0). Across independently constructed stores, the versioned state write/read round trip succeeded and a stale write was rejected. Capability tests for each selected production profile include cross-JVM recovery, workspace routing, snapshot restore and concurrent sandbox locking. Flyway upgrade, deadlock retry, primary failover and full outbox rollback are application acceptance tests.

On 2026-11-21 the existing localhost:3306 instance was upgraded from 8.0.45 to 8.4.13 LTS. The shared Compose deployment owns one mysql8 container and its original mysql_mysql_data volume. The platform database (super_harness) and the SDK database (super_harness_runtime) use separate schemas on this instance. Local and CI now target the same patch.

A cold backup was restored to an isolated volume and upgraded before cutover. Schema names, row counts and extended checksums for all 126 user tables (17,041 rows), and the six account records compared by the upgrade check matched the original snapshot both in rehearsal and on the final instance. After cutover both project schemas were opened through the configured localhost JDBC URLs, and a temporary-table write/read probe passed.

## Consequences

- Avoid breaking upgrade friction from relying on main snapshots.
- Build enterprise reliability around authoritative MySQL task and event records.
- Accept that arbitrary mid-LLM-stream token-perfect resume is not achievable with just Redis AgentState; handle partial output and safe re-generation explicitly.
