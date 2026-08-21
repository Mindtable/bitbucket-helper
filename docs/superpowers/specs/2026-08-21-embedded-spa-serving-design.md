# Embedded SPA Serving and Real Backend Integration Design

**Date:** 2026-08-21

**Status:** Approved

## Executive summary

Bitbucket Helper will package the Vue production build inside the executable
Kotlin fat JAR, serve it from the existing loopback browser server, and replace
the production fixture source with a real same-origin adapter built on the
committed generated TypeScript API client.

The resulting user flow is:

```text
build fat JAR -> start Kotlin service -> configure through CLI -> open backend URL
```

Node and npm remain source-build tools only. A running installation needs one
Java process and no Vite, Node, external static directory, or second HTTP
server. Initial workspace and repository configuration remains a CLI workflow.

## Authority and current state

This design extends, rather than replaces, the existing authorities:

1. [SPA to Kotlin Backend API Contract Design](2026-08-15-spa-kotlin-api-contract-design.md)
   remains authoritative for API schemas, business outcomes, HTTP status
   semantics, browser security, privacy, and transport parity.
2. [Dashboard and Detail Drawer Real SPA Design](2026-08-15-dashboard-drawer-real-spa-design.md)
   remains authoritative for product interaction, presentation, and
   accessibility behavior.
3. This document is authoritative for production SPA packaging, Kotlin static
   delivery, generated-client integration, runtime source selection, and the
   associated verification boundary.

The repository already contains:

- a Vue 3/Vite/TypeScript dashboard and detail drawer under `web/`;
- a browser-facing `DashboardSource` port with deterministic fixture sources;
- a generated TypeScript fetch client rooted at the relative `/api/v1` path;
- a loopback Ktor browser transport with exact Host, Origin, and CSRF checks;
- a generated-client drift gate; and
- a fat-JAR build for the Kotlin application.

The missing assembly is the production `DashboardSource`, embedded static
assets, Ktor static routes, static-response hardening and observability, and
proof that the fat JAR contains a usable SPA.

## Goals

- Serve the real dashboard from the same Kotlin process and origin as the API.
- Package the complete production SPA in the existing executable fat JAR.
- Use generated OpenAPI clients and models at the wire boundary.
- Preserve the existing view-model and composable boundaries behind
  `DashboardSource`.
- Keep CSRF state in memory and recover safely after a service restart.
- Preserve API V1 response-body business outcomes and HTTP status semantics.
- Keep fixture journeys available for focused development and tests without
  allowing production users to select them.
- Add secure, diagnosable static serving without logging paths or content.
- Make the build and manual end-to-end run reproducible from documented
  commands.

## Non-goals

- Workspace or repository configuration screens.
- Service install/start/stop/status commands or a LaunchAgent.
- A health, lifecycle, or log-management screen.
- Vue Router, server-side rendering, WebSockets, or server-sent events.
- A runtime Node process, Vite server, external web root, or second HTTP server.
- CORS support or a second production origin.
- A change to the canonical API V1 contract unless implementation discovers a
  genuine contract defect requiring separate review.
- A new Playwright journey against the packaged backend. Real assembly is
  accepted manually by the user.

## Selected approach

Production assets are embedded classpath resources in the fat JAR.

An external `web/dist` deployment was rejected because it creates version skew,
runtime path validation, replacement, and upgrade concerns. A separate static
server was rejected because it adds another runtime process and undermines the
existing same-origin security design.

The embedded approach produces one versioned artifact in which the SPA, API
server, and generated client are built from the same source revision.

## Build and artifact architecture

### Build graph

The fat-JAR task owns a deterministic frontend build through dedicated Gradle
tasks:

```text
validate Node/npm
        |
install locked npm dependencies
        |
build production SPA
        |
verify production assets
        |
embed under classpath /spa
        |
buildFatJar
```

