# Bitbucket Helper API V1

The canonical machine-readable contract is
[`openapi/api-v1.yaml`](../../openapi/api-v1.yaml). Generated Kotlin models under
`src/generated/api-v1/kotlin/` and generated TypeScript models under
`web/src/generated/api-v1/` are verified against it by
`verifyApiV1Generated`. This document summarizes consumer behavior; it does not
replace the OpenAPI schemas.

## Envelope and HTTP semantics

Every processed business outcome uses HTTP `200 OK` and a closed response
envelope:

```json
{"apiVersion":"1","requestId":"req_...","result":{"type":"..."}}
```

Consumers must branch on `result.type`, never on a success-family status code or
message text. Pending, stale, partial, unavailable, rejected, not-found,
deferred, degraded, and unhealthy outcomes are all valid HTTP `200` results.
Specifically, refresh registration/in-progress uses neither `202` nor `503`,
exact-version acknowledgment mismatch uses no `409`, and service health does not
use `503` for a business-health state.

HTTP `4xx` is reserved for request and transport-contract errors:

- `400` malformed or invalid input;
- `403` browser Host, Origin, or CSRF failure;
- `404` unknown API route;
- `405` unsupported method;
- `415` unsupported mutation content type.

HTTP `500` is an unexpected server failure. Error responses use the versioned
`RequestErrorEnvelope`, not a business `result`. Every API V1 response has
`Cache-Control: no-store`.

## Routes

Except for browser session bootstrap, the same business routes are served over
secured loopback HTTP and HTTP over the configured Unix socket.

| Method | Path | Main HTTP `200` result discriminators |
|---|---|---|
| `GET` | `/api/v1/dashboard?afterRevision=...` | `snapshotChanged`, `snapshotUnchanged`, `workspaceNotConfigured` |
| `GET` | `/api/v1/pull-requests` | `available`, `workspaceNotConfigured` |
| `GET` | `/api/v1/pull-requests/{pullRequestId}` | `pullRequestFound`, `pullRequestNotFound`, `workspaceNotConfigured` |
| `GET` | `/api/v1/inbox` | `available`, `workspaceNotConfigured` |
| `GET` | `/api/v1/action-items/{actionItemId}/content?activityVersion=...` | `contentAvailable`, `staleActivityVersion`, `newerActivityObserved`, `contentUnavailable`, `actionItemNotFound` |
| `PUT` | `/api/v1/action-items/{actionItemId}/acknowledgment` | `acknowledged`, `alreadyAcknowledged`, `staleActivityVersion`, `acknowledgmentRejected`, `actionItemNotFound` |
| `POST` | `/api/v1/refresh-runs` | `refreshRunRegistered`, `noRepositoriesConfigured`, `workspaceNotConfigured` |
| `GET` | `/api/v1/refresh-runs/{refreshRunId}` | `refreshRunInProgress`, `refreshRunCompleted`, `refreshRunUnavailable` |
| `GET` | `/api/v1/synchronization` | `available`, `workspaceNotConfigured` |
| `GET` | `/api/v1/configuration/workspace` | `workspaceConfigured`, `workspaceNotConfigured` |
| `PUT` | `/api/v1/configuration/workspace` | `workspaceConfigured`, `workspaceAlreadyConfigured`, `workspaceIdentityMismatch`, `workspaceNotFound`, `workspaceResolutionUnavailable` |
| `POST` | `/api/v1/configuration/workspace/repositories` | `repositoryAdded`, `repositoryAlreadyConfigured`, `repositoryNotFound`, `repositoryResolutionUnavailable`, `workspaceNotConfigured` |
| `DELETE` | `/api/v1/configuration/workspace/repositories/{repositoryId}` | `repositoryRemoved`, `repositoryNotConfigured` |
| `GET` | `/api/v1/health` | `healthSnapshot` with `healthy`, `degraded`, or `unhealthy` status |
| `GET` | `/api/v1/browser-session` | `browserSession`; loopback browser transport only |

Identifiers and revisions are opaque. Return them unchanged to the operation
that consumes them. Request and response objects reject unknown properties, and
timestamps are UTC RFC 3339 instants ending in `Z`.

## Dashboard and refresh polling

