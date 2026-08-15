# Bitbucket Helper V1 Parallel Assembly Implementation Plan

> **For the Assembly orchestrator:** Execute the verification/assembly phase in its own fifth top-level Codex session, separate from all four implementation sessions. REQUIRED SUB-SKILLS: Use superpowers:using-git-worktrees for isolation, superpowers:receiving-code-review while validating handoffs, superpowers:requesting-code-review on the assembled range, and superpowers:verification-before-completion before merging to main. Do not implement any companion workstream in this session. Check off every step as it is completed.

**Goal:** Establish one reviewed contract baseline, hand four conflict-free plans to four independent session orchestrators, then independently verify and assemble their branch results in a separate Assembly session before merging to main.

**Architecture:** A one-time preparation checkpoint freezes shared contracts. Four top-level sessions then start from the exact same commit and use separate orchestrators/worktrees for core/persistence, Bitbucket/API transports, product CLI, and notifications/scheduler. After all four sessions hand off immutable branch heads, a fifth Assembly orchestrator distrusts-and-verifies their evidence, merges the reviewed ranges, and alone wires real implementations together.

**Tech Stack:** Existing Gradle 9.6.1/JDK 25/Kotlin 2.4.10 build, Ktor 3.5.1 CIO, kotlinx.serialization 1.11.0, coroutines 1.11.0, Clikt 5.1.0, Quartz 2.5.2 RAMJobStore, Liquibase 5.0.3 XML, jOOQ 3.21.6, SQLite JDBC 3.53.1.0, OpenAPI Generator 7.24.0, ArchUnit 1.4.2, and JUnit 6.1.3.

## Companion Execution Plans

1. docs/superpowers/plans/2026-08-15-bitbucket-helper-v1-core-persistence-parallel.md
2. docs/superpowers/plans/2026-08-15-bitbucket-helper-v1-api-transports-parallel.md
3. docs/superpowers/plans/2026-08-15-bitbucket-helper-v1-product-cli-parallel.md
4. docs/superpowers/plans/2026-08-15-bitbucket-helper-v1-notifications-scheduler-parallel.md

## Approved Inputs

- docs/superpowers/plans/2026-08-15-kotlin-walking-skeleton.md
- docs/superpowers/specs/2026-08-14-bitbucket-assistant-architecture-design.md
- docs/superpowers/specs/2026-08-15-spa-kotlin-api-contract-design.md
- sibling desktop-notifications revision fe12b2e and send-fixture SHA-256 91e5cfd97445eba9c0f0f596958584f76043513e521b080b5ce7d415ada19270

The removed monolithic plan was the traceability source, not an additional execution plan. This document and its four companions replace it.

## Session Topology and Handoff

1. A repository maintainer completes Tasks 1-4 in the Preparation phase and records B0.
2. Open four independent top-level Codex sessions. Give each session exactly one companion plan, one worktree, its branch name, and the full B0 SHA.
3. Each session has its own orchestrator. It may use task-level subagents inside that session, but it must not execute another companion plan or share a worktree.
4. Each orchestrator ends with an immutable handoff: B0 SHA, branch HEAD SHA, task commits, changed-path manifest, clean status, verification results, and unresolved risks.
5. Only after all four handoffs exist, open the separate Assembly session. Its orchestrator begins at Task 5, independently rechecks B0 and every handoff, and does not trust claims made by an implementation session.

Do not implement the four plans by dispatching them as sibling agents from the Assembly session. They are separate user-visible sessions with separate context, lifecycle, and orchestrator responsibility.

## Global Constraints