The npm installation uses `npm ci` and the committed lockfile. It runs when the
package manifests or lockfile change, when the installed dependency marker is
missing, or when explicitly requested. A current matching installation is
reused for incremental builds. Gradle offline mode is forwarded to npm so an
offline build never silently downloads a missing frontend dependency.

The production-build task deletes or empties its output before invoking the
existing type-checked Vite build. `web/dist/` remains ignored and untracked.
Only final production files are copied into the isolated `spa/` classpath
subtree. Source files, environment files, dependency metadata, test artifacts,
and source maps are excluded.

The asset verification task fails before JAR assembly when:

- `index.html` is absent;
- an asset referenced by `index.html` is absent;
- a referenced path escapes the packaged SPA subtree;
- a source map or unexpected source/configuration file is present; or
- production source selection does not resolve to the real Kotlin adapter.

### Runtime artifact

The built artifact remains:

```text
build/libs/bitbucket-helper-0.1.0-all.jar
```

It contains Kotlin classes, API resources, and the frontend production output
under one classpath namespace. Runtime static serving never resolves an
operator-supplied path and never reads `web/dist` from the filesystem.

Service startup verifies that the packaged SPA entry resource exists, is
readable, and is non-empty before the HTTP servers accept requests. A failed
check aborts startup through the existing sanitized service-start failure path
with the fixed component category `spa_assets`.

## HTTP route ownership

Only the loopback browser server exposes SPA routes. The Unix-socket server
continues to expose the business API and no static resources.

| Method | Browser path | Owner and result |
|---|---|---|
| `GET`, `HEAD` | `/` | Packaged SPA `index.html` |
| `GET`, `HEAD` | `/index.html` | Packaged SPA `index.html` |
| `GET`, `HEAD` | `/assets/...` | Packaged Vite assets |
| Existing API methods | `/api/v1/...` | Existing versioned API |
| Other static methods | SPA paths | Fixed `405` transport response |
| Unknown static path | Outside `/api/v1` | Fixed non-API `404` |
| Unknown API path | `/api/v1/...` | Existing versioned JSON API `404` |

The SPA handler explicitly refuses the `/api/v1` namespace. Route ordering and
tests must prove that static delivery can never turn an unknown API route into
`index.html` or a non-versioned error.

The product has no client-side router, so arbitrary-path SPA fallback is not
implemented. Adding browser routes later requires a separate decision about
fallback and route ownership.

## Production frontend composition

### Real source adapter

Production `main.ts` injects a `KotlinApiDashboardSource` that implements the
existing browser-facing port. It is composed from:

- generated `DashboardApi`, `PullRequestsApi`, `ActionItemsApi`, `RefreshApi`,
  and `BrowserSecurityApi` clients;
- an in-memory browser-session manager; and
- pure wire-to-view mapping functions.

The generated client's relative `/api/v1` base path is retained. The SPA has no
backend URL setting and contains no credentials or environment-derived runtime
configuration.

Vue components and composables continue to depend only on `DashboardSource`
and view models. Generated wire models do not enter component props or local UI
state.

### Operation mapping

| `DashboardSource` capability | Generated API operation |
|---|---|
| Load/revision-poll dashboard | `DashboardApi.getDashboard` |
| Start complete refresh | `RefreshApi.startRefreshRun` with `allConfiguredRepositories` |
| Start repository refresh | `RefreshApi.startRefreshRun` with one repository ID |
| Load pull-request detail | `PullRequestsApi.getPullRequest` |
| Load exact-version content | `ActionItemsApi.getLiveActivityContent` |
| Acknowledge exact version | `ActionItemsApi.acknowledgeActionItem` |

The canonical repository-refresh request contains repository IDs, not an
observed activity version. `DashboardSource.startRepositoryRefresh` therefore
accepts only the repository ID. The drawer retains the observed activity
version in local state for display and reconciliation but does not invent an
unsupported wire field.

The current dashboard-revision polling behavior remains: registration is a
typed business result, and the dashboard's polling advice drives follow-up
snapshot reads. This slice does not add a second client-side refresh-run state
machine.

### Wire-to-view mapping

