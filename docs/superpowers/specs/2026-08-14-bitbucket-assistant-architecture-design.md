# Bitbucket Helper Kotlin DDD Architecture Design

**Date:** 2026-08-14

**Status:** Approved

## Context

Bitbucket Helper is a single-user, local macOS assistant for monitoring and
triaging Bitbucket Cloud pull requests. It presents one product through a focused
web dashboard, a service-backed command-line interface, and scheduled jobs. One
long-running service owns all durable state and is the only process allowed to
execute business use cases directly.

The approved product architecture makes Bitbucket Helper a Kotlin/JVM
application. The generic desktop notification library remains Python in the
separate sibling repository `desktop-notifications`. The Kotlin application will
invoke that library only through its installed CLI after the shared CLI contract
is designed and implemented.

Repository separation is complete: `desktop-notifications` contains the verified
generic Python/UV foundation, and `bitbucket-helper` no longer contains the
obsolete tracked Python scaffold. The Kotlin/Gradle foundation remains separate
design-gated work. The untracked `source/` directory is a protected shell
prototype and remains unchanged.

## Architectural drivers

- Run entirely on one Mac for exactly one user and one Bitbucket identity.
- Configure exactly one Bitbucket workspace and an allowlist of repositories.
- Make application use cases the only business entrypoints.
- Keep the domain independent of Ktor, Quartz, Clikt, persistence, Bitbucket
  response models, and notification process details.
- Keep the dashboard deliberately small and easy to customize.
- Let the CLI and scheduler reuse the service's behavior rather than reimplement
  or bypass it.
- Preserve useful last-known-good state through Bitbucket outages.
- Allow persistence, scheduling, and notification implementations to be replaced
  behind ports without building a general plugin platform.
- Keep the generic notification capability reusable by other local Python projects
  and an AI assistant.

## V1 scope

- Monitor open pull requests authored by the configured Bitbucket identity.
- Treat draft pull requests like other open pull requests.
- Configure repositories by slug within one configured workspace.
- Display pull requests grouped by repository.
- Calculate the fixed-denominator seven-check readiness assessment.
- Detect every build-green false-to-true transition.
- Create actionable items for external comments, replies, and formal
  changes-requested events.
- Acknowledge the exact locally observed activity version.
- Populate the inbox from existing activity during first synchronization and send
  one initial digest per repository.
- Send notifications for new or advanced action items, build-green transitions,
  and periodic reminders.
- Provide a Vue dashboard and a read-and-triage CLI.
- Start one service at login through a per-user LaunchAgent.
- Ship SQLite and in-memory implementations of the persistence contract after the
  focused persistence design is approved.

## Non-goals

- Multiple users, multiple Bitbucket identities, multiple workspaces, or remote
  deployment.
- Bitbucket mutations such as commenting, approving, merging, or changing PR
  state.
- Persisting raw comment or thread bodies.
- Reproducing the complete Bitbucket interface.
- Selecting PRs assigned to the user for review in v1. The selection policy must
  allow this later.
- Runtime readiness plugins, a rule DSL, or end-user-authored checks in v1.
- Supporting arbitrary databases merely to demonstrate the persistence port.
- Event sourcing, separate command/read databases, microservices, or multiple
  service daemons.
- Claiming exactly-once notification delivery before the focused notification
  design defines identifiers and crash-window behavior.
- Implementing ignored-actor matching before Bitbucket identity availability and
  matching semantics have been researched.
- Quiet hours in v1. The default reminder cadence is hourly.

## Chosen solution shape

The selected approach is a two-repository local system:

1. `bitbucket-helper` is a Kotlin/JVM modular monolith. It contains the service,
   product CLI, Vue SPA, domain, application use cases, and adapters. It is built
   as one fat JAR and runs on an installed JDK 25.
2. `desktop-notifications` is a standalone Python/UV library and CLI. It contains
   only generic notification concepts and a macOS `terminal-notifier` adapter. It
   is installed persistently with `uv tool install`.

