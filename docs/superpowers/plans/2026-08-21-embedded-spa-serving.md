# Embedded SPA Serving Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one executable Bitbucket Helper fat JAR that embeds the Vue SPA, serves it from the loopback Kotlin server, and connects the existing dashboard to the real generated API client without a runtime Node/Vite server.

**Architecture:** Gradle performs the locked frontend production build and copies verified assets into the main classpath under `spa/`. The browser Ktor application serves those assets alongside `/api/v1`, while a generated-client-backed `DashboardSource` maps wire outcomes into the existing Vue view models and uses an in-memory, restart-aware CSRF session manager.

**Tech Stack:** Kotlin 2.4.10, JDK 25, Ktor 3.5.1, Gradle 9.6.1, Log4j2, Vue 3.5.40, TypeScript 6.0.3, Vite 8.1.5, Vitest 4.1.10, npm 11.17.0, generated OpenAPI `typescript-fetch` client.

**Spec:** `docs/superpowers/specs/2026-08-21-embedded-spa-serving-design.md`

## Global Constraints

- Read the spec and repository `AGENTS.md` before editing.
- Use macOS, JDK 25, Node.js `^22.22.2`, `^24.15.0`, or `>=26.0.0`, and npm `11.17.0`.
- Node/npm are build-time dependencies only; the finished application runs as one Java process.
- Package production assets inside `build/libs/bitbucket-helper-0.1.0-all.jar`; do not introduce a runtime web directory.
- Production always uses the real same-origin adapter at relative base path `/api/v1`; URL state must never enable fixtures.
- Keep the existing fixture-backed `npm run dev` and existing fixture Playwright suite. Do not add a packaged-backend Playwright suite.
- Keep initial workspace and repository setup in the product CLI; do not add configuration, lifecycle, health, or log-management screens.
- Preserve API V1 semantics: every processed business outcome is HTTP `200` and branches on `result.type`; `4xx` is request/transport failure and `500` is unexpected server failure.
- Keep loopback Host fixed to `127.0.0.1`; preserve exact Host/Origin/CSRF checks and disabled CORS.
- Never persist or log the CSRF token, credentials, raw Markdown, request URLs, paths, queries, headers, cookies, bodies, upstream payloads, SQL/binds, exception messages, or arbitrary objects.
- Every changed inbound/lifecycle boundary emits one typed terminal outcome with allowlisted fields and explicit correlations; tests prove both terminal and rotating JSON destinations.
- Keep domain code logging-free and continue using the outbound observability port where application failures are converted or swallowed.
- Do not change `openapi/api-v1.yaml` or generated API artifacts unless a separately reviewed contract defect is found.
- Preserve the existing uncommitted `README.md` and `docs/installation-and-web-ui.md` changes from the preceding documentation request. If execution uses a new worktree, carry those edits forward before Task 7 instead of discarding them.
- Follow strict red-green TDD in every task. Run each named failing test before implementation, then rerun it after the minimal change.

---

## File Structure

### Frontend files

- Create `web/src/features/dashboard/backend/apiMappers.ts` — pure generated-wire to `DashboardSource`/view-model conversion and fixed safe presentation copy.
- Create `web/src/features/dashboard/backend/apiMappers.spec.ts` — every business discriminator, nested state, validation, and echo test.
- Create `web/src/features/dashboard/backend/apiTestData.ts` — typed canonical wire builders shared only by backend-adapter unit tests.
- Create `web/src/features/dashboard/backend/browserSession.ts` — memory-only CSRF bootstrap, single-flight state, and restart-aware one-retry mutation wrapper.
- Create `web/src/features/dashboard/backend/browserSession.spec.ts` — concurrency, failure, restart, and retry tests.
- Create `web/src/features/dashboard/backend/kotlinApiDashboardSource.ts` — generated API orchestration behind `DashboardSource`.
- Create `web/src/features/dashboard/backend/kotlinApiDashboardSource.spec.ts` — exact generated operation arguments and outcome mapping tests.
- Create `web/src/features/dashboard/createDashboardSource.ts` — compile-time production/backend-development/fixture source selection.
- Create `web/src/features/dashboard/createDashboardSource.spec.ts` — production cannot select fixtures; development journey validation remains.
- Modify `web/src/features/dashboard/dashboardSource.ts` — align repository refresh and detail results with the real wire boundary.
- Modify `web/src/features/dashboard/usePullRequestDrawer.ts` and its tests — remove the unwired activity-version refresh parameter and handle detail setup guidance.
- Modify `web/src/features/dashboard/fixtures/fixtureDashboardSource.ts` and its tests — satisfy the aligned port without changing fixture behavior.
- Modify `web/src/main.ts` — bootstrap the selected source instead of importing fixtures directly.
- Modify `web/vite.config.ts` and `web/package.json` — add the development-only real-backend proxy command.

### Build and packaging files

- Modify `build.gradle.kts` — validate Node/npm, run locked install/build, verify assets, add generated SPA resources to `processResources`, and clean `web/dist`.
- Create `src/test/kotlin/com/mindtable/bitbuckethelper/FatJarTestSupport.kt` — locate the single built fat JAR for process and archive tests.
- Modify `src/test/kotlin/com/mindtable/bitbuckethelper/MissingCredentialsProcessTest.kt` — reuse the shared fat-JAR locator.
- Create `src/test/kotlin/com/mindtable/bitbuckethelper/SpaJarPackagingTest.kt` — inspect `spa/index.html` and every referenced archive asset.

### Backend serving files

- Create `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaAssets.kt` — classpath resource validation, normalized lookup, and MIME classification.
- Create `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaAssetsTest.kt` — missing/empty entry, traversal, lookup, and MIME tests.
- Create `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaObservability.kt` — static request categorization and terminal typed events.
- Create `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaObservabilityTest.kt` — completed/rejected/failed event and privacy tests.
- Create `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaRoutes.kt` — root/index/assets routes, hardened headers, cache policy, GET/HEAD handling, and fixed errors.
- Create `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaRoutesTest.kt` — content, headers, authority, route isolation, method, error, and no-CORS tests.
- Modify `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/RequestIds.kt` — share local request IDs between API and SPA observations.
- Modify `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BrowserSecurity.kt` and its tests — exact authority for every browser request and optional Origin validation for static reads.
- Modify `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Errors.kt` — fixed non-API `403`/`404`/`405` handling without changing API envelopes.
- Modify `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/LocalApiServers.kt` and parity tests — install SPA only in the browser application.
- Modify `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Commands.kt` and `ServiceLoggingBootstrapTest.kt` — validate the SPA before runtime construction and log `spa_assets` failure.
- Modify `src/test/kotlin/com/mindtable/bitbuckethelper/observability/ServiceLoggingTest.kt` — prove SPA operations in both log destinations.

### Documentation files

- Modify `README.md`, `docs/installation-and-web-ui.md`, `docs/operations/manual-service-run.md`, `web/README.md`, and `docs/project-backlog.md` — document the one-process production workflow and manual acceptance checklist.

---

### Task 1: Align the Dashboard Port and Add Exhaustive Wire Mappers