Pure mappers exhaustively branch on generated discriminators and validate the
fields and echoed opaque IDs used by the UI. Representative presentation
mappings include:

- workspace and repository wire projections to the existing grouped dashboard;
- `upstreamNumber` to the displayed pull-request number;
- COMMENT/REPLY/THREAD-style activity kinds to the UI's comment category and
  changes-requested activity to its dedicated category;
- readiness totals and checks to the existing fixed `N of 7` presentation;
- synchronization activity, freshness age, and typed failure metadata to fixed
  safe UI copy;
- no-build/unknown build states to explicit unavailable presentation; and
- exact-version live Markdown only to the drawer content result.

Formatting an already-computed age is presentation logic; the browser does not
reimplement freshness thresholds. Failure descriptions are derived from fixed
typed categories, never arbitrary exception or upstream text.

Unknown discriminators, unsupported activity kinds, missing required values,
invalid readiness totals, impossible state combinations, or mismatched echoed
IDs fail the source call. They are not coerced into a successful business
outcome.

Every declared business discriminator is handled deliberately. Dashboard and
refresh `workspaceNotConfigured` results use the existing setup state. The
pull-request-detail source result gains an explicit `workspaceNotConfigured`
variant with the setup command; the drawer presents it as setup guidance while
retaining the last dashboard snapshot. It is never misreported as a missing
pull request or network error.

## Browser session and mutation security

The source starts a browser-session bootstrap when the application is composed.
Reads proceed concurrently because they do not require a CSRF token.
Mutations await the shared bootstrap and pass its token through the generated
operation parameter.

The session manager:

- holds the CSRF token and service-instance ID in closure memory only;
- shares one in-flight session request across concurrent callers;
- clears a failed bootstrap so a later user action can retry;
- never uses cookies, `localStorage`, `sessionStorage`, IndexedDB, or URL state;
  and
- never logs or displays the token.

After a mutation receives HTTP `403`, the manager obtains a fresh browser
session. It retries that mutation exactly once only when the newly returned
service-instance ID differs from the one used for the failed attempt. A changed
instance proves a service restart rotated the CSRF token. A matching instance,
another `403`, a non-`403` response, or a bootstrap failure is surfaced without
retry.

This retry is safe because browser security rejects the original request before
invoking an application use case. It is not a general retry policy for
ambiguous mutations.

## Development source selection

Production source selection is compile-time and always chooses the real Kotlin
adapter. Query parameters cannot enable fixtures in a production bundle.

The existing deterministic fixtures remain the default for `npm run dev` and
fixture-oriented Vitest/Playwright coverage. A separate developer-only command
runs the real adapter through Vite's `/api/v1` proxy. That proxy rewrites the
forwarded Host and Origin to the backend's exact loopback authority; it does not
enable CORS or add a production origin.

The Vite server is development tooling only and is absent from installation and
runtime documentation.

## Static-response security

The browser security boundary validates the exact configured
`127.0.0.1:<resolved-port>` Host for SPA and API requests. An Origin supplied on
a static `GET` or `HEAD` must equal the exact loopback origin. API rules remain
unchanged: mutations require exact Origin and CSRF, and body-bearing `POST` and
`PUT` requests require JSON.

Static resources are served only from the isolated classpath subtree with
normalized paths. Traversal, encoded traversal, directory listing, and access
to unrelated classpath resources are forbidden.

Browser static responses include:

- `Content-Security-Policy` with `default-src 'none'`, same-origin script,
  style, image, font, and connection directives, and disabled object, base,
  form, and frame ancestors;
- `X-Content-Type-Options: nosniff`;
- `Referrer-Policy: no-referrer`;
- `Cross-Origin-Opener-Policy: same-origin`;
- `Cross-Origin-Resource-Policy: same-origin`;
- `X-Frame-Options: DENY`; and
- a restrictive `Permissions-Policy` disabling unused capabilities.