The alternatives were rejected for these reasons:

- Embedding notification delivery in Kotlin would remove the reusable Python
  library boundary required by other local projects and the AI assistant.
- Running the scheduler, API, and state owner as separate daemons would introduce
  failure coordination and deployment overhead without benefiting a single-user
  installation.
- Splitting every domain layer and adapter into separate Gradle modules would make
  the build structure larger than the application. One JVM module plus executable
  architecture tests gives an enforceable boundary with less ceremony.

## Technology decisions

| Area | Decision |
| --- | --- |
| Language/runtime | Kotlin/JVM on an installed JDK 25 |
| Build/distribution | Gradle Wrapper, one JVM module, one fat JAR |
| HTTP | Ktor 3.5.x, CIO, and `kotlinx.serialization` |
| Product CLI | One Clikt entrypoint with nested commands |
| Scheduling | Quartz 2.5.x with `RAMJobStore` as an inbound adapter |
| SPA | Vue 3, Vite, and TypeScript in a distinct `web/` workspace |
| Durable persistence | SQLite adapter behind application ports |
| Reference persistence | In-memory adapter passing the same contract suite |
| Generic notifications | Separate Python project installed through `uv tool install` |
| macOS service | Per-user LaunchAgent |

Exact compatible Kotlin, Gradle, and library patch versions are pinned in the
implementation plan or the focused adapter design that introduces them. The
architecture intentionally does not choose a JDBC library, schema, migration
tool, or Quartz wiring policy in this session.

## Target repository boundaries

The target `bitbucket-helper` repository is conceptually:

```text
bitbucket-helper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
├── gradlew
├── src/
│   ├── main/
│   │   ├── kotlin/<base-package>/
│   │   └── resources/web/
│   └── test/kotlin/<base-package>/
├── web/
│   ├── package.json
│   ├── src/
│   └── tests/
├── docs/
└── source/                         # protected shell prototype; unchanged
```

The target sibling notification repository is conceptually:

```text
desktop-notifications/
├── pyproject.toml
├── uv.lock
├── src/desktop_notifications/
├── tests/
└── docs/
```

The SPA source remains in `bitbucket-helper`. Its production build is packaged as
static resources in the fat JAR and served by the Kotlin service.

The repository-separation phase established and verified the sibling Python
foundation before removing the obsolete Python scaffold from `bitbucket-helper`.
The canonical UV structure guide now lives in `desktop-notifications`; the Kotlin
repository intentionally has no build foundation until its focused design and
implementation task is approved.

## Strategic DDD model

The core bounded context is **Pull Request Triage**: deciding which pull requests
need the user's attention, why, and whether that attention has been acknowledged.
The domain does not mirror all of Bitbucket.

The strategic boundaries are:

- **Pull Request Triage** — the one internal core bounded context.
- **Installation configuration** — a supporting model within that context.
- **Dashboard and inbox** — read projections, not transactional aggregates.
- **Bitbucket Cloud** — an upstream external context translated through an
  anti-corruption adapter.
- **desktop-notifications** — a separate generic context exposed to this product
  through a CLI anti-corruption adapter.
- Ktor, Quartz, Clikt, Vue, SQLite, and LaunchAgent management — adapters and
  operational infrastructure, not bounded contexts.

```text
Vue / product CLI / Quartz
            |
            v
   application use cases
            |
            v
   Pull Request Triage domain
      |                 |
      v                 v
 query projections   domain events
      |                 |
      v                 v
 dashboard/inbox    notification policy

Bitbucket -> anti-corruption adapter -> application ports
application -> notification adapter -> desktop-notifications CLI
```

## Ubiquitous language

- **Observation:** normalized metadata seen during one Bitbucket synchronization.
- **Readiness assessment:** the seven-check result for one coherent PR
  observation.
