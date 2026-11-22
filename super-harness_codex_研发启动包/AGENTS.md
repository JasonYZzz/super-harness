# super-harness | Agent Engineering Instructions

## Mandatory references

Read `docs/IMPLEMENTATION_PLAN.md`, `docs/ADR-001-UPSTREAM-BASELINE.md`, and `.codex/TASKS.md` before implementing changes. Fixed upstream baseline: https://github.com/agentscope-ai/agentscope-java/tree/v2.0.5.

## Architecture

- Java 21, Maven 3.9+; Spring Boot app roles: `api`, `worker`, `reconciler`.
- Platform/domain/API/events must not depend on `io.agentscope.*`; use `sh-runtime-spi`.
- Use AgentScope Java HarnessAgent + ReActAgent and its official extension APIs; do not rewrite Agent Loop or assume classes from main are in v2.0.5.
- Keep immutable agent revisions; old runs never silently use newly published agent settings.
- Persist user-acknowledged runs and events in MySQL 8.4 LTS / InnoDB. Use one primary connection and transaction for each state change, event and outbox write.
- For real local development, reuse localhost:3306 (one shared mysql8 container, MySQL 8.4.13 LTS) with super_harness and super_harness_runtime. Read credentials from the local environment. Use isolated databases for CI and fault injection.
- Configure AgentScope runtime storage separately through DistributedStore. Default to JdbcDistributedStore with a separate MySQL DataSource. Pin the runtime store profile and version for recovery. Keep vendor wiring in sh-runtime-agentscope.
- For every run: per-conversation serialization, lease TTL + heartbeat, fencing, and CAS checks. Keep the conversation gate through approval, pause and recovery. Fence publication of immutable state/workspace snapshots before another attempt can read them.
- Commit business events, their state transition and outbox record together. Publish notifications after commit and replay events by `runId:seq`; SSE clients may disconnect without cancelling runs.
- Tool side effects require idempotency/receipt verification, or manual reconciliation if outcome is unknown.
- Use sandbox for untrusted code; never expose host shell to multi-tenant users.
- Enforce tenant scope from verified identity at every boundary; client-provided tenantId is untrusted. Scope business queries and joins by tenant, enforce composite foreign keys, and test pooled-connection reuse across tenants.
- Implement OIDC/RBAC/ABAC, model/tool quotas, audit, and secrets management. No credentials in Prompt, workspace, or repository.

## Delivery policy

- Implement one task at a time. For each task create implementation, JUnit/Testcontainers tests, docs, and clear error handling.
- First verify upstream class/package/method with v2.0.5 code; do not invent SDK methods.
- For each Pull Request provide: scope/non-goals, upstream citations, API/schema changes, security impact, tests, migration and rollback plan.
- Always run the relevant targeted tests and report failure details instead of assuming success.
- Never claim exact model-token continuation after crash; classify recovery safety at checkpoints.
