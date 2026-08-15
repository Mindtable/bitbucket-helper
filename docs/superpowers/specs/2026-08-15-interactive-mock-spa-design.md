# Interactive Mock SPA Design

**Date:** 2026-08-15

**Status:** Design approved; written specification pending review

## Purpose

This specification defines a disposable, product-first interactive mock of the
Bitbucket Helper dashboard. It visualizes the riskiest interactions in the
approved [SPA-to-Kotlin API contract](2026-08-15-spa-kotlin-api-contract-design.md)
before the real Vue SPA is scoped or implemented.

The prototype covers:

- the dashboard payload hierarchy;
- revision-aware polling and independent repository completion;
- drawer-only live-content loading; and
- exact-version acknowledgment success and stale-version behavior.

The mock is an exploratory product artifact, not an API source of truth. The
approved API specification remains authoritative whenever the two differ.

## Artifact boundary

The prototype is conversation-only and disposable. It lives outside tracked
project source and must not create `web/`, select a Vue component architecture,
call the Kotlin service, or become production code. It makes no network requests
and stores no data outside its own in-memory state.

The only durable repository change from this design cycle is this specification.
The later real SPA receives its own implementation design and plan.

Representative mock data follows the approved contract's concepts and typed
business outcomes, but it is illustrative. It is not a replacement for the
future canonical OpenAPI document or shared Kotlin and TypeScript fixtures.

## Goals

- Test whether one repository-grouped dashboard presents the approved read model
  clearly.
- Test whether a context-first drawer can load live bodies without confusing
  stored metadata with ephemeral content.
- Make dashboard and repository revisions visible through realistic product
  changes.
- Demonstrate that business outcomes such as partial failure and stale
  acknowledgment remain typed results under HTTP `200`.
- Preserve last-known-good information visibly through partial refresh and live
  content failure.
- Make a stale acknowledgment unmistakably different from successful
  acknowledgment.
- Keep the visual structure compact, neutral, accessible, and easy to replace.

## Non-goals

- Creating Vue, Vite, TypeScript, Kotlin, Ktor, or OpenAPI source files.
- Choosing production component, routing, styling, or state-management libraries.
- Exhaustively visualizing every result discriminator in the API contract.
- Providing workspace or repository configuration controls.
- Adding separate inbox, pull-request list, or pull-request detail routes.
- Implementing authentication, browser-session, CSRF, CORS, or Unix-socket
  behavior.
- Rendering arbitrary upstream HTML or implementing a production Markdown parser.
- Persisting prototype state or retaining raw activity bodies.

## Approaches considered

### Product-first state simulator

One realistic dashboard and drawer run against a deterministic in-memory scenario
model. A compact controller outside the product chrome selects and advances API
journeys.

This is the selected approach. It tests product comprehension and state
transitions together while keeping demo controls visually distinct from the
future product.

### State gallery

Separate static screens would show each API outcome. This would be faster to scan
but would not validate transitions, revision handling, or the relationship
between dashboard changes and drawer actions.

### Protocol walkthrough

A request-and-response timeline beside a simplified dashboard would explain the
contract precisely but would give weak evidence about the product experience.

## Overall composition

The prototype has two deliberately separate layers.

### Demo controller

The controller sits outside the product chrome and contains only:

- a journey selector;
- a deterministic reset action;
- a manual next-response action when the selected journey has pending work; and
- one compact trace of the most recent operation, requested revision or activity
  version, HTTP status, and typed result discriminator.

Manual progression prevents intermediate polling and loading states from racing
past the reviewer. Selecting or resetting a journey reconstructs its complete
initial state deterministically.

### Product surface

The product surface behaves like the intended application. Its controls open
pull requests, select action items, start refresh work, load live content,
acknowledge an exact version, and close the drawer. Demo controls never appear
inside the product header, dashboard, or drawer.

The first render is useful without advancing the simulation.

## Product layout

The selected layout is one desktop-first repository feed with a context-first
right-side drawer.

### Header

The compact header shows:

- the Bitbucket Helper name and configured workspace;
- overall freshness or synchronization activity; and
- a refresh action.

It contains no route navigation, configuration, search, filters, or invented
aggregate metrics.

### Needs-attention section

The dashboard begins with the complete currently actionable inbox. Each item
shows repository and pull-request context, activity kind, actor, activity time,
and acknowledgment state. Selecting an item opens its pull request and activity
in the drawer.

### Repository sections

Repository groups appear in the contract's stable order. Each header shows its
display name, freshness, current synchronization activity, last-attempt problem
when present, and Bitbucket link.

Every current pull request appears as a compact card containing:

- display number, title, author, and update time;
- build state;
- readiness as fixed `N of 7` or explicitly unavailable;
- actionable and acknowledged counts; and
- a Bitbucket link.

Selecting a card opens the drawer. If the pull request has actionable items, the
newest one is selected initially; otherwise the drawer opens with pull-request
details and no fabricated activity.

### Context-first drawer

The drawer preserves pull-request context above the selected live activity. It
contains:

- repository and pull-request identity;
- readiness and actionable summaries;
- individual readiness checks where representative data is available;
- action-item metadata ordered newest first;
- the selected activity's exact displayed version;
- a loading or typed live-content result region; and
- the acknowledgment action and result.

Raw activity text appears only in the live-content region. Closing the drawer
returns to the unchanged dashboard position.

At narrow widths the drawer stacks above or below the dashboard content rather
than compressing both into unreadable columns.