- **Action item:** external activity currently requiring attention.
- **Activity version:** the exact external state to which an acknowledgment refers.
- **Acknowledgment:** local confirmation of one observed activity version.
- **Green transition:** a build state changing from not-green to green.
- **Notification intent:** the application's durable decision to request a generic
  notification.
- **Inbox item:** a read projection of an actionable item.
- **Repository dashboard:** a read projection grouping repository metadata, PRs,
  action summaries, and synchronization health.

Bitbucket DTOs, database records, API request objects, notification CLI payloads,
and UI component models are not domain types.

## Dependency direction and package responsibilities

The single JVM module uses package boundaries enforced by architecture tests:

```text
domain <- application <- adapters <- bootstrap
```

The conceptual package tree is:

```text
src/main/kotlin/<base-package>/
├── domain/
│   ├── configuration/
│   ├── pullrequest/
│   └── actionitem/
├── application/
│   ├── command/
│   ├── query/
│   ├── policy/
│   └── port/
├── adapter/
│   ├── inbound/
│   │   ├── http/
│   │   └── scheduler/
│   └── outbound/
│       ├── bitbucket/
│       ├── persistence/
│       ├── notification/
│       └── macos/
├── cli/
└── bootstrap/
```

Rules:

- Domain code imports only Kotlin/JDK primitives and domain-owned code.
- Application code depends on the domain and application-owned port abstractions.
- Inbound adapters translate requests or triggers into use-case commands and
  queries.
- Outbound adapters implement application ports.
- The bootstrap package composes the service and may see every layer.
- Product CLI business commands use the Unix-socket API and cannot import domain,
  application, Bitbucket, or persistence implementations as a shortcut.
- Service lifecycle commands may use macOS lifecycle adapters because they must
  operate while the service is stopped.
- `desktop-notifications` never imports or depends on Bitbucket Helper code.

## Identities and aggregate roots

### Identity value objects

- `WorkspaceSlug` identifies the configured workspace.
- `RepositoryId` is the stable repository identity. Workspace and repository slug
  form its external Bitbucket address and display metadata.
- `PullRequestId` combines `RepositoryId` with the upstream PR identifier.
- `ActionItemId` combines the PR with the upstream actionable-source identity.
- `ActivityVersion` is an opaque identity for the exact external state observed.
  Re-fetching unchanged state reproduces it; an edit, later reply, reopen, or other
  relevant advance produces a new version.

### `InstallationConfiguration`

This aggregate owns the one workspace, repository allowlist, retention settings,
and mutable non-secret installation configuration.

Invariants:

- Exactly one workspace is configured per installation.
- Repository slugs are unique within that workspace.
- Bitbucket credentials are never domain state and never live in this aggregate.

### `PullRequest`

This aggregate owns the latest coherent metadata observation, active/inactive
lifecycle, author and draft metadata, head commit, normalized readiness facts, the
seven-check assessment, and the previous build-green predicate.

Invariants:

- An older or less authoritative observation cannot overwrite a newer accepted
  observation.
- Readiness is calculated from one coherent observation.
- A green event is emitted only for a false-to-true transition.
- Raw comment and thread bodies are never stored.

### `ActionItem`

This aggregate owns one actionable comment thread, reply chain, or formal
changes-requested source. It stores remote identity, actor metadata, timestamps,
current `ActivityVersion`, acknowledgment state, and open/acknowledged/closed
lifecycle state.

Invariants:

- Only the exact current version can be acknowledged.
- Repeating acknowledgment of that current version is idempotent success.
- Newer external activity advances the version and reopens an acknowledged item.
- Resolution or deletion closes it; reopening or later external activity may make
  it actionable again.
- The aggregate never stores raw body content.

Repositories do not aggregate all their PRs, and PRs do not aggregate all their
action items. This keeps transaction and concurrency boundaries small.

## Supporting application state

Not every durable record is a domain aggregate. The application also owns:

- per-repository synchronization checkpoints and cursors;
- last-success timestamps, current errors, and backoff state;
- notification intents, attempts, and retry history;
- inactive timestamps and pruning metadata; and
- read-model data or indexes needed for dashboard queries.