**Files:**
- Create: `web/src/features/dashboard/backend/apiMappers.ts`
- Create: `web/src/features/dashboard/backend/apiMappers.spec.ts`
- Create: `web/src/features/dashboard/backend/apiTestData.ts`
- Modify: `web/src/features/dashboard/dashboardSource.ts`
- Modify: `web/src/features/dashboard/usePullRequestDrawer.ts`
- Modify: `web/src/features/dashboard/usePullRequestDrawer.spec.ts`
- Modify: `web/src/features/dashboard/fixtures/fixtureDashboardSource.ts`
- Modify: `web/src/features/dashboard/fixtures/fixtureDashboardSource.spec.ts`
- Modify: `web/src/features/dashboard/testing/dashboardTestSource.ts`
- Modify: `web/src/features/dashboard/DashboardView.spec.ts`

**Interfaces:**
- Consumes: generated result types exported from `@/generated/api-v1/src` and existing models from `dashboard.models.ts`.
- Produces: `PullRequestDetailSourceModel`; updated `DashboardSource.startRepositoryRefresh(repositoryId: string)`; `mapDashboardResult`, `mapRefreshResult`, `mapPullRequestDetailResult`, `mapLiveContentResult`, and `mapAcknowledgmentResult`.

- [ ] **Step 1: Write failing port and mapper tests**

Add typed wire builders to `apiTestData.ts` with fixed IDs, UTC timestamps, all seven readiness checks, one COMMENT action, one closed action, and each synchronization/build variant. In `apiMappers.spec.ts`, start with these assertions:

```ts
import { describe, expect, it } from 'vitest'
import { WorkspaceNotConfiguredResultSetupCommandEnum } from '@/generated/api-v1/src'

import {
  dashboardChangedResult,
  pullRequestFoundResult,
} from './apiTestData'
import {
  mapDashboardResult,
  mapPullRequestDetailResult,
} from './apiMappers'

describe('API result mappers', () => {
  it('maps a changed snapshot and filters closed action metadata', () => {
    const mapped = mapDashboardResult(dashboardChangedResult())
    expect(mapped.type).toBe('snapshotChanged')
    if (mapped.type !== 'snapshotChanged') throw new Error('expected snapshotChanged')
    expect(mapped.dashboard.workspaceDisplayName).toBe('Mindtable')
    expect(mapped.dashboard.repositoryGroups[0]?.pullRequests[0]?.displayNumber).toBe(42)
    expect(mapped.dashboard.repositoryGroups[0]?.pullRequests[0]?.actionItems).toHaveLength(1)
    expect(JSON.stringify(mapped.dashboard)).not.toContain('closed_action')
  })

  it('keeps workspace setup distinct from pull-request not found', () => {
    expect(
      mapPullRequestDetailResult(
        {
          type: 'workspaceNotConfigured',
          setupCommand:
            WorkspaceNotConfiguredResultSetupCommandEnum.bitbucket_helper_workspace_configure,
        },
        'pr_expected',
      ),
    ).toEqual({
      type: 'workspaceNotConfigured',
      setupCommand: 'bitbucket-helper workspace configure',
    })
  })

  it('rejects a mismatched pull-request echo', () => {
    expect(() => mapPullRequestDetailResult(pullRequestFoundResult('pr_other'), 'pr_expected'))
      .toThrow('pull request response did not match the request')
  })
})
```

Add table-driven assertions for these exact result sets:

- dashboard: `snapshotChanged`, `snapshotUnchanged`, `workspaceNotConfigured`;
- refresh: `refreshRunRegistered`, `noRepositoriesConfigured`, `workspaceNotConfigured`;
- PR detail: `pullRequestFound`, `pullRequestNotFound`, `workspaceNotConfigured`;
- content: `contentAvailable`, `contentUnavailable`, `newerActivityObserved`, `staleActivityVersion`, `actionItemNotFound`;
- acknowledgment: `acknowledged`, `alreadyAcknowledged`, `staleActivityVersion`, `acknowledgmentRejected`, `actionItemNotFound`;
- nested polling, freshness, synchronization activity/problem, readiness, build state, action state, action kind, and content-unavailable reason variants;
- invalid readiness total other than `7`, unsupported action kind, missing/invalid values, and mismatched action/version echoes.

Decode the committed canonical fixtures through generated functions before
mapping them, rather than treating test objects as proof of generated decoding:

```ts
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  AcknowledgeActionItemResponseFromJSON,
  DashboardResponseFromJSON,
  LiveActivityContentResponseFromJSON,
} from '@/generated/api-v1/src'

function contractFixture(relativePath: string): unknown {
  const url = new URL(`../../../../../openapi/fixtures/v1/${relativePath}`, import.meta.url)
  return JSON.parse(readFileSync(fileURLToPath(url), 'utf8'))
}

expect(mapDashboardResult(
  DashboardResponseFromJSON(contractFixture('valid/dashboard-snapshot-unchanged.json')).result,
)).toEqual({
  type: 'snapshotUnchanged',
  dashboardRevision: 'dr_dashboard_fixture',
  serverTime: '2026-08-15T17:30:00Z',
  polling: { type: 'idle' },
})

expect(mapLiveContentResult(
  LiveActivityContentResponseFromJSON(contractFixture('valid/live-content-available.json')).result,
  { actionItemId: 'ai_comment_fixture', activityVersion: 'av_comment_fixture_1' },
).type).toBe('contentAvailable')

expect(mapAcknowledgmentResult(
  AcknowledgeActionItemResponseFromJSON(
    contractFixture('valid/acknowledgment-already-applied.json'),
  ).result,
  { actionItemId: 'ai_comment_fixture', activityVersion: 'av_comment_fixture_1' },
).type).toBe('alreadyAcknowledged')
```

Assert `DashboardResponseFromJSON` throws for
`invalid/unknown-discriminator.json`; assert the mapper rejects decoded objects
whose required values are `undefined` rather than silently building a view.

- [ ] **Step 2: Run the tests to verify the new boundary does not exist**

Run: `npm run test:unit -- src/features/dashboard/backend/apiMappers.spec.ts`

Expected: FAIL because `apiMappers.ts`, its exports, and the aligned source types do not exist.

- [ ] **Step 3: Align the source port and drawer contract**

Change `dashboardSource.ts` to these exact shapes:

```ts
export interface PullRequestDetailSourceModel {
  pullRequest: PullRequestSummary
  readinessChecks: readonly ReadinessCheckModel[]
  actionItems: readonly ActionItemSummary[]
}

export interface DashboardSource {
  loadDashboard(afterRevision?: string): Promise<DashboardSourceResult>
  startRefresh(): Promise<RefreshSourceResult>
  loadPullRequest(pullRequestId: string): Promise<PullRequestDetailSourceResult>
  loadActionContent(actionItemId: string, activityVersion: string): Promise<ActionContentSourceResult>
  acknowledgeActionItem(actionItemId: string, activityVersion: string): Promise<AcknowledgmentSourceResult>
  startRepositoryRefresh(repositoryId: string): Promise<RefreshSourceResult>
}

export type PullRequestDetailSourceResult =
  | { type: 'pullRequestAvailable'; detail: PullRequestDetailSourceModel }
  | { type: 'pullRequestNotFound' }
  | { type: 'workspaceNotConfigured'; setupCommand: string }
```

