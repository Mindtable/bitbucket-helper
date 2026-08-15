# Vue Project Structure Design

**Date:** 2026-08-15

**Status:** Approved

## Context

Bitbucket Helper has an approved SPA-to-Kotlin API contract and an approved
product architecture that places a Vue 3 application under `web/`. It does not
yet have a JavaScript workspace or browser application.

This design defines the first working Vue slice. It establishes the project and
test structure, renders a repository-grouped dashboard from an in-process fixture,
and leaves one narrow data boundary for later OpenAPI-client integration. It does
not connect to the Kotlin service.

The approved API contract names `openapi/api-v1.yaml` as the future canonical,
machine-readable contract. That file and its generated TypeScript client are not
present yet. This slice therefore must not invent handwritten wire DTOs that could
drift from the future OpenAPI document.

The untracked `source/` shell prototype remains protected and outside this work.

## Goals

- Create a runnable Vue 3, Vite, and TypeScript application under `web/`.
- Prove a feature-oriented structure through one fixture-backed dashboard slice.
- Render pull requests grouped by repository rather than as one flat collection.
- Show readiness, build, actionable-item, freshness, and synchronization state.
- Keep expected business outcomes distinct from request and transport failures.
- Establish type-checking, formatting, linting, component tests, a production
  build, and one browser smoke test.
- Leave a narrow seam where the generated OpenAPI client can replace the fixture.
- Keep the structure and styling small enough for later personal customization.

## Non-goals

- Calling the Kotlin service or choosing its development startup workflow.
- Authoring `openapi/api-v1.yaml` or generating a TypeScript API client.
- Implementing revision-aware polling or starting background refresh work.
- Implementing pull-request details, a drawer, live activity content, or
  exact-version acknowledgment.
- Adding workspace or repository configuration controls.
- Adding Vue Router, Pinia, a component library, a CSS framework, MSW, or a design
  system.
- Packaging `web/dist/` into the Kotlin fat JAR.
- Creating a polished final dashboard design.

## Approaches considered

### Feature-oriented thin slice

The selected approach gives application startup, the dashboard feature, and the
future API adapter separate boundaries. The dashboard consumes a small source
interface, with an in-process fixture supplying the first implementation.

This adds a few purposeful files compared with an official starter, but it proves
the replacement seam that the real generated client will use and keeps the first
feature understandable in isolation.

### Flat starter structure

A flat `src/components/` application with the fixture imported directly by the
root component would create fewer files. It was rejected because data loading,
business-result handling, and presentation would immediately become coupled in
`App.vue`.

### Full application platform

Adding Router, Pinia, MSW, a shared component library, and multiple application
layers from the beginning would anticipate later features. It was rejected because
one screen and one fixture do not yet need those dependencies or abstractions.

## Tooling and workspace boundary

`web/` is an independent npm workspace, not a root npm monorepo. Kotlin/Gradle and
npm commands remain independently runnable. The first scaffold uses:

- Vue 3 with Composition API and `<script setup>`;
- Vite;
- TypeScript;
- npm with a committed `package-lock.json`;
- ESLint and Prettier;
- Vitest and Vue Test Utils; and
- Playwright with Chromium for one browser smoke test.

The current local Node.js 24.19.0 and npm 11.17.0 toolchain is suitable. The
`package.json` records the supported Node range and the npm package-manager
version. Direct dependency versions are exact rather than floating ranges, and
`package-lock.json` locks the full graph. Installation and CI use `npm ci` after
the initial scaffold.

The official `create-vue` generator supplies the initial Vite, TypeScript,
Vitest, ESLint, and Prettier configuration. Router and Pinia generation options
remain disabled. Playwright is added as the only end-to-end dependency.

## Repository layout

