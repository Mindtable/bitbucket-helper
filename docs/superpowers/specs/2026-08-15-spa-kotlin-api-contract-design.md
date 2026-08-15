# SPA to Kotlin Backend API Contract Design

**Date:** 2026-08-15

**Status:** Approved

## Purpose

This specification defines the version-one JSON API between the packaged Vue SPA
and the local Kotlin/Ktor service. The same application contract is also exposed
to product CLI clients through HTTP over a Unix-domain socket.

It refines the boundaries established by the approved
[Kotlin DDD architecture](2026-08-14-bitbucket-assistant-architecture-design.md).
Application use cases remain the only business entrypoints. HTTP routes translate
between transport DTOs and those use cases; they do not contain domain rules.

This session is intentionally limited to the API contract. A separate backlog
item covers visualization of the payload hierarchy and interaction flows.

## Product and sizing assumptions

The API serves one local installation, one user, one Bitbucket identity, one
Bitbucket Cloud-compatible API root, and one workspace.

The expected operating size is:

- no more than roughly 10–20 configured repositories, usually 2–3;
- roughly 20 simultaneous open pull requests; and
- roughly 50 open action items, usually 10–15.

These are sizing assumptions, not enforced product limits. The service does not
reject a twenty-first pull request or fifty-first action item. They justify
complete, unpaginated version-one read models. Collections must never be silently
truncated when actual usage exceeds these assumptions.

## Chosen API shape

Version one uses a screen-shaped dashboard snapshot, focused detail queries,
explicit command endpoints, and revision-aware polling.

The alternatives were:

- Fine-grained REST resources assembled by the SPA. This was rejected because it
  creates request fan-out and makes a coherent screen harder to assemble.
- A snapshot followed by server-sent events. This was rejected because reconnect,
  ordering, and transport-parity complexity is unnecessary for a local
  single-user version one.

The chosen design gives the SPA one coherent initial render while keeping live
content and mutations narrowly scoped. The bounded data size makes returning a
complete changed snapshot simpler than defining a delta protocol.

## Contract ownership and generated clients

`openapi/api-v1.yaml` is the canonical source of truth. It is authored and
reviewed as a product contract rather than generated from Ktor implementation
details.

The build generates:

- Kotlin transport DTOs for the inbound HTTP adapter; and
- the TypeScript client and types used by the SPA.

Ktor routes remain handwritten and map generated transport DTOs to
framework-independent application commands and results. Generated code is never
edited manually. Generator versions and configuration are pinned. CI regenerates
both sides and fails if the output differs from the committed generated
artifacts.

Shared JSON fixtures are decoded and encoded by both Kotlin and TypeScript
contract tests.

## HTTP status semantics

HTTP status codes describe request and transport success or failure. They do not
encode business logic.

- `200 OK` means a valid request was processed and a versioned business result is
  available.
- `4xx` means the client request, route, media type, method, API version, or
  browser-security context is invalid.
- `500` means an unexpected server failure prevented normal processing.

Refresh lifecycle, partial synchronization, expected Bitbucket or notification
unavailability, missing domain entities, stale activity versions, and
domain-rule rejection are successful request processing. They are represented as
typed results under HTTP `200`.

A persistence, transaction, routing, or serialization failure that prevents safe
processing is a server failure and returns `500`.

The contract does not use `202 Accepted` for refresh work, `304 Not Modified` for
dashboard polling, or `409 Conflict` for stale acknowledgments.

Representative request-level errors are:

- `400` for malformed JSON, schema violations, invalid identifier syntax, or an
  unsupported body version;
- `403` for Host, Origin, or CSRF rejection;
- `404` for an unknown route;
- `405` for an unsupported method; and
- `415` for an unsupported content type.

An unknown pull request, action item, repository, or refresh run is a typed
business result under HTTP `200`, not a route-level `404`.

## Versioned envelopes

Every normal response has exactly one `result`:

```json
{
  "apiVersion": "1",
  "requestId": "req_opaque",
  "result": {
    "type": "refreshRunRegistered",
    "refreshRun": {}
  }
}
```

A `4xx` or `500` response replaces `result` with exactly one `error`:

```json
{
  "apiVersion": "1",
  "requestId": "req_opaque",
  "error": {
    "code": "INVALID_REQUEST",
    "message": "repositoryIds must contain valid identifiers",
    "violations": []
  }
}
```

Error messages are safe for display but do not contain upstream payloads,
credentials, filesystem secrets, notification arguments, or stack traces.
`requestId` correlates the response with redacted diagnostics.

