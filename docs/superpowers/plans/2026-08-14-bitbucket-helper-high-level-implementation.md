# Bitbucket Helper High-Level Implementation Plan

**Date:** 2026-08-14

**Status:** High-level scope for review; detailed implementation plans are
separate design sessions

## Goal

Deliver a single-user, local macOS Bitbucket assistant through a Vue dashboard,
service-backed product CLI, and scheduled jobs. One Kotlin service owns state and
business execution. A separate generic Python project delivers desktop
notifications through a deliberately narrow CLI contract.

This document defines delivery order, outcomes, dependencies, and design gates.
It intentionally does not define Kotlin source files, Gradle configuration,
package-level implementation, test code, database schemas, HTTP payloads, or
notification command arguments.

## Delivery principles

- Implement vertical behavior through explicit application use cases.
- Keep the domain independent of Ktor, Clikt, Quartz, persistence, Bitbucket
  response types, and notification process details.
- Keep one long-running service as the only state owner and business executor.
- Treat the web dashboard and product CLI as clients of that service.
- Keep repository grouping in read projections rather than repository-sized
  transactional aggregates.
- Establish a focused design before implementing each deferred public or durable
  contract.
- Keep each phase independently verifiable and avoid introducing infrastructure
  before the use case that requires it.
- Preserve the existing untracked `source/` shell prototype unchanged throughout
  the transition.

## Phase map

```text
0. Design gates and repository safety
             |
             v
1. Repository separation and Kotlin foundation
             |
             v
2. Domain model and application use cases
             |
             v
3. Persistence foundation
             |
             v
4. Bitbucket synchronization and read projections
             |
       +-----+------------------+
       |                        |
       v                        v
5. Local service/API/CLI    6. Scheduling/notifications
       |                        |
       +------------+-----------+
                    v
             7. Vue dashboard
                    |
                    v
             8. macOS operations
                    |
                    v
             9. End-to-end hardening
```

## Phase 0 — Design gates and repository safety

### Outcomes

- Keep the approved architecture specification as the product boundary.
- Maintain `docs/project-backlog.md` as the durable register for deferred design
  and implementation work.
- Capture and verify the protected `source/` baseline before repository changes.
- Confirm the two-repository boundary:
  - `bitbucket-helper`: Kotlin service, product CLI, and Vue SPA.
  - `desktop-notifications`: generic Python library and CLI.

### Required design gates

The following focused designs must be completed before their implementation
begins:

- Kotlin/Gradle project foundation details.
- Persistence ports, SQLite, migrations, and in-memory parity.
- Quartz scheduling and coroutine integration.
- SPA/backend API contract.
- Kotlin/desktop-notifications CLI contract.
- Generic notification delivery and deduplication semantics.
- Ignored Bitbucket actor matching.
- Kotlin application installation and update behavior.

### Exit condition

Every implementation phase has an identified use-case boundary, prerequisite
design, and verification outcome; no protected prototype file is modified.

## Phase 1 — Repository separation and Kotlin foundation

### Outcomes

- Establish `../desktop-notifications` as an independent Git repository before
  removing the Python scaffold from `bitbucket-helper`.
- Recreate the generic UV/Hatchling package foundation under
  `desktop_notifications`, with matching tests and structure documentation.
- Do not expose the notification CLI until its cross-repository contract is
  designed.
- Replace the Bitbucket Helper Python scaffold with a one-module Kotlin/JVM
  foundation using the separately scoped Kotlin plan.
- Produce one application entrypoint and executable distribution shape.
- Add executable architecture checks for DDD dependency direction and product CLI
  isolation.
- Replace root Python setup documentation only after the sibling package is
  independently verified.

### Exit condition

Both repositories build and test independently; Bitbucket Helper has an
enforceable Kotlin foundation; the notification project is generic; `source/`
remains byte-identical and untracked.

## Phase 2 — Domain model and application use cases

### Outcomes

- Model installation configuration, pull requests, action items, stable
  identities, activity versions, acknowledgments, readiness, and green
  transitions.
- Implement the fixed seven-check readiness policy and repeated false-to-true
  build-green transitions.
- Implement action-item evolution for external comments, replies, edits,
  changes-requested events, own replies, resolution, deletion, and reopening.
- Make exact-version acknowledgment idempotent while rejecting stale versions
  with explicit newer-state information.
- Define commands and queries as suspendable application entrypoints.
- Define application-owned ports for Bitbucket, persistence, notifications,
  transactions, and time without implementing production adapters.
- Build repository-grouped dashboard and inbox projections on the read side.

### Exit condition

Pure domain and application tests cover the approved invariants with in-memory
fakes; no test requires Ktor, SQLite, Quartz, terminal-notifier, or a live
Bitbucket account.

## Phase 3 — Persistence foundation

### Prerequisite

Complete the focused persistence design.

### Outcomes

- Implement SQLite as the embedded durable adapter and an in-memory reference
  adapter against one behavioral contract suite.
- Persist configuration, normalized PR observations, action metadata and
  versions, acknowledgments, synchronization health, notification intents and
  attempts, and retention metadata.
- Keep raw comment and thread bodies outside durable storage.
- Implement migrations, transaction boundaries, pruning, and recovery behavior.

### Exit condition

Both adapters pass the same contract tests, application transactions preserve
invariants, and restart/recovery tests retain the required state without storing
live content bodies.

## Phase 4 — Bitbucket synchronization and read projections

### Outcomes

- Implement the Bitbucket anti-corruption adapter using environment-provided
  credentials.
- Configure one workspace and a repository allowlist, with repository addition
  available through an application use case.