In `usePullRequestDrawer.ts`, call `source.startRepositoryRefresh(repositoryId)` while retaining `observedActivityVersion` only in the drawer state. Handle detail setup with fixed safe copy:

```ts
if (result.type === 'workspaceNotConfigured') {
  state.value = {
    type: 'detailUnavailable',
    context: state.value.context,
    message: `Workspace not configured. ${result.setupCommand}`,
  }
  return
}
```

Update fixture/test sources and every assertion found by:

Run: `rg -n "startRepositoryRefresh|PullRequestDetailSourceResult" web/src`

Expected: all implementations accept one repository ID and all detail switches are exhaustive.

- [ ] **Step 4: Implement the pure mappers**

Export these signatures from `apiMappers.ts`:

```ts
export function mapDashboardResult(result: DashboardResult): DashboardSourceResult
export function mapRefreshResult(result: StartRefreshRunResult): RefreshSourceResult
export function mapPullRequestDetailResult(
  result: PullRequestDetailResult,
  requestedPullRequestId: string,
): PullRequestDetailSourceResult
export function mapLiveContentResult(
  result: LiveActivityContentResult,
  requested: { actionItemId: string; activityVersion: string },
): ActionContentSourceResult
export function mapAcknowledgmentResult(
  result: AcknowledgeActionItemResult,
  requested: { actionItemId: string; activityVersion: string },
): AcknowledgmentSourceResult
```

Use exhaustive `switch` statements ending in this helper:

```ts
function assertNever(value: never): never {
  throw new Error(`Unsupported API result: ${String(value)}`)
}
```

Before constructing view models, validate every consumed field with four fixed
helpers: non-empty strings; non-negative safe integers; UTC instants that parse
and end in `Z`; and absolute `http`/`https` URLs without user information.
Require active polling delays to be positive and all count relationships to be
internally consistent. Each helper throws fixed field-category copy such as
`Invalid dashboard API model: instant`; it never interpolates the received
value.

Apply these exact presentation policies:

```ts
const NO_REPOSITORIES_SETUP_COMMAND = 'bitbucket-helper repository add <slug>'

const CONTENT_REASON_COPY = {
  authentication: 'Bitbucket authentication failed.',
  authorization: 'Bitbucket authorization failed.',
  rateLimited: 'Bitbucket rate limiting delayed this content.',
  timeout: 'Bitbucket content loading timed out.',
  network: 'Bitbucket content is unavailable because of a network failure.',
  upstream: 'Bitbucket could not provide this content.',
  malformedUpstream: 'Bitbucket returned content in an unsupported form.',
  deleted: 'This activity was deleted.',
} as const
```

- Normalize COMMENT, REPLY, and THREAD (case-insensitive) to `comment`; normalize CHANGES_REQUESTED to `changesRequested`; reject every other kind.
- Filter `closed` actions from card/detail projections; map `open` to `actionable` and `acknowledged` to `acknowledged`.
- Require readiness `total === 7`; map a passed check to `passed`, a failed check with `safeReason` to `unavailable`, and another failed check to `failed`.
- For pull-request detail, map readiness checks only from an `available` readiness result; an `unavailable` readiness result produces an empty check list while the card retains its safe unavailable reason.
- Map `noBuilds` and `unknown` to `unavailable` with fixed copy; do not invent failed-check counts.
- Format `ageMilliseconds` as `Just now`, singular/plural minutes, hours, or days without recalculating freshness thresholds.
- Derive synchronization problem copy only from typed failure categories/counts; use `Retry after the service backoff expires.` when any `retryAt` is present.
- Validate echoed pull-request/action/version/repository IDs before returning a source result.
- Require acknowledgment `staleActivityVersion.hasNewerActivity === true`; reject an impossible false value instead of weakening the exact-version flow.
- Map acknowledgment rejection to `This activity can no longer be acknowledged.` and never copy arbitrary error text.

- [ ] **Step 5: Run focused and affected frontend checks**

Run: `npm run test:unit -- src/features/dashboard/backend/apiMappers.spec.ts src/features/dashboard/fixtures/fixtureDashboardSource.spec.ts src/features/dashboard/usePullRequestDrawer.spec.ts src/features/dashboard/DashboardView.spec.ts`

Expected: PASS with every generated result and port consumer covered.

Run: `npm run type-check`

Expected: PASS; no two-argument `startRepositoryRefresh` call remains.

- [ ] **Step 6: Commit the mapper boundary**

```bash
git add web/src/features/dashboard/backend/apiMappers.ts \
  web/src/features/dashboard/backend/apiMappers.spec.ts \
  web/src/features/dashboard/backend/apiTestData.ts \
  web/src/features/dashboard/dashboardSource.ts \
  web/src/features/dashboard/usePullRequestDrawer.ts \
  web/src/features/dashboard/usePullRequestDrawer.spec.ts \
  web/src/features/dashboard/fixtures/fixtureDashboardSource.ts \
  web/src/features/dashboard/fixtures/fixtureDashboardSource.spec.ts \
  web/src/features/dashboard/testing/dashboardTestSource.ts \
  web/src/features/dashboard/DashboardView.spec.ts
git commit -m "feat: map generated API results to dashboard models"
```

### Task 2: Add the Memory-Only Browser Session Manager

**Files:**
- Create: `web/src/features/dashboard/backend/browserSession.ts`
- Create: `web/src/features/dashboard/backend/browserSession.spec.ts`

**Interfaces:**
- Consumes: generated `BrowserSessionResponse` and `ResponseError`.
- Produces: `BrowserSessionClient`, `BrowserSessionState`, and `BrowserSessionManager.prefetch()` / `runMutation()`.

- [ ] **Step 1: Write failing CSRF lifecycle tests**

```ts
import { describe, expect, it, vi } from 'vitest'
import {
  ApiVersion,
  BrowserSessionResultTypeEnum,
} from '@/generated/api-v1/src'
import { ResponseError } from '@/generated/api-v1/src/runtime'
import { BrowserSessionManager } from './browserSession'

const session = (serviceInstanceId: string, csrfToken: string) => ({
  apiVersion: ApiVersion._1,
  requestId: `req_${serviceInstanceId}`,
  result: {
    type: BrowserSessionResultTypeEnum.browserSession,
    serviceInstanceId,
    csrfToken,
  },
})

describe('BrowserSessionManager', () => {
  it('shares one bootstrap across concurrent mutations', async () => {
    const getBrowserSession = vi.fn().mockResolvedValue(session('svc_one', 'csrf_one'))
    const manager = new BrowserSessionManager({ getBrowserSession })
    await Promise.all([
      manager.runMutation(async (token) => token),
      manager.runMutation(async (token) => token),
    ])
    expect(getBrowserSession).toHaveBeenCalledTimes(1)
  })

  it('retries once only after the service instance changes', async () => {
    const getBrowserSession = vi.fn()
      .mockResolvedValueOnce(session('svc_old', 'csrf_old'))
      .mockResolvedValueOnce(session('svc_new', 'csrf_new'))
    const operation = vi.fn()
      .mockRejectedValueOnce(new ResponseError(new Response(null, { status: 403 })))
      .mockResolvedValueOnce('accepted')
    const manager = new BrowserSessionManager({ getBrowserSession })
    await expect(manager.runMutation(operation)).resolves.toBe('accepted')
    expect(operation.mock.calls.map(([token]) => token)).toEqual(['csrf_old', 'csrf_new'])
  })
})
```

