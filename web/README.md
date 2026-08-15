# Bitbucket Helper Web

Vue 3, Vite, and TypeScript frontend for Bitbucket Helper.

The current slice renders deterministic in-process journeys through the
browser-facing `DashboardSource` port. It does not call the Kotlin service.

## Requirements

- Node.js ^22.22.2 || ^24.15.0 || >=26.0.0
- npm 11.17.0

## Setup

    npm ci
    npx playwright install chromium

## Commands

    npm run dev
    npm run format:check
    npm run lint
    npm run type-check
    npm run test:unit
    npm run test:e2e
    npm run build
    npm run check

npm run build writes untracked production assets to dist/.

## Development fixture journeys

Start the Vite development server with `npm run dev`, then open one of these
URLs:

- `http://127.0.0.1:5173/?fixtureJourney=healthy-refresh`
- `http://127.0.0.1:5173/?fixtureJourney=partial-refresh`
- `http://127.0.0.1:5173/?fixtureJourney=content-success`
- `http://127.0.0.1:5173/?fixtureJourney=content-unavailable`
- `http://127.0.0.1:5173/?fixtureJourney=newer-activity`
- `http://127.0.0.1:5173/?fixtureJourney=stale-acknowledgment`

The query parameter is development-only test scaffolding. It is deliberately
outside the product chrome: there is no fixture selector, reset control,
response-step control, or request trace in the dashboard UI. Development builds
validate the value and default unknown or missing values to `healthy-refresh`.
Production builds ignore the parameter and use that default.

Each navigation creates a fresh source instance, so refresh counters, published
activity versions, and other journey state are isolated to that page load. Raw
activity bodies stay in the separate exact-version content catalog and are never
part of dashboard snapshots or browser storage.

## Kotlin adapter handoff

The fixtures implement the same browser-facing `DashboardSource` port that the
real SPA adapter must implement. Before switching adapters:

1. Generate the TypeScript client from the versioned API contract and implement
   a same-origin Kotlin adapter behind `DashboardSource`; do not introduce
   handwritten wire DTOs into the components or composables.
2. Preserve the current mapping from versioned response-body discriminators to
   the typed dashboard, refresh, detail, content, and acknowledgment outcomes.
   A valid processed request returns `200 OK` even when its business outcome is
   pending, stale, partial, unavailable, rejected, or otherwise unsuccessful.
   Use `4xx` only for request/client errors and `500` for unexpected server
   failures; do not use `202` or `409` for refresh lifecycle or exact-version
   acknowledgment outcomes.
3. Obtain CSRF state from the same-origin `browser-session` endpoint and keep it
   in memory. Do not persist the token in cookies, `localStorage`, or
   `sessionStorage` from application code.
4. Run contract-fixture tests against the generated client and typed mapping
   before changing `main.ts` to inject the real adapter. Keep the deterministic
   fixture journeys available for browser acceptance after the switch.
