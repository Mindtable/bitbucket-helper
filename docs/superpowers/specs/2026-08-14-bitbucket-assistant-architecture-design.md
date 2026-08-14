# Bitbucket Assistant Architecture Design

**Date:** 2026-08-14

**Status:** Approved

## Context

Bitbucket Helper is a single-user, local macOS product for monitoring and triaging
Bitbucket Cloud pull requests. It will expose one Bitbucket Assistant through a
small web dashboard, a service-backed CLI, and scheduled jobs. The service is the
only long-running process and the only owner of durable state.

The existing repository is a minimal Python 3.12 UV/Hatchling project. The
untracked `source/` directory is a protected shell prototype and remains unchanged.
This design defines the intended product architecture; it does not implement the
new package tree.

## Goals

- Show open pull requests authored by the configured Bitbucket identity, grouped by
  repository in one configured workspace.
- Preserve the prototype's fixed-denominator seven-check readiness result.
- Surface actionable external comments, replies, and formal changes-requested
  events with exact-version local acknowledgment.
- Notify on new actionable activity, initial repository state, hourly reminders,
  and every build-green false-to-true transition.
- Provide a focused Vue dashboard and a read-and-triage CLI without duplicating
  application logic.
- Run entirely on one Mac with a per-user LaunchAgent, embedded persistence,
  loopback browser access, a Unix socket for the CLI, and macOS notifications.
- Use DDD application use cases as the only business entrypoints and preserve
  ports-and-adapters dependency direction.
- Ship a reusable generic `desktop_notifications` Python package and CLI that can
  later move to a separate repository.
- Keep external frameworks and storage implementations replaceable without adding
  a plugin system or abstraction at every internal seam.

## Non-goals

- Multiple users, multiple Bitbucket identities, or multiple workspaces in one
  installation.
- Bitbucket mutations such as approving, commenting, merging, or changing PR state.
- A complete reproduction of the Bitbucket web interface.
- Runtime configuration, a DSL, or third-party plugins for readiness checks in v1.
- Persisting raw comment or thread bodies.
- A separately deployed database or more than the SQLite and in-memory adapters in
  v1.
- Event sourcing, separate read and write databases, microservices, or multiple
  Python distributions in the initial repository.
- A separate notification for the full seven-check transition in v1; the score is
  displayed, while build-green has the transition notification.
- Quiet-hour scheduling in v1. Actionable-item reminders use an hourly default and
  stop when the item is acknowledged or closed.
- Claiming exactly-once notification delivery before the deferred notification
  design establishes its identifiers and crash-window semantics.
- Implementing ignored-actor matching before the deferred Bitbucket identity
  research is complete.
- Selecting PRs where the user is a reviewer in v1. The selection policy must allow
  this to be added later.

## Chosen approach

The product uses a layered modular monolith in one Python distribution. A
feature-first tree was rejected because synchronization, acknowledgments,
persistence, and notifications cross the initial features and would obscure
ownership. A multi-distribution UV workspace was rejected because it adds build,
installation, and versioning overhead before any module needs an independent
release.

The generic notification code is a sibling import package named
`desktop_notifications`, not a subpackage of `bitbucket_helper`. This gives it an
extraction-ready boundary without the collision risk of a top-level package named
only `notifications`.

## Proposed repository structure

```text
.
├── pyproject.toml
├── uv.lock
├── web/
│   ├── package.json
│   ├── src/
│   └── tests/
├── src/
│   ├── bitbucket_helper/
│   │   ├── assistant/
│   │   │   ├── domain/
│   │   │   │   ├── model/
│   │   │   │   ├── policies/
│   │   │   │   └── events/
│   │   │   └── application/
│   │   │       ├── use_cases/
│   │   │       │   ├── commands/
│   │   │       │   └── queries/
│   │   │       ├── ports/
│   │   │       └── dto/
│   │   ├── adapters/
│   │   │   ├── inbound/
│   │   │   │   ├── api/
│   │   │   │   └── scheduler/
│   │   │   └── outbound/
│   │   │       ├── bitbucket/
│   │   │       ├── persistence/
│   │   │       └── notifications/
│   │   ├── service/
│   │   └── cli/
│   └── desktop_notifications/
│       ├── model.py
│       ├── ports.py
│       ├── service.py
│       ├── adapters/
│       │   └── terminal_notifier.py
│       └── cli.py
└── tests/
    ├── unit/
    │   ├── domain/
    │   └── application/
    ├── architecture/
    ├── contract/
    ├── integration/
    └── e2e/
```

