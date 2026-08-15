# Bitbucket Helper V1 Product CLI Parallel Implementation Plan

> **For the CLI workstream orchestrator:** Run this complete plan in its own top-level Codex session, separate from the other three workstreams and from assembly. REQUIRED SUB-SKILLS: Use superpowers:subagent-driven-development to orchestrate task-level implementer/reviewer agents, superpowers:test-driven-development for each command/client task, and superpowers:verification-before-completion before handing off the branch. Work only in codex/v1-product-cli. Check off every step and commit after every task.

**Goal:** Implement the complete service-backed product CLI using only Unix-socket HTTP and committed generated v1 API DTOs.

**Architecture:** LocalApiClient is the sole business-data boundary. Commands validate CLI syntax, call one versioned API operation, render human output or the original response JSON, and map typed results to stable exits. ProductCommandFactory exposes finished Clikt commands for assembly; this branch never edits the root bootstrap command tree.

**Tech Stack:** Kotlin 2.4.10, Clikt 5.1.0, Ktor 3.5.1 CIO client, kotlinx.serialization 1.11.0, JUnit 6.1.3, and B0 committed generated product DTOs.

## Preconditions

- Start from the exact B0 SHA recorded by docs/superpowers/plans/2026-08-15-bitbucket-helper-v1-parallel-assembly.md.
- Work on branch codex/v1-product-cli in .worktrees/v1-product-cli.
- Run ./gradlew clean check verifyApiV1Generated before changing a file.
- Treat openapi/**, generated DTOs, and all B0 service/application contracts as read-only.
- Never run syncApiV1Generated. Report contract gaps to the baseline maintainer with a focused failing test.

## Dedicated Session Contract

- This plan is the sole implementation brief for the CLI session orchestrator. Do not run another companion plan in this session.
- No other session may use this worktree or write to codex/v1-product-cli.
- Task-level subagents are allowed inside this session, but the CLI orchestrator owns sequencing, review, commits, and final evidence.
- Do not depend on messages or uncommitted files from another execution session. B0 and its generated API artifacts are the only shared input.
- A B0/generated-contract defect is a stop condition: add only a focused failing test in an owned path, report the smallest requested change, and wait for a new baseline SHA.
- The final handoff must include the full B0 SHA, branch HEAD SHA, task commit list, git diff --name-status B0..HEAD, clean git status, every verification command with result, the ProductCommandFactory signature, and unresolved risks.
- After handoff, do not rebase, merge, or add commits unless the assembly orchestrator explicitly returns a defect to this session.

Before Task 1, the CLI orchestrator runs:

~~~bash
git branch --show-current
git rev-parse HEAD
git status --short
./gradlew clean check verifyApiV1Generated
~~~

Expected: branch codex/v1-product-cli, HEAD exactly B0, clean status, passing baseline gate.

## Exclusive Ownership

This branch may create or modify only:

- src/main/kotlin/com/mindtable/bitbuckethelper/cli/**
- src/test/kotlin/com/mindtable/bitbuckethelper/cli/**
- src/test/resources/cli/v1/**
- docs/cli-json-v1.md

Do not edit build.gradle.kts, domain/**, application/**, adapter/** outside cli, bootstrap/**, application.conf, openapi/**, generated code, ArchitectureTest.kt, WalkingSkeletonEndToEndTest.kt, README.md, docs/project-backlog.md, or source/.

Assembly alone edits bootstrap/Commands.kt, RootCommandTest.kt, and Main.kt. This branch produces ProductCommandFactory and a factory-level command-tree test so it remains independently compileable.

## Global Constraints

- Every business command uses the configured Unix socket. Never fall back to loopback TCP, persistence, Bitbucket, credentials, or in-process application services.
- LocalApiClient uses a 10-second request timeout and closes all CIO resources.
- JSON stdout is exactly the original UTF-8 API response document plus one LF, with no wrapper or reserialization. A local transport failure has the documented cliVersion 1 error document.
- Human mode never recomputes sorting, readiness, freshness, or business rules.
- Exit 0: achieved/idempotent/read result. Exit 2: Clikt usage error. Exit 3: typed business result that did not achieve the requested mutation. Exit 4: service/protocol/transport failure. Exit 1: unexpected local failure.
- Stderr is empty for API/business results and reserved for unexpected local diagnostics.
- Opening a browser uses ProcessBuilder with the exact list /usr/bin/open and URL, never a shell.
- Tests use temporary Unix servers, fake clients, fake OpenUrl/Sleeper, and captured streams. They never launch a GUI or sleep in real time.
- Every task ends with focused verification, git diff --check, an owned-path audit, and one commit.

## Original-Plan Coverage

This branch completes original Tasks 23-26 except bootstrap attachment, which Assembly owns.

---

### Task 1: Implement the Unix-Socket API Client

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/LocalApiClient.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/LocalApiResponse.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/cli/LocalApiClientTest.kt
- Create fixtures: src/test/resources/cli/v1/**

**Interfaces:**
- Consumes: Path to the B0-resolved Unix socket and generated request/response DTOs.
- Produces: GET/POST/PUT/DELETE operations returning strict decoded DTO plus original response JSON.

- [ ] **Step 1: Write failing transport tests**

Start a real temporary Unix HTTP server. Cover each method, request JSON, generated DTO decoding, original byte preservation, 10-second timeout via injected client configuration, connection refusal, missing socket, malformed JSON, non-2xx request-error decoding, cancellation, and idempotent close.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*LocalApiClientTest'
~~~

- [ ] **Step 3: Implement the narrow client**

Use HttpClient(CIO), http://localhost as the logical URL, and unixSocket(socketPath.toString()) on every request. Read a bounded response before strict decoding and retain the exact successful or error document. Do not retry mutations.

- [ ] **Step 4: Run GREEN and network-boundary scan**

~~~bash
./gradlew test --tests '*LocalApiClientTest'
rg -n '127\\.0\\.0\\.1|BITBUCKET_|jdbc:|BitbucketGateway|ApplicationTransaction' src/main/kotlin/com/mindtable/bitbuckethelper/cli
~~~

Expected: tests pass and no forbidden fallback/dependency is present.

- [ ] **Step 5: Commit**

~~~text
feat: add Unix socket product API client
~~~

---

### Task 2: Freeze Output and Exit Semantics

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/CliOutput.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/CliExit.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/cli/CliOutputTest.kt

**Interfaces:**
- Consumes: LocalApiResponse and typed generated result discriminators.
- Produces: OutputMode, CliOutcome, byte-exact JSON output, local error output, and stable exit mapping.

- [ ] **Step 1: Write failing output tests**

Require exactly one UTF-8 JSON document plus LF, empty stderr for typed API/business outcomes, byte preservation including field order, human output to stdout, no ANSI when not a terminal, stable 0/1/2/3/4 exits, and this local transport failure:

~~~json
{"cliVersion":"1","error":{"code":"SERVICE_UNAVAILABLE","message":"Bitbucket Helper service is unavailable. Run 'bitbucket-helper service status' and then 'bitbucket-helper service start'."}}
~~~

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*CliOutputTest'
~~~

- [ ] **Step 3: Implement one rendering boundary**

Commands return CliOutcome rather than writing ad hoc. JSON success/request-error uses retained API JSON; local failures use the fixed CLI envelope. Human renderers receive already ordered generated values.

- [ ] **Step 4: Run GREEN**

~~~bash
./gradlew test --tests '*CliOutputTest' --tests '*LocalApiClientTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: define product CLI output contract
~~~

---

### Task 3: Add Pull-Request and Inbox Read Commands

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/PullRequestCommands.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/InboxCommand.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/cli/ReadCommandsTest.kt

**Interfaces:**
- Consumes: LocalApiClient and CliOutput.
- Produces: pr list, pr show <pull-request-id>, and inbox commands.

- [ ] **Step 1: Write failing command tests**

With a fake client, require pr list grouped in API order with fixed N of 7/unavailable; pr show checks/builds/action metadata/freshness/links; inbox only actionable items with exact activityVersion; explicit successful empty states; syntactically invalid IDs as usage errors; valid unknown IDs as typed results; service unavailable exit 4; and byte-exact --output json.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*ReadCommandsTest'
~~~

- [ ] **Step 3: Implement minimal renderers**

Do not sort, calculate readiness, or fetch live content. Apply ANSI only behind injected terminal capability. Treat opaque ID validation as syntax only.

- [ ] **Step 4: Run GREEN**

~~~bash
./gradlew test --tests '*ReadCommandsTest' --tests '*CliOutputTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: add pull request and inbox commands
~~~

---

### Task 4: Add Safe Open Command

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/OpenCommand.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/OpenUrl.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/cli/OpenCommandTest.kt

**Interfaces:**
- Consumes: PR detail through LocalApiClient and injected OpenUrl.
- Produces: open <pull-request-id> and a direct production macOS opener.

- [ ] **Step 1: Write failing command tests**

Require exactly one PR-detail API call, exactly one opener call only for a successful HTTPS Bitbucket link, no call for not-found/unavailable/invalid links, bounded process wait, safe fixed local failure, and no shell interpretation of spaces/metacharacters.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*OpenCommandTest'
~~~

- [ ] **Step 3: Implement direct ProcessBuilder invocation**

Production OpenUrl uses ProcessBuilder(listOf("/usr/bin/open", url)), bounded wait, exit inspection, and cleanup. Tests inject a fake and never open a GUI.

- [ ] **Step 4: Run GREEN and shell scan**

~~~bash
./gradlew test --tests '*OpenCommandTest'
rg -n 'sh -c|bash -c|zsh -c|Runtime\\.getRuntime' src/main/kotlin/com/mindtable/bitbuckethelper/cli
~~~

Expected: scan returns no shell execution.

- [ ] **Step 5: Commit**

~~~text
feat: open pull requests safely
~~~

---

### Task 5: Add Exact-Version Acknowledgment

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/AcknowledgeCommand.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/cli/AcknowledgeCommandTest.kt

**Interfaces:**
- Consumes: acknowledgment generated request/result DTOs.
- Produces: ack <action-item-id> <activity-version>.

- [ ] **Step 1: Write failing result tests**

Cover acknowledged and alreadyAcknowledged exit 0; stale, rejected, and not found exit 3; malformed arguments exit 2; service/protocol failure exit 4; exact apiVersion "1" request body; human messages that show the targeted version; and byte-exact JSON.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*AcknowledgeCommandTest'
~~~

- [ ] **Step 3: Implement one request/one outcome**

Never infer or refetch an activity version. Send exactly the user-provided opaque version and map the server discriminator without local business logic.

- [ ] **Step 4: Run GREEN**

~~~bash
./gradlew test --tests '*AcknowledgeCommandTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: acknowledge exact activity versions
~~~

---

### Task 6: Add Refresh Registration and Polling

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/RefreshCommand.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/Sleeper.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/cli/RefreshCommandTest.kt

**Interfaces:**
- Consumes: refresh start/status DTOs, LocalApiClient, and injected Sleeper.
- Produces: refresh [--repository <repository-id>] [--no-wait].

- [ ] **Step 1: Write failing polling tests**

Cover all configured repositories by default; deduplicated repeated repository IDs; run registration; join/in-progress dispositions; server-provided active polling delay; terminal completion; --no-wait; unavailable exit 3; JSON mode emitting only the last applicable API envelope; run expiry cap; and interruption with no detached job.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*RefreshCommandTest'
~~~

- [ ] **Step 3: Implement bounded synchronous polling**

Use a Sleeper seam; no real sleeps in tests. Respect run expiry, always close the client, and offer no force/ignore-backoff option.

- [ ] **Step 4: Run GREEN**

~~~bash
./gradlew test --tests '*RefreshCommandTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: add refresh CLI workflow
~~~

---

### Task 7: Add Workspace and Repository Configuration Commands

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/ConfigurationCommands.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/cli/ConfigurationCommandsTest.kt

**Interfaces:**
- Consumes: configuration generated DTOs.
- Produces: workspace show/configure and repository add/remove.

- [ ] **Step 1: Write failing command matrices**

Cover workspace show; workspace configure --api-base-url <url> --slug <slug>; repository add <slug>; repository remove <repository-id>; all typed result variants and exits; global output propagation; immutable-identity human guidance; and one-document JSON output.

- [ ] **Step 2: Add credential/privacy cases**

Help and parsing must never accept username/password/app-password options. Messages never echo credential environment names, raw upstream errors, or activity content.

- [ ] **Step 3: Run RED**

~~~bash
./gradlew test --tests '*ConfigurationCommandsTest'
~~~

- [ ] **Step 4: Implement and run GREEN**

~~~bash
./gradlew test --tests '*ConfigurationCommandsTest' --tests '*ReadCommandsTest' --tests '*AcknowledgeCommandTest' --tests '*RefreshCommandTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: add configuration CLI commands
~~~

---

### Task 8: Export the Product Command Factory and Accept the Branch

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/cli/ProductCommandFactory.kt
- Create: docs/cli-json-v1.md
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/cli/ProductCommandFactoryTest.kt
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/cli/CliArchitectureTest.kt

**Interfaces:**
- Consumes: completed commands plus injected socket Path, streams, terminal capability, OpenUrl, and Sleeper.
- Produces: productCommands(dependencies): List<CliktCommand>, full help surface, documentation, and architecture evidence for Assembly.

- [ ] **Step 1: Write the failing factory test**

Build a test root from ProductCommandFactory without bootstrap. Require pr list/show, inbox, open, ack, refresh, workspace show/configure, repository add/remove, and global --output human|json. Help must create no client, access no socket, and start no service.

- [ ] **Step 2: Write the architecture test**

Inspect CLI classes/imports and fail on domain, application, persistence, Bitbucket adapter, HTTP server, bootstrap ServiceRuntime/ServiceConfiguration, environment credential, or TCP-client dependencies.

- [ ] **Step 3: Implement and document**

docs/cli-json-v1.md records exact commands, original API-envelope output, local SERVICE_UNAVAILABLE document, exits 0/1/2/3/4, stderr policy, refresh JSON behavior, and the rule to branch on result.type/error.code rather than human text.

- [ ] **Step 4: Run the complete branch gate**

~~~bash
./gradlew test --tests 'com.mindtable.bitbuckethelper.cli.*'
./gradlew clean check verifyApiV1Generated
./gradlew buildFatJar
rg -n '127\\.0\\.0\\.1|jdbc:|BitbucketGateway|ApplicationTransaction|ServiceRuntime' src/main/kotlin/com/mindtable/bitbuckethelper/cli
git diff --check
git diff --name-only B0..HEAD
~~~

Expected: Gradle commands pass, dependency scan has no forbidden use, and every changed path is owned by this plan. Replace B0 with the recorded SHA.

- [ ] **Step 5: Commit and report**

Commit:

~~~text
feat: export complete product CLI
~~~

Report branch HEAD, B0 SHA, task commits, full gate output, and the exact ProductCommandFactory signature. Do not attach commands to bootstrap.
