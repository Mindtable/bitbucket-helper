# Bitbucket Helper V1 Notifications and Scheduler Parallel Implementation Plan

> **For the Notifications workstream orchestrator:** Run this complete plan in its own top-level Codex session, separate from the other three workstreams and from assembly. REQUIRED SUB-SKILLS: Use superpowers:subagent-driven-development to orchestrate task-level implementer/reviewer agents, superpowers:test-driven-development for every policy/process/scheduler task, superpowers:systematic-debugging for timing or process failures, and superpowers:verification-before-completion before handing off the branch. Work only in codex/v1-notifications-scheduler. Check off every step and commit after every task.

**Goal:** Implement safe notification-intent policy, strict desktop-notifications process delivery, durable dispatch/retry behavior over frozen lease-aware ports, hourly reminders, and a generic four-job Quartz timing adapter.

**Architecture:** Notification policy turns safe domain/application facts into generic intents. Dispatch claims durable intents through B0 transaction ports, invokes a directly configured executable outside a transaction, then records an attempt. Quartz owns timing only and awaits injected suspend use cases. This branch tests against a notification-specific fake transaction runner; Core alone implements real persistence and migrations.

**Tech Stack:** Kotlin 2.4.10, coroutines 1.11.0, kotlinx.serialization 1.11.0, Quartz 2.5.2 RAMJobStore, JUnit 6.1.3, POSIX process fixtures, and B0 notification/store/use-case contracts.

## Preconditions

- Start from the exact B0 SHA recorded by docs/superpowers/plans/2026-08-15-bitbucket-helper-v1-parallel-assembly.md.
- Work on branch codex/v1-notifications-scheduler in .worktrees/v1-notifications-scheduler.
- Run ./gradlew clean check verifyApiV1Generated before changing a file.
- Treat every B0 model/port, all persistence adapters/migrations, bootstrap, and application.conf as read-only.
- The provider baseline is sibling revision fe12b2e, release 0.3.0, with send-fixture SHA-256 91e5cfd97445eba9c0f0f596958584f76043513e521b080b5ce7d415ada19270.
- If a frozen lease/store operation is insufficient, stop and send the baseline maintainer a focused failing contract test. Do not edit the port or Core path.

## Dedicated Session Contract

- This plan is the sole implementation brief for the Notifications session orchestrator. Do not run another companion plan in this session.
- No other session may use this worktree or write to codex/v1-notifications-scheduler.
- Task-level subagents are allowed inside this session, but the Notifications orchestrator owns sequencing, review, commits, and final evidence.
- Do not depend on messages or uncommitted files from another execution session. B0, the copied provider fixture, and approved documents are the only shared inputs.
- A B0 lease/store defect is a stop condition: add only a focused failing test in an owned path, report the smallest requested change, and wait for a new baseline SHA.
- The final handoff must include the full B0 SHA, branch HEAD SHA, task commit list, git diff --name-status B0..HEAD, clean git status, every verification command with result, fixture checksum, process argv contract, scheduler factory names, and unresolved risks.
- After handoff, do not rebase, merge, or add commits unless the assembly orchestrator explicitly returns a defect to this session.

Before Task 1, the Notifications orchestrator runs:

~~~bash
git branch --show-current
git rev-parse HEAD
git status --short
./gradlew clean check verifyApiV1Generated
~~~

Expected: branch codex/v1-notifications-scheduler, HEAD exactly B0, clean status, passing baseline gate.

## Exclusive Ownership

This branch may create or modify only:

- contracts/desktop-notifications-send-cases.json
- contracts/desktop-notifications-provider.txt
- src/main/kotlin/com/mindtable/bitbuckethelper/application/policy/DefaultNotificationIntentPolicy.kt
- src/main/kotlin/com/mindtable/bitbuckethelper/application/policy/NotificationRetryPolicy.kt
- src/main/kotlin/com/mindtable/bitbuckethelper/application/service/DispatchNotificationsService.kt
- src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RetryPendingNotificationsService.kt
- src/main/kotlin/com/mindtable/bitbuckethelper/application/service/SendDueRemindersService.kt
- src/main/kotlin/com/mindtable/bitbuckethelper/application/service/ImmediatePostCommitNotificationDispatcher.kt
- src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification/**
- new files under src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/** named in this plan
- corresponding focused tests
- src/test/kotlin/com/mindtable/bitbuckethelper/support/FakeDesktopNotificationsExecutable.kt
- src/test/kotlin/com/mindtable/bitbuckethelper/NotificationWorkstreamAcceptanceTest.kt

Do not edit B0 models/ports, domain/**, Core services, persistence/**, migrations, build.gradle.kts, Bitbucket/HTTP/CLI adapters, existing walking-skeleton scheduler files, bootstrap/**, application.conf, ArchitectureTest.kt, WalkingSkeletonEndToEndTest.kt, README.md, docs/project-backlog.md, or source/.

Leave RefreshBitbucketConnectionJob.kt, UseCaseJobFactory.kt, QuartzRefreshScheduler.kt, and QuartzRefreshSchedulerTest.kt intact. Use the conflict-free new name ApplicationUseCaseJobFactory.kt. Assembly deletes the legacy scheduler after it wires the new one.

## Global Constraints

- Use ProcessBuilder(List<String>) only. Never invoke a shell.
- Validate an injected executable Path as normalized, absolute, regular, and executable. Configuration loading remains Assembly-owned and performs this validation before opening persistence.
- Provider process outer deadline is 15 seconds around its fixed 10-second adapter deadline. Capture stdout and stderr concurrently, at most 65,536 bytes each.
- Parse exactly one UTF-8 JSON object followed by one final LF with unknown members rejected.
- Retry the unchanged delivery key, title, body, URL, and sound. Never claim exactly-once delivery.
- Retry delays after attempts 1-6 are 1 minute, 5 minutes, 15 minutes, 1 hour, 6 hours, and 24 hours. Seventh unsuccessful attempt exhausts.
- invalid_arguments and unsupported_platform exhaust immediately. Accepted is terminal. Other provider/process failures follow bounded retry.
- B0 durable leases last two minutes. Sender invocation occurs outside a database transaction.
- Reminder key is reminder:<repositoryId>:<UTC-hour>; body contains only repository display name and count.
- Quartz owns no due/backoff/reminder/retention policy and launches no detached coroutine.
- No test accesses the sibling repository. The fixture is copied once and then verified locally by checksum.
- Every task ends with focused verification, git diff --check, an owned-path audit, and one commit.

## Original-Plan Coverage

This branch completes original Task 11's notification-intent policy, Tasks 27-30 except V0003/persistence (Core) and runtime/configuration/legacy deletion (Assembly), and provides support for Task 31. Assembly owns the production restart/retry integration.

---

### Task 1: Pin and Decode the Provider Contract

**Files:**
- Create: contracts/desktop-notifications-send-cases.json
- Create: contracts/desktop-notifications-provider.txt
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification/DesktopNotificationsContractFixtureTest.kt

**Interfaces:**
- Consumes: approved copied fixture bytes and provider metadata.
- Produces: a hermetic Kotlin consumer contract suite.

- [ ] **Step 1: Copy and pin once**

Copy the sibling send-cases fixture byte-for-byte during implementation. Write:

~~~text
repository=../desktop-notifications
revision=fe12b2e
fixture_sha256=91e5cfd97445eba9c0f0f596958584f76043513e521b080b5ce7d415ada19270
executable=desktop-notifications
provider_release=0.3.0
~~~

- [ ] **Step 2: Write the failing checksum/decoder test**

Verify SHA-256 before decoding. Cover accepted, invalid_arguments, unsupported_platform, dependency_unavailable, delivery_timeout, delivery_failed, and internal_error. Require the superseding lowercase sound contract, including ping and every named fixture sound.

- [ ] **Step 3: Run RED**

~~~bash
./gradlew test --tests '*DesktopNotificationsContractFixtureTest'
~~~

- [ ] **Step 4: Add test-only strict fixture models and run GREEN**

~~~bash
./gradlew test --tests '*DesktopNotificationsContractFixtureTest'
shasum -a 256 contracts/desktop-notifications-send-cases.json
~~~

Expected checksum: 91e5cfd97445eba9c0f0f596958584f76043513e521b080b5ce7d415ada19270.

- [ ] **Step 5: Commit**

~~~text
test: pin desktop notifications contract
~~~

---

### Task 2: Implement Bounded Concurrent Process Capture

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification/BoundedProcessCapture.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification/NotificationProcessResult.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification/BoundedProcessCaptureTest.kt
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/support/FakeDesktopNotificationsExecutable.kt

**Interfaces:**
- Consumes: a started Process and coroutine cancellation.
- Produces: bounded stdout/stderr, exit/signal/timeout classification, and guaranteed cleanup/reaping.

- [ ] **Step 1: Write adversarial failing tests**

Use executable POSIX fixtures. Cover stdout-first, stderr-first, both streams larger than pipe buffers, exact 65,536-byte boundary, overflow, natural exit, outer timeout, signal, cancellation after start, graceful destroy, forced destroy after one second, and proof the child is reaped.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*BoundedProcessCaptureTest'
~~~

- [ ] **Step 3: Implement structured concurrent capture**

Drain both streams concurrently under the caller scope. Bound memory while continuing to drain or terminate safely. On timeout/cancellation destroy, wait at most one second, destroyForcibly, and reap. Do not include captured bytes in exception messages.

- [ ] **Step 4: Run GREEN repeatedly**

~~~bash
./gradlew test --tests '*BoundedProcessCaptureTest'
./gradlew test --tests '*BoundedProcessCaptureTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: bound notification process capture
~~~

---

### Task 3: Implement the Strict Desktop-Notifications Adapter

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification/DesktopNotificationsProcessAdapter.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification/DesktopNotificationsProcessAdapterTest.kt

**Interfaces:**
- Consumes: B0 NotificationSender/NotificationRequest and Task 2 capture.
- Produces: strict NotificationDeliveryResult classification.

- [ ] **Step 1: Write failing invocation tests**

Cover exact argv send --delivery-key --title --body plus optional --open-url and --sound; every lowercase sound; accepted exit 0; failed exit 1; malformed/empty/multiple JSON; BOM/prefix/suffix/missing LF; unknown JSON members; exit/result mismatch; other exit; signal; missing/non-absolute/non-executable path; start failure; timeout; cancellation; and privacy-safe diagnostics.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*DesktopNotificationsProcessAdapterTest'
~~~

- [ ] **Step 3: Implement direct invocation**

Build an argument list and call ProcessBuilder(command).start(). Apply withTimeout(15 seconds). Decode exactly one strict JSON document with its final LF. Classify start failure as not-started/non-ambiguous; signal, outer timeout, or cancellation after start as ambiguous; provider failed JSON as provider failure; accepted as terminal success.

- [ ] **Step 4: Run GREEN and shell/privacy scans**

~~~bash
./gradlew test --tests '*DesktopNotificationsContractFixtureTest' --tests '*BoundedProcessCaptureTest' --tests '*DesktopNotificationsProcessAdapterTest'
rg -n 'sh -c|bash -c|zsh -c|Runtime\\.getRuntime' src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification
~~~

Expected: no shell execution.

- [ ] **Step 5: Commit**

~~~text
feat: adapt desktop notifications process
~~~

---

### Task 4: Implement Safe Notification-Intent Policy

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/policy/DefaultNotificationIntentPolicy.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/policy/DefaultNotificationIntentPolicyTest.kt

**Interfaces:**
- Consumes: B0 NotificationIntentPolicy input facts from Core refresh.
- Produces: initialDigest, actionableActivity, and buildsBecameGreen intents with deterministic delivery keys.

- [ ] **Step 1: Write failing policy tests**

Cover first repository digest; new/advanced actionable item; all-builds-green edge; replay idempotency; stable delivery keys; safe title/body/open URL/sound; multiple events ordering; and suppression when no user-relevant transition occurred.

- [ ] **Step 2: Add privacy tests**

Inject marker text as raw comment/body/title-like actor input. Intents may use approved repository/PR display metadata and counts, never raw activity content or arbitrary provider error text.

- [ ] **Step 3: Run RED**

~~~bash
./gradlew test --tests '*DefaultNotificationIntentPolicyTest'
~~~

- [ ] **Step 4: Implement pure deterministic mapping and run GREEN**

~~~bash
./gradlew test --tests '*DefaultNotificationIntentPolicyTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: create safe notification intents
~~~

---

### Task 5: Implement Bounded Retry Policy

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/policy/NotificationRetryPolicy.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/policy/NotificationRetryPolicyTest.kt

**Interfaces:**
- Consumes: B0 delivery classifications, attempt number, and Clock instant.
- Produces: accepted, retry-at, or exhausted decision.

- [ ] **Step 1: Write the failing table**

Assert 1m, 5m, 15m, 1h, 6h, and 24h after attempts one through six; seventh unsuccessful attempt exhausted; invalid_arguments/unsupported_platform immediate exhaustion; dependency_unavailable/delivery_timeout/delivery_failed/internal_error/start/malformed/exit/signal/ambiguous failures retry; accepted terminal.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*NotificationRetryPolicyTest'
~~~

- [ ] **Step 3: Implement one pure decision table**

Use injected attempt-completion Instant with exact Duration addition. Do not add jitter, unbounded retry, quiet hours, or provider calls.

- [ ] **Step 4: Run GREEN**

~~~bash
./gradlew test --tests '*NotificationRetryPolicyTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: bound notification retry
~~~

---

### Task 6: Implement Leased Dispatch and Post-Commit Triggering

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/DispatchNotificationsService.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/RetryPendingNotificationsService.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/ImmediatePostCommitNotificationDispatcher.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/service/DispatchNotificationsServiceTest.kt
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/application/service/FakeNotificationTransactionRunner.kt

**Interfaces:**
- Consumes: B0 notification use cases, lease-aware NotificationIntentStore, NotificationSender, Clock, and Task 5 policy.
- Produces: ordered claim/send/record behavior and post-commit dispatch implementation.

- [ ] **Step 1: Write failing dispatch tests**

Prove due intents are ordered by createdAt/id; claim uses a unique worker and two-minute lease; two workers send once; stale lease is reclaimable; send occurs outside transaction; each attempt records in a short transaction; accepted never resends; not-due skips; retries preserve byte-identical payload/key; sender failure cannot change creating domain state; and no exactly-once claim appears in API/docs.

- [ ] **Step 2: Add cancellation/ambiguity tests**

Cancellation before process start releases safely without an attempt. Cancellation after start records an ambiguous attempt and retry decision in NonCancellable cleanup before rethrowing. A failed cleanup remains observable through safe health/attempt state.

- [ ] **Step 3: Run RED**

~~~bash
./gradlew test --tests '*DispatchNotificationsServiceTest'
~~~

- [ ] **Step 4: Implement claim-send-record and run GREEN**

ImmediatePostCommitNotificationDispatcher accepts only committed intent IDs and invokes focused dispatch. RetryPendingNotifications scans due work through the frozen store; Quartz timing is not embedded here.

~~~bash
./gradlew test --tests '*DispatchNotificationsServiceTest' --tests '*NotificationRetryPolicyTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: dispatch and retry durable notifications
~~~

---

### Task 7: Implement Hourly Repository Reminders

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/application/service/SendDueRemindersService.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/application/service/SendDueRemindersServiceTest.kt

**Interfaces:**
- Consumes: B0 SendDueReminders, transaction stores, Clock, NotificationIntentPolicy, and PostCommitNotificationDispatcher.
- Produces: one safe intent per repository and UTC hour for still-actionable items.

- [ ] **Step 1: Write failing grouping/idempotency tests**

Cover no actionable items; multiple items in one repository; two repositories; repeat in same UTC hour; next hour; acknowledged/closed/inactive/removed-repository exclusions; transaction failure; and post-commit dispatch only.

- [ ] **Step 2: Write privacy tests**

Use reminder key reminder:<repositoryId>:<YYYYMMDDTHHZ>. Title is Bitbucket Helper reminder. Body is <repository display name>: <count> item(s) still need attention. Link to repository HTTPS page and use default sound. No actor-supplied/raw body.

- [ ] **Step 3: Run RED**

~~~bash
./gradlew test --tests '*SendDueRemindersServiceTest'
~~~

- [ ] **Step 4: Implement UTC buckets and run GREEN**

~~~bash
./gradlew test --tests '*SendDueRemindersServiceTest' --tests '*DispatchNotificationsServiceTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: create hourly repository reminders
~~~

---

### Task 8: Implement the Generic Quartz Application Scheduler

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/SuspendingUseCaseJob.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/ApplicationUseCaseJobFactory.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/QuartzApplicationScheduler.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/ScheduledUseCases.kt
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler/QuartzApplicationSchedulerTest.kt

**Interfaces:**
- Consumes: B0 RefreshAllRepositories, RetryPendingNotifications, SendDueReminders, PruneInactivePullRequests, and lifecycle/health seams.
- Produces: four awaited schedules with safe observable health.

- [ ] **Step 1: Write failing schedule tests**

Require refresh starts now/every 5m; retry starts after 1m/every 1m; reminders next UTC hour/hourly; prune daily 03:00 UTC; no self-concurrency; simple-trigger misfire skips catch-up; cron misfire does nothing; four worker threads; random instance name; RAMJobStore; all registrations before start.

- [ ] **Step 2: Write failing bridge/lifecycle tests**

Each job awaits its suspend use case, exposes safe completion/failure, uses no GlobalScope/detached launch, times out through an injected bound, and shutdown(true) waits for active jobs. Health distinguishes running/stopped/failed-registration without exception text.

- [ ] **Step 3: Run RED**

~~~bash
./gradlew test --tests '*QuartzApplicationSchedulerTest'
~~~

- [ ] **Step 4: Implement timing-only scheduling and run GREEN**

ScheduledUseCases maps stable keys to suspend lambdas. ApplicationUseCaseJobFactory constructor-injects the matching function/timeout. SuspendingUseCaseJob uses runBlocking with withTimeout and @DisallowConcurrentExecution. Keep all due/backoff/reminder/retention logic in use cases.

~~~bash
./gradlew test --tests '*QuartzApplicationSchedulerTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: schedule all application maintenance
~~~

---

### Task 9: Run Notification Workstream Acceptance

**Files:**
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/NotificationWorkstreamAcceptanceTest.kt

**Interfaces:**
- Consumes: policy, fake transaction runner, real process adapter against fake executable, and Quartz adapter.
- Produces: branch-local acceptance independent of Core persistence and production runtime.

- [ ] **Step 1: Write an end-to-end workstream scenario**

Create safe event facts -> policy intent -> commit in fake runner -> post-commit dispatch -> fake provider failure -> recorded unchanged retry -> fake accepted retry. Add reminder creation and invoke every scheduled use-case bridge once.

- [ ] **Step 2: Add lifecycle/privacy assertions**

Cover timeout/cancellation cleanup, no live child, no scheduler thread leak, idempotent close, unchanged delivery data, and marker text absent from exceptions, diagnostics, attempts, scheduler health, and non-live outputs.

- [ ] **Step 3: Run focused acceptance twice**

~~~bash
./gradlew test --tests '*NotificationWorkstreamAcceptanceTest'
./gradlew test --tests '*NotificationWorkstreamAcceptanceTest'
~~~

- [ ] **Step 4: Run the complete branch gate**

~~~bash
./gradlew clean check verifyApiV1Generated
./gradlew buildFatJar
shasum -a 256 contracts/desktop-notifications-send-cases.json
rg -n 'sh -c|bash -c|zsh -c|GlobalScope' src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/notification src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/scheduler
git diff --check
git diff --name-only B0..HEAD
~~~

Expected: Gradle commands pass, checksum matches the pinned value, forbidden scan has no use, and every changed path belongs to this plan. Replace B0 with the recorded SHA.

- [ ] **Step 5: Commit and report**

Commit:

~~~text
test: accept notification workstream
~~~

Report branch HEAD, B0 SHA, task commits, full gate output, executable argument contract, and scheduler factory names. Do not wire configuration/runtime or delete the legacy scheduler.