These records live behind application ports. Their concrete schema and transaction
contract are defined by the focused persistence design.

## Domain policies and events

Pure domain policies include:

- authored-open-PR selection;
- seven-check readiness;
- activity evolution;
- build-green transition detection; and
- retention eligibility.

Representative domain events are:

- `ReadinessChanged`
- `BuildsBecameGreen`
- `ActionItemOpened`
- `ActionItemReopened`
- `ActionItemAcknowledged`
- `ActionItemClosed`
- `PullRequestDeactivated`

The architecture does not use event sourcing. Current aggregate state is the
source of truth. Domain events are outputs of completed state transitions; the
application converts relevant events into durable notification intents.

## Seven-check readiness policy

The readiness score always has a denominator of seven in v1:

1. At least one approval exists.
2. At least one effective default reviewer approved; this passes when there are no
   effective default reviewers.
3. No changes-requested state exists.
4. No build is `FAILED` or `STOPPED`.
5. No build is `INPROGRESS`.
6. At least one build is `SUCCESSFUL`; zero builds fails this check.
7. Every task is resolved; zero tasks passes this check.

Unknown or malformed source data makes readiness unavailable rather than silently
passing or failing selected checks. The runtime policy is fixed in v1 but may be
replaced or extended by changing Kotlin code later.

The build-green predicate is separate: it is true only when at least one current
build exists and every current build is successful. A new in-progress build resets
the predicate to false. Returning to all-success emits another green transition,
including for the same head commit.

## Action-item lifecycle and acknowledgment

External comments, replies, and formal changes-requested events create actionable
items. External edits and later replies advance the activity version. A reply by
the configured Bitbucket identity acknowledges the version that the reply
observed. Resolving or deleting the remote source closes it. Reopening or later
external activity creates another actionable version.

`AcknowledgeActionItem` always carries both `ActionItemId` and the exact displayed
`ActivityVersion`:

- Matching the current local version succeeds.
- Repeating the same acknowledgment succeeds idempotently.
- If the service already knows a newer version, the command returns a stale
  conflict, the current version, and an explicit newer-state flag.
- During a Bitbucket outage, the exact locally stored version may still be
  acknowledged.
- A later sync may advance and immediately reopen the item if Bitbucket contains
  newer activity.

## Repository-grouped read models

Visualization grouping belongs to the read side, not to a repository-sized
aggregate:

```text
RepositoryDashboard
└── RepositoryHeader
    ├── SyncStatus
    ├── ReadinessSummary
    └── PullRequestCard[]
        ├── ReadinessAssessment
        ├── BuildState
        └── ActionItemSummary[]
```

Pull requests carry `RepositoryId`; action items carry `PullRequestId`.
`GetRepositoryDashboard` joins and groups projections by those identities. The
implementation may compute or materialize this view behind a query port without
changing the domain.

## Application use cases

Use cases are coroutine-friendly `suspend` operations with framework-independent
commands and results.

Command use cases:

- `ConfigureWorkspace`
- `AddRepository`
- `RefreshRepository`
- `RefreshAllRepositories`
- `AcknowledgeActionItem`
- `SendInitialRepositoryDigest`
- `SendDueReminders`
- `RetryPendingNotifications`
- `PruneInactivePullRequests`

Query use cases:

- `GetRepositoryDashboard`
- `ListPullRequests`
- `GetPullRequest`
- `GetInbox`
- `GetLiveActivityContent`
- `GetSynchronizationStatus`

Quartz and manual requests invoke the same refresh, reminder, retry, and pruning
use cases. No scheduler or transport adapter contains domain rules.

## Application ports

Representative outbound ports are:

- `BitbucketGateway`
- `ConfigurationStore`
- `PullRequestStore`
- `ActionItemStore`
- `SynchronizationCheckpointStore`
- `NotificationIntentStore`
- `NotificationSender`
- `Clock`
- `TransactionRunner`