- HTTP status codes describe request or transport failure only. Every valid request returns 200 with a typed business result, including pending, stale, partial, unavailable, rejected, and missing outcomes.
- Preserve domain <- application <- adapters <- bootstrap. Product CLI commands depend only on generated API DTOs and a local API client.
- Keep all pinned versions unchanged.
- Credentials come only from BITBUCKET_USERNAME and BITBUCKET_APP_PASSWORD and are never persisted, echoed, included in argv, or exposed through API/CLI/health/error output.
- Normal clean, check, generation verification, packaging, and branch acceptance must be hermetic: no Bitbucket Cloud, npm, sibling-repository, or GUI access.
- Never persist, bulk-return, log, diagnose, or send raw comment or thread bodies in notification arguments.
- Preserve the untracked source/ prototype byte-for-byte and never stage it.
- Use RED-GREEN-REFACTOR for behavior. Every task ends with focused verification, git diff --check, an owned-path review, and one focused commit.
- No implementation session may edit a frozen baseline file. Contract defects return to the baseline maintainer; fix and regenerate them once, then restart or rebase all four branches onto the new recorded baseline.
- Do not merge work that uses 202, 409, or 503 to represent a business outcome.
- Vue implementation, LaunchAgent/install/update work, and Testcontainers remain separate follow-up plans.

## Baseline and Ownership Rules

The baseline commit is named B0 in these plans. B0 is a label, not a literal SHA. Record its exact SHA during Task 4 and give that SHA to every session orchestrator.

During the four implementation sessions, these paths are frozen and may be changed only by a new baseline-preparation commit or later by the Assembly orchestrator:

- build.gradle.kts, settings.gradle.kts, gradle/**, and .gitignore, except the API branch's explicitly authorized Bitbucket reducer changes to build.gradle.kts
- openapi/**, committed generated product API code, and shared API fixtures
- src/main/kotlin/com/mindtable/bitbuckethelper/domain/shared/Identifiers.kt
- src/main/kotlin/com/mindtable/bitbuckethelper/application/model/** files created by Task 2
- src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/** files created by Task 2
- src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/** files created by Task 2
- src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/**
- src/main/resources/application.conf
- src/test/kotlin/com/mindtable/bitbuckethelper/ArchitectureTest.kt
- the assembly-only repository-level tests named in Tasks 8-9, README.md, docs/project-backlog.md, and docs/operations/**

Each companion plan lists its exclusive paths. A session orchestrator that discovers a required edit outside those paths must stop and report the requested contract change; it must not make the edit.

## Source-Task Traceability

| Original task | New owner |
|---|---|
| 1 | B0 identifiers; Core installation configuration |
| 2-4 | Core |
| 5 | B0 ports/models; Core contracts and implementations |
| 6-7 | Core |
| 8 | B0 gateway/configuration ports; Core services |
| 9 | API |
| 10 | B0 refresh contract; Core behavior |
| 11 | Core refresh/action synchronization; Notifications intent policy |
| 12 | Core |
| 13 | B0 projection/query contracts; Core services |
| 14 | B0 action/retention contracts; Core services |
| 15 | B0 refresh-run contracts; Core services |
| 16-18 | B0 canonical product contract and generated code |
| 19-22 | API transport behavior; Assembly production composition |
| 23-26 | CLI; Assembly command attachment |
| 27 | Notifications adapter; Assembly configuration |
| 28 | Core lease persistence; Notifications dispatch/retry; Assembly wiring |
| 29-30 | Notifications |
| 31-33 | Assembly |

---

## Preparation Phase — Complete Before Opening the Four Sessions

Tasks 1-4 are a one-time prerequisite. After Task 4, stop this phase and let the four independent implementation sessions run. Do not continue into Task 5 until all four immutable handoffs are available.

### Task 1: Finish and Integrate the Walking Skeleton

**Files:**
- Verify: docs/superpowers/plans/2026-08-15-kotlin-walking-skeleton.md
- Merge from: codex/kotlin-walking-skeleton
- Preserve: docs/project-backlog.md and source/

**Interfaces:**
- Consumes: the completed walking-skeleton branch.
- Produces: a clean main commit containing the accepted build, lifecycle, persistence, generated Bitbucket current-user client, CLI shell, and tests.

- [ ] **Step 1: Audit the source plan**

Confirm every walking-skeleton checkbox through Task 11 and its final review gate has evidence. Do not infer completion from file presence.

- [ ] **Step 2: Run the source-branch gate**

Run from its worktree:

~~~bash
./gradlew clean check
./gradlew buildFatJar
git status --short
~~~

Expected: both Gradle commands pass and only intentionally untracked planning material, if any, remains.

- [ ] **Step 3: Review and integrate**

Review the complete main-to-codex/kotlin-walking-skeleton commit range, then merge without staging the protected main-worktree changes.

- [ ] **Step 4: Verify main**

~~~bash
./gradlew clean check
./gradlew buildFatJar
git diff --check
~~~

Expected: the integrated skeleton passes from main.

- [ ] **Step 5: Commit**

If the merge is not already represented by a merge commit, create one focused integration commit. Record the resulting SHA in the execution log.

---

### Task 2: Freeze the Shared Domain and Application Contract Spine

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/domain/shared/Identifiers.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/model/ConfigurationModels.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/model/GatewayModels.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/model/SynchronizationModels.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/model/NotificationModels.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/model/ProjectionModels.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/model/RefreshRunModels.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/model/HealthModels.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/WorkspaceConfigurationUseCases.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/RefreshUseCases.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/ReadQueries.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/ActionItemUseCases.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/PruneInactivePullRequests.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/RefreshRunUseCases.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/GetHealthSnapshot.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/inbound/NotificationUseCases.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/ApplicationTransaction.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/PersistenceStores.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/BitbucketGateway.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/NotificationSender.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/HealthComponentProbe.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/port/outbound/NotificationCoordination.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/contract/SharedContractTest.kt
- Modify: src/test/kotlin/com/mindtable/bitbuckethelper/ArchitectureTest.kt

**Interfaces:**
- Consumes: approved domain/API semantics only.
- Produces: framework-independent types against which all four branches compile.

- [ ] **Step 1: Write failing contract tests**

Cover URL-safe prefixed IDs; exhaustive sealed business results; exact activity versions; freshness and partial-failure metadata; notification delivery keys and leases; gateway failures without generated DTOs; and behaviorless stored snapshots for configuration, pull requests, action items, synchronization, and reminder projections.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*SharedContractTest' --tests '*ArchitectureTest'
~~~

Expected: compilation fails because the shared contract does not exist.

- [ ] **Step 3: Implement the smallest stable contract**

Keep transport-neutral command/result models in application/model. Keep gateway observations separate from future domain aggregates. Persistence ports use complete behaviorless stored records so B0 compiles before Core creates its aggregates; Core will hydrate/map those records at its boundary. Define NotificationIntentPolicy and PostCommitNotificationDispatcher seams so Core can create committed intents without owning notification delivery. Define reminder reads in terms of safe repository/action projections rather than future Core classes.

- [ ] **Step 4: Run GREEN and API-surface review**

~~~bash
./gradlew test --tests '*SharedContractTest' --tests '*ArchitectureTest'
rg -n 'io\\.ktor|org\\.jooq|org\\.quartz|adapter\\.' src/main/kotlin/com/mindtable/bitbuckethelper/domain src/main/kotlin/com/mindtable/bitbuckethelper/application
~~~

Expected: tests pass and the dependency scan has no framework/adapter import.

- [ ] **Step 5: Commit**

Stage only the listed shared files and commit with message:

~~~text
feat: freeze v1 application contracts
~~~

---

### Task 3: Create the Canonical Product API Baseline

**Files:**
- Create: openapi/api-v1.yaml
- Create: openapi/generator/kotlin-models.yaml
- Create: openapi/generator/typescript-fetch.yaml
- Create: openapi/fixtures/v1/**
- Create: src/generated/api-v1/kotlin/**
- Create: web/src/generated/api-v1/**
- Modify: build.gradle.kts
- Modify: .gitignore
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/contract/ApiV1ContractGenerationTest.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/contract/ApiV1FixtureTest.kt

**Interfaces:**
- Consumes: all B0 application command/result models and the approved API design.
- Produces: one complete v1 OpenAPI contract, reviewed Kotlin DTOs, reviewed TypeScript client output, fixtures, and deterministic drift tasks.

- [ ] **Step 1: Add failing generation and fixture checks**

Require validateApiV1, generateApiV1KotlinCandidate, generateApiV1TypeScriptCandidate, verifyApiV1Generated, and syncApiV1Generated. Tests must reject missing discriminators, undocumented variants, non-RFC-3339 instants, nullable ambiguity, and extra fields.

- [ ] **Step 2: Author the complete contract**

Include dashboard, PR list/detail, inbox, synchronization, live content, exact-version acknowledgment, refresh start/status, workspace/repository configuration, health, browser session, normal envelopes, and request-error envelopes. Declare 200 for every typed business result; reserve 4xx and 500 for request/transport/server failures.

- [ ] **Step 3: Generate once and review**

~~~bash
./gradlew validateApiV1 syncApiV1Generated
git diff -- src/generated/api-v1/kotlin web/src/generated/api-v1 openapi
~~~

Inspect every generated type and operation. Generated code is never hand-edited.

- [ ] **Step 4: Prove determinism and offline behavior**

~~~bash
./gradlew verifyApiV1Generated
./gradlew test --tests '*ApiV1ContractGenerationTest' --tests '*ApiV1FixtureTest'
./gradlew --offline clean check verifyApiV1Generated
~~~

Expected: candidate output is byte-identical and normal verification uses no network.

- [ ] **Step 5: Commit**

Stage only contract, generated output, build wiring, and contract tests. Commit with message:

~~~text
feat: freeze generated v1 API contract
~~~

---

### Task 4: Cut Four Worktrees and Issue Session Handoffs

**Files:**
- Verify: .gitignore
- Record: exact B0 SHA in every implementation-session brief

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces: four worktrees and four independent session briefs starting at one commit.

- [ ] **Step 1: Run the baseline gate**

~~~bash
./gradlew clean check verifyApiV1Generated
./gradlew buildFatJar
git diff --check
git status --short
~~~

Expected: all commands pass. Protected user-owned changes remain unstaged.

- [ ] **Step 2: Record B0**

~~~bash
git rev-parse HEAD
git check-ignore -q .worktrees
~~~

Save the full SHA as B0. Abort if .worktrees is not ignored.

- [ ] **Step 3: Create worktrees from B0**

~~~bash
git worktree add .worktrees/v1-core-persistence -b codex/v1-core-persistence B0
git worktree add .worktrees/v1-api-transports -b codex/v1-api-transports B0
git worktree add .worktrees/v1-product-cli -b codex/v1-product-cli B0
git worktree add .worktrees/v1-notifications-scheduler -b codex/v1-notifications-scheduler B0
~~~

Replace B0 with the recorded SHA. Do not branch from a moving branch name.

- [ ] **Step 4: Open four independent implementation sessions**

Create four separate top-level sessions, each with its own orchestrator. Give each exactly one companion plan, exact worktree path, exact branch name, full B0 SHA, and the ownership rules in this plan. Do not spawn them as subagents of one implementation or Assembly session.

Require every session to make a focused commit per task and finish with the immutable handoff package defined under Session Topology and Handoff.

- [ ] **Step 5: Confirm isolation**

~~~bash
git worktree list
git -C .worktrees/v1-core-persistence merge-base --is-ancestor B0 HEAD
git -C .worktrees/v1-api-transports merge-base --is-ancestor B0 HEAD
git -C .worktrees/v1-product-cli merge-base --is-ancestor B0 HEAD
git -C .worktrees/v1-notifications-scheduler merge-base --is-ancestor B0 HEAD
~~~

Expected: four distinct branches, all rooted at B0.

---

## Assembly Verification Phase — Run in the Separate Fifth Session

Start a fresh Assembly session only after all four implementation orchestrators have finished. Provide this plan, B0, the four branch names/HEAD SHAs, and their handoff evidence. The Assembly orchestrator begins here; it must rerun verification rather than relying on prior session output.

### Task 5: Review Each Parallel Result Before Merge

**Files:**
- Inspect only: all four branch ranges B0..branch

**Interfaces:**
- Consumes: four completed session branches and their immutable handoffs.
- Produces: reviewed heads that obey ownership and pass branch-local acceptance.

- [ ] **Step 1: Validate B0 and immutable handoffs**

Confirm all four reported B0 SHAs are identical to the preparation SHA, each reported HEAD still equals its branch HEAD, each worktree is clean, and each branch contains only descendants of B0. Reject a missing/incomplete handoff.

Then run each companion plan's final gate from its own worktree. Prior session output is context, not evidence for this Assembly session.

- [ ] **Step 2: Audit path ownership**

~~~bash
git diff --name-only B0..codex/v1-core-persistence
git diff --name-only B0..codex/v1-api-transports
git diff --name-only B0..codex/v1-product-cli
git diff --name-only B0..codex/v1-notifications-scheduler
~~~

Expected: every changed file belongs to exactly one plan. Reject opportunistic shared-file edits.

- [ ] **Step 3: Review behavior and tests**

Review every branch commit and diff. Require evidence that fakes exercise both success and failure paths, business outcomes remain typed, privacy constraints hold, and no branch weakens a test to pass.

- [ ] **Step 4: Return defects to their owner**

Use the receiving-code-review workflow. Return a behavioral defect to the owning implementation session rather than fixing owned code in Assembly. Require that orchestrator to issue a new immutable handoff; then re-run the focused gate and record the corrected HEAD.

- [ ] **Step 5: Freeze accepted heads**

Do not allow new commits after acceptance without repeating this task for the changed branch.

---

### Task 6: Merge the Four Branches into an Assembly Branch

**Files:**
- Merge: the four accepted branch heads
- Create branch: codex/v1-assembly

**Interfaces:**
- Consumes: B0 plus four reviewed, disjoint branch ranges.
- Produces: one compileable tree with no production composition yet.

- [ ] **Step 1: Create the assembly branch from B0**

~~~bash
git switch -c codex/v1-assembly B0
~~~

- [ ] **Step 2: Merge and verify Core**

Preflight with git merge-tree --write-tree HEAD codex/v1-core-persistence, then merge --no-ff the accepted Core HEAD.

~~~bash
./gradlew test --tests '*CoreApplicationAcceptanceTest' --tests '*JooqApplicationPersistenceTest'
git diff --check B0..HEAD
~~~

- [ ] **Step 3: Merge and verify API**

Preflight and merge --no-ff the accepted API HEAD.

~~~bash
./gradlew test --tests '*ApiTransportAcceptanceTest' --tests '*GeneratedBitbucketGatewayTest'
git diff --check B0..HEAD
~~~

- [ ] **Step 4: Merge and verify CLI**

Preflight and merge --no-ff the accepted CLI HEAD.

~~~bash
./gradlew test --tests '*ProductCommandFactoryTest' --tests '*CliArchitectureTest'
git diff --check B0..HEAD
~~~

- [ ] **Step 5: Merge and verify Notifications**

Preflight and merge --no-ff the accepted Notifications HEAD.

~~~bash
./gradlew test --tests '*NotificationWorkstreamAcceptanceTest' --tests '*QuartzApplicationSchedulerTest'
git diff --check B0..HEAD
~~~

Disjoint plans should not conflict. If any preflight or merge conflicts outside an intentional delete/replace seam, stop and determine which session violated ownership rather than choosing a side mechanically.

- [ ] **Step 6: Run the uncomposed combined gate**

~~~bash
./gradlew clean check verifyApiV1Generated
git diff --check B0..HEAD
~~~

Expected: all branch-local tests pass together. Failures now indicate a hidden contract mismatch.

- [ ] **Step 7: Commit reconciliation only if needed**

Any necessary reconciliation gets a dedicated commit naming the seam. Do not mix it with production wiring.

---

### Task 7: Compose Runtime, Configuration, and Product Commands

**Files:**
- Modify: src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceConfiguration.kt
- Modify: src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntime.kt
- Modify: src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Commands.kt
- Modify: src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Main.kt
- Modify: src/main/resources/application.conf
- Delete: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BitbucketStatusRoutes.kt
- Delete: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BitbucketStatusRoutesTest.kt
- Delete: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/RefreshBitbucketConnectionJob.kt
- Delete: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/UseCaseJobFactory.kt
- Delete: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/QuartzRefreshScheduler.kt
- Delete: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/QuartzRefreshSchedulerTest.kt
- Modify: src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceConfigurationTest.kt
- Modify: src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceRuntimeLifecycleTest.kt
- Modify: src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/RootCommandTest.kt
- Modify: src/test/kotlin/com/mindtable/bitbuckethelper/WalkingSkeletonEndToEndTest.kt
- Modify: src/test/kotlin/com/mindtable/bitbuckethelper/ArchitectureTest.kt

**Interfaces:**
- Consumes: all four branch factories and B0 contracts.
- Produces: one real lifecycle with persistence, Bitbucket, use cases, notification process, Quartz, loopback/Unix API, and product commands.

- [ ] **Step 1: Write failing composition tests**

Require configuration to validate credentials and all paths before opening a resource; a product CLI invocation to load only the socket locator; one shared application instance behind both transports; command attachment through the CLI branch's ProductCommandFactory; and reverse-order idempotent shutdown.

The credential-independent locator reads BITBUCKET_HELPER_UNIX_SOCKET_PATH, then bitbucket-helper.unix-socket.path, and returns a normalized absolute Path. It must not load Bitbucket credentials, validate the database or notification executable, or start any resource.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*ServiceConfigurationTest' --tests '*ServiceRuntimeLifecycleTest' --tests '*RootCommandTest' --tests '*WalkingSkeletonEndToEndTest'
~~~

Expected: failures because real branch factories are not yet wired.

- [ ] **Step 3: Wire production dependencies**

Construct one SupervisorJob service scope, SQLite/jOOQ persistence, generated Bitbucket gateway, Core use cases, notification sender and dispatch services, Quartz scheduler, and LocalApiServers. Attach product commands without giving CLI code direct access to persistence, application services, credentials, or TCP fallback. Delete the transitional route and legacy scheduler only after the new composition compiles and their useful health/synchronization behavior is covered.

Start only after full validation. Stop accepting requests, stop Quartz, cancel and join the scope, close servers/process resources/gateway/persistence, and remove the Unix socket in reverse order. Repeated close remains safe.

- [ ] **Step 4: Run GREEN and architecture checks**

~~~bash
./gradlew test --tests '*ServiceConfigurationTest' --tests '*ServiceRuntimeLifecycleTest' --tests '*RootCommandTest' --tests '*WalkingSkeletonEndToEndTest' --tests '*ArchitectureTest'
~~~

- [ ] **Step 5: Commit**

Commit only bootstrap, application.conf, and the named composition tests:

~~~text
feat: compose the v1 application
~~~

---

### Task 8: Verify Notification Restart and Retry Integration

**Files:**
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/NotificationIntegrationTest.kt
- Reuse without editing: src/test/kotlin/com/mindtable/bitbuckethelper/support/FakeDesktopNotificationsExecutable.kt
- Modify: src/test/kotlin/com/mindtable/bitbuckethelper/WalkingSkeletonEndToEndTest.kt

**Interfaces:**
- Consumes: production runtime, real temporary SQLite, Quartz, real process adapter, and fake Bitbucket/provider processes.
- Produces: original Task 31 restart/retry evidence.

- [ ] **Step 1: Write the failing lifecycle matrix**

Cover intent committed before process launch; process rejection; service restart; lease expiry; retry with unchanged delivery key/payload; provider-accepted replacement key; exhausted attempts; and no raw content in database, logs, diagnostics, or argv.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*NotificationIntegrationTest'
~~~

- [ ] **Step 3: Correct composition seams only**

Fix wiring/lifecycle defects in assembly-owned files. A behavioral defect returns to its owning branch and is merged as a focused follow-up commit.

- [ ] **Step 4: Run GREEN twice**

~~~bash
./gradlew test --tests '*NotificationIntegrationTest'
./gradlew test --tests '*NotificationIntegrationTest'
~~~

Two clean runs guard against leaked processes, sockets, scheduler threads, and timing order dependence.

- [ ] **Step 5: Commit**

~~~text
test: verify notification restart and retry
~~~

---

### Task 9: Run Full API, CLI, Security, and Privacy Acceptance

**Files:**
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/V1ApplicationEndToEndTest.kt
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/V1SecurityAndPrivacyTest.kt
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/ProductCliProcessTest.kt
- Modify: src/test/kotlin/com/mindtable/bitbuckethelper/ArchitectureTest.kt

**Interfaces:**
- Consumes: complete production composition.
- Produces: original Task 32 acceptance evidence.

- [ ] **Step 1: Add the failing end-to-end matrix**

Exercise immutable workspace setup, repository add/remove/re-add, independent refresh completion, last-known-good preservation, dashboard/PR/inbox projections, live-content success/version advance/failure, exact-version acknowledgment, refresh polling, and typed degraded outcomes.

- [ ] **Step 2: Add transport and CLI parity**

Run the same business matrix over loopback and Unix HTTP. Invoke the real product CLI process for at least one read and one mutation; JSON output must preserve the API body byte-for-byte and exit codes must match the documented mapping.

- [ ] **Step 3: Add security/privacy assertions**

Cover Host, Origin, CSRF, content type, disabled CORS, socket mode/cleanup, bounded response/error text, credential redaction, and absence of raw activity bodies from persistence, snapshots, diagnostics, notification argv, and bulk responses.

- [ ] **Step 4: Run GREEN**

~~~bash
./gradlew test --tests '*V1ApplicationEndToEndTest' --tests '*V1SecurityAndPrivacyTest' --tests '*ProductCliProcessTest' --tests '*ArchitectureTest'
~~~

- [ ] **Step 5: Commit**

~~~text
test: accept the complete v1 application
~~~

---

### Task 10: Document, Clean-Verify, and Integrate to Main

**Files:**
- Modify: README.md
- Modify: docs/project-backlog.md
- Create: docs/operations/manual-service-run.md
- Create: docs/contracts/api-v1.md
- Create: docs/contracts/desktop-notifications-consumer.md

**Interfaces:**
- Consumes: verified v1 behavior.
- Produces: truthful operational/contract documentation, follow-up gates, and the final main integration.

- [ ] **Step 1: Update documentation**

Document configuration precedence, Unix socket discovery, CLI exit/JSON behavior, API status semantics, provider pin/checksum, delivery guarantees, retry/lease behavior, manual verification, and privacy limits. Mark only demonstrably complete backlog items.

- [ ] **Step 2: Record follow-up design gates**

Keep focused follow-ups for Vue integration, macOS LaunchAgent/install/update, ignored-actor configuration, and any later Testcontainers coverage. Do not implement them here.

- [ ] **Step 3: Run clean acceptance**

~~~bash
./gradlew --offline clean check verifyApiV1Generated
./gradlew --offline buildFatJar
git diff --check
git status --short
~~~

Expected: all tests and packaging pass without network. Only deliberate documentation/plan changes and protected pre-existing user changes are present.

- [ ] **Step 4: Review the complete range**

Use superpowers:requesting-code-review on B0..codex/v1-assembly. Resolve every material finding and repeat the affected focused and clean gates.

- [ ] **Step 5: Integrate**

Use superpowers:finishing-a-development-branch. Merge codex/v1-assembly into main only after verifying the current main HEAD and preserving unrelated user work. Remove the four execution worktrees only after integration is confirmed.

## Final Review Gate

Before claiming completion, verify all of the following:

- Every original task 1-33 maps to B0, one implementation session, or Assembly.
- Every parallel branch started from the recorded B0 SHA.
- Parallel changed-path sets are disjoint and comply with their plans.
- No generated product API file was hand-edited after B0.
- Valid business outcomes always use HTTP 200.
- Core state and notification intents commit before external process delivery.
- Retry preserves delivery key and payload; exactly-once delivery is not claimed.
- CLI business commands use only Unix HTTP and generated DTOs.
- Raw activity bodies are live-only and absent from durable/bulk/notification surfaces.
- Both transports, CLI, scheduler, restart/retry, clean check, generation drift, and fat-JAR packaging have fresh passing evidence.
