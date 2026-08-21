# Bitbucket Helper Web

Vue 3, Vite, and TypeScript frontend for Bitbucket Helper.

The production build uses the generated V1 client and a same-origin Kotlin API
adapter. The fat JAR embeds the resulting static assets, so the Java service is
the only production HTTP and static runtime.

## Requirements

- Node.js ^22.22.2 || ^24.15.0 || >=26.0.0
- npm 11.17.0

## Setup

    npm ci
    npx playwright install chromium

## Commands

    npm run dev
    npm run dev:backend
    npm run format:check
    npm run lint
    npm run type-check
    npm run test:unit
    npm run test:e2e
    npm run build
    npm run check

`npm run build` writes untracked production assets to `dist/`; Gradle embeds
those assets in the fat JAR.

## Developer and product modes

- `npm run dev` runs deterministic fixture journeys for UI development and the
  existing Playwright fixture suite.
- `npm run dev:backend` is development-only: Vite proxies browser requests to a
  Kotlin backend running at `127.0.0.1:8080` and selects the real API adapter.
- `npm run build` builds the production real adapter that Gradle embeds in the
  fat JAR.
- Normal product use never runs npm. Build the fat JAR, start `service run`,
  and open the Kotlin backend URL at `http://127.0.0.1:8080/`.

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
Production builds ignore `fixtureJourney` and always use the real Kotlin API
adapter; the `healthy-refresh` default applies only to fixture development.

Each navigation creates a fresh source instance, so refresh counters, published
activity versions, and other journey state are isolated to that page load. Raw
activity bodies stay in the separate exact-version content catalog and are never
part of dashboard snapshots or browser storage.

## API adapter guarantees

The real adapter uses the generated TypeScript client behind `DashboardSource`;
components and composables do not use handwritten wire DTOs. It preserves typed
dashboard, refresh, detail, live-content, and acknowledgment outcomes from the
versioned response-body discriminators. A valid processed request returns
`200 OK` even when its business outcome is pending, stale, partial,
unavailable, rejected, or otherwise unsuccessful. `4xx` remains for
request/client errors and `500` for unexpected server failures; refresh and
exact-version acknowledgment do not use `202` or `409`.

The adapter obtains CSRF state from the same-origin `browser-session` endpoint
and keeps it in memory. Application code does not persist the token in cookies,
`localStorage`, or `sessionStorage`. Fixture journeys remain available for
development and fixture-browser acceptance only.