Port names describe application capabilities, not SQLite tables, Ktor calls,
Quartz jobs, or CLI commands. Adapter-specific DTOs are normalized before reaching
the application or domain.

## Synchronization flow

Manual and scheduled refreshes enter through `RefreshRepository`. A process-local
single-flight coordinator is keyed by `RepositoryId`: callers overlapping for one
repository await one shared result, while different repositories may refresh
concurrently.

The flow is:

1. Validate that the repository is configured.
2. Fetch lightweight summaries for open authored PRs.
3. Probe mutable build, task, and activity signals and fetch detail only for new or
   changed PRs.
4. Periodically perform bounded reconciliation for upstream signals that lack one
   complete cursor.
5. Translate Bitbucket responses into normalized observations.
6. Apply observations to `PullRequest` and `ActionItem` aggregates and collect
   domain events.
7. Persist aggregate state, checkpoints, health data, and notification intents
   within the selected transaction boundary.
8. Commit, then attempt external notification delivery.
9. Return a refresh result containing freshness and partial-failure information.

Polling occurs approximately every five minutes. Manual refresh waits for the same
flow to complete. Only an authoritative successful PR-list response may mark a
missing or closed PR inactive. Network, authentication, rate-limit, malformed, or
partial-detail failure preserves the last successful snapshot.

The first successful repository synchronization populates the inbox from existing
activity and produces exactly one initial digest intent for that repository. It
does not also send one notification for every pre-existing action item.

Inactive PRs stop normal polling and notifications, retain metadata and
acknowledgment history for a configurable default of 30 days, and are then eligible
for pruning.

## Live-content policy

Raw comment and thread bodies are fetched at query time. Query results first load
durable identity, version, actor, time, acknowledgment, and staleness metadata,
then request the live body through `BitbucketGateway` only when the view needs it.

Failure to retrieve a body does not remove the action item or fail the entire
dashboard. The response marks that body unavailable and preserves the item's
version, acknowledgment state, snapshot age, and Bitbucket link.

## Notification boundary

The application owns *why and when* to notify. `desktop-notifications` owns generic
macOS delivery.

The application creates durable notification intents for:

- one first-sync digest per repository;
- newly opened or advanced actionable activity;
- every build-green transition; and
- hourly reminders for still-actionable items.

Delivery happens after the application state and intent commit. A failed CLI call
records or retains retry state without rolling back synchronized PR state. Periodic
retry and reminder use cases provide best-effort recovery.

The outbound notification adapter invokes an absolute executable path resolved
from the persistent `uv tool install` installation. It passes an argument list
directly rather than invoking a shell. The generic CLI exposes explicit arguments,
versioned JSON output, documented exit codes, and no Bitbucket-specific concepts.

Notifications are grouped by repository and identical logical content should not
be displayed twice. The focused notification design must define the delivery key,
replacement behavior, process timeouts, version compatibility, and crash-window
semantics before the architecture claims exactly-once behavior.

## HTTP and transport boundary

Ktor exposes one explicit JSON API contract through two local transports:

- loopback-only HTTP for the browser and packaged SPA; and
- HTTP over a user-only Unix-domain socket for the product CLI.

The route and request/response models represent the same application contract.
Transport-specific security and hosting may use separate CIO configurations
without duplicating use cases. Ktor also publishes OpenAPI for the JSON API.

The SPA is a replaceable client. It shows repository-grouped PRs, `N of 7`
readiness, actionable activity, acknowledgment controls, synchronization age and
errors, unavailable-body state, and Bitbucket links.

## Product CLI

One fat JAR has one Clikt main entrypoint. A reserved `service run` command composes
and runs the long-lived Ktor and Quartz service. Public product commands include:

- `pr list`
- `pr show`
- `inbox`
- `ack`
- `refresh`
- `open`
- workspace configuration
- repository allowlist management, including repository addition
- `service install`, `start`, `stop`, `status`, and `logs`

