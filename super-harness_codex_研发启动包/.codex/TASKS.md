# Codex 任务清单：逐项执行，不要一次性生成整个项目

## SH-001: AgentScope v2.0.5 API compatibility spike
**Depends on:** None.  
**Deliver:** `docs/upstream/API-COMPATIBILITY.md` and a HelloHarness integration test.  
**Steps:** Read pinned tag; confirm `HarnessAgent`, `RuntimeContext`, `AgentRun`, `streamEvents`, `JdbcDistributedStore`, the registered JDBC dialects and optional non-JDBC providers, `CompactionConfig`, Docker/Remote Filesystem and key class paths. Create a small Java 21/Maven project that compiles against v2.0.5. Test second turn remembers the first, streaming emits typed events, and interrupt behaves as documented.  
**Acceptance:** `mvn test` passes locally; every class/path is cited to pinned source; required tests run without provider credentials. Distinguish interrupt before subscription, cooperative interrupt during execution, and hard cancellation. Exercise the MySQL runtime profile and an alternative JDBC backend against the same store tests. Record state CAS, workspace, snapshots and guard results separately for each enabled profile. Record the pinned main Durable comparison from ADR-001.

## SH-002: Monorepo and architecture rules
**Depends on:** SH-001.  
**Deliver:** root POM, `sh-domain`, `sh-contract`, `sh-runtime-spi`, `sh-runtime-agentscope`, `sh-persistence`, `sh-run-engine`, API/Worker/Reconciler apps, CI.  
**Acceptance:** clean build; ArchUnit rejects `io.agentscope.*` leaking into domain/contract; all versions pinned. Local dev-up uses the existing MySQL instance and the two databases in plan §17.1. It resolves environment settings and checks connectivity without provisioning a MySQL container.

## SH-003: MySQL 8.4 / InnoDB schema & Flyway
**Depends on:** SH-002.  
**Deliver:** Run/Events/ToolExecutions/Approvals + AgentRevision tables, tenant filters, CAS helpers, MySQL Connector/J, flyway-mysql and MySQL 8.4.13 Testcontainers matching the local baseline. Keep platform and SDK migrations/credentials separate.  
**Acceptance:** test clean installation, upgrade and forward repair after partially applied MySQL DDL; test same-caller replay and changed-payload conflict, distinct callers reusing a key, tenant-qualified foreign keys, case-sensitive idempotency keys and mandatory tenant predicates under pooled-connection reuse, fenced CAS and gap-free event sequence after rollback.

## SH-004: Identity & AgentRevision API
**Depends on:** SH-002, SH-003.  
**Deliver:** OIDC/JWT, trusted principal context, AgentDefinition/Revision CRUD, OpenAPI.  
**Acceptance:** cross tenant access denied; published revision immutable, including runtime store profile ID and version.

## SH-005: Run Create API and Worker Claim
**Depends on:** SH-003, SH-004.  
**Deliver:** `POST /runs`, `GET /runs/{id}`, idempotent creation, queue Claim via SKIP LOCKED, per conversation session gate.  
**Acceptance:** duplicate POST maps to the authorized original runId. Two DB connections racing to claim different runs in one conversation admit exactly one. An unrelated conversation can still proceed. A held conversation gate blocks later runs through approval and recovery until terminal commit. Exercise rollback after lock timeout/deadlock and confirm the lease on the primary after an ambiguous commit.

## SH-006: AgentScope adapter & durable SSE events
**Depends on:** SH-001, SH-005.  
**Deliver:** `AgentRuntimeAdapter`, `EventNormalizer`, transactional EventAppender, `GET /runs/{id}/events` using `Last-Event-ID`.  
**Acceptance:** disconnect/reconnect with mixed API instances yields contiguous seq; no event pushed before commit. Check rollback of the event, per-run sequence counter and outbox in one MySQL transaction. Drop the final notification and verify replay reaches the terminal event through DB polling.

## SH-007: Lease, heartbeat, cancel, recovery coordinator
**Depends on:** SH-006.  
**Deliver:** fencing-aware transitions, graceful drain, reaper and recovery classification.  
**Acceptance:** process kill, stale worker writes and API rolling restart chaos tests pass. Reject expired renewals even before reassignment. Delay the old SDK state save until before and after the new worker saves. The next turn must load only the snapshot published by a valid owner.

## SH-008: Tool ledger & HITL
**Depends on:** SH-007.  
**Deliver:** Tool Manifest, policy checks, side-effect receipts, approval API + audit.  
**Acceptance:** side effect repeated delivery/retry never silently duplicates; approval after crash retains actor, version and arguments hash. Compensation alone never authorizes automatic replay. Demonstrate a persisted continuation that consumes the approved action exactly once, or record RECOVERY_REQUIRED.

## SH-009: Workspace/Sandbox/Artifact
**Depends on:** SH-008.  
**Deliver:** isolated filesystem backend, sandbox template and runtime image conformance, artifact delivery.  
**Acceptance:** cross-tenant file and network boundary tests; workspace restore test succeeds or clearly reports unrecoverable state.

## SH-010: Skills/MCP/Memory/Models
**Depends on:** SH-006, SH-008, SH-009.  
**Deliver:** registries, allowlist/policy, compaction and memory policies, provider routing/budget.  
**Acceptance:** skill version fixed at run submission; model/tool quotas enforced server-side; memory ACL.

## SH-011: Web console + operations
**Depends on:** SH-004, SH-006, SH-008, SH-009.  
**Deliver:** Web chat/reconnect, task timeline, approval, agent configs, artifacts, traces; Prometheus/Grafana.  
**Acceptance:** E2E UI test can kill SSE connection and reconstruct events; task status accurate.

## SH-012: Release readiness
**Depends on:** SH-007 to SH-011.  
**Deliver:** Helm, security scans, chaos/perf report, migration/runbooks, upstream upgrade matrix, Pilot checklist.  
**Acceptance:** the technical lead explicitly approves pilot after test evidence; no auto-approval solely from code compilation.