Mutation bodies that have content include `apiVersion: "1"`. GET and DELETE
requests derive their major version from the path.

All `/api/v1` responses use `Cache-Control: no-store`. Dashboard revisions are an
application polling contract, not HTTP cache validators.

## Endpoint map

All application endpoints use the `/api/v1` prefix.

| Operation | Method and path | Primary consumers |
|---|---|---|
| Load dashboard snapshot and inbox widget | `GET /api/v1/dashboard` | SPA |
| List pull requests | `GET /api/v1/pull-requests` | CLI and automation |
| Load pull-request details | `GET /api/v1/pull-requests/{pullRequestId}` | SPA drawer and CLI |
| List actionable inbox items | `GET /api/v1/inbox` | CLI and automation |
| Load one live activity body | `GET /api/v1/action-items/{actionItemId}/content` | SPA drawer and CLI |
| Acknowledge an exact activity version | `PUT /api/v1/action-items/{actionItemId}/acknowledgment` | SPA drawer and CLI |
| Start or join repository refresh work | `POST /api/v1/refresh-runs` | SPA, CLI, and automation |
| Inspect one refresh run | `GET /api/v1/refresh-runs/{refreshRunId}` | CLI and automation |
| Inspect synchronization state | `GET /api/v1/synchronization` | SPA, CLI, and diagnostics |
| Read workspace configuration | `GET /api/v1/configuration/workspace` | CLI |
| Initially configure workspace identity | `PUT /api/v1/configuration/workspace` | CLI |
| Add one configured repository | `POST /api/v1/configuration/workspace/repositories` | CLI |
| Remove one configured repository | `DELETE /api/v1/configuration/workspace/repositories/{repositoryId}` | CLI |
| Inspect application health | `GET /api/v1/health` | SPA, CLI, and lifecycle diagnostics |
| Obtain browser CSRF state | `GET /api/v1/browser-session` | SPA over loopback only |

Configuration routes are present on both application transports, but the SPA has
no configuration UI. `browser-session` is the only browser-transport-only API
operation.

Service lifecycle commands such as install, start, stop, and repair remain local
CLI behavior because they must operate while the service is unavailable.

### Application use-case mapping

HTTP adapters invoke application entrypoints rather than repositories, gateways,
or domain objects directly:

| API operation | Application entrypoint |
|---|---|
| Dashboard | `GetDashboardSnapshot`, a composite read query over repository dashboard and inbox projections |
| Pull-request list/detail | `ListPullRequests` and `GetPullRequest` |
| Inbox | `GetInbox` |
| Live activity content | `GetLiveActivityContent` |
| Acknowledgment | `AcknowledgeActionItem` |
| Start/inspect refresh run | `StartRefreshRun` and `GetRefreshRun`, coordinating `RefreshRepository` and `RefreshAllRepositories` |
| Synchronization status | `GetSynchronizationStatus` |
| Read/configure workspace | `GetWorkspaceConfiguration` and `ConfigureWorkspace` |
| Add/remove repository | `AddRepository` and `RemoveRepository` |
| Health | `GetHealthSnapshot` |

`GetDashboardSnapshot`, `StartRefreshRun`, `GetRefreshRun`,
`GetWorkspaceConfiguration`, `RemoveRepository`, and `GetHealthSnapshot` are
application-level entrypoints required by this refined contract. They compose or
expose approved application capabilities; Ktor does not own their business
coordination. `browser-session` remains transport security rather than a
business use case.

## Wire conventions

### Identifiers

`RepositoryId`, `PullRequestId`, `ActionItemId`, `ActivityVersion`,
`RefreshRunId`, revisions, and request IDs are serialized as opaque, URL-safe
strings. Clients compare and return them but never parse business meaning from
them.

Workspace and repository slugs are configuration or display values, not stable
client-side identities.

### Time

Instants use RFC 3339 UTC strings. TypeScript keeps them as strings at the wire
boundary rather than implicitly converting them to JavaScript `Date` values.
Durations and retry delays use explicitly named integer millisecond or second
fields.

### Required fields and state

Fields are required by default. `null` is used only when it represents a
meaningful known absence. Discriminated state variants replace ambiguous nullable
objects. Clients ignore unknown object fields but fail clearly on an unknown
discriminated result type.

Serialized enum and result discriminators use stable lower-camel-case strings.

### Ordering and pagination

Version-one dashboard, pull-request, inbox, and action-item collections are
complete and unpaginated.

Ordering is contractual:

- repositories sort by display name using locale-independent case folding, then
  by opaque ID;