Business commands are Unix-socket API clients. They fail with service status/start
guidance when the service is unavailable and never fall back to direct persistence
or Bitbucket access.

CLI output has an explicit human or JSON mode. Human-readable output is the
interactive default. AI and automation callers pass `--output json`; stdout then
contains exactly one versioned result document, expected errors included, while
stderr is reserved for diagnostics. Exit behavior is stable and documented.

## Coroutines, scheduling, and shutdown

The service owns a structured coroutine scope. Request and scheduled work remains
attached to a defined parent; detached global work is prohibited. Independent
repository work may run concurrently, while per-repository single-flight prevents
duplicate refresh flows.

Quartz 2.5.x with `RAMJobStore` owns timing only. Schedules are registered on every
service start. Durable retry, notification, and synchronization state remains in
application persistence. Quartz jobs invoke and await suspend use cases on
scheduler-owned workers so completion, failure, and overlap semantics remain
observable; they do not fire and forget coroutines.

Shutdown stops new requests and triggers, cancels or allows active work according
to the focused lifecycle policy, closes adapters, and removes the Unix socket.

## macOS lifecycle and credentials

A per-user LaunchAgent starts the service at login. The application is distributed
as a fat JAR and depends on an installed JDK 25 rather than a bundled runtime.

`service install` resolves and validates absolute paths to:

- the JDK 25 `java` executable;
- the installed fat JAR; and
- the `desktop-notifications` executable.

The LaunchAgent records the Java and JAR paths. `service status` detects when an
upgrade or removal makes either path invalid and recommends reinstalling. The
application update design defines the stable install path and upgrade behavior.

Bitbucket credentials enter the service only through environment variables.
`service install` captures current credential values into the LaunchAgent plist.
This is an explicit plaintext-at-rest compromise. The plist uses user-only
permissions, and secrets are redacted from logs, errors, status, and diagnostics.
Mutable non-secret settings remain behind the persistence port.

## Local security boundary

- No non-loopback TCP listener exists.
- Browser HTTP validates Host and Origin and does not enable permissive CORS.
- Browser mutations require CSRF protection.
- CLI traffic uses a user-only Unix socket.
- The socket, database, log files, and LaunchAgent plist use user-only permissions.
- The browser and CLI cannot write persistence directly.
- V1 sends no telemetry.

## Error behavior

- **Bitbucket outage:** keep the last successful snapshot, expose its age and
  current synchronization error, and mark unavailable live content explicitly.
- **Rate limit or transient upstream failure:** apply bounded backoff without
  discarding last-known-good state.
- **Authentication or authorization failure:** expose a non-transient sync error and
  avoid retry storms.
- **Stale acknowledgment:** reject with a conflict and return the currently known
  activity version and newer-state flag.
- **Persistence failure:** fail and roll back the application transaction; no
  notification may be delivered for uncommitted state.
- **Notification CLI failure:** retain the committed intent for best-effort retry;
  synchronized PR state remains committed.
- **Service unavailable:** fail the product CLI clearly and never bypass the
  service.
- **Broken installation path:** expose a diagnostic through lifecycle commands and
  service health.
- **Unknown readiness input:** report readiness as unavailable and never infer a
  green transition.

## Testing strategy

- Pure domain tests cover readiness, green transitions, activity evolution,
  acknowledgment invariants, and retention decisions.
- Application tests invoke use cases with in-memory stores, fake gateways, a
  controlled clock, and deterministic coroutine scheduling.
- Architecture tests enforce package imports and dependency direction.
- One persistence contract suite runs against the in-memory and SQLite adapters.
- Adapter tests cover Bitbucket translation, Ktor routes and security, Unix-socket
  parity, Quartz triggers, notification process integration, and LaunchAgent
  generation.
- Concurrency tests cover single-flight refresh, stale acknowledgment races, and
  protection from slower observations overwriting newer state.