`web/` is a separate Vue 3, Vite, and TypeScript workspace. The Python wheel
contains both import packages and the compiled SPA assets. Every implemented
layout, dependency, test-path, or build-command change must update
`docs/uv-project-structure.md` in the same change.

## Dependency rules

The dependency direction is:

```text
domain <- application use cases <- adapters <- service composition
```

- The domain imports only the standard library and domain-owned modules.
- Application use cases import the domain and application-owned port abstractions,
  never concrete adapters or frameworks.
- Inbound adapters translate transport or scheduler input into use-case commands
  and queries.
- Outbound adapters implement application ports.
- The service package is the composition root and may import all product layers to
  wire them together.
- The Bitbucket assistant CLI is a remote service client, not an in-process shortcut
  into application or persistence code.
- `desktop_notifications` never imports `bitbucket_helper`. The Bitbucket product's
  outbound notification adapter may import `desktop_notifications`.
- Readiness checks are internal domain strategies rather than infrastructure ports.

Architecture tests enforce these rules so the intended shape is executable rather
than conventional documentation only.

## Application entrypoints

Explicit command and query use cases are the only business entrypoints. They use
framework-independent command, result, and error types. One use case represents a
user or system intention, not one transport endpoint.

Representative command use cases are:

- `ConfigureWorkspace`
- `AddRepository`
- `RefreshRepository`
- `AcknowledgeActivity`
- `DispatchDueNotifications`
- `PruneInactivePullRequests`

Representative query use cases are:

- `ListPullRequests`
- `GetPullRequest`
- `ListInbox`
- `GetDashboard`
- `GetServiceStatus`
- `FetchActivityBody`

FastAPI and APScheduler invoke these use cases. The browser and product CLI invoke
FastAPI over different transports. Operational CLI commands that install or control
the LaunchAgent belong to the service-management boundary rather than the business
domain. The generic notification CLI directly invokes `desktop_notifications` and
does not call the Bitbucket service.

A state-changing use case validates its command, acquires application-level
coordination where required, loads state through ports, invokes aggregate behavior,
persists the resulting state and durable notification intents atomically, commits,
and then allows external delivery. Delivery never occurs for an uncommitted domain
transition.

## Domain model

The Bitbucket Assistant is one bounded context. `desktop_notifications` is a
separate generic bounded module.

### Identity value objects

- `WorkspaceSlug` identifies the one configured workspace.
- `RepositoryId` holds the stable Bitbucket repository UUID; `RepositorySlug` is
  mutable display and CLI metadata.
- `PullRequestId` is the repository ID plus the Bitbucket PR number.
- `ActionItemId` is the PR ID plus the remote actionable-source identity.
- `ActivityVersion` is an opaque, stable identity for one externally observable
  activity version. Re-fetching unchanged remote state must reproduce the same
  version; an external edit, reply, reopen, or later change produces a different
  version.

### Aggregate roots

`InstallationConfiguration` owns the workspace, repository allowlist, retention
settings, and other mutable non-secret configuration.

`PullRequest` owns PR lifecycle, author and draft metadata, head commit, normalized
readiness facts, the seven-check result, and the previous build-green predicate.
Applying a new build set can emit `BuildsBecameGreen`.

`ActionItem` owns one actionable external comment thread, reply chain, or formal
changes-requested source. It holds remote identifiers, actor identity, timestamps,
the latest observed version, local acknowledgment history, and actionable,
acknowledged, or closed state. It never stores raw body content.

