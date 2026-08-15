# Interactive Mock SPA Feed Hierarchy Revision

**Date:** 2026-08-15

**Status:** Approved

## Purpose

This specification revises the disposable interactive mock defined by
[Interactive Mock SPA Design](2026-08-15-interactive-mock-spa-design.md). It
improves scanability in the dashboard feed and adds one representative failed
build without changing the prototype's scenario engine, API semantics, or
artifact boundary.

The original mock specification remains authoritative except where this document
explicitly changes the needs-attention section, repository hierarchy, or pull
request #92 build presentation.

## Goals

- Let a reviewer collapse the needs-attention inbox while retaining its label
  and current item count.
- Make each repository visibly read as the parent of its pull requests.
- Demonstrate an unsuccessful build in both the dashboard card and drawer.
- Preserve all six existing scenario journeys and their deterministic state
  transitions.
- Keep the revision presentation-only and safe to discard before real SPA work.

## Non-goals

- Creating or changing Vue, TypeScript, Kotlin, Ktor, or OpenAPI source.
- Changing request or response fixtures for the approved API contract.
- Making repository groups collapsible.
- Adding a working build-details destination or simulating a build API.
- Adding persistence, navigation, network access, or new demo journeys.
- Reworking the drawer, scenario selector, contract trace, or acknowledgment
  lifecycle beyond reflecting the failed-build fixture added by this revision.

## Selected approach

The dashboard uses a disclosure for needs-attention content and visual tree rails
for repository-to-pull-request hierarchy. This keeps the existing single-feed
composition intact while making parent-child relationships explicit. Nested
panels would add unnecessary visual weight, and repository accordions would add
new interaction state that is outside this revision.

## Needs-attention disclosure

The full needs-attention header becomes one native button controlling the inbox
body. The header always shows:

- the `Needs attention` label;
- the current actionable-item count; and
- a chevron whose orientation communicates expanded or collapsed state.

The control exposes `aria-expanded` and references the inbox body with
`aria-controls`. The body is present and visible while expanded and hidden while
collapsed. The initial state is expanded so the first render remains useful.

Collapsing the inbox hides only the actionable rows. It must not alter the
dashboard snapshot, counts, selected pull request, open drawer, acknowledgment
state, contract trace, or scenario progress. Ordinary product interactions and
rerenders preserve the reviewer's chosen expanded state. Selecting a different
scenario or using the deterministic reset action reconstructs the initial state
and expands the inbox again.

When successful acknowledgment removes the last actionable item, the persistent
header count updates to zero whether the body is expanded or collapsed. Existing
empty-state behavior remains visible only when the disclosure is expanded.

## Repository tree hierarchy

Each repository section is presented as a tree-like group:

- the repository header is the parent row;
- its pull-request list is indented beneath that header;
- a vertical rail connects the child list to the parent context;
- each pull-request card has a short elbow connector from the rail; and
- the rail terminates at the final child rather than continuing into the next
  repository.

The hierarchy is a visual and structural presentation of data already grouped by
repository. It does not introduce ARIA tree keyboard behavior, because the pull
request cards remain independent native buttons or button-containing articles
in ordinary document order. Repository metadata, freshness, synchronization
state, last-attempt problems, and inert Bitbucket controls remain in the parent
header. All existing card actions continue to open the same drawer state.

Rails and elbows use neutral borders with sufficient contrast in both light and
dark appearance. Child indentation is reduced at narrow widths, but the parent,
rail, elbow, and card relationship remains recognizable. Long repository or pull
request text wraps without crossing the rail or clipping the card.

## Representative unsuccessful build

Pull request #92 is the single failed-build example. Its representative fixture
contains an explicit failed build state and a failed-check count of two. The
dashboard card displays all of the following together:

- `Build failed`;
- `2 failed checks`; and
- an inert `View build` control.

The failure uses warning/error color plus text and status shape, so color is not
the only signal. `View build` is rendered as a button with
`aria-disabled="true"`; activating it produces no navigation, request, scenario
transition, or trace mutation. It is deliberately inert because the disposable
mock has no build-details destination.

Opening pull request #92 shows the same failed state and failed-check count in
the drawer's pull-request context. Both surfaces read from the same fixture data
so they cannot drift. The existing readiness value remains independent of build
status and is not recalculated for this revision.

## State and rendering changes

One presentation-state field records whether the needs-attention body is
expanded. It initializes to `true`, changes only through the disclosure control,
and returns to `true` when scenario selection or reset recreates state.

The repository rail and connector treatment is derived entirely from the
existing repository grouping and child order. It adds no persistent state.

The pull request #92 fixture is the sole source of its failed status and count.
Rendering helpers consume those values in both the card and drawer. No build
failure is encoded in the contract trace or mapped to an HTTP error.

All existing business outcomes continue to be represented in versioned response
bodies under HTTP `200`. Only request errors use `4xx`, and only unexpected
server failures use `500`, consistent with project API status semantics.

## Responsive and accessibility behavior

- The disclosure is keyboard-operable as a native button and has a visible focus
  state.
- Its expanded state is available to assistive technology, and the count remains
  available while collapsed.
- Hidden inbox content is removed from interaction and accessibility traversal.
- Tree rails are decorative; semantic reading order remains repository header
  followed by its pull requests.
- Failed build state is expressed with text as well as color.
- The disabled build control communicates its unavailable state without
  pretending to be a live external link.
- At desktop, standard conversation, and narrow widths, indentation may compress
  but must not create horizontal scrolling or obscure card controls.

## Verification

Verification starts every existing journey from a deterministic reset and checks
the following additions:

- needs attention starts expanded with the correct count and expanded chevron;
- activating its header collapses and re-expands the body;
- the count and header remain visible while collapsed;
- opening or closing the drawer, advancing a response, refreshing, and
  acknowledging do not unexpectedly reopen a collapsed inbox;
- selecting a journey or resetting restores the expanded initial state;
- successful acknowledgment updates the header count while collapsed;
- every pull request appears beneath the correct repository with a continuous
  parent rail, individual elbows, and a terminating final-child rail;
- hierarchy remains clear at approximately 1024 px, 736 px, and 360 px widths;
- pull request #92 displays `Build failed`, `2 failed checks`, and inert
  `View build` in the dashboard;
- its drawer repeats the same failed status and count;
- activating `View build` does not mutate state, create a trace entry, or make a
  network request; and
- the original six journeys, light and dark appearance, keyboard flow, zero
  network requests, and zero persistent storage continue to pass.

## Acceptance criteria

- A reviewer can collapse needs attention without losing its identity or count.
- The collapse preference survives normal interaction and resets only with a
  scenario selection or deterministic reset.
- Repositories are unmistakable parents of their pull requests on desktop and
  narrow layouts.
- Pull request #92 provides a clear, accessible unsuccessful-build example in
  both dashboard and drawer.
- `View build` is visibly unavailable and has no side effects.
- Existing scenario and acknowledgment behavior is unchanged.
- No real SPA source or production API contract is modified.
