# Bitbucket Helper V1 Core and Persistence Parallel Implementation Plan

> **For the Core workstream orchestrator:** Run this complete plan in its own top-level Codex session, separate from the other three workstreams and from assembly. REQUIRED SUB-SKILLS: Use superpowers:subagent-driven-development to orchestrate task-level implementer/reviewer agents, superpowers:test-driven-development for every behavioral task, and superpowers:verification-before-completion before handing off the branch. Work only in codex/v1-core-persistence. Check off every step and commit after every task.

**Goal:** Implement the complete framework-independent domain, application behavior, and durable SQLite/jOOQ state behind the frozen v1 contracts.

**Architecture:** Domain aggregates own triage transitions. Application services coordinate a frozen Bitbucket gateway, transaction/store ports, notification-intent policy, and post-commit dispatcher. Memory and jOOQ adapters satisfy one behavior contract. This branch never owns HTTP, generated clients, CLI, notification processes, Quartz, or production composition.

**Tech Stack:** Kotlin 2.4.10, coroutines 1.11.0, Liquibase 5.0.3 XML, jOOQ 3.21.6, SQLite JDBC 3.53.1.0, JUnit 6.1.3, and the shared B0 application contracts.

## Preconditions

- Start from the exact B0 SHA recorded by docs/superpowers/plans/2026-08-15-bitbucket-helper-v1-parallel-assembly.md.
- Work on branch codex/v1-core-persistence in .worktrees/v1-core-persistence.
- Run ./gradlew clean check verifyApiV1Generated before changing a file.
- Treat every B0 domain/shared, application/model, application/port, openapi, and generated file as read-only.
- If a frozen type is insufficient, stop and send the baseline maintainer the failing test and smallest requested contract change.

## Dedicated Session Contract

- This plan is the sole implementation brief for the Core session orchestrator. Do not run another companion plan in this session.
- No other session may use this worktree or write to codex/v1-core-persistence.
- Task-level subagents are allowed inside this session, but the Core orchestrator owns sequencing, review, commits, and final evidence.
- Do not depend on messages or uncommitted files from another execution session. B0 and its approved documents are the only shared input.
- A B0 defect is a stop condition: leave the branch unchanged beyond the focused failing test, report the requested contract change, and wait for a new baseline SHA.
- The final handoff must include the full B0 SHA, branch HEAD SHA, task commit list, git diff --name-status B0..HEAD, clean git status, every verification command with result, and unresolved risks.
- After handoff, do not rebase, merge, or add commits unless the assembly orchestrator explicitly returns a defect to this session.

Before Task 1, the Core orchestrator runs:

~~~bash
git branch --show-current
git rev-parse HEAD
git status --short
./gradlew clean check verifyApiV1Generated
~~~

Expected: branch codex/v1-core-persistence, HEAD exactly B0, clean status, passing baseline gate.

## Exclusive Ownership

This branch may create or modify only:

- src/main/kotlin/com/mindtable/bitbuckethelper/domain/configuration/**
- src/main/kotlin/com/mindtable/bitbuckethelper/domain/pullrequest/**
- src/main/kotlin/com/mindtable/bitbuckethelper/domain/actionitem/**
- the named files under application/policy and application/service in this plan
- src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/**
- src/main/resources/db/migration/V0002__create_triage_state.xml
- src/main/resources/db/migration/V0003__add_notification_delivery_lease.xml
- corresponding tests under domain/**, application/contract/**, application/policy/**, application/service/**, and adapter/outbound/persistence/**
- src/test/kotlin/com/mindtable/bitbuckethelper/CoreApplicationAcceptanceTest.kt

Do not edit build.gradle.kts, openapi/**, generated code, Bitbucket/HTTP/CLI/notification/scheduler adapters, bootstrap/**, application.conf, ArchitectureTest.kt, WalkingSkeletonEndToEndTest.kt, README.md, docs/project-backlog.md, or source/.

## Global Constraints

- Only an authoritative successful open-PR list can deactivate a missing PR. Upstream failure preserves last-known-good state.
- Same-repository refreshes share one process-local flight; different repositories may refresh concurrently.
- Commit domain state, synchronization state, and notification intents atomically before calling PostCommitNotificationDispatcher.
- Acknowledgment is exact-version, idempotent for the acknowledged version, and typed for stale/missing/closed outcomes.
- Persist no raw activity body. Live-content reads go directly through BitbucketGateway.
- Never edit V0001. V0002 owns triage state; V0003 owns notification delivery leases.
- Keep generated jOOQ Kotlin below build/generated/sources and out of Git.
- Both memory and jOOQ implementations must pass the same transaction and store contract.
- Each task ends with its focused tests, git diff --check, an owned-path audit, and one commit.

## Original-Plan Coverage

This branch completes original Tasks 1-8 and 10-15 except for B0-owned contracts, Task 11's notification policy implementation, and the production Bitbucket adapter. It also completes the persistence/lease slice of Task 28 and the application health-service slice of Task 22.

---

### Task 1: Model Installation Configuration

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/domain/configuration/InstallationConfiguration.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/domain/configuration/InstallationConfigurationTest.kt

**Interfaces:**
- Consumes: B0 WorkspaceId and RepositoryId.
- Produces: WorkspaceIdentity, ConfiguredRepository, InstallationConfiguration, and typed identity/collision failures.

- [ ] **Step 1: Write failing examples**

Cover normalized HTTPS API base URLs; immutable workspace identity; idempotent repository add; stable-ID remove/re-add; slug collision; and rejection of credentials, query, fragment, insecure production URLs, and identity replacement.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*InstallationConfigurationTest'
~~~

Expected: compilation fails because the configuration aggregate is absent.

- [ ] **Step 3: Implement immutable behavior**

Normalize scheme/host, retain the /2.0 path, strip redundant trailing slash, and expose an explicit test-only HTTP factory. Removal records removedAt and preserves retained identity. Re-add clears removedAt only for the same stable repository.

- [ ] **Step 4: Run GREEN**

~~~bash
./gradlew test --tests '*InstallationConfigurationTest'
git diff --check
~~~

- [ ] **Step 5: Commit**

~~~text
feat: model installation configuration
~~~

---

### Task 2: Implement the Fixed Seven-Check Readiness Policy

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/domain/pullrequest/Readiness.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/domain/pullrequest/ReadinessTest.kt

**Interfaces:**
- Consumes: B0 gateway fact values.
- Produces: BuildStatus, ReadinessCheckName, ReadinessCheck, ReadinessAssessment, and SevenCheckReadiness.assess.

- [ ] **Step 1: Write the failing table**

Cover the exact seven checks and a fixed denominator: approvals, unresolved tasks, unresolved comments, branch freshness, merge conflicts, successful builds, and required reviewer state. Include no-build, mixed-build, unknown, boundary, and null-upstream cases.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*ReadinessTest'
~~~

- [ ] **Step 3: Implement one pure assessment**

Return all seven named checks in stable order with passed count, total seven, and explicit safe failure reasons. Do not introduce adapter/generated types.

- [ ] **Step 4: Run GREEN**

~~~bash
./gradlew test --tests '*ReadinessTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: evaluate pull request readiness
~~~

---

### Task 3: Model Pull-Request Observation and Lifecycle

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/domain/pullrequest/PullRequest.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/domain/pullrequest/PullRequestEvents.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/domain/pullrequest/PullRequestTest.kt

**Interfaces:**
- Consumes: B0 IDs and Task 2 ReadinessAssessment.
- Produces: PullRequestObservation, PullRequest, PullRequestTransition, ReadinessChanged, BuildsBecameGreen, PullRequestDeactivated, and PullRequestReactivated.

- [ ] **Step 1: Write failing transition tests**

Cover first observation, unchanged replay, metadata change, readiness change, all-success build edge transition, inactive/reactivated lifecycle, monotonic observedAt, and rejection of a changed stable identity.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*PullRequestTest'
~~~

- [ ] **Step 3: Implement deterministic transitions**

Separate current state from emitted transition facts. Emit BuildsBecameGreen only on a false-to-true edge, never on replay. Deactivation accepts an authoritative list timestamp and retains last-known metadata.

- [ ] **Step 4: Run GREEN**

~~~bash
./gradlew test --tests '*PullRequestTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: model pull request evolution
~~~

---

### Task 4: Model Action Items and Exact-Version Acknowledgment

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/domain/actionitem/ActionItem.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/domain/actionitem/ActionItemEvents.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/domain/actionitem/ActionItemTest.kt

**Interfaces:**
- Consumes: B0 PullRequestId, ActionItemId, and ActivityVersion.
- Produces: ActionSourceKind, ActionObservation, ActionItem transitions, and exhaustive AcknowledgmentResult.

- [ ] **Step 1: Write failing evolution tests**

Cover open, unchanged replay, version advance, acknowledge current version, acknowledge same version twice, stale acknowledgment, close, reopen, source-kind identity, and observations arriving out of order.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*ActionItemTest'
~~~

- [ ] **Step 3: Implement the aggregate**

Derive stable action identity without raw bodies. Store only safe author/time/version/link metadata. Return typed results for acknowledged, already acknowledged, stale version, not actionable, and missing at the service boundary.

- [ ] **Step 4: Run GREEN**

~~~bash
./gradlew test --tests '*ActionItemTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: model exact version action items
~~~

---

### Task 5: Define One Persistence Behavior Contract and Memory Adapter

**Files:**
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/application/contract/ApplicationPersistenceContract.kt
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/application/contract/PersistenceContractFixtures.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/memory/InMemoryApplicationPersistence.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/memory/InMemoryApplicationPersistenceTest.kt

**Interfaces:**
- Consumes: B0 ApplicationTransactionRunner and store interfaces.
- Produces: reusable atomicity/store semantics and a reference implementation.

- [ ] **Step 1: Write the failing reusable contract**

Require commit/rollback atomicity across configuration, PRs, action items, synchronization, notification intents, attempts, and leases; stable ordering; idempotent upsert; authoritative-deactivation support; compare-and-set acknowledgment; lease claim/release/expiry; and no state visibility before commit.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*InMemoryApplicationPersistenceTest'
~~~

- [ ] **Step 3: Implement copy-on-write transactions**

Serialize commits, isolate transaction state, publish one snapshot only on success, and make close idempotent. Implement the frozen lease-aware NotificationIntentStore without adding new public methods.

- [ ] **Step 4: Run GREEN and a concurrency repeat**

~~~bash
./gradlew test --tests '*InMemoryApplicationPersistenceTest'
./gradlew test --tests '*InMemoryApplicationPersistenceTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: add reference application persistence
~~~

---

### Task 6: Implement V0002/V0003 and jOOQ Parity

**Files:**
- Create: src/main/resources/db/migration/V0002__create_triage_state.xml
- Create: src/main/resources/db/migration/V0003__add_notification_delivery_lease.xml
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqApplicationPersistence.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqRecordMappings.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqApplicationPersistenceTest.kt
- Modify: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/JooqGenerationSmokeTest.kt
- Modify: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/persistence/SqliteDatabaseTest.kt

**Interfaces:**
- Consumes: Task 5 contract and existing SqliteDatabase/jOOQ generation.
- Produces: complete durable v1 state with lease-aware notification delivery.

- [ ] **Step 1: Run the contract against a missing adapter**

~~~bash
./gradlew test --tests '*JooqApplicationPersistenceTest'
~~~

Expected: compilation or behavior failure.

- [ ] **Step 2: Add forward and rollback migration tests**

V0002 creates installation, configured repository, PR, action-item, synchronization, notification-intent, and notification-attempt state with keys/indexes. V0003 adds two-minute lease owner/expiry state and claim indexes. Verify V0001 remains unchanged and each rollback restores the prior schema.

- [ ] **Step 3: Implement jOOQ transactions and mappings**

Use one JDBC transaction per ApplicationTransactionRunner call. Preserve Instants, nullable values, enum wire names, deterministic ordering, atomic acknowledgment, and lease compare-and-set behavior.

- [ ] **Step 4: Run parity and generation**

~~~bash
./gradlew test --tests '*JooqApplicationPersistenceTest' --tests '*JooqGenerationSmokeTest' --tests '*SqliteDatabaseTest'
./gradlew validateMigrationNames jooqCodegen
~~~

- [ ] **Step 5: Commit**

~~~text
feat: persist complete v1 triage state
~~~

---

### Task 7: Implement Configuration and Repository Refresh

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/WorkspaceConfigurationServices.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshRepositoryService.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/ObservationAssembler.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/ActionObservationAssembler.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/service/WorkspaceConfigurationServicesTest.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshRepositoryServiceTest.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshRepositoryActionItemsTest.kt

**Interfaces:**
- Consumes: B0 configuration/refresh ports, BitbucketGateway, NotificationIntentPolicy, PostCommitNotificationDispatcher, and Task 5 transaction runner.
- Produces: workspace/repository configuration and one complete atomic repository refresh.

- [ ] **Step 1: Write failing configuration tests**

Cover current-user/workspace/repository resolution, immutable installation identity, add/remove/re-add, upstream typed failures, and zero state change on failure.

- [ ] **Step 2: Write failing refresh tests**

Cover pagination completion, fixed readiness, PR/action upserts, initial digest, actionable activity, builds-became-green intents, authoritative deactivation only after a successful full open-list, partial detail failure, and last-known-good preservation.

- [ ] **Step 3: Run RED**

~~~bash
./gradlew test --tests '*WorkspaceConfigurationServicesTest' --tests '*RefreshRepositoryServiceTest' --tests '*RefreshRepositoryActionItemsTest'
~~~

- [ ] **Step 4: Implement transaction and post-commit ordering**

Map gateway records in assemblers, complete all required pages, write domain state/synchronization/intents in one transaction, commit, and only then invoke PostCommitNotificationDispatcher. A dispatch failure never rolls back committed domain state.

- [ ] **Step 5: Run GREEN and commit**

~~~bash
./gradlew test --tests '*WorkspaceConfigurationServicesTest' --tests '*RefreshRepositoryServiceTest' --tests '*RefreshRepositoryActionItemsTest'
git diff --check
~~~

Commit:

~~~text
feat: synchronize configured repositories
~~~

---

### Task 8: Add Single-Flight, Backoff, and Refresh-All

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/policy/SynchronizationBackoff.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RepositoryRefreshCoordinator.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshAllRepositoriesService.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/policy/SynchronizationBackoffTest.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/service/RepositoryRefreshCoordinatorTest.kt

**Interfaces:**
- Consumes: B0 refresh ports, RefreshRepositoryService, configured repositories, and persisted synchronization.
- Produces: repository-local single-flight coordination, deterministic backoff, and bounded refresh-all concurrency.

- [ ] **Step 1: Write failing coroutine tests**

Prove same-repository callers join one result; cancellation of one waiter does not cancel the shared run; different repositories overlap; backoff is persisted and honored; forced bypass does not exist; and refresh-all bounds concurrency while preserving per-repository results.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*SynchronizationBackoffTest' --tests '*RepositoryRefreshCoordinatorTest'
~~~

- [ ] **Step 3: Implement structured coordination**

Use a mutex-protected map of repository to deferred within an injected service scope. Remove completed flights in finally. Use Clock and deterministic delay calculation rather than wall-clock sleeps in tests.

- [ ] **Step 4: Run GREEN repeatedly**

~~~bash
./gradlew test --tests '*SynchronizationBackoffTest' --tests '*RepositoryRefreshCoordinatorTest'
./gradlew test --tests '*RepositoryRefreshCoordinatorTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: coordinate repository refresh
~~~

---

### Task 9: Implement Projections, Live Actions, Retention, and Refresh Runs

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/ReadQueryServices.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/ActionItemServices.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/PruneInactivePullRequestsService.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/InMemoryRefreshRunRegistry.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshRunServices.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/service/ReadQueryServicesTest.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/service/ActionItemServicesTest.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/service/PruneInactivePullRequestsServiceTest.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/service/RefreshRunServicesTest.kt

**Interfaces:**
- Consumes: B0 read/action/prune/refresh-run ports and Core stores/coordinator.
- Produces: API-ready projections, live content, acknowledgment, retention, and expiring in-memory refresh runs.

- [ ] **Step 1: Write failing projection tests**

Cover repository grouping, stable sorting, revision tokens, freshness, partial failures, inactive visibility rules, inbox actionability, and complete absence of raw bodies.

- [ ] **Step 2: Write failing command tests**

Cover live-content gateway success/unavailable/version advance, exact-version acknowledgment, 30-day inactive pruning without active/actionable loss, start/join/backoff refresh dispositions, run polling, expiry, and bounded registry size.

- [ ] **Step 3: Run RED**

~~~bash
./gradlew test --tests '*ReadQueryServicesTest' --tests '*ActionItemServicesTest' --tests '*PruneInactivePullRequestsServiceTest' --tests '*RefreshRunServicesTest'
~~~

- [ ] **Step 4: Implement and run GREEN**

~~~bash
./gradlew test --tests '*ReadQueryServicesTest' --tests '*ActionItemServicesTest' --tests '*PruneInactivePullRequestsServiceTest' --tests '*RefreshRunServicesTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: expose core projections and commands
~~~

---

### Task 10: Add Health Behavior and Core Acceptance

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/GetHealthSnapshotService.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/service/GetHealthSnapshotServiceTest.kt
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/CoreApplicationAcceptanceTest.kt

**Interfaces:**
- Consumes: B0 health contracts plus all Core outputs.
- Produces: aggregate health semantics and branch-level end-to-end evidence for the complete core.

- [ ] **Step 1: Write failing health tests**

Map required component probes to healthy, degraded, and unhealthy without leaking exception messages, paths, credentials, raw bodies, or adapter-specific types.

- [ ] **Step 2: Write the core acceptance scenario**

With temporary SQLite, fake BitbucketGateway, fake NotificationIntentPolicy/dispatcher, controlled Clock, and test coroutine scope: configure -> add repository -> refresh -> project -> fetch live content -> acknowledge exact version -> deactivate authoritatively -> prune. Reopen persistence and prove durable state and leases survive.

- [ ] **Step 3: Run RED, implement health, then run GREEN**

~~~bash
./gradlew test --tests '*GetHealthSnapshotServiceTest' --tests '*CoreApplicationAcceptanceTest'
~~~

- [ ] **Step 4: Run the complete branch gate**

~~~bash
./gradlew clean check verifyApiV1Generated
./gradlew buildFatJar
rg -n 'rawBody|commentBody|threadBody' src/main/kotlin/com/mindtable/bitbuckethelper/domain src/main/kotlin/com/mindtable/bitbuckethelper/application
git diff --check
git diff --name-only B0..HEAD
~~~

Expected: Gradle commands pass, privacy scan returns no result, and every changed path is owned by this plan. Replace B0 with the recorded SHA.

- [ ] **Step 5: Commit and report**

Commit:

~~~text
test: accept core application behavior
~~~

Report the branch HEAD, B0 SHA, task commits, full gate output, and any assumptions. Do not merge or edit assembly-owned files.