Repositories do not aggregate all their PRs, and PRs do not aggregate all their
action items. This keeps mutation boundaries small and lets one acknowledgment
change without rewriting an entire repository snapshot.

### Domain policies and events

Pure domain policies include `SevenCheckReadinessPolicy`,
`AuthoredPullRequestSelectionPolicy`, `ActivityEvolutionPolicy`, and
`RetentionPolicy`. New policy types may be added in Python, but v1 has no runtime
policy registry.

Representative domain events are `PullRequestDiscovered`, `BuildsBecameGreen`,
`ActionItemOpened`, `ActionItemAdvanced`, `ActionItemAcknowledged`,
`ActionItemClosed`, and `PullRequestBecameInactive`.

Domain events describe completed transitions. The application converts events that
need user-visible delivery into durable notification intents. The architecture does
not use an event store.

## Pull request population and repository configuration

V1 selects open PRs authored by the configured Bitbucket identity. Drafts are
treated exactly like other open PRs. The selection policy is replaceable in source
code so a later policy can include PRs awaiting the user's review.

One persisted `WorkspaceSlug` applies to the installation. The CLI addresses
repositories by slug within that workspace. Adding a repository resolves and stores
the stable Bitbucket repository UUID plus its current slug. The allowlist and
workspace are mutable non-secret state behind the persistence port; credentials are
not stored there.

The exact spelling of the one-time workspace configuration command and the product
CLI's human/JSON presentation are owned by the API/CLI delivery slice. They do not
change the required `ConfigureWorkspace` and `AddRepository` use-case contracts.

## Seven-check readiness policy

The score has a fixed denominator of seven in v1:

1. At least one approval exists.
2. At least one effective default reviewer approved; this check passes when there
   are no effective default reviewers.
3. No changes-requested state exists.
4. No build is `FAILED` or `STOPPED`.
5. No build is `INPROGRESS`.
6. At least one build is `SUCCESSFUL`; no builds means this check fails.
7. Every task is resolved; zero tasks means this check passes.

Unknown or malformed source data makes readiness unavailable rather than silently
passing or failing selected checks.

The build-green predicate is true only when at least one current build exists and
every current build is successful. A false-to-true transition emits
`BuildsBecameGreen`. If a new in-progress build appears, the predicate resets to
false; returning to all-success emits another event even for the same head commit.
Each transition receives a distinct identity.

## Action-item lifecycle and acknowledgment

External comments, external replies, and formal changes-requested events create
actionable items. An external edit or later reply advances the activity version and
reopens an acknowledged item. A reply by the configured Bitbucket identity
acknowledges the version that reply observed. Resolving or deleting the remote
source closes the item. Reopening or later external activity creates a new
actionable version.

The acknowledgment command always carries `ActionItemId` and the exact displayed
`ActivityVersion`:

- When it matches the current local version, acknowledgment succeeds.
- Repeating acknowledgment of the same current version is idempotent success.
- When the service already knows a newer version, acknowledgment fails with a stale
  conflict and returns the current version plus `newer_activity: true`.
- During a Bitbucket outage, a matching local version can still be acknowledged.
- A later synchronization may immediately advance and reopen it if remote state is
  newer.

## Query model and repository-grouped dashboard

The domain preserves identity relationships; it does not contain visualization
collections. Query use cases compose CQRS-style read models from the same embedded
database:

```text
RepositoryDashboardGroup
├── repository: RepositorySummary
├── sync_status: SyncStatus
└── pull_requests: list[PullRequestDashboardItem]
    ├── readiness: SevenCheckResult
    ├── builds_green: bool
    └── action_items: list[InboxEntry]
```

`PullRequest` records carry `RepositoryId`, and `ActionItem` records carry
`PullRequestId`. `GetDashboard` loads configured repositories, groups PR projections
by repository ID, attaches action items by PR ID, and performs best-effort live-body
enrichment for actionable entries on the requested dashboard page, grouped by PR to
avoid one remote request per item. A repository rename changes display metadata
without breaking grouping because the stable UUID is the key.

This is a lightweight command/query separation, not event sourcing and not a
separate read database.