- pull requests sort by most recently updated first, then by opaque ID; and
- inbox and per-PR action items sort by newest activity first, then by opaque ID.

## Dashboard contract

`GET /api/v1/dashboard` accepts an optional `afterRevision` query parameter.

It returns one of:

- `snapshotChanged` with the complete current snapshot;
- `snapshotUnchanged` with the current revision and server time; or
- `workspaceNotConfigured` with CLI setup guidance.

The initial request omits `afterRevision`. Subsequent polling supplies the most
recent `dashboardRevision`. Equality with the current revision produces
`snapshotUnchanged`. A missing, expired, or otherwise non-current revision
produces `snapshotChanged` with a complete snapshot.

Every configured-workspace dashboard result contains a required `polling` state:
`idle`, or `active` with `afterMilliseconds`. Poll timing is therefore explicit
without an optional or nullable delay.

`DashboardSnapshot` contains:

```text
DashboardSnapshot
├── dashboardRevision
├── generatedAt
├── workspace
├── repositoryGroups[]
└── inbox
```

Each repository group contains:

- `repositoryId`, slug, display name, and Bitbucket web link;
- its own opaque `repositoryRevision`;
- current synchronization activity;
- snapshot freshness and last-attempt information;
- synchronization problem state as either `none` or `present` with safe details;
- a readiness summary; and
- every current pull-request card for the repository.

Any response-visible change to a repository group advances its
`repositoryRevision` and `dashboardRevision`. A response-visible change to the
global inbox or workspace metadata advances `dashboardRevision`.

Each pull-request card contains:

- `pullRequestId`, upstream display number, title, author, draft state,
  timestamps, and Bitbucket link;
- readiness as either `available` with `passed` and fixed `total: 7`, or
  `unavailable` with a reason;
- explicit build state;
- actionable and acknowledged item counts; and
- action-item metadata summaries without raw bodies.

The dashboard inbox includes only currently actionable items. Each item still
contains:

- `actionItemId` and the exact `activityVersion`;
- repository and pull-request identities and display metadata;
- activity kind;
- actor identity and display name;
- activity timestamp;
- acknowledgment state; and
- Bitbucket link.

`GET /api/v1/pull-requests/{pullRequestId}` returns a richer projection with
individual readiness checks and all relevant action-item metadata. It never
embeds live bodies.

`GET /api/v1/pull-requests` and `GET /api/v1/inbox` expose the corresponding
unpaginated projections for CLI and automation callers. In version one, the inbox
query returns only currently actionable items.

## Background refresh and polling

After rendering its persisted snapshot, the SPA automatically starts or joins
background refresh work. Refresh is never a side effect of a GET request.

`POST /api/v1/refresh-runs` accepts one of:

```json
{
  "apiVersion": "1",
  "target": {
    "type": "allConfiguredRepositories"
  }
}
```

or a `repositories` target containing explicit repository IDs.

A processed request returns one of:

- `workspaceNotConfigured`;
- `noRepositoriesConfigured` for an all-repositories target; or
- `refreshRunRegistered` with an opaque run ID, expiry time, and one disposition
  per repository.

Registered per-repository dispositions are:

- `started`;
- `joinedExisting` through per-repository single-flight;
- `deferredByBackoff` with `retryAt`; or
- `repositoryNotConfigured`.

A single run may contain different dispositions for different repositories.
Version one has no force or ignore-backoff option.

Refresh runs are operational in-memory resources, not durable business state.
Inspection returns:

- `refreshRunInProgress`;
- `refreshRunCompleted`; or
- `refreshRunUnavailable` after expiry, service restart, or an unknown ID.

`StartRefreshRun` attaches started work to the service-owned structured
coroutine scope before returning its result. Completing the HTTP request does not
detach the work or tie its lifetime to the browser tab. Service shutdown handles
active work according to the service lifecycle policy.

Per-repository run entries use `queued`, `running`, `succeeded`,
`partialFailure`, `failed`, or `deferredByBackoff`. The CLI can poll until every
repository is terminal.

While work is active, both changed and unchanged dashboard results carry the
`active` polling variant. The SPA waits the supplied interval and polls with its
last dashboard revision. Each repository commit changes the corresponding group
and dashboard revisions, so repositories become visible individually without a
delta protocol.

Scheduled, SPA-triggered, and CLI-triggered work shares the same
per-repository single-flight coordinator and synchronization state.

## Synchronization and freshness

Dashboard and `GET /api/v1/synchronization` representations separate:

- current activity: `idle`, `queued`, or `running`;
- last attempt: `succeeded`, `partialFailure`, or `failed`; and
- snapshot freshness: `neverSynchronized`, `fresh`, or `stale`.

The backend owns freshness policy. Responses provide the snapshot timestamp,
computed age, and `staleSince` when stale; the SPA does not reproduce the
threshold.

An unsuccessful or partial attempt never erases the last successful snapshot.
Partial results retain last-known-good metadata for failed details while exposing
safe failure categories, counts, retryability, and retry timing. Authentication,
authorization, rate-limit, transient-network, and malformed-upstream failures
remain distinct business states.

## Live activity content

Raw comment and thread bodies appear only in:

```http
GET /api/v1/action-items/{actionItemId}/content?activityVersion={displayedVersion}
```

`activityVersion` is mandatory. The response result is one of:

- `contentAvailable` with raw Markdown source, the exact version, and fetch time;
- `staleActivityVersion` when durable state already has a newer version;
- `newerActivityObserved` when the live Bitbucket response reveals a newer
  version not yet incorporated by synchronization;
- `contentUnavailable` with a categorized reason and retryability; or
- `actionItemNotFound`.

The service never returns upstream-rendered HTML. If newer activity is observed,
the response includes the observed version and repository ID, does not present
the newer body as the requested version, and allows the client to start or join a
repository refresh.

Live bodies never enter persistence, logs, diagnostics, metrics, notification
arguments, bulk responses, or error documents. Content responses use
`Cache-Control: no-store`.

## Exact-version acknowledgment

Acknowledgment uses:

```http
PUT /api/v1/action-items/{actionItemId}/acknowledgment
Content-Type: application/json
```

```json
{
  "apiVersion": "1",
  "activityVersion": "av_opaque"
}
```

The result is one of:

- `acknowledged`;
- `alreadyAcknowledged` for an idempotent repeat;
- `staleActivityVersion` with requested and current versions,
  `hasNewerActivity: true`, and current metadata;
- `acknowledgmentRejected` for a closed or otherwise non-acknowledgeable item; or
- `actionItemNotFound`.

Version comparison and state change are atomic. A matching locally stored version
remains acknowledgeable during an upstream outage. Later synchronization may
advance the version and reopen the item.

Version one exposes acknowledgment from the detail drawer. Inbox entries already
carry `actionItemId` and `activityVersion` so row-level acknowledgment can be
added later without a contract change.

## Workspace and repository configuration

Initial workspace configuration uses:

```json
{
  "apiVersion": "1",
  "bitbucketApiBaseUrl": "https://api.bitbucket.org/2.0",
  "workspaceSlug": "example-workspace"
}
```

`bitbucketApiBaseUrl` identifies the Bitbucket Cloud-compatible API root,
including its API-version path. It:

- is absolute;
- contains no credentials, query parameters, or fragment;
- uses HTTPS in production;
- may use loopback HTTP only in explicit test or development mode; and
- is normalized by removing redundant trailing slashes.

The normalized base URL and workspace slug form immutable installation identity.
Repeating the initial PUT with identical normalized values is idempotent.
Different values return `workspaceIdentityMismatch`. Changing installation
identity requires a separate explicit reset/reconfigure workflow outside this
contract.

Before the first configuration is committed, the service validates the URL and
resolves the workspace through the Bitbucket Cloud-compatible API. The result is
`workspaceConfigured`, `workspaceAlreadyConfigured`,
`workspaceIdentityMismatch`, `workspaceNotFound`, or
`workspaceResolutionUnavailable`. Failed first-time resolution does not persist
an immutable identity that the user cannot correct.

Repositories are added iteratively by slug. Addition resolves the repository
synchronously against Bitbucket before modifying the allowlist, establishing a
stable opaque repository ID and catching typos.

Addition results include:

- `repositoryAdded`;
- `repositoryAlreadyConfigured`;
- `repositoryNotFound`;
- `repositoryResolutionUnavailable`; and
- `workspaceNotConfigured`.

Removing a configured repository returns `repositoryRemoved` or
`repositoryNotConfigured`. Removal stops polling and notifications but retains
last-known metadata and acknowledgment history under the normal retention policy.
Re-adding reconnects retained state when Bitbucket resolves the same stable
identity.

Credentials remain process-environment inputs. They never appear in
configuration requests or responses.

## Health and diagnostics

`GET /api/v1/health` reports an application health snapshot. HTTP remains `200`
when the reported overall state is `healthy`, `degraded`, or `unhealthy` because
those are operational business outcomes. HTTP `500` is reserved for a failure
that prevents the normal envelope from being produced.