`GET /dashboard` without `afterRevision`, or with an older revision, returns
`snapshotChanged` with the complete repository-grouped snapshot. Supplying the
current revision returns `snapshotUnchanged` with the same `dashboardRevision`,
the current `serverTime`, and current polling advice. The server can advise
`polling.type=active` with `afterMilliseconds`; otherwise polling is `idle`.

`POST /refresh-runs` registers work and returns `refreshRunRegistered` with an
opaque `refreshRunId`, expiry, repository entries, and per-request disposition:
`started`, `joinedExisting`, `deferredByBackoff`, or
`repositoryNotConfigured`. It does not wait for completion.

Poll `GET /refresh-runs/{refreshRunId}` according to the returned
`polling.afterMilliseconds` while the result is `refreshRunInProgress`. Terminal
repository entries are `succeeded`, `partialFailure`, `failed`, or
`deferredByBackoff`; the enclosing result becomes `refreshRunCompleted`.
Expired or evicted in-memory runs return `refreshRunUnavailable`. Each of these
is HTTP `200`.

The product CLI performs this registration and polling internally unless
`refresh --no-wait` is supplied. It writes only one final JSON document rather
than streaming intermediate envelopes.

## Live content and exact versions

Bulk dashboard, PR, detail, and inbox projections contain metadata and opaque
`activityVersion` values, never raw activity bodies. To display a body, request
the live-content route with the exact action-item ID and version currently shown.

- `contentAvailable` is the only result that contains `markdown`; it echoes the
  requested version and records `fetchedAt`.
- `staleActivityVersion` means durable state already has another current
  version and returns the current metadata.
- `newerActivityObserved` means the live upstream fetch observed a newer
  version than the durable projection.
- `contentUnavailable` reports a typed safe reason, retryability, and optional
  retry time without erasing durable metadata.
- `actionItemNotFound` means no eligible current action item exists.

Acknowledgment is a separate local mutation with request body
`{"apiVersion":"1","activityVersion":"..."}`. It targets exactly that
version. `acknowledged` and `alreadyAcknowledged` achieved the requested state.
`staleActivityVersion` carries `hasNewerActivity=true` plus current metadata;
the service never silently acknowledges the newer version.
`acknowledgmentRejected` and `actionItemNotFound` are also typed HTTP `200`
business results. Because acknowledgment is local, an exact current version can
be acknowledged while Bitbucket is unavailable; a later authoritative refresh
can advance or reopen state.

## Degraded and last-known-good reads

Synchronization failures do not erase the last successfully committed
repository, pull-request, build, readiness, or action metadata. Bulk projections
continue returning that last-known-good state and expose freshness as
`neverSynchronized`, `fresh`, or `stale`. Repository synchronization reports
current activity, last attempt, last success, and either `problem.type=none` or
`problem.type=present` with bounded typed failure metadata. Partial repository
refresh is represented explicitly.

Health behaves the same way at the transport level: component state can make the
typed snapshot `degraded` or `unhealthy`, while the valid request remains HTTP
`200`.

## Transport security

The browser server binds only to `127.0.0.1`.

- Every browser API request must use the exact configured
  `Host: 127.0.0.1:<resolved-port>` authority.
- Browser reads may omit `Origin`; when present it must be exactly
  `http://127.0.0.1:<resolved-port>`.
- Browser mutations require that exact Origin and the exact in-memory
  `X-CSRF-Token` returned by loopback-only `/browser-session`.
- Body-bearing browser `POST` and `PUT` mutations require `application/json`;
  a bodyless `DELETE` mutation may omit `Content-Type`.
- No permissive CORS is enabled and no `Access-Control-Allow-Origin` header is
  emitted.
- Unix-socket calls do not require Host, Origin, or CSRF, but body-bearing
  `POST` and `PUT` mutations still require `application/json`; bodyless
  `DELETE` may omit `Content-Type`.

The socket parent is validated as a real current-user-owned `0700` directory;
the bound socket is hardened to `0600`, guarded against unsafe stale-path
replacement, and removed on orderly shutdown.

## Privacy limits

Credentials never appear in API fields, request errors, health, or diagnostics.
Raw activity/comment/thread bodies appear only as `markdown` in a successful
live-content response. They are absent from SQLite, dashboard and other bulk
projections, notification argv, logs, and diagnostics. Do not copy live-content
responses into durable client caches or logs.