## Synchronization flow

APScheduler and manual HTTP requests invoke the same `RefreshRepository` use case.
A process-local single-flight coordinator is keyed by `RepositoryId`. Overlapping
callers for the same repository share one refresh and synchronously receive its
result; different repositories may refresh concurrently.

The refresh flow is:

1. Fetch lightweight summaries for open authored PRs in the allowed repository.
2. Fetch minimal change probes for mutable streams that are not guaranteed to alter
   the PR head, including build status, task count, and activity metadata. The
   adapter uses Bitbucket
   [partial-response fields](https://developer.atlassian.com/cloud/bitbucket/rest/intro/#partial-responses)
   where available.
3. Compare the resulting fingerprint with the last successful snapshot and fetch
   full build, task, and activity metadata only for new or changed PRs.
4. Periodically perform a bounded full metadata reconciliation. This is a defensive
   design inference: the documented resources expose useful fields and individual
   collections, but not one complete cursor covering every PR, comment edit, task,
   and build transition.
5. Normalize Bitbucket response data before passing it to aggregates and policies.
6. Apply PR and action-item transitions and collect domain events.
7. Atomically persist aggregate changes, snapshot metadata, sync status, and durable
   notification intents.
8. Commit, then invoke or schedule notification delivery.

Polling runs approximately every five minutes. The first successful sync populates
the inbox from existing actionable state but coalesces initial notification events
into exactly one digest per repository; it does not also display one notification
per pre-existing action item. Manual refresh uses the same flow and waits for
completion.

Only a successful authoritative PR-list response may mark a missing or closed PR
inactive. Network, authentication, rate-limit, malformed-response, or partial-detail
failure never replaces the last successful list with an empty snapshot. Inactive
PRs stop polling and notifications, retain metadata and acknowledgment history for
a configurable default of 30 days, and are then pruned.

Sync cursors, last-success timestamps, current errors, retry state, and delivery
history are operational application state rather than behaviors forced into a
domain aggregate.

## Live content policy

Raw comment and thread bodies are fetched at query time and are never persisted.
Dashboard and PR-detail queries first load durable grouping, version, author, time,
acknowledgment, and staleness metadata, then request live bodies through the
Bitbucket port when required by the view.

Failure to retrieve a body does not fail the whole dashboard. The response marks
that body explicitly unavailable while preserving its action item, exact version,
snapshot age, acknowledgment state, and Bitbucket link.

## Notification flow and generic package

The application creates notification intents for:

- the first successful repository digest;
- newly opened or advanced actionable activity;
- every `BuildsBecameGreen` transition; and
- hourly reminders for still-actionable items.

Notifications are grouped by repository. The notification design must distinguish a
logical user event from a delivery attempt: retries of one logical event must not
display it twice, while a later green transition for the same PR and commit is a new
logical event.

The application commits the intent before delivery. `DispatchDueNotifications`
passes eligible intents through an application notification port implemented by a
Bitbucket adapter that calls `desktop_notifications`. A failed external delivery
records an attempt and retry time without rolling back synchronized state. Periodic
dispatch recovers committed but undelivered intents after process failure.

`desktop_notifications` contains only generic notification concepts and a
`terminal-notifier` outbound adapter. It exposes a narrow CLI for the user's other
local projects and AI assistant. That CLI must use explicit arguments, versioned
JSON results, documented exit codes, and no Bitbucket-specific options. Whether
macOS `say` belongs in the package, the exact logical-delivery identity, active
notification replacement behavior, and crash guarantees are gates in the deferred
notification design.

## Persistence boundary

The application depends on persistence ports rather than SQLite APIs. V1 ships:

- SQLite as the durable embedded adapter; and
- an in-memory reference and test adapter.

Both adapters must pass one behavioral contract suite. No separately deployed
database is required. Only the service process writes durable state; the browser and
product CLI never bypass it.

Durable scope includes PR and repository metadata snapshots, action versions and
acknowledgments, inactive timestamps, sync cursors and health, repository and
installation configuration, notification intents and attempts, and retention data.
It explicitly excludes raw comment and thread bodies.

The transaction/unit-of-work API, SQLite schema, migrations, backup behavior, and
adapter concurrency guarantees are defined by the required persistence follow-up
design before implementation of this slice.

## API, transports, and web UI

FastAPI exposes explicit request and response models and generated OpenAPI. One
application contract is hosted over:

- loopback HTTP for the browser; and
- HTTP over a user-only Unix-domain socket for the product CLI.

The initial ASGI host may use Hypercorn's multiple-bind support; server selection is
an infrastructure decision and must not leak into use cases. The CLI fails clearly
with service status/start guidance when the socket is unavailable and never falls
back to direct SQLite or Bitbucket access.

The product CLI v1 surface includes `pr list`, `pr show`, `inbox`, `ack`, `refresh`,
`open`, repository addition, and `service install`, `start`, `stop`, `status`, and
`logs`. It performs read and triage operations only.

The Vue SPA is a deliberately small action dashboard showing repository-grouped
authored PRs, `N of 7` readiness, build state, actionable activity,
acknowledgments, synchronization age/error, unavailable-body state, and links to
Bitbucket. It is a replaceable client of the same API rather than a second
application layer.

Vite builds generated assets under `web/dist`. The packaging pipeline builds the
SPA first and includes its output in the Python wheel. In development, the Vite
server proxies API requests to the loopback service so permissive development CORS
is unnecessary.

## Browser and local-process security

The service has no non-loopback TCP listener. SPA and API share one browser origin.
HTTP handling validates exact Host and Origin values, does not enable permissive
CORS, and requires a service-issued CSRF token for mutations. Unix-socket CLI
requests are outside the browser CSRF path. The socket, database, logs, and
LaunchAgent plist use user-only permissions.

Bitbucket credentials enter the application only through process environment
variables. `service install` captures their current values into the per-user
LaunchAgent plist. This is an explicit plaintext-at-rest compromise, not secretless
configuration. The plist uses mode `0600`; credentials are redacted from logs,
status, errors, and diagnostics. Reinstalling safely replaces the definition and is
the v1 credential-rotation mechanism.

Mutable non-secret settings live behind the persistence port. The design does not
store Bitbucket credentials in SQLite.

## Scheduler and service lifecycle

APScheduler 3.11.x, pinned below 4, is an inbound adapter. It invokes the same use
cases as manual requests and contains no domain rules. Its concrete job registration,
coalescing, overlap, misfire, retry, shutdown, and replacement seam are defined by
the required scheduler follow-up design.

A per-user LaunchAgent starts one service at login. Startup validates runtime
configuration, initializes persistence, binds loopback HTTP and the Unix socket, and
then starts scheduling. Shutdown stops accepting new work, stops scheduling, lets
active use cases finish or roll back, closes persistence, and removes the socket.

`service status` reports process and API reachability, version, scheduler and
database health, and per-repository last-success age/error without exposing secrets.
Logs are structured and local. V1 sends no telemetry.

## Error contract

API errors use a stable envelope with `code`, `message`, `retryable`, and structured
`details`.

- Stale acknowledgment returns HTTP 409 with the current activity version.
- Invalid configuration or repository input returns a validation error.
- A missing local resource returns HTTP 404.
- Bitbucket authentication and authorization failures become non-transient sync
  errors and do not trigger retry storms.
- Rate-limit responses honor upstream guidance; network and upstream server failures
  use bounded backoff.
- Unknown build status makes readiness unavailable and cannot create a green event.
- Persistence failure rolls back the use case and leaves no deliverable notification
  for uncommitted state.
- Notification failure leaves the committed sync intact and records retry state.
- Live-body failure produces partial data with explicit unavailability rather than a
  false empty body.

Scheduled job failures are recorded and do not terminate unrelated jobs or erase
last-known-good state.

## Testing strategy

Domain tests exhaustively cover the seven checks, green transitions, activity
evolution, exact-version acknowledgment, and retention decisions.

Application tests invoke use cases with in-memory persistence, fake Bitbucket and
notification ports, fake clocks, and deterministic concurrency. They do not start
FastAPI or SQLite.

Architecture tests enforce layer imports and the one-way dependency from the
Bitbucket adapter to `desktop_notifications`.

Contract tests run the persistence behavior against SQLite and the in-memory
adapter. Integration tests use fake Bitbucket HTTP responses, a fake
`terminal-notifier` process, both API transports, scheduler invocation, temporary
local state, and generated LaunchAgent configuration without requiring a real
account.

SPA tests cover grouping, readiness, unavailable bodies, sync errors,
acknowledgment, and stale conflicts. A small end-to-end suite covers local service
startup, workspace/repository configuration, first sync, dashboard and CLI reads,
acknowledgment, restart persistence, and notification recovery. Tests requiring a
real Bitbucket account remain opt-in under the existing `live` marker.

## Delivery decomposition

This architecture is implemented through independently designed and verifiable
slices rather than one monolithic plan:

1. Package boundaries, domain model, use-case entrypoints, in-memory ports, and
   architecture tests.
2. Persistence follow-up design and SQLite adapter.
3. Bitbucket synchronization and repository-grouped query projections.
4. FastAPI service and Bitbucket assistant CLI.
5. Generic notification follow-up design, delivery flow, scheduler design, and
   LaunchAgent management.
6. Minimal Vue dashboard and packaged static assets.

The implementation plan created after this architecture specification covers the
first slice. Later slices receive their required focused design and plan before
implementation.

## Required deferred designs

The following are explicit follow-up design gates, not hidden implementation
decisions:

1. Persistence: port and unit-of-work contracts, SQLite schema and migrations,
   in-memory parity, backup/recovery, pruning, and concurrency.
2. Scheduler: APScheduler job registration, overlap, coalescing, misfire, lifecycle,
   retry, observability, and replacement seam.
3. Desktop notifications: generic model, CLI schema and exit codes,
   `terminal-notifier` semantics, repository grouping, logical deduplication,
   delivery attempts, crash guarantees, optional speech, and extraction.
4. Ignored actors: Bitbucket identity availability, stable identifiers, friendly
   name/email configuration, ambiguity, rename/privacy behavior, and whether ignored
   activity remains visible.

Post-v1 opportunities are PR reviewer selection, runtime readiness configuration,
another embedded persistence adapter when justified, Keychain-backed credentials,
and extraction of `desktop_notifications` to its own distribution and repository.

## Acceptance criteria

- All business behavior is reachable through explicit application use cases, and
  architecture tests prevent inward layers from importing outward adapters.
- One local service owns all durable writes and serves the same FastAPI contract over
  loopback HTTP and a Unix socket.
- The product CLI never bypasses an unavailable service.
- One configured workspace and repository allowlist produce repository-grouped open
  authored PRs in the dashboard and CLI.
- The fixed seven-check score follows the specified edge cases and represents
  unknown data as unavailable.
- An in-progress build resets green; returning to all-success creates another
  distinct green transition for the same commit.
- Action items evolve by exact version, stale acknowledgments conflict, and matching
  local versions can be acknowledged during an outage.
- First sync creates one initial digest per repository without suppressing existing
  actionable items.
- Failed or partial Bitbucket refreshes preserve the last successful snapshot and
  expose its age and current error.
- Closed or removed PRs become inactive only after an authoritative sync and are
  pruned after the configured retention period.
- Raw comment and thread bodies are absent from durable storage and explicitly marked
  unavailable when live retrieval fails.
- Notification state survives restart and supports retries without treating a later
  green transition as a duplicate of an earlier one.
- `desktop_notifications` has no import dependency on `bitbucket_helper` and exposes
  a generic, machine-readable CLI contract after its focused design is approved.
- The built Python distribution includes both Python packages and the compiled SPA,
  and the LaunchAgent starts the service using user-only local state and credential
  files.
- Unit, architecture, contract, integration, SPA, and focused end-to-end tests cover
  the boundaries described above without requiring live Bitbucket access by default.