```text
web/
├── package.json
├── package-lock.json
├── index.html
├── vite.config.ts
├── vitest.config.ts
├── playwright.config.ts
├── tsconfig.json
├── tsconfig.app.json
├── tsconfig.node.json
├── tsconfig.vitest.json
├── eslint.config.ts
├── .prettierrc.json
├── src/
│   ├── main.ts
│   ├── app/
│   │   └── App.vue
│   ├── assets/
│   │   └── main.css
│   └── features/
│       └── dashboard/
│           ├── DashboardView.vue
│           ├── DashboardView.spec.ts
│           ├── dashboard.models.ts
│           ├── dashboardSource.ts
│           ├── useDashboard.ts
│           ├── useDashboard.spec.ts
│           ├── components/
│           │   ├── RepositoryGroup.vue
│           │   └── PullRequestCard.vue
│           └── fixtures/
│               └── fixtureDashboardSource.ts
├── tests/
│   └── setup.ts
└── e2e/
    └── dashboard.spec.ts
```

The implementation may retain additional generator-owned TypeScript configuration
files when current `create-vue` requires them. It removes demonstration components
and assets that do not serve this slice.

No empty `src/shared/api/generated/` directory is committed. When the canonical
OpenAPI file exists, deterministic generation will own that directory. A
handwritten adapter adjacent to the generated client will map transport DTOs into
the dashboard feature's view model.

## File responsibilities

### Application startup

`src/main.ts` creates the Vue application and supplies
`FixtureDashboardSource` as the root `dashboardSource` prop. `src/app/App.vue`
owns only the application frame and passes the source into `DashboardView`.

This is intentionally explicit dependency wiring. Components do not import the
fixture themselves, so replacing it later does not require editing dashboard
presentation files.

### Dashboard models

`dashboard.models.ts` contains browser-facing view models, not HTTP request or
response types. Identifiers remain opaque strings and instants remain RFC 3339
strings. Discriminated unions represent readiness, build, synchronization, and
freshness states so unavailable values cannot be confused with false or empty
values.

The initial model contains:

```text
Dashboard
├── workspaceDisplayName
├── generatedAt
└── repositoryGroups[]
    ├── repositoryId
    ├── displayName
    ├── webUrl
    ├── synchronization
    ├── freshness
    └── pullRequests[]
        ├── pullRequestId
        ├── displayNumber
        ├── title
        ├── authorDisplayName
        ├── updatedAt
        ├── webUrl
        ├── readiness
        ├── buildState
        └── actionableItemCount
```

The fixture contains two repository groups. This makes repository grouping an
observable behavior rather than an unexercised container in the type definition.

### Data source and orchestration

`dashboardSource.ts` exports one narrow interface:

```ts
export interface DashboardSource {
  load(): Promise<DashboardSourceResult>
}
```

`DashboardSourceResult` is a discriminated browser-facing result with
`dashboardAvailable` and `workspaceNotConfigured` variants. It is not a duplicate
of the future generated API envelope.

`useDashboard.ts` invokes the source and exposes:

- `loading` while the promise is unsettled;
- `ready` with the dashboard;
- `workspaceNotConfigured` with safe CLI setup guidance; or
- `failed` with a stable, safe display message.

It also exposes `reload()`. Rejected promises and unexpected exceptions enter the
`failed` state; typed expected results do not. Reload starts a new loading cycle
and repeats the source call.

The first implementation does not add cancellation, caching, polling, or a global
store. Those behaviors belong to later slices that have concrete concurrency and
lifecycle requirements.

### Presentation components

`DashboardView.vue` owns asynchronous screen-state selection and the dashboard
header. `RepositoryGroup.vue` renders repository identity plus synchronization and
freshness state. `PullRequestCard.vue` renders PR identity, readiness as `N of 7`,
build state, actionable-item count, author, timestamps, and a normal Bitbucket
link.

The components receive models through typed props. They do not load data, inspect
HTTP status, import fixtures, or know about generated DTOs.

## Runtime flow