The health result includes:

- service and build version;
- supported API version;
- service-instance ID and start time;
- persistence state;
- scheduler state;
- installation-path state; and
- notification-adapter state.

Health and synchronization diagnostics never expose credentials, environment
values, upstream payloads, raw activity bodies, stack traces, or notification
arguments. Operational and browser-session responses are non-cacheable.

## Browser security

The packaged SPA and API are same-origin. Browser HTTP:

- listens only on explicit loopback addresses;
- validates `Host` against the exact configured loopback host and port;
- disables CORS and never reflects or wildcards an allowed origin;
- validates any supplied `Origin` on reads;
- requires an exact allowed `Origin`, JSON content type, and `X-CSRF-Token` on
  every mutation; and
- returns Host, Origin, and CSRF failures as `4xx` error envelopes.

`GET /api/v1/browser-session` returns a random per-service-instance CSRF token
and the service-instance ID. The SPA retains the token only in memory. It is not
stored in a cookie or local storage. Service restart rotates it.

Production disables CORS. Local SPA development uses a same-origin proxy that
rewrites requests to the loopback service; it does not add an alternate allowed
browser origin or enable wildcard CORS.

No authentication cookie or browser session state exists in version one. These
controls protect against browser cross-site requests and DNS rebinding. They do
not claim to isolate the service from another process already running as the same
macOS user.

## Unix-socket parity

HTTP over the user-only Unix socket exposes the same business routes, schemas,
results, and version rules. It does not require browser Host, Origin, or CSRF
headers; the transport supplies a trusted non-browser context and relies on
user-only filesystem permissions.

The same contract suite runs against both transports. The only intentional API
difference is that `browser-session` exists only on loopback HTTP.

## Compatibility

Compatible changes within `/api/v1` are limited to:

- adding optional response fields;
- adding optional request fields with behavior-preserving defaults;
- adding endpoints that do not change existing operations; and
- clarifying documentation without changing semantics.

Removing or renaming fields, changing field meaning or ordering guarantees,
adding required input, or changing an existing discriminated result requires
`/api/v2`.

Adding a new result discriminator to an existing operation is treated as
breaking unless version-one clients were explicitly defined to accept it.

## Verification

Contract verification includes:

- OpenAPI linting and schema validation;
- deterministic Kotlin and TypeScript generation;
- a CI drift check for committed generated artifacts;
- automated breaking-change detection against the previous committed contract;
- shared valid and invalid JSON fixtures consumed by Kotlin and TypeScript;
- Ktor route tests for every declared business result, `4xx` shape, and `500`
  shape;
- the same business contract suite over loopback and Unix-socket HTTP;
- browser-specific Host, Origin, CSRF, content-type, and disabled-CORS tests;
- tests proving live bodies never enter snapshots, errors, diagnostics, or
  persistence fixtures; and
- end-to-end tests with a fake Bitbucket Cloud-compatible server.

End-to-end scenarios cover:

- initial immutable workspace configuration;
- iterative repository addition;
- immediate dashboard rendering followed by automatic background refresh;
- independent repository completion;
- partial failure with preserved last-known-good metadata;
- live-content success, version advance, and failure;
- exact-version acknowledgment, idempotency, and stale versions; and
- repository removal and re-addition.

Live Bitbucket account tests remain explicit and opt-in.

## Explicit non-goals

Version one does not include:

- pagination or silent collection caps;
- SSE or WebSockets;
- a generic filtering/query language;
- SPA workspace or repository configuration controls;
- Bitbucket Server or Data Center compatibility;
- raw activity content in dashboard, inbox, or pull-request projections;
- changing an installation's base URL or workspace identity in place; or
- a forced refresh mode that bypasses backoff.

## Acceptance criteria

- The SPA renders one complete persisted dashboard snapshot before starting
  background refresh.
- Repository results become visible independently through revision-aware polling.
- HTTP codes contain no business-state logic.
- Kotlin DTOs and the TypeScript client derive from one reviewed OpenAPI source.
- Dashboard, inbox, and PR projections contain no raw activity bodies.
- Every acknowledgment targets the exact displayed activity version.
- Partial and failed synchronization preserve last-known-good metadata and expose
  explicit freshness.
- Browser mutations require Host, Origin, and CSRF validation without permissive
  CORS.
- Loopback and Unix-socket transports pass the same business contract suite.
- The documented sizing figures remain assumptions rather than enforced limits.