- Synchronize open authored PRs, treating drafts normally.
- Poll lightweight summaries and fetch detail only for new or changed PRs, with
  bounded reconciliation for incomplete upstream signals.
- Implement per-repository single-flight refresh and protect newer state from
  slower observations.
- Populate existing action items on first sync and create one initial digest
  intent per repository.
- Preserve last-known-good snapshots through upstream failures and expose
  freshness, current errors, and unavailable live content explicitly.
- Mark PRs inactive only after authoritative observation and retain them according
  to the configured policy.

### Exit condition

Fake-backed integration and concurrency tests cover first sync, repeated refresh,
partial failure, rate limiting, stale observations, live-body unavailability, and
repository-grouped query results.

## Phase 5 — Local service, API, and product CLI

### Prerequisites

- Complete the SPA/backend API contract design.
- Complete the detailed Kotlin/Ktor transport plan.

### Outcomes

- Run one Ktor service that is the only process allowed to execute business use
  cases and durable writes.
- Serve the approved application contract over loopback HTTP for the browser and
  over a user-only Unix socket for the product CLI.
- Publish OpenAPI from the agreed source of truth.
- Enforce loopback Host/Origin validation, mutation CSRF protection, and no
  permissive CORS.
- Implement the Clikt command surface for PR queries, inbox, acknowledgment,
  refresh, opening Bitbucket links, workspace/repository configuration, and
  service lifecycle operations.
- Keep business CLI commands as service clients only, with explicit human and
  versioned JSON output modes.
- Fail clearly with service-start/status guidance when the socket is unavailable.

### Exit condition

Contract and transport tests prove equivalent business behavior over browser HTTP
and Unix-socket HTTP, stale acknowledgments remain explicit, and no CLI business
command bypasses the service.

## Phase 6 — Scheduling and desktop notifications

### Prerequisites

- Complete the Quartz/coroutine scheduling design.
- Complete the desktop-notifications CLI contract design.
- Complete generic notification delivery and deduplication design.

### Outcomes

- Add Quartz with RAMJobStore as a replaceable timing adapter.
- Have scheduled work invoke and await the same application use cases as manual
  requests.
- Keep durable retry and reminder state in application persistence rather than
  Quartz.
- Expose the generic notification Python CLI according to the approved protocol
  and install it persistently with `uv tool install`.
- Invoke its validated absolute path from Kotlin without a shell.
- Commit notification intents before external delivery and retain failed intents
  for bounded retry and periodic recovery.
- Deliver first-sync digests, actionable-activity notifications, every green
  transition, and due reminders, grouped for display by repository.

### Exit condition

Fake-process and scheduler tests cover protocol compatibility, timeouts, malformed
results, retry, restart, overlap, repeated green transitions, and logical
deduplication without claiming guarantees beyond the approved design.

## Phase 7 — Vue dashboard

### Prerequisite

The SPA/backend API contract and Ktor implementation are stable enough for a
client.

### Outcomes

- Create a small Vue 3, Vite, and TypeScript workspace under `web/`.
- Display repository-grouped PR cards, fixed `N of 7` readiness, build state,
  actionable activity, synchronization health, and Bitbucket links.
- Support exact-version acknowledgment and show newer activity when a conflict
  occurs.
- Show unavailable live bodies and stale snapshots explicitly.
- Keep components and styling intentionally simple for personal customization.
- Build static assets and package them into the Kotlin service distribution.

### Exit condition

Component and browser tests cover grouping, readiness, errors, unavailable
content, acknowledgment success, and stale conflicts; the packaged service serves
the production SPA without a separate web deployment.

## Phase 8 — macOS service management and installation

### Prerequisite

Complete the application installation and update design.

### Outcomes

- Implement LaunchAgent installation, start, stop, status, logs, repair guidance,
  and uninstall behavior.
- Resolve absolute Java, application JAR, and desktop-notifications executable
  paths during installation.
- Capture current Bitbucket credentials into a user-only LaunchAgent plist while
  documenting and containing the plaintext-at-rest risk.
- Create user-only socket, database, log, and service paths.
- Add orderly service startup and structured coroutine shutdown.
- Define and verify upgrade, rollback, credential rotation, and broken-path
  recovery.

### Exit condition

Lifecycle tests validate generated LaunchAgent configuration, permissions,
redaction, path diagnostics, restart behavior, and safe replacement without
requiring manual database edits.

## Phase 9 — End-to-end hardening and v1 release

### Outcomes

- Exercise the complete flow with fake Bitbucket and notification processes.
- Verify first sync, manual and scheduled refresh, exact-version acknowledgment,
  notification retry, outage recovery, retention, and restart behavior.
- Verify browser security and Unix-socket permissions.
- Verify no raw comment/thread body reaches persistence and no credential reaches
  logs or diagnostics.
- Document setup, configuration, service operation, troubleshooting, backup,
  recovery, upgrades, and uninstall.
- Keep live-account tests explicit and opt-in.

### Exit condition

All automated checks pass from clean checkouts, operational documentation matches
the installed system, and every v1 acceptance criterion in the architecture
specification has evidence.

## Separate scoping sessions

The next planning session for Kotlin should decide only Kotlin foundation details:
project/bootstrap structure, exact compatible dependency versions, Gradle Wrapper
and fat-JAR setup, package namespace, Clikt entrypoint shape, architecture-test
mechanism, verification commands, and the safe removal sequence for the temporary
Python scaffold.

The SPA/backend and desktop-notifications CLI contracts remain independent design
sessions because they are public cross-component boundaries. Their open questions
and expected outputs are recorded explicitly in `docs/project-backlog.md`.