1. Vite loads `main.ts`.
2. `main.ts` creates `App` with the fixture source.
3. `App` passes the source to `DashboardView`.
4. `useDashboard` begins an asynchronous load on mount.
5. The view first exposes a loading status, then renders the typed source result.
6. The ready result renders repository sections and their nested PR cards.
7. A source rejection renders the safe failed state; retry invokes `reload()`.

The fixture is in-process and deterministic. This slice performs no fetch, embeds
no server base URL, and reads no environment or credential value.

## API result and error semantics

The browser design follows the project-wide HTTP semantics. HTTP codes will later
describe request and transport handling only. Domain and operational outcomes
processed successfully by Kotlin remain typed response-body results under HTTP
`200 OK`.

In particular, `workspaceNotConfigured` is a normal dashboard state. It must not
be thrown or presented as a network failure. A future generated-client adapter
will inspect the versioned `result.type` discriminator and map it to the source
result. Only request failures, invalid envelopes, unknown discriminators, decoding
failures, and unexpected exceptions map to `failed`.

The failure screen shows a generic safe message and a retry control. It does not
render raw response bodies, stack traces, credentials, environment values, or
upstream diagnostics.

## Styling and accessibility baseline

The scaffold uses plain CSS with a small set of custom properties for color,
spacing, borders, and type. It uses no CSS framework or component library.

The dashboard uses semantic landmarks and heading order. Bitbucket destinations
are real links. Retry is a native button. Synchronization, freshness, readiness,
and build meaning are present in text and do not rely on color alone. Layout
collapses cleanly to one column on narrow screens.

This baseline is functional rather than a final visual identity.

## Test strategy

Implementation follows test-first development. Component and composable tests run
in Vitest with Vue Test Utils and a browser-like DOM environment.

`DashboardView.spec.ts` proves:

- the screen transitions from loading to the ready result;
- repository names are rendered as separate groups;
- each PR remains nested under its owning repository;
- readiness, build state, actionable count, and synchronization state render;
  and
- `workspaceNotConfigured` renders setup guidance as a normal state.

`useDashboard.spec.ts` proves:

- a rejected source produces the safe failed state; and
- retry begins another load and can recover to ready.

Tests use small real fake implementations of `DashboardSource`. They assert on
rendered behavior and public composable state rather than framework internals or
mock call details.

`e2e/dashboard.spec.ts` starts the Vite application on loopback, opens it in
Chromium, and verifies the fixture-backed page heading, both repository groups,
and representative PR information. It makes no Kotlin or Bitbucket request.

## Commands and build output

`package.json` exposes these stable commands:

```text
npm run dev
npm run format:check
npm run lint
npm run type-check
npm run test:unit
npm run test:e2e
npm run build
npm run check
```

`npm run build` type-checks the application before Vite emits production assets
to `web/dist/`. `npm run check` runs formatting verification, linting,
type-checking, unit/component tests, and the production build. The Playwright
smoke test is a separate explicit command because its first run requires the
Chromium browser artifact.

`web/node_modules/`, test artifacts, coverage, and `web/dist/` remain untracked.
The later Kotlin packaging slice will consume `web/dist/`; this slice makes no
Gradle change.

## Acceptance criteria

- `web/` installs reproducibly with `npm ci` on a supported Node runtime.
- `npm run dev` serves a fixture-backed Bitbucket Helper dashboard.
- The dashboard visibly groups pull requests under two repositories.
- The ready screen shows readiness as `N of 7`, build state, action counts,
  synchronization, freshness, timestamps, and Bitbucket links.
- Typed business results and unexpected failures use distinct UI states.
- No browser code calls Kotlin, Bitbucket, or another network service.
- No handwritten API wire DTO is introduced before OpenAPI generation.
- Formatting, linting, type-checking, unit/component tests, and production build
  pass without warnings.
- The explicit Playwright smoke test passes in Chromium.
- Generated output and dependency directories remain untracked.
- The protected untracked `source/` prototype remains untouched and unstaged.