- SPA tests cover repository grouping, readiness, unavailable bodies,
  synchronization errors, and stale acknowledgment conflicts.
- Focused end-to-end tests use fake Bitbucket and notification processes; live
  Bitbucket tests remain explicit and opt-in.

## Delivery decomposition

Implementation is divided into independently designed and verifiable slices:

1. Establish the separate notification repository and migrate the current Python
   scaffold safely.
2. Scaffold the Kotlin/Gradle single-module application, package boundaries,
   domain model, use-case entrypoints, in-memory ports, and architecture tests.
3. Complete the focused persistence design and implement SQLite.
4. Implement Bitbucket synchronization and repository-grouped query projections.
5. Implement the Ktor service, Unix and browser transports, and product CLI.
6. Complete the focused scheduler and notification designs, then integrate Quartz,
   notification delivery, and LaunchAgent management.
7. Build the minimal Vue dashboard and package its static assets.

The first implementation plan written after this specification covers only the
next coherent slice; later deferred areas receive their own focused design first.

## Required follow-up designs

These are explicit design gates rather than hidden implementation decisions:

1. **Persistence:** unit-of-work contract, SQLite library and schema, migrations,
   in-memory parity, backup/recovery, pruning, and concurrency.
2. **Scheduler:** Quartz registration, overlap, misfire, lifecycle, retry,
   observability, coroutine bridging, and replacement seam.
3. **SPA/backend API contract:** resource and operation boundaries, explicit
   request/response/error models, exact-version acknowledgment conflicts,
   freshness and unavailable-content representations, browser security, OpenAPI
   ownership, transport parity, compatibility, and Kotlin/TypeScript contract
   tests.
4. **Desktop-notifications CLI contract:** generic argument-vector schema,
   protocol handshake, versioned JSON results, stderr and exit behavior, process
   failure semantics, delivery-identity ownership, compatibility, and shared
   Kotlin/Python contract fixtures.
5. **Desktop notifications:** generic model, `terminal-notifier` behavior, grouping
   and logical deduplication, delivery attempts, crash guarantees, optional
   speech, installation compatibility, and updates.
6. **Ignored actors:** available Bitbucket identities, name/email configuration,
   stable matching, ambiguity, rename/privacy behavior, and whether ignored
   activity remains visible.
7. **Application updates:** stable installation paths, Kotlin service replacement,
   JAR and JDK compatibility, data migration, rollback, and notification-tool
   version compatibility.

## Acceptance criteria

- The Kotlin application is one Gradle/JVM module and one fat JAR, while package
  architecture tests enforce inward dependency direction.
- Every business behavior is reachable through an explicit application use case.
- One local service owns scheduling, synchronization, state transitions,
  notification dispatch, and every durable write.
- Browser and product CLI use the same application API contract over loopback HTTP
  and a Unix socket respectively.
- The product CLI never bypasses an unavailable service and offers an explicit,
  stable JSON mode for AI callers.
- One configured workspace and repository allowlist produce repository-grouped
  authored open PRs.
- The seven-check assessment and build-green transition follow the specified edge
  cases.
- Action items use exact activity versions; stale acknowledgments conflict and
  locally known versions remain acknowledgeable during outages.
- First sync produces one digest per repository without suppressing existing inbox
  items.
- Failed or partial Bitbucket refreshes preserve the last successful snapshot and
  expose freshness and errors.
- Closed or removed PRs become inactive only after authoritative observation and
  are pruned after the configured retention period.
- Raw comment and thread bodies never enter durable storage.
- Notification failure cannot roll back synchronized domain state, and committed
  intents remain retryable.
- `desktop-notifications` is a separate generic Python repository with no
  Bitbucket dependency and is invoked only through its installed CLI.
- The LaunchAgent uses validated absolute Java and JAR paths and protects its
  credential-bearing plist with user-only permissions.
- Domain, application, architecture, adapter, concurrency, SPA, and focused
  end-to-end tests cover the boundaries without requiring a live Bitbucket account
  by default.
