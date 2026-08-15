# Bitbucket Helper V1 Bitbucket and API Transports Parallel Implementation Plan

> **For the API workstream orchestrator:** Run this complete plan in its own top-level Codex session, separate from the other three workstreams and from assembly. REQUIRED SUB-SKILLS: Use superpowers:subagent-driven-development to orchestrate task-level implementer/reviewer agents, superpowers:test-driven-development for each adapter/route task, and superpowers:verification-before-completion before handing off the branch. Work only in codex/v1-api-transports. Check off every step and commit after every task.

**Goal:** Implement the production Bitbucket Cloud anti-corruption adapter and the complete versioned Ktor API over secure loopback and user-only Unix-socket transports.

**Architecture:** A generated client talks to a pinned, reduced Bitbucket snapshot and maps immediately into frozen application gateway models. Handwritten Ktor routes map frozen application results to committed generated product DTOs. Both transports install one business route set; browser-only security wraps loopback. Tests use local fake HTTP servers and fake use cases, never Core implementations or production bootstrap.

**Tech Stack:** Kotlin 2.4.10, Ktor 3.5.1 CIO server/client, kotlinx.serialization 1.11.0, OpenAPI Generator 7.24.0, JUnit 6.1.3, the pinned Bitbucket snapshot, and B0 generated product API DTOs.

## Preconditions