The SPA shell uses `Cache-Control: no-store`. Files below Vite's content-hashed
`/assets/` namespace use long-lived immutable caching. Other static responses
are not cacheable. API V1 responses retain their existing `no-store` rule.

CORS remains uninstalled and no `Access-Control-Allow-*` header is emitted.

## Error and status semantics

Static delivery uses ordinary transport status codes: `200`, `403`, `404`,
`405`, and `500` where appropriate. Fixed non-API failures do not reflect a
request path or include exception details.

API semantics do not change. Every valid processed business outcome remains
HTTP `200` and is represented by `result.type`. The adapter never interprets
`202`, `409`, or another status as refresh, conflict, or health business state.
API `4xx` remains a request/transport failure and `500` an unexpected server
failure.

Generated-client `ResponseError`, fetch failure, decode failure, mapper
failure, or invalid echo becomes a technical `DashboardSource` failure. The
existing page and drawer error states display fixed safe copy and offer only
the retries already defined by the product design. No raw response body,
request URL, token, Markdown, or exception message is written to browser
storage or console diagnostics.

## Backend observability

Static serving is a new inbound boundary and records one stable terminal event
per request, whether completed, rejected, or unexpectedly failed. It reuses the
typed HTTP completed, rejected, and failed event vocabulary with:

- a generated request ID, also returned as `X-Request-ID` on the static
  response;
- transport `browser`;
- an allowlisted method;
- a fixed operation such as `spa_shell`, `spa_asset`, or `spa_unknown`;
- status, fixed outcome, and duration; and
- no domain correlation fields unless the boundary actually has one.

The operation is a category, not a filename. Events never contain a raw path,
query, Host, Origin, header, asset name, absolute resource location, body,
exception message, or arbitrary object text.

Static successes are DEBUG, rejections are WARN, and unexpected failures are
ERROR, following the existing typed event contract. The startup asset check
uses the existing service-start event with component `spa_assets`. Recorder
failure handling follows the current backend containment rules and never
changes an API response-body business outcome.

Tests for the changed boundary prove event name, level, required correlation,
terminal behavior, both terminal and rotating JSON destinations, and privacy
exclusions for success, rejection, and failure.

## Automated verification

### Frontend

- Pure mapper tests cover every generated result discriminator and relevant
  nested state variant.
- Canonical API JSON fixtures are decoded by the generated client and mapped to
  exact `DashboardSource` results.
- Adapter tests cover dashboard revision polling, complete and repository
  refresh, PR detail, exact-version content, and acknowledgment.
- Browser-session tests prove single-flight bootstrap, memory-only state,
  restart recovery, same-instance rejection, and at-most-once retry.
- Failure tests cover fetch errors, HTTP `4xx`/`500`, malformed data, unknown
  discriminators, unsupported kinds, and mismatched echoes.
- Existing fixture composable, component, and Playwright journeys remain in
  place.

### Backend and packaging

- Ktor tests cover root/index and asset delivery, `GET`/`HEAD`, MIME types,
  caching, security headers, missing paths, and unsupported methods.
- Security tests cover exact Host, optional Origin, traversal attempts, absent
  CORS, and unchanged API mutation rules.
- Route-isolation tests prove unknown API routes retain the versioned API error
  and the Unix transport exposes no SPA.
- Startup tests cover missing SPA resources without opening runtime boundaries.
- Observability tests cover success, rejection, failure, both destinations, and
  privacy exclusions.
- The fat-JAR acceptance test inspects the archive for `index.html` and every
  referenced hashed asset and rejects source maps or source/config files.
- Existing Kotlin tests, architecture checks, OpenAPI validation, generated
  client drift checks, frontend formatting/lint/type/unit checks, and production
  build remain required gates.

No new Playwright suite starts the packaged Kotlin backend. The user will
perform real assembled-system acceptance manually.

## Manual assembled-system acceptance

The operational guide will contain this checklist:

1. Build and verify the fat JAR from a clean checkout.
2. Prepare private runtime paths, credentials, and the notification executable.
3. Start `java -jar ... service run`; leave no Node/Vite process running.
4. Configure the workspace through the real product CLI.
5. Add at least one repository through the real product CLI.
6. Start or await a repository refresh.
7. Open `http://127.0.0.1:<configured-port>/`.
8. Confirm the dashboard displays the configured workspace, repository, and
   live pull-request state.
9. Open a pull request, load exact-version activity content, acknowledge it,
   and observe dashboard reconciliation.
10. Confirm browser API requests are same-origin and the UI remains usable
    without any npm development server.

Until the user reports this checklist complete, automated verification proves
the assembly boundaries but does not claim a live Bitbucket end-to-end run.

## Documentation changes

Implementation updates:

- the root README, making the backend URL the production UI entry point;
- `docs/installation-and-web-ui.md`, removing the disconnected-fixture
  limitation and all npm-server runtime steps;
- `web/README.md`, separating fixture development, optional live-adapter
  development, and production embedding; and
- the project backlog items for real adapter integration and fat-JAR SPA
  packaging.

The documented supported flow is build JAR, start service, configure through
CLI, and open the backend URL. It remains explicit that background-service
installation is deferred.

## Component boundaries

The implementation keeps these responsibilities separate:

- Gradle tasks own frontend tool validation, locked install, production build,
  asset verification, and JAR inclusion.
- A Ktor SPA module owns static route matching, classpath lookup, static headers,
  non-API errors, and static request observations.
- Browser security owns authority/origin/CSRF decisions and contains no asset or
  application business logic.
- The generated TypeScript client owns the wire protocol.
- The browser-session manager owns only in-memory CSRF lifecycle and restart
  recovery.
- Pure mappers own generated-wire to view-model conversion.
- `KotlinApiDashboardSource` orchestrates generated APIs, session state, and
  mappers behind the existing product port.
- Vue composables and components retain product interaction and rendering.

No unit reads credentials, accesses SQLite, calls Bitbucket directly, or
invents a second wire model.

## Risks and mitigations

- **Longer JAR builds:** incremental npm installation and frontend build inputs
  avoid unnecessary work while `npm ci` preserves locked clean installs.
- **Route shadowing:** explicit `/api/v1` reservation and route-isolation tests
  prevent SPA fallback from swallowing API failures.
- **Client/server skew:** embedding both in one JAR and retaining generated
  drift checks ties them to one revision.
- **Backend restart with an open page:** service-instance-aware CSRF refresh
  allows one safe retry without introducing general mutation replay.
- **Wire/view drift:** pure exhaustive mappers and canonical fixture tests fail
  closed at the adapter boundary.
- **Static content exposure:** isolated classpath roots, traversal tests, CSP,
  and no source maps limit the served surface.
- **Manual final acceptance:** the explicit checklist makes the remaining live
  verification visible instead of implying automated proof that does not exist.

## Acceptance criteria

- `buildFatJar` produces one executable JAR containing the production SPA and
  every asset referenced by its entry document.
- Running that JAR requires Java and the service's existing runtime
  dependencies, but no Node, npm, Vite, or external web directory.
- Opening the configured loopback root renders the existing dashboard using the
  real `/api/v1` source.
- Production cannot be switched to fixture data through URL state.
- CLI-configured workspace and repository state appears in the browser after
  service refresh.
- Dashboard refresh, PR detail, live exact-version content, and acknowledgment
  use the generated client and preserve typed HTTP `200` business outcomes.
- CSRF state is memory-only and one safe service-restart recovery is supported.
- SPA and API are same-origin, CORS remains disabled, and exact Host/Origin rules
  are preserved.
- Unknown API routes never return the SPA shell.
- The Unix-socket transport never serves SPA resources.
- Static responses have the approved cache and browser-security headers.
- Static inbound outcomes are logged with stable categories and no prohibited
  data.
- Automated backend, frontend, contract, packaging, and existing fixture browser
  gates pass.
- The user-facing manual acceptance checklist is documented without claiming it
  was executed automatically.