Also test failed bootstrap resets, malformed/empty session fields fail with fixed
copy, same-instance `403` is rethrown, second `403` is rethrown, non-`403` is
never retried, concurrent stale callers reuse one refreshed session, and no
browser storage/cookie API is touched.

- [ ] **Step 2: Run the tests and observe the missing class**

Run: `npm run test:unit -- src/features/dashboard/backend/browserSession.spec.ts`

Expected: FAIL because `BrowserSessionManager` does not exist.

- [ ] **Step 3: Implement the session manager**

```ts
import {
  ApiVersion,
  BrowserSessionResultTypeEnum,
  type BrowserSessionResponse,
} from '@/generated/api-v1/src'
import { ResponseError } from '@/generated/api-v1/src/runtime'

export interface BrowserSessionClient {
  getBrowserSession(): Promise<BrowserSessionResponse>
}

export interface BrowserSessionState {
  csrfToken: string
  serviceInstanceId: string
}

export class BrowserSessionManager {
  private resolved: BrowserSessionState | undefined
  private inFlight: Promise<BrowserSessionState> | undefined

  constructor(private readonly client: BrowserSessionClient) {}

  prefetch(): void {
    void this.session().catch(() => undefined)
  }

  async runMutation<T>(operation: (csrfToken: string) => Promise<T>): Promise<T> {
    const used = await this.session()
    try {
      return await operation(used.csrfToken)
    } catch (failure) {
      if (!(failure instanceof ResponseError) || failure.response.status !== 403) throw failure
      const refreshed = await this.refreshAfter(used.serviceInstanceId)
      if (refreshed.serviceInstanceId === used.serviceInstanceId) throw failure
      return operation(refreshed.csrfToken)
    }
  }

  private session(): Promise<BrowserSessionState> {
    if (this.resolved) return Promise.resolve(this.resolved)
    if (this.inFlight) return this.inFlight
    const request = Promise.resolve()
      .then(() => this.client.getBrowserSession())
      .then((response) => this.toState(response))
    const tracked = request.then(
      (state) => {
        this.resolved = state
        this.inFlight = undefined
        return state
      },
      (failure) => {
        this.inFlight = undefined
        throw failure
      })
    this.inFlight = tracked
    return tracked
  }

  private async refreshAfter(serviceInstanceId: string): Promise<BrowserSessionState> {
    const current = this.resolved
    if (current && current.serviceInstanceId !== serviceInstanceId) return current
    if (current?.serviceInstanceId === serviceInstanceId) this.resolved = undefined
    return this.session()
  }

  private toState(response: BrowserSessionResponse): BrowserSessionState {
    const { result } = response
    if (
      response.apiVersion !== ApiVersion._1 ||
      result.type !== BrowserSessionResultTypeEnum.browserSession ||
      typeof result.csrfToken !== 'string' ||
      result.csrfToken.length === 0 ||
      typeof result.serviceInstanceId !== 'string' ||
      !/^svc_[A-Za-z0-9_-]+$/.test(result.serviceInstanceId)
    ) {
      throw new Error('Browser session response was invalid')
    }
    return {
      csrfToken: result.csrfToken,
      serviceInstanceId: result.serviceInstanceId,
    }
  }
}
```

Keep failures opaque: do not log, stringify, or wrap tokens or response bodies.

- [ ] **Step 4: Run the focused tests**

Run: `npm run test:unit -- src/features/dashboard/backend/browserSession.spec.ts`

Expected: PASS, including concurrency and at-most-once retry assertions.

- [ ] **Step 5: Commit the session manager**

```bash
git add web/src/features/dashboard/backend/browserSession.ts \
  web/src/features/dashboard/backend/browserSession.spec.ts
git commit -m "feat: manage browser CSRF state in memory"
```

### Task 3: Implement the Real Generated-Client Source and Bootstrap Selection

**Files:**
- Create: `web/src/features/dashboard/backend/kotlinApiDashboardSource.ts`
- Create: `web/src/features/dashboard/backend/kotlinApiDashboardSource.spec.ts`
- Create: `web/src/features/dashboard/createDashboardSource.ts`
- Create: `web/src/features/dashboard/createDashboardSource.spec.ts`
- Modify: `web/src/main.ts`
- Modify: `web/vite.config.ts`
- Modify: `web/package.json`

**Interfaces:**
- Consumes: Task 1 mapper exports and Task 2 `BrowserSessionManager`.
- Produces: `KotlinApiDashboardSource`, `createKotlinApiDashboardSource()`, async `createDashboardSource()`, `selectDashboardSourceMode`, and `fixtureJourneyFromSearch`.

- [ ] **Step 1: Write failing generated-operation tests**

Use `Pick<GeneratedApi, 'operation'>` dependency seams and assert exact inputs:

```ts
it('registers one repository through the canonical refresh target', async () => {
  const startRefreshRun = vi.fn().mockResolvedValue(refreshRegisteredResponse('rr_one'))
  const source = sourceWith({ startRefreshRun })

  await expect(source.startRepositoryRefresh('repo_one')).resolves.toEqual({
    type: 'refreshRunRegistered',
    refreshRunId: 'rr_one',
  })
  expect(startRefreshRun).toHaveBeenCalledWith({
    startRefreshRunRequest: {
      apiVersion: ApiVersion._1,
      target: {
        type: RepositoriesTargetTypeEnum.repositories,
        repositoryIds: new Set(['repo_one']),
      },
    },
    xCSRFToken: 'csrf_test',
  })
})
```

Cover all six source methods, relative generated configuration, CSRF on the three mutation paths, no CSRF on reads, mapper propagation, and rejected generated-client failures.

In `createDashboardSource.spec.ts`, prove production ignores fixture query state:

```ts
expect(selectDashboardSourceMode({
  production: true,
  backendDevelopment: false,
})).toBe('kotlin')
expect(fixtureJourneyFromSearch('?fixtureJourney=partial-refresh')).toBe('partial-refresh')
expect(fixtureJourneyFromSearch('?fixtureJourney=unknown')).toBe('healthy-refresh')
```

Development fixture mode must still validate the six approved journey names and default unknown values to `healthy-refresh`; backend-development mode must select the real source.

- [ ] **Step 2: Run the tests to prove source/bootstrap are absent**

Run: `npm run test:unit -- src/features/dashboard/backend/kotlinApiDashboardSource.spec.ts src/features/dashboard/createDashboardSource.spec.ts`

Expected: FAIL because the source and selector modules do not exist.

- [ ] **Step 3: Implement `KotlinApiDashboardSource`**

Define focused API seams:

```ts
export interface KotlinApiClients {
  dashboard: Pick<DashboardApi, 'getDashboard'>
  refresh: Pick<RefreshApi, 'startRefreshRun'>
  pullRequests: Pick<PullRequestsApi, 'getPullRequest'>
  actionItems: Pick<ActionItemsApi, 'getLiveActivityContent' | 'acknowledgeActionItem'>
}
```