- Start from the exact B0 SHA recorded by docs/superpowers/plans/2026-08-15-bitbucket-helper-v1-parallel-assembly.md.
- Work on branch codex/v1-api-transports in .worktrees/v1-api-transports.
- Run ./gradlew clean check verifyApiV1Generated before changing a file.
- Treat B0 ports, models, openapi/**, committed product DTOs, and shared fixtures as read-only.
- Never run syncApiV1Generated on this branch. A product-contract defect goes back to the baseline maintainer.

## Dedicated Session Contract

- This plan is the sole implementation brief for the API session orchestrator. Do not run another companion plan in this session.
- No other session may use this worktree or write to codex/v1-api-transports.
- Task-level subagents are allowed inside this session, but the API orchestrator owns sequencing, review, commits, and final evidence.
- Do not depend on messages or uncommitted files from another execution session. B0 and its approved documents are the only shared input.
- A B0 or generated-contract defect is a stop condition: add only a focused failing test when it lies in an owned path, report the smallest contract change, and wait for a new baseline SHA.
- The final handoff must include the full B0 SHA, branch HEAD SHA, task commit list, git diff --name-status B0..HEAD, clean git status, every verification command with result, and unresolved risks.
- After handoff, do not rebase, merge, or add commits unless the assembly orchestrator explicitly returns a defect to this session.

Before Task 1, the API orchestrator runs:

~~~bash
git branch --show-current
git rev-parse HEAD
git status --short
./gradlew clean check verifyApiV1Generated
~~~

Expected: branch codex/v1-api-transports, HEAD exactly B0, clean status, passing baseline gate.

## Exclusive Ownership

This branch may create or modify only:

- the Bitbucket reducer/generator section of build.gradle.kts
- specs/bitbucket-cloud/openapi-templates/** when a focused generated-response test proves it necessary
- src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/**
- src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/**
- src/test/resources/bitbucket/v1/**
- new files under src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/**
- new tests under src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/**

Do not edit openapi/**, src/generated/**, web/src/generated/**, domain/**, application/**, CLI, notification/scheduler adapters, bootstrap/**, application.conf, ArchitectureTest.kt, WalkingSkeletonEndToEndTest.kt, README.md, docs/project-backlog.md, or source/.

Keep the transitional BitbucketStatusRoutes source/test until assembly because the B0 runtime still references it. Assembly removes it after production composition switches to the v1 routes.
Keep GeneratedBitbucketAccountGateway as a compatibility wrapper or factory delegating to the new gateway so the B0 runtime and walking-skeleton tests continue to compile. Assembly removes the obsolete composition reference after merge.

## Global Constraints

- Generated Bitbucket DTOs never escape adapter/outbound/bitbucket.
- Authentication comes only from the B0-injected BITBUCKET_USERNAME/BITBUCKET_APP_PASSWORD values; never log, persist, or return them.
- Follow pagination only through opaque same-origin next URLs, with a hard maximum of 100 pages and no silent collection cap.
- Never place credentials, Authorization headers, query strings, response bodies, raw activity content, or stack traces in gateway failures.
- Every valid API request returns 200 with a typed business result. Only malformed/unauthorized transport requests use 4xx; unexpected server failure uses 500.
- All /api/v1 responses use JSON and Cache-Control: no-store.
- Browser requests enforce exact Host and supplied Origin; mutations additionally require exact Origin, application/json, and CSRF. Do not enable CORS.
- Unix transport uses the same business route suite without browser security and exposes no browser-session endpoint.
- Normal verification uses only local servers, temporary sockets, committed specs, and fake use cases.
- Every task ends with focused verification, git diff --check, an owned-path audit, and one commit.

## Original-Plan Coverage

This branch completes original Task 9 and the adapter/transport portions of Tasks 19-22. B0 owns Tasks 16-18. Assembly owns configuration/runtime wiring, legacy-route deletion, and repository-level end-to-end tests.

---

### Task 1: Expand the Deterministic Bitbucket Client Reducer

**Files:**
- Modify: build.gradle.kts
- Modify only if required: specs/bitbucket-cloud/openapi-templates/libraries/jvm-ktor/infrastructure/HttpResponse.kt.mustache
- Modify: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/OpenApiSnapshotContractTest.kt
- Create or modify: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedHttpResponseContractTest.kt

**Interfaces:**
- Consumes: the committed canonical Bitbucket Swagger/OpenAPI 2 snapshot.
- Produces: one deterministic read-only generated client with response metadata sufficient for pagination.

- [ ] **Step 1: Extend the failing snapshot contract**

Require only these GET operations with explicit operation IDs: current user; workspace; repository; authored open PR list; PR detail; effective default reviewers; statuses; tasks; activity; and one comment. Reject every mutation.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*OpenApiSnapshotContractTest'
~~~

Expected: the current reducer contains only GET /user.

- [ ] **Step 3: Generalize selection**

Use an ordered path/method/operationId table, copy path-level parameters, recursively close definitions/parameters/responses, retain the canonical snapshot byte-for-byte, and write deterministic reduced output below build/.

- [ ] **Step 4: Prove pagination metadata**

Only change the local template if the existing seam cannot expose Link/next information safely. The focused test must prove generated output can carry the needed response metadata without exposing auth or body text in errors.

- [ ] **Step 5: Verify and commit**

~~~bash
./gradlew test --tests '*OpenApiSnapshotContractTest' --tests '*GeneratedHttpResponseContractTest'
./gradlew verifyApiV1Generated
git diff --check
~~~

Commit:

~~~text
build: expand the selected Bitbucket client
~~~

---

### Task 2: Map Account, Workspace, and Repository Identity

**Files:**
- Replace: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketAccountGateway.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketGateway.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/BitbucketMappings.kt
- Create: src/test/resources/bitbucket/v1/current-user.json
- Create: src/test/resources/bitbucket/v1/workspace.json
- Create: src/test/resources/bitbucket/v1/repository.json
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketGatewayIdentityTest.kt

**Interfaces:**
- Consumes: B0 BitbucketGateway and gateway result models.
- Produces: currentUser, resolveWorkspace, and resolveRepository operations.

- [ ] **Step 1: Write failing mapping tests**

Use a local fake server to cover stable UUIDs/slugs/display names/links, normalized configured origin/path, missing required fields, invalid UUIDs, 401, 403, 404, 429 with retry metadata, 5xx, malformed JSON, timeout, and cancellation.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*GeneratedBitbucketGatewayIdentityTest'
~~~

- [ ] **Step 3: Implement immediate anti-corruption mapping**

Reuse the walking-skeleton Basic-auth engine ownership. Convert generated values before returning. Preserve cancellation. Map all expected upstream outcomes to safe, typed B0 failures.

- [ ] **Step 4: Run GREEN and privacy scan**

~~~bash
./gradlew test --tests '*GeneratedBitbucketGatewayIdentityTest'
rg -n 'Authorization|responseBody|password|appPassword' src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket
~~~

Review every match; no secret/body may enter a result or log.

- [ ] **Step 5: Commit**

~~~text
feat: resolve Bitbucket installation identities
~~~

---

### Task 3: Implement Authored Open Pull-Request Pagination

**Files:**
- Modify: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketGateway.kt
- Modify: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/BitbucketMappings.kt
- Create: src/test/resources/bitbucket/v1/pull-requests-page-1.json
- Create: src/test/resources/bitbucket/v1/pull-requests-page-2.json
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketGatewayPaginationTest.kt

**Interfaces:**
- Consumes: authenticated current-user UUID and configured repository.
- Produces: complete authored-open PR summary results plus authoritative/non-authoritative failure distinction.

- [ ] **Step 1: Write failing request/pagination tests**

Require state="OPEN" AND author.uuid="<current uuid>", normal treatment of drafts, stable ID mapping, all pages, exactly one request per opaque next link, rejection of cross-origin next, loop detection, more than 100 pages, malformed next, and mid-page failure.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*GeneratedBitbucketGatewayPaginationTest'
~~~

- [ ] **Step 3: Implement bounded same-origin traversal**

Resolve opaque next URLs against the configured API origin, require exact scheme/host/effective port, retain the configured /2.0 scope, track visited URLs, and return a non-authoritative typed failure on any incomplete traversal.

- [ ] **Step 4: Run GREEN**

~~~bash
./gradlew test --tests '*GeneratedBitbucketGatewayPaginationTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: list complete authored open pull requests
~~~

---

### Task 4: Implement PR Detail, Readiness Inputs, Activity, and Live Content

**Files:**
- Modify: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketGateway.kt
- Modify: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/BitbucketMappings.kt
- Create: src/test/resources/bitbucket/v1/pull-request-detail.json
- Create: src/test/resources/bitbucket/v1/default-reviewers.json
- Create: src/test/resources/bitbucket/v1/statuses.json
- Create: src/test/resources/bitbucket/v1/tasks.json
- Create: src/test/resources/bitbucket/v1/activity.json
- Create: src/test/resources/bitbucket/v1/comment.json
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketGatewayTest.kt

**Interfaces:**
- Consumes: remaining B0 BitbucketGateway operations.
- Produces: normalized detail/reviewer/build/task/activity observations and narrowly scoped live content.

- [ ] **Step 1: Write failing fixture tests**

Cover participants/approvals, effective default reviewers, merge-conflict/branch state, all build statuses including STOPPED and unknown, unresolved tasks, comments/replies/change requests, deleted content, version metadata, and pagination for every collection.

- [ ] **Step 2: Add failure/privacy cases**

Cover 401/403/404/429/5xx, invalid JSON, timeout, missing required fields, cross-origin pagination, and raw live Markdown. Raw content may appear only in the successful live-content return value; never in failure, toString, exception, diagnostic, or captured request list.

- [ ] **Step 3: Run RED**

~~~bash
./gradlew test --tests '*GeneratedBitbucketGatewayTest'
~~~

- [ ] **Step 4: Implement and run GREEN**

~~~bash
./gradlew test --tests '*OpenApiSnapshotContractTest' --tests '*GeneratedBitbucketGatewayIdentityTest' --tests '*GeneratedBitbucketGatewayPaginationTest' --tests '*GeneratedBitbucketGatewayTest' --tests '*GeneratedHttpResponseContractTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: translate complete Bitbucket pull request state
~~~

---

### Task 5: Establish Ktor V1 Envelopes and Request Errors

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Module.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Envelopes.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Errors.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/RequestIds.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1ModuleTest.kt

**Interfaces:**
- Consumes: committed generated v1 DTOs and fake ApiV1Dependencies.
- Produces: Application.installApiV1, TransportKind, request IDs, JSON/no-store handling, and safe request-error envelopes.

- [ ] **Step 1: Write failing request-level tests**

Cover valid envelope, unique URL-safe req_ IDs, malformed JSON 400, invalid parameters with violations 400, unsupported media type 415, method 405, route 404, unexpected exception 500, cancellation rethrow, safe fixed messages, and Cache-Control: no-store on every /api/v1 response.

- [ ] **Step 2: Run RED**

~~~bash
./gradlew test --tests '*ApiV1ModuleTest'
~~~

- [ ] **Step 3: Install common Ktor behavior**

Configure kotlinx serialization consistently with generated DTOs. Create the request ID before routing. StatusPages handles only request/transport/unexpected errors; it must never translate a typed use-case outcome to a non-200 status.

- [ ] **Step 4: Run GREEN and forbidden-status scan**

~~~bash
./gradlew test --tests '*ApiV1ModuleTest'
rg -n 'Accepted|Conflict|ServiceUnavailable' src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http
~~~

Expected: tests pass and scan returns no response-status usage.

- [ ] **Step 5: Commit**

~~~text
feat: establish versioned Ktor API
~~~

---

### Task 6: Implement Read Routes

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ReadRoutes.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ReadResponseMappings.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ReadRoutesTest.kt

**Interfaces:**
- Consumes: B0 dashboard, PR, inbox, and synchronization query interfaces.
- Produces: every approved read route except health and live content.

- [ ] **Step 1: Write a failing result matrix**

For every frozen result variant, assert HTTP 200, apiVersion "1", requestId, exact generated discriminator, required nulls, stable array order, revision/freshness fields, and absence of raw content.

- [ ] **Step 2: Add syntax/entity distinction**

Malformed afterRevision or opaque ID is a 400 request error. A valid unknown ID is a typed not-found business result under 200.

- [ ] **Step 3: Run RED**

~~~bash
./gradlew test --tests '*ReadRoutesTest'
~~~

- [ ] **Step 4: Implement thin mappings and run GREEN**

Each route validates syntax, invokes exactly one use case, maps its result, and returns 200. Put no sorting, grouping, freshness, readiness, or lookup rules in Ktor.

~~~bash
./gradlew test --tests '*ReadRoutesTest' --tests '*ApiV1ModuleTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: expose API read projections
~~~

---

### Task 7: Implement Live-Content and Command Routes

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ActionItemRoutes.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/RefreshRunRoutes.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ConfigurationRoutes.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/CommandResponseMappings.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ActionItemRoutesTest.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/RefreshRunRoutesTest.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ConfigurationRoutesTest.kt

**Interfaces:**
- Consumes: B0 action, refresh-run, and configuration use cases.
- Produces: every approved live-content and business mutation route.

- [ ] **Step 1: Write failing result matrices**

Cover every B0 discriminator for live content, acknowledgment, refresh registration/inspection, workspace configuration, repository add, and repository remove. Every valid result uses 200.

- [ ] **Step 2: Add request errors**

Missing/invalid activityVersion, malformed IDs, invalid JSON/apiVersion, absent application/json, empty repository target, and invalid base URL are request errors. Unknown well-formed entities remain 200 typed business results.

- [ ] **Step 3: Run RED**

~~~bash
./gradlew test --tests '*ActionItemRoutesTest' --tests '*RefreshRunRoutesTest' --tests '*ConfigurationRoutesTest'
~~~

- [ ] **Step 4: Implement thin mappings and run GREEN**

Live content is the only mapper allowed to see raw Markdown and returns it only in contentAvailable. Never include it in an exception or diagnostic.

~~~bash
./gradlew test --tests '*ActionItemRoutesTest' --tests '*RefreshRunRoutesTest' --tests '*ConfigurationRoutesTest' --tests '*ApiV1ModuleTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: expose API commands
~~~

---

### Task 8: Add Browser Security, Health, and Dual Local Servers

**Files:**
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/LocalApiServerConfiguration.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BrowserSecurity.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/HealthRoutes.kt
- Create: src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/LocalApiServers.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BrowserSecurityTest.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/HealthRoutesTest.kt
- Test: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/LocalTransportParityTest.kt

**Interfaces:**
- Consumes: GetHealthSnapshot and all B0 route dependencies.
- Produces: browser-only session/security, safe health, and lifecycle-owned loopback/Unix CIO servers.

- [ ] **Step 1: Write failing browser-security tests**

Require exact Host equal to configured 127.0.0.1:resolvedPort; optional GET Origin must match when present; mutations require exact Origin, application/json, and X-CSRF-Token; browser-session returns random in-memory token/serviceInstanceId; restart changes both; rejected requests invoke no use case; and CORS headers are always absent.

- [ ] **Step 2: Write failing health/parity tests**

Start real CIO servers with fake use cases. Run the shared route matrix over loopback and Unix. Browser-session is 404 on Unix; browser controls are not required there. Health is always HTTP 200 with typed healthy/degraded/unhealthy components and safe messages.

- [ ] **Step 3: Add socket lifecycle cases**

Require parent directory rwx------ before bind, stale socket removal, socket rw------- immediately after start, fail-closed permission errors, idempotent close, and socket removal on close.

- [ ] **Step 4: Implement and run GREEN**

LocalApiServers owns two server instances and installs identical business routes with TransportKind.BROWSER or UNIX. LocalApiServerConfiguration carries already validated host, port, and socket Path; the adapter does not load environment or credentials.

~~~bash
./gradlew test --tests '*BrowserSecurityTest' --tests '*HealthRoutesTest' --tests '*LocalTransportParityTest'
~~~

- [ ] **Step 5: Commit**

~~~text
feat: serve secured API on local transports
~~~

---

### Task 9: Run Branch Acceptance

**Files:**
- Create: src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiTransportAcceptanceTest.kt

**Interfaces:**
- Consumes: the complete API branch with fake application use cases and local fake Bitbucket.
- Produces: branch-local evidence independent of Core and production bootstrap.

- [ ] **Step 1: Add a failing acceptance matrix**

Start both real transports. Exercise at least one result variant per endpoint family, all request-error classes, browser security, health states, socket permissions/cleanup, one multipage Bitbucket refresh input mapping, and a live-content value containing sensitive marker text.

- [ ] **Step 2: Assert seam and privacy rules**

Prove generated Bitbucket DTO classes do not appear in B0 gateway return types; generated product DTOs do not enter fake use-case signatures; sensitive live marker appears only in the successful live-content body; error/log captures do not contain it.

- [ ] **Step 3: Run focused acceptance**

~~~bash
./gradlew test --tests '*ApiTransportAcceptanceTest'
~~~

- [ ] **Step 4: Run the complete branch gate**

~~~bash
./gradlew clean check verifyApiV1Generated
./gradlew buildFatJar
rg -n 'Accepted|Conflict|ServiceUnavailable' src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http
git diff --check
git diff --name-only B0..HEAD
~~~

Expected: all Gradle commands pass, forbidden-status scan has no use, and every changed path is owned by this plan. Replace B0 with the recorded SHA.

- [ ] **Step 5: Commit and report**

Commit:

~~~text
test: accept Bitbucket and API transports
~~~

Report the branch HEAD, B0 SHA, task commits, full gate output, and any requested baseline changes. Do not merge or modify production bootstrap.