## State architecture

One in-memory model owns:

- selected journey and current journey step;
- dashboard and repository revisions;
- repository synchronization, last-attempt, and freshness states;
- the current complete dashboard snapshot;
- selected pull request and action item;
- drawer loading and live-content result state;
- the exact displayed activity version;
- acknowledgment result; and
- the last compact contract trace.

The fixture catalog, transition logic, dashboard rendering, drawer rendering,
and demo controller remain logically separate even if the disposable artifact
packages them together. Rendering reads state; only explicit transition
functions mutate it.

Every reset recreates state from immutable representative fixtures so one journey
cannot leak mutations into another.

## Representative journeys

### 1. Healthy refresh

The product first renders a persisted `snapshotChanged` dashboard result. Starting
refresh produces `refreshRunRegistered`. The next dashboard request uses the
displayed revision and returns `snapshotUnchanged`, leaving the visible snapshot
intact.

Subsequent manual responses complete repositories independently. Each committed
repository update advances its repository revision and the dashboard revision,
returns a complete changed snapshot, and becomes visible without waiting for
other repositories. The final response returns the dashboard to idle polling.

### 2. Partial refresh

One repository completes successfully while another reports partial failure. The
failed repository retains last-known-good pull-request cards, shows its last
successful snapshot age, and exposes a safe categorized problem. Other
repositories remain usable and independently fresh.

### 3. Content and acknowledgment success

Selecting an actionable item opens the drawer with stored metadata but no raw
body. Live loading requests the exact displayed activity version and resolves to
`contentAvailable` with representative Markdown.

Acknowledgment sends that same version and resolves to `acknowledged`. The item
leaves the actionable inbox, its pull-request counts update, and the dashboard
revision advances. The UI does not require a Bitbucket round trip before showing
the successful local acknowledgment.

### 4. Content unavailable

Live loading resolves to `contentUnavailable`. The drawer retains actor, time,
kind, version, freshness, and Bitbucket link, and shows the categorized reason and
retryability. It does not erase the item, close the drawer, or convert the entire
dashboard into an error state.

### 5. Newer activity discovered

The drawer requests the displayed version, but the live response resolves to
`newerActivityObserved`. The prototype does not show the newly observed body as
though it belonged to the requested version. It displays the version advance,
starts or joins repository refresh, incorporates the newer metadata through a
changed dashboard snapshot, and then permits live loading for the current
version.

### 6. Stale acknowledgment

The drawer displays one version while the acknowledgment result reports a newer
current version and `hasNewerActivity: true`. No acknowledged state, count
decrement, success message, or inbox removal occurs. The action area instead
offers the path to refresh and inspect the newer activity.

## Contract trace

The demo-only trace makes the contract mechanics inspectable without adding
developer information to the product surface. It shows only the latest:

- method and API operation;
- `afterRevision` or `activityVersion` when relevant;
- HTTP status; and
- result discriminator.

The partial-failure, unavailable-content, newer-activity, and stale-acknowledgment
journeys all display HTTP `200`. Request-validation and unexpected-server errors
are outside this representative prototype.

## Failure, content, and privacy behavior

Expected business failures remain local to the affected repository or drawer.
Last-known-good metadata remains visible whenever the approved contract preserves
it. The mock never uses a generic full-page error for a repository-level or
content-level outcome.

Representative Markdown is displayed as safe content. No upstream-rendered HTML
is accepted. Raw activity text never appears in dashboard fixtures, contract
traces, error text, storage, diagnostics, or any scenario other than the open
live-content region.

The prototype contains no credentials, environment values, real account data,
or external links requiring a live Bitbucket account.

## Visual and accessibility rules

- Use restrained, neutral product styling that works in light and dark
  appearance.
- Keep the hierarchy legible at desktop width and reflow without clipping at
  narrow width.
- Use native buttons and selects with visible labels and focus behavior.
- Make every interactive state reachable by keyboard in normal document order.
- Pair status color with text and shape; never rely on color alone.
- Preserve readable labels and avoid internal scrolling for the overall mock.
- Avoid decorative metrics, oversized icons, and animation that hides state
  changes.

## Verification

Verification exercises every journey from a fresh deterministic reset and checks:

- scenario selection, reset, and manual progression;
- the first useful render before interaction;
- `snapshotUnchanged` preserving the current visual snapshot;
- independent repository updates advancing the correct revisions;
- partial failure preserving last-known-good cards;
- drawer opening from both inbox items and pull-request cards;
- live-content loading and unavailable-content behavior;
- successful acknowledgment updating inbox and pull-request counts;
- stale acknowledgment performing no successful-state mutation;
- newer live activity being withheld until the current version is incorporated;
- the trace showing typed business outcomes under HTTP `200`;
- absence of network requests and persistent storage;
- keyboard operation and visible focus;
- light and dark appearance; and
- usable layouts at desktop, standard conversation, and narrow widths.

## Acceptance criteria

- The prototype is recognizably a small product dashboard rather than an API
  inspector.
- Repository grouping, actionable inbox items, fixed readiness, freshness, and
  synchronization problems are understandable without reading the API spec.
- Revision-aware polling is inspectable one response at a time.
- Live bodies appear only after opening the drawer and loading an exact version.
- Successful and stale acknowledgment cannot be mistaken for one another.
- Expected business failures retain useful context and appear as typed HTTP `200`
  outcomes in the demo trace.
- The prototype creates no real SPA source and remains safe to discard.