Implement each method by calling the generated API and immediately mapping `response.result`. Mutation bodies are exactly:

```ts
const allTarget = {
  apiVersion: ApiVersion._1,
  target: { type: AllConfiguredRepositoriesTargetTypeEnum.allConfiguredRepositories },
}

const repositoryTarget = (repositoryId: string) => ({
  apiVersion: ApiVersion._1,
  target: {
    type: RepositoriesTargetTypeEnum.repositories,
    repositoryIds: new Set([repositoryId]),
  },
})

const acknowledgment = (activityVersion: string) => ({
  apiVersion: ApiVersion._1,
  activityVersion,
})
```

`createKotlinApiDashboardSource()` constructs one generated `Configuration({ basePath: '/api/v1' })`, all generated API clients, one `BrowserSessionManager`, calls `prefetch()`, and returns the source.

- [ ] **Step 4: Implement compile-time source selection and Vite backend development**

Move fixture-query parsing out of `main.ts`. Keep the compile-time branch inside
the production function so Vite can remove the development-only dynamic import:

```ts
export interface DashboardSourceModeEnvironment {
  production: boolean
  backendDevelopment: boolean
}

export function selectDashboardSourceMode(
  environment: DashboardSourceModeEnvironment,
): 'kotlin' | 'fixture' {
  return environment.production || environment.backendDevelopment ? 'kotlin' : 'fixture'
}

export async function createDashboardSource(): Promise<DashboardSource> {
  if (import.meta.env.PROD || import.meta.env.MODE === 'backend') {
    return createKotlinApiDashboardSource()
  }
  const { createFixtureDashboardSource } = await import('./fixtures/fixtureDashboardSource')
  return createFixtureDashboardSource(fixtureJourneyFromSearch(window.location.search))
}
```

Update `main.ts`:

```ts
async function bootstrap() {
  const source = await createDashboardSource()
  createApp(App, { dashboardSource: source }).mount('#app')
}

void bootstrap()
```

Add `"dev:backend": "vite --host 127.0.0.1 --mode backend"` to `package.json`.
Keep `vite.config.ts` as a static configuration object so the existing
`vitest.config.ts` merge remains valid. Add a fixed `/api/v1` development proxy
with target `http://127.0.0.1:8080`, `changeOrigin: true`, and a `proxyReq` hook
that sets `Origin` to that same target. Fixture mode makes no API requests, so
only `dev:backend` uses the proxy. Do not enable CORS.

- [ ] **Step 5: Run frontend source and regression checks**

Run: `npm run test:unit -- src/features/dashboard/backend/kotlinApiDashboardSource.spec.ts src/features/dashboard/createDashboardSource.spec.ts`

Expected: PASS.

Run: `npm run check`

Expected: PASS; production build type-checks and fixture unit/component tests remain green.

Run: `npm run test:e2e`

Expected: PASS against the existing fixture-only Vite journey; no packaged-backend test is added.

- [ ] **Step 6: Commit the production source**

```bash
git add web/src/features/dashboard/backend/kotlinApiDashboardSource.ts \
  web/src/features/dashboard/backend/kotlinApiDashboardSource.spec.ts \
  web/src/features/dashboard/createDashboardSource.ts \
  web/src/features/dashboard/createDashboardSource.spec.ts \
  web/src/main.ts web/vite.config.ts web/package.json
git commit -m "feat: connect dashboard to generated backend client"
```

### Task 4: Build and Embed Verified Production Assets

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/test/kotlin/com/mindtable/bitbuckethelper/FatJarTestSupport.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/MissingCredentialsProcessTest.kt`
- Create: `src/test/kotlin/com/mindtable/bitbuckethelper/SpaJarPackagingTest.kt`

**Interfaces:**
- Consumes: Task 3's production bundle and existing `buildFatJar` task.
- Produces: Gradle tasks `validateWebToolchain`, `installWebDependencies`, `buildWebProduction`, `verifyWebProductionAssets`, and `syncWebProductionResources`; classpath `spa/**`; `locateSingleFatJar()` test helper.

- [ ] **Step 1: Write the failing fat-JAR archive test**

```kotlin
class SpaJarPackagingTest {
    @Test
    fun `fat jar contains only the verified production SPA`() {
        ZipFile(locateSingleFatJar().toFile()).use { jar ->
            val index = jar.getInputStream(requireNotNull(jar.getEntry("spa/index.html")))
                .bufferedReader()
                .use { it.readText() }
            val references = Regex("(?:src|href)=\"/assets/([^\"]+)\"")
                .findAll(index)
                .map { "spa/assets/${it.groupValues[1]}" }
                .toList()
            assertTrue(references.isNotEmpty())
            references.forEach { assertNotNull(jar.getEntry(it), "missing $it") }
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertTrue(names.none { it.startsWith("spa/") && it.endsWith(".map") })
            assertTrue(names.none { it.startsWith("spa/") && it.endsWith(".ts") })
        }
    }
}
```

Extract `locateSingleFatJar()` from `MissingCredentialsProcessTest` into `FatJarTestSupport.kt` without changing its process behavior.

- [ ] **Step 2: Run the archive test before Gradle packaging changes**

Run: `./gradlew test --tests 'com.mindtable.bitbuckethelper.SpaJarPackagingTest'`

Expected: FAIL because `spa/index.html` is absent from the current fat JAR.

- [ ] **Step 3: Add the locked frontend build graph**

At the top-level Gradle script define:

```kotlin
val webDirectory = layout.projectDirectory.dir("web")
val webDistDirectory = webDirectory.dir("dist")
val generatedSpaResources = layout.buildDirectory.dir("generated/resources/spa/main")

data class WebSemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<WebSemVer> {
    override fun compareTo(other: WebSemVer) =
        compareValuesBy(this, other, WebSemVer::major, WebSemVer::minor, WebSemVer::patch)
}

fun parseWebSemVer(raw: String) = raw.trim().removePrefix("v").split('.').let { parts ->
    check(parts.size >= 3) { "Unsupported version output" }
    WebSemVer(parts[0].toInt(), parts[1].toInt(), parts[2].takeWhile { it.isDigit() }.toInt())
}
```

`validateWebToolchain` runs `node --version` and `npm --version`, accepts Node `22.22.2 <= v < 23.0.0`, `24.15.0 <= v < 25.0.0`, or `v >= 26.0.0`, and requires npm exactly `11.17.0`. Failure text contains only the tool name, required range, and observed version.

`installWebDependencies`:

```kotlin
val installWebDependencies by tasks.registering(Exec::class) {
    dependsOn(validateWebToolchain)
    workingDir(webDirectory.asFile)
    inputs.files(webDirectory.file("package.json"), webDirectory.file("package-lock.json"))
    outputs.file(webDirectory.file("node_modules/.package-lock.json"))
    commandLine(*(
        listOf("npm", "ci") +
            if (gradle.startParameter.isOffline) listOf("--offline") else emptyList()
        ).toTypedArray())
}
```

`buildWebProduction` depends on installation, declares `web/src`, Vite/TypeScript config, package files, and committed generated API files as inputs, deletes `web/dist` in `doFirst`, runs `npm run build`, and declares `web/dist` as output.

- [ ] **Step 4: Verify and sync the asset closure**

`verifyWebProductionAssets` reads `web/dist/index.html`, extracts only absolute `/assets/...` `src`/`href` references, requires at least one JS and one CSS reference, normalizes each target below `web/dist`, and requires every referenced file. Reject `.map`, `.ts`, `.tsx`, `.vue`, `.env*`, `package.json`, `package-lock.json`, and any JavaScript containing `fixtureJourney` or the fixture body sentinel `Could we cap the retry window`.

`syncWebProductionResources` is a `Sync` task that depends on verification and copies `web/dist/**` into `${generatedSpaResources}/spa/`. Register the generated root as a main resources directory and make `processResources` depend on the sync task:

```kotlin
sourceSets.named("main") {
    resources.srcDir(generatedSpaResources)
}

tasks.named("processResources") {
    dependsOn(syncWebProductionResources)
}

tasks.named("clean") {
    doLast { delete(webDistDirectory) }
}
```

- [ ] **Step 5: Run packaging and existing process tests**

Run: `./gradlew test --tests 'com.mindtable.bitbuckethelper.SpaJarPackagingTest' --tests 'com.mindtable.bitbuckethelper.MissingCredentialsProcessTest'`

Expected: PASS; the archive contains the complete production asset closure and the existing process test still locates one JAR.

Run: `./gradlew buildFatJar`

Expected: `BUILD SUCCESSFUL`; `jar tf build/libs/bitbucket-helper-0.1.0-all.jar` lists `spa/index.html` and hashed `spa/assets/*` entries.

- [ ] **Step 6: Commit the asset pipeline**

```bash
git add build.gradle.kts \
  src/test/kotlin/com/mindtable/bitbuckethelper/FatJarTestSupport.kt \
  src/test/kotlin/com/mindtable/bitbuckethelper/MissingCredentialsProcessTest.kt \
  src/test/kotlin/com/mindtable/bitbuckethelper/SpaJarPackagingTest.kt
git commit -m "build: embed verified SPA assets in fat jar"
```

### Task 5: Add Classpath Asset Validation and the Startup Gate

**Files:**
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaAssets.kt`
- Create: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaAssetsTest.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Commands.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceLoggingBootstrapTest.kt`

**Interfaces:**
- Consumes: classpath `spa/index.html` produced by Task 4.
- Produces: `SpaResourceReader`, `SpaAsset`, `SpaAssets.classpath()`, `requireEntryPoint()`, `find(relativePath)`, and bootstrap seam `validateSpaAssets`.

- [ ] **Step 1: Write failing classpath catalog tests**

```kotlin
class SpaAssetsTest {
    @Test
    fun `entry point must exist and be non-empty`() {
        val missing = SpaAssets(SpaResourceReader { null })
        val empty = SpaAssets(SpaResourceReader { ByteArray(0) })
        assertEquals("Packaged SPA assets are unavailable", assertThrows<IllegalStateException> {
            missing.requireEntryPoint()
        }.message)
        assertThrows<IllegalStateException> { empty.requireEntryPoint() }
    }

    @Test
    fun `lookup rejects traversal and classifies allowed content`() {
        val assets = SpaAssets(SpaResourceReader { resource -> resource.encodeToByteArray() })
        assertNull(assets.find("../application.conf"))
        assertNull(assets.find("assets/../../application.conf"))
        assertEquals(ContentType.Text.CSS.withCharset(Charsets.UTF_8), assets.find("assets/app.css")?.contentType)
        assertEquals(ContentType.parse("text/javascript; charset=UTF-8"), assets.find("assets/app.js")?.contentType)
    }
}
```

Add coverage for `/`, `index.html`, nested allowed asset segments, backslashes, empty/dot segments, unsupported extensions, SVG/PNG/ICO/WOFF2/JSON MIME types, and classpath reads confined to `spa/`.

- [ ] **Step 2: Run the catalog test before implementation**

Run: `./gradlew test --tests 'com.mindtable.bitbuckethelper.adapter.inbound.http.SpaAssetsTest'`

Expected: FAIL because `SpaAssets` does not exist.

- [ ] **Step 3: Implement the immutable classpath catalog**

```kotlin
internal fun interface SpaResourceReader {
    fun read(resourceName: String): ByteArray?
}

internal data class SpaAsset(
    val bytes: ByteArray,
    val contentType: ContentType,
)

internal class SpaAssets(
    private val reader: SpaResourceReader,
) {
    fun requireEntryPoint() {
        val entry = reader.read("spa/index.html")
        check(entry != null && entry.isNotEmpty()) {
            "Packaged SPA assets are unavailable"
        }
    }

    fun find(relativePath: String): SpaAsset? {
        val normalized = normalizeSpaRelativePath(relativePath) ?: return null
        val contentType = spaContentType(normalized) ?: return null
        val bytes = reader.read("spa/$normalized") ?: return null
        return SpaAsset(bytes, contentType)
    }

    companion object {
        fun classpath(): SpaAssets = SpaAssets(SpaResourceReader { name ->
            SpaAssets::class.java.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
        })
    }
}
```

Normalization accepts exactly `index.html` and `assets/` followed by non-empty `[A-Za-z0-9._-]+` segments; rejects `.`/`..`, slash/backslash inside a segment, control characters, and every other top-level path.

- [ ] **Step 4: Write a failing bootstrap-order test**

In `ServiceLoggingBootstrapTest.kt`, inject `validateSpaAssets = { throw IllegalStateException("private-spa-path") }`, count `createRuntime` calls, and assert:

```kotlin
assertEquals(0, runtimeCreations)
val event = events.single { it is BackendLogEvent.ServiceStartFailed } as BackendLogEvent.ServiceStartFailed
assertEquals("spa_assets", event.component)
assertFalse(event.toString().contains("private-spa-path"))
```

Run: `./gradlew test --tests 'com.mindtable.bitbuckethelper.bootstrap.ServiceLoggingBootstrapTest'`

Expected: FAIL because `ServiceBootstrapSeams` has no asset validator and runtime construction proceeds.

- [ ] **Step 5: Gate runtime construction on packaged assets**

Add `validateSpaAssets: () -> Unit` to `ServiceBootstrapSeams`, defaulting in production to `SpaAssets.classpath()::requireEntryPoint`. After service configuration succeeds and before `createRuntime`, call it in a dedicated `try/catch`; record exactly one `BackendLogEvent.ServiceStartFailed("spa_assets", failure)` and rethrow. Do not initialize persistence, gateway, scheduler, or HTTP when validation fails.

- [ ] **Step 6: Run catalog and bootstrap tests**

Run: `./gradlew test --tests 'com.mindtable.bitbuckethelper.adapter.inbound.http.SpaAssetsTest' --tests 'com.mindtable.bitbuckethelper.bootstrap.ServiceLoggingBootstrapTest'`

Expected: PASS; asset failure is sanitized, terminal, and precedes runtime construction.

- [ ] **Step 7: Commit the startup boundary**

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaAssets.kt \
  src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaAssetsTest.kt \
  src/main/kotlin/com/mindtable/bitbuckethelper/bootstrap/Commands.kt \
  src/test/kotlin/com/mindtable/bitbuckethelper/bootstrap/ServiceLoggingBootstrapTest.kt
git commit -m "feat: validate packaged SPA before service startup"
```

### Task 6: Serve the SPA Securely with Typed Terminal Observations

**Files:**
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaObservability.kt`
- Create: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaObservabilityTest.kt`
- Create: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaRoutes.kt`
- Create: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaRoutesTest.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/RequestIds.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BrowserSecurity.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BrowserSecurityTest.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Errors.kt`
- Modify: `src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/LocalApiServers.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/LocalTransportParityTest.kt`
- Modify: `src/test/kotlin/com/mindtable/bitbuckethelper/observability/ServiceLoggingTest.kt`

**Interfaces:**
- Consumes: Task 5 `SpaAssets`; existing `BackendLogEvent.HttpRequestCompleted/Rejected/Failed`, `BackendEventRecorder`, `MonotonicTimeSource`, and `BrowserSecurity`.
- Produces: `Application.installSpa(assets, recorder, timeSource)`, `SpaOperation`, `observeSpaRequestError`, `observeSpaFailure`, and shared `localRequestId()`.

- [ ] **Step 1: Write failing route/security tests**

Build a `testApplication` with browser security, `installApiV1`, and the proposed `installSpa`. Assert:

```kotlin
val shell = client.get("/") { header(HttpHeaders.Host, TEST_AUTHORITY) }
assertEquals(HttpStatusCode.OK, shell.status)
assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), shell.contentType())
assertEquals("no-store", shell.headers[HttpHeaders.CacheControl])
assertEquals("nosniff", shell.headers["X-Content-Type-Options"])
assertEquals("no-referrer", shell.headers["Referrer-Policy"])
assertTrue(shell.headers.getValue("Content-Security-Policy").contains("connect-src 'self'"))
assertTrue(shell.headers.getValue("X-Request-ID").startsWith("req_"))
assertFalse(shell.headers.contains(HttpHeaders.AccessControlAllowOrigin))
```

Add exact tests for:

- `/index.html` and hashed JS/CSS `GET` responses;
- `HEAD` content type/status with an empty response body;
- `public, max-age=31536000, immutable` for `/assets/**`;
- wrong Host and wrong optional Origin returning fixed `403` before lookup;
- `POST /` returning fixed `405`;
- missing/traversal/encoded-traversal paths returning fixed `404` without exposing the path;
- loader failure returning fixed `500` without the exception message;
- unknown `/api/v1/private-sentinel` retaining the JSON API error envelope;
- browser root available while Unix-socket root remains non-SPA `404`.

- [ ] **Step 2: Write failing observability tests**

In `SpaObservabilityTest`, use deterministic nanoseconds and assert one event for each terminal category:

```kotlin
val completed = events.single() as BackendLogEvent.HttpRequestCompleted
assertEquals("spa_shell", completed.operation)
assertEquals("spa_served", completed.outcome)
assertEquals("browser", completed.transport)
assertEquals("GET", completed.method)
assertEquals(200, completed.status)
assertEquals(false, completed.mutation)
assertFalse(completed.toString().contains("private-path-sentinel"))
```

Rejected events use operations `spa_shell`, `spa_asset`, or `spa_unknown` and fixed error codes `FORBIDDEN`, `ROUTE_NOT_FOUND`, or `METHOD_NOT_ALLOWED`. Failed events contain only sanitized exception diagnostics. Recorder failure must not change the static response. A custom method sentinel must be recorded only as `OTHER` and must be absent from the event text and both logging destinations.

- [ ] **Step 3: Run the new backend tests before implementation**

Run: `./gradlew test --tests 'com.mindtable.bitbuckethelper.adapter.inbound.http.SpaRoutesTest' --tests 'com.mindtable.bitbuckethelper.adapter.inbound.http.SpaObservabilityTest'`

Expected: FAIL because SPA routing and observation modules do not exist.

- [ ] **Step 4: Generalize request IDs and static observations**

Refactor `RequestIds.kt` so one private attribute backs both compatibility API names and new local names:

```kotlin
internal fun ApplicationCall.assignLocalRequestId() { attributes.put(LocalRequestIdAttribute, newRequestId()) }
internal fun ApplicationCall.localRequestId(): String = attributes[LocalRequestIdAttribute]
internal fun ApplicationCall.assignApiV1RequestId() = assignLocalRequestId()
fun ApplicationCall.apiV1RequestId(): String = localRequestId()
```

`installSpaObservability` begins observations only for non-API calls in the browser application, appends `X-Request-ID`, infers the fixed operation without retaining the path, and finishes on Ktor's `ResponseSent` hook. Reuse existing typed HTTP events with `mutation = false`; catch recorder failures through `reportBackendEventRecorderFailure()`.

Map request methods to the fixed set `GET`, `HEAD`, `POST`, `PUT`, `DELETE`,
`PATCH`, and `OPTIONS`; record every other token as `OTHER`. Never copy an
arbitrary method string into an event.

- [ ] **Step 5: Extend browser authority checks without changing API CSRF behavior**

In `BrowserSecurity.authorize`:

1. Require exact Host for every browser request.
2. For non-API `GET`/`HEAD`, accept absent Origin or require the exact loopback Origin, then return.
3. For other non-API methods, apply no CSRF rule; routing returns `405`.
4. For API calls, retain the existing read/mutation/content-type/CSRF rules byte-for-byte.

Use a distinct `ForbiddenBrowserRequestException` for non-API authority/origin rejection. Update `ApiV1Errors.kt` to translate it to fixed empty `403`, and mark the SPA observation `FORBIDDEN`. In existing `404`/`405` handlers, mark a SPA observation only when the SPA observation attribute is present; Unix non-API requests remain ordinary empty transport errors.

- [ ] **Step 6: Implement static routes and hardened headers**

Export:

```kotlin
internal fun Application.installSpa(
    assets: SpaAssets,
    backendEventRecorder: BackendEventRecorder,
    monotonicTimeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
)
```

Install observation and a response-policy interceptor, then explicit `GET`/`HEAD` routes for `/`, `/index.html`, and `/assets/{path...}`. Use `SpaAssets.find`, never a filesystem API. Catch `CancellationException` by rethrowing it; on another `Throwable`, call `observeSpaFailure` and return fixed `500`.

Use one response helper so GET and HEAD cannot drift:

```kotlin
private suspend fun ApplicationCall.respondSpaAsset(asset: SpaAsset, headOnly: Boolean) {
    if (headOnly) {
        response.contentType(asset.contentType)
        response.header(HttpHeaders.ContentLength, asset.bytes.size)
        respond(HttpStatusCode.OK)
    } else {
        respondBytes(asset.bytes, asset.contentType)
    }
}
```

When lookup returns `null`, call `observeSpaRequestError("ROUTE_NOT_FOUND")`
and return an empty `404`. Do not reflect the path or unsupported extension.

Use these exact headers for every observed SPA response:

```text
Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Resource-Policy: same-origin
X-Frame-Options: DENY
Permissions-Policy: camera=(), geolocation=(), microphone=()
```

Shell/unknown/error responses use `Cache-Control: no-store`; `/assets/**` successes use `public, max-age=31536000, immutable`.

- [ ] **Step 7: Install SPA only in the browser server**

In the browser `embeddedServer` application in `LocalApiServers.kt`, keep `installBrowserSecurity` and `installBusinessApi`, then call:

```kotlin
installSpa(
    assets = SpaAssets.classpath(),
    backendEventRecorder = backendEventRecorder,
    monotonicTimeSource = monotonicTimeSource,
)
```

Do not call it in the Unix application. Add `spaAssets: SpaAssets = SpaAssets.classpath()` to the internal server-start seam so route/parity tests inject a deterministic catalog while every public production overload uses classpath assets.

- [ ] **Step 8: Prove both logging destinations**

Add a `ServiceLoggingTest` case that records completed/rejected/failed SPA HTTP events and asserts terminal and JSONL records contain `operation=spa_asset`, `request_id`, status, outcome/error code, and duration. Assert neither destination contains sentinels representing a path, query, Origin, asset name, or exception message.

- [ ] **Step 9: Run the complete changed backend boundary**

Run: `./gradlew test --tests 'com.mindtable.bitbuckethelper.adapter.inbound.http.Spa*' --tests 'com.mindtable.bitbuckethelper.adapter.inbound.http.BrowserSecurityTest' --tests 'com.mindtable.bitbuckethelper.adapter.inbound.http.LocalTransportParityTest' --tests 'com.mindtable.bitbuckethelper.observability.ServiceLoggingTest'`

Expected: PASS; every static call has exactly one terminal event, API envelopes are unchanged, and Unix serves no SPA.

- [ ] **Step 10: Commit the serving boundary**

```bash
git add src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaObservability.kt \
  src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaObservabilityTest.kt \
  src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaRoutes.kt \
  src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/SpaRoutesTest.kt \
  src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/RequestIds.kt \
  src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BrowserSecurity.kt \
  src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/BrowserSecurityTest.kt \
  src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/ApiV1Errors.kt \
  src/main/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/LocalApiServers.kt \
  src/test/kotlin/com/mindtable/bitbuckethelper/adapter/inbound/http/LocalTransportParityTest.kt \
  src/test/kotlin/com/mindtable/bitbuckethelper/observability/ServiceLoggingTest.kt
git commit -m "feat: serve embedded SPA from secured backend"
```

### Task 7: Update User Documentation and Run the Complete Assembly Gates

**Files:**
- Modify: `README.md`
- Modify: `docs/installation-and-web-ui.md`
- Modify: `docs/operations/manual-service-run.md`
- Modify: `web/README.md`
- Modify: `docs/project-backlog.md`

**Interfaces:**
- Consumes: the production commands and URL delivered by Tasks 3–6.
- Produces: one supported operator flow and a manual live acceptance checklist; no new runtime code.

- [ ] **Step 1: Update the root and installation guide**

Replace every statement that the Vue UI is disconnected or requires a Vite runtime with this supported flow:

```text
./gradlew clean check verifyApiV1Generated
./gradlew buildFatJar
java -jar build/libs/bitbucket-helper-0.1.0-all.jar service run
open http://127.0.0.1:8080/
```

Keep secure credential/provider/path preparation and CLI configuration commands. State that Node/npm are required only to build from source and that the running JAR serves both UI and API.

- [ ] **Step 2: Add the manual assembled-system checklist**

In `docs/installation-and-web-ui.md` and the manual runbook, document:

1. start only the Java service;
2. configure workspace with `workspace configure`;
3. add a repository with `repository add <slug>`;
4. run or await `refresh`;
5. open the configured backend root;
6. verify workspace/repository/PR state, drawer detail, live exact-version content, and acknowledgment;
7. confirm no Node/Vite process is running and browser API calls are same-origin.

Explicitly label this as a user-executed manual acceptance checklist. Do not claim it ran automatically.

- [ ] **Step 3: Update frontend developer docs and backlog**

`web/README.md` must distinguish:

- `npm run dev` — fixture journeys;
- `npm run dev:backend` — development-only proxy to a backend on `127.0.0.1:8080`;
- `npm run build` — production real adapter embedded by Gradle;
- normal product usage — open the Kotlin backend URL, never run npm.

Mark the backlog items for real SPA data access and fat-JAR static packaging complete. Leave background installation, configuration UI, and other deferred items open.

- [ ] **Step 4: Verify documentation and forbidden claims**

Run: `rg -n "does not call the Kotlin service|fixture-backed development application|npm run dev.*then open" README.md docs/installation-and-web-ui.md web/README.md`

Expected: fixture-only language appears only in the explicitly labeled development section, not production/operator instructions.

Run: `git diff --check`

Expected: no whitespace errors.

- [ ] **Step 5: Run the full frontend gates**

Run: `npm run check`

Expected: formatting, lint, type-check, unit tests, and production build all PASS.

Run: `npm run test:e2e`

Expected: existing fixture Playwright suite PASS; no packaged-backend suite exists.

- [ ] **Step 6: Run the full backend and packaging gates**

Run: `./gradlew --offline clean check verifyApiV1Generated`

Expected: `BUILD SUCCESSFUL`; Kotlin tests, architecture checks, OpenAPI validation, generated drift, frontend production build, SPA route/logging tests, and fat-JAR archive test pass without network access when caches are populated.

Run: `./gradlew --offline buildFatJar`

Expected: `BUILD SUCCESSFUL` and `build/libs/bitbucket-helper-0.1.0-all.jar` exists.

Run: `jar tf build/libs/bitbucket-helper-0.1.0-all.jar`

Expected: output includes `spa/index.html`, at least one hashed `.js`, and at least one hashed `.css`; it contains no SPA `.map`, `.ts`, `.vue`, `.env`, or package manifest.

Run: `rg -n "HttpStatusCode\.(Accepted|Conflict)|status\(202\)|status\(409\)" src/main/kotlin`

Expected: no new refresh/acknowledgment business-status encoding.

- [ ] **Step 7: Commit documentation after all automated gates pass**

```bash
git add README.md docs/installation-and-web-ui.md \
  docs/operations/manual-service-run.md web/README.md docs/project-backlog.md
git commit -m "docs: document embedded SPA workflow"
```

- [ ] **Step 8: Hand off manual acceptance without claiming completion**

Report the automated command results and give the user the documented manual checklist. State explicitly that live Bitbucket end-to-end acceptance remains pending until the user performs it.

---

## Final Review Checklist

- Every task ends in a focused commit and passes its own red-green cycle.
- The production bundle contains the real adapter and no fixture selector/body data.
- The fat JAR owns the entire SPA asset closure.
- The Java process is the only production HTTP/static runtime.
- API V1 envelope/status behavior is unchanged.
- Static Host/Origin, CSP, cache, CORS, route isolation, and traversal tests pass.
- Every static request produces one privacy-safe typed terminal event.
- Startup asset failure is logged once as `spa_assets` before runtime construction.
- CSRF tokens remain memory-only and mutation replay is limited to one proven service restart.
- Existing fixture tests remain available; no new packaged Playwright suite exists.
- Documentation distinguishes automated gates from the user's manual live acceptance.
