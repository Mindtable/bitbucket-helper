# Dashboard and Detail Drawer Real SPA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Evolve the existing fixture-backed Vue dashboard into the approved product-first dashboard with revision-aware refresh, collapsible needs attention, repository/PR hierarchy, a detail drawer, exact-version live content, and correct acknowledgment outcomes without connecting to Kotlin.

**Architecture:** Keep the existing feature-oriented `web/src/features/dashboard` boundary and evolve `DashboardSource` into a browser-facing port for dashboard, refresh, detail, content, and acknowledgment operations. `useDashboard` owns the one polling loop and complete-snapshot reconciliation; `usePullRequestDrawer` owns selection, stale-response protection, ephemeral content, and exact-version commands. Typed props/events keep presentation components free of fixtures, transport status, and request orchestration.

**Tech Stack:** Vue 3.5.40 Composition API with `<script setup>`, TypeScript 6.0.3, Vite 8.1.5, Vitest 4.1.10, Vue Test Utils 2.4.11, Playwright 1.61.1, and plain CSS with no new runtime dependency.

## Global Constraints

- Follow `docs/superpowers/specs/2026-08-15-dashboard-drawer-real-spa-design.md`; use `docs/superpowers/specs/2026-08-15-spa-kotlin-api-contract-design.md` as the authority for API semantics.
- Work only in `web/` plus the `web/README.md` update named by this plan.
- Keep the pre-existing unstaged `docs/project-backlog.md` edit and untracked `source/` prototype untouched and unstaged.
- Use the existing exact npm dependency versions and committed `package-lock.json`; add no package.
- Use Vue Composition API, `<script setup>`, explicit dependency injection, and plain CSS.
- Do not add Vue Router, Pinia, a component library, a CSS framework, MSW, or handwritten HTTP/OpenAPI DTOs.
- Do not call Kotlin, Bitbucket, or another network service. Runtime and browser tests use the in-process `DashboardSource` fixture.
- Do not add the mock's journey selector, reset control, response-step control, or request trace to product chrome.
- Treat expected business outcomes as typed results corresponding to HTTP `200`; never encode business state in transport status.
- Keep dashboard revisions and activity versions opaque. Compare for equality only; never parse, increment, sort, or infer order.
- Keep raw activity content out of dashboard fixtures, errors, logs, browser storage, and diagnostics. Render loaded content with Vue text interpolation, never `v-html`.
- Keep dashboard, drawer, disclosure, raw content, and future CSRF state in memory only; use no cookies, `localStorage`, or `sessionStorage`.
- Keep one active dashboard polling loop. Late dashboard/detail/content results cannot overwrite a newer UI context.
- Needs attention starts expanded per page load, preserves its state across ordinary renders, and keeps `N open` visible while collapsed.
- Repository rails are decorative around native lists; do not add ARIA tree roles or custom tree keyboard behavior.
- PR #92 renders `Build failed`, optional `2 failed checks`, disabled `View build`, and independent `5 of 7` readiness. The disabled control has no side effect.
- `Open in Bitbucket` uses only fixture/backend-supplied URLs, opens a new tab, and includes `rel="noopener noreferrer"`.
- Acknowledgment is enabled only after exact-version content loads. Stale acknowledgment performs no removal, decrement, or success mutation.
- Preserve useful last-known-good data during background work, partial refresh, and drawer-local failures.
- Meet light/dark and no-horizontal-overflow regression targets at `1024px`, `736px`, and `360px`.
- Begin every product change with a focused failing test, verify the failure reason, implement the smallest passing change, run the focused test, then run the affected suite before committing.

---

## File map

### Browser-facing models and ports

- Modify `web/src/features/dashboard/dashboard.models.ts`: complete dashboard, repository, PR, action-item, detail, polling, and problem view models.
- Modify `web/src/features/dashboard/dashboardSource.ts`: typed browser-facing operations and result unions; still not a wire contract.
- Create `web/src/features/dashboard/pollScheduler.ts`: injectable one-shot scheduler used by the polling loop.
- Modify `web/src/features/dashboard/useDashboard.ts`: initial load, non-blocking refresh, single revision-aware polling loop, snapshot reconciliation, and local acknowledgment reconciliation.
- Modify `web/src/features/dashboard/useDashboard.spec.ts`: orchestration, scheduling, expected results, failures, concurrency, and disposal.

### Fixture and test support

- Create `web/src/features/dashboard/fixtures/fixtureDashboardData.ts`: immutable metadata snapshots, PR details, and a separate exact-version raw-content catalog.
- Modify `web/src/features/dashboard/fixtures/fixtureDashboardSource.ts`: deterministic stateful source and six fixture journeys.
- Create `web/src/features/dashboard/fixtures/fixtureDashboardSource.spec.ts`: isolated state-machine sequences for all fixture journeys.
- Create `web/src/features/dashboard/testing/dashboardTestData.ts`: small test factories with valid defaults.
- Create `web/src/features/dashboard/testing/dashboardTestSource.ts`: strict source stub and deferred helper for unit/component tests.
- Create `web/src/features/dashboard/dashboardSource.spec.ts`: port-level fixture relationship and raw-content-boundary tests.

### Product composition and components

- Modify `web/src/features/dashboard/DashboardView.vue`: page states, product composition, dashboard/drawer orchestration, and reconciliation wiring.
- Modify `web/src/features/dashboard/DashboardView.spec.ts`: page-level expected states, integration events, and safe technical failure.
- Create `web/src/features/dashboard/components/ProductHeader.vue`: workspace, overall sync, revision, and refresh intent.
- Create `web/src/features/dashboard/components/NeedsAttention.vue`: page-lifetime disclosure and complete inbox.
- Create `web/src/features/dashboard/components/AttentionItem.vue`: exact action selection event.
- Modify `web/src/features/dashboard/components/RepositoryGroup.vue`: semantic repository parent and native PR child list.
- Modify `web/src/features/dashboard/components/PullRequestCard.vue`: compact health and explicit product/external actions.
- Create `web/src/features/dashboard/components/BuildStatus.vue`: shared card/drawer build rendering.
- Create `web/src/features/dashboard/components/PullRequestDrawer.vue`: non-modal drawer composition and focus entry.
- Create `web/src/features/dashboard/components/ReadinessSummary.vue`: shared readiness/build/count detail.
- Create `web/src/features/dashboard/components/ActivityOutcome.vue`: live-content and acknowledgment outcomes/actions.
- Create focused component specs beside each new or materially changed component.
- Create `web/src/features/dashboard/usePullRequestDrawer.ts`: selection, detail/content/acknowledgment state machine, stale-response guards, and focus return.
- Create `web/src/features/dashboard/usePullRequestDrawer.spec.ts`: drawer state transitions and races.

### Runtime, styling, browser acceptance, and docs

- Modify `web/src/main.ts`: inject the default fixture source and a development-only query-selected fixture journey.
- Modify `web/src/assets/main.css`: approved light/dark tokens, feed hierarchy, drawer layout, responsive behavior, status treatments, and focus.
- Replace `web/e2e/dashboard.spec.ts`: product smoke, all six journeys, responsive/theme checks, no network data calls, and no console errors.
- Modify `web/README.md`: fixture-journey development URLs and explicit Kotlin/OpenAPI integration boundary.

---

### Task 1: Expand the browser-facing model and fixture port

**Files:**

- Modify: `web/src/features/dashboard/dashboard.models.ts:1-42`
- Modify: `web/src/features/dashboard/dashboardSource.ts:1-9`
- Create: `web/src/features/dashboard/fixtures/fixtureDashboardData.ts`
- Modify: `web/src/features/dashboard/fixtures/fixtureDashboardSource.ts:1-58`
- Create: `web/src/features/dashboard/testing/dashboardTestData.ts`
- Create: `web/src/features/dashboard/testing/dashboardTestSource.ts`
- Create: `web/src/features/dashboard/dashboardSource.spec.ts`
- Modify: `web/src/features/dashboard/DashboardView.spec.ts:8-208`
- Modify: `web/src/features/dashboard/useDashboard.spec.ts:8-99`

**Interfaces:**

- Consumes: current `DashboardViewModel`, `DashboardSource`, and fixture dependency injection.
- Produces: the exact view-model and source signatures below. Every later task imports these names rather than redeclaring them.

```ts
export interface DashboardSource {
  loadDashboard(afterRevision?: string): Promise<DashboardSourceResult>;
  startRefresh(): Promise<RefreshSourceResult>;
  loadPullRequest(
    pullRequestId: string,
  ): Promise<PullRequestDetailSourceResult>;
  loadActionContent(
    actionItemId: string,
    activityVersion: string,
  ): Promise<ActionContentSourceResult>;
  acknowledgeActionItem(
    actionItemId: string,
    activityVersion: string,
  ): Promise<AcknowledgmentSourceResult>;
  startRepositoryRefresh(
    repositoryId: string,
    observedActivityVersion: string,
  ): Promise<RefreshSourceResult>;
}
```

- [ ] **Step 1: Write the failing fixture-boundary test**

Create `web/src/features/dashboard/dashboardSource.spec.ts`:

```ts
import { describe, expect, it } from "vitest";

import { fixtureDashboardSource } from "./fixtures/fixtureDashboardSource";

describe("fixtureDashboardSource", () => {
  it("returns the approved repository hierarchy without embedding raw content", async () => {
    const result = await fixtureDashboardSource.loadDashboard();
    expect(result.type).toBe("snapshotChanged");
    if (result.type !== "snapshotChanged")
      throw new Error("expected snapshotChanged");

    expect(
      result.dashboard.repositoryGroups.map((repository) => ({
        repositoryId: repository.repositoryId,
        pullRequestIds: repository.pullRequests.map(
          (pullRequest) => pullRequest.pullRequestId,
        ),
      })),
    ).toEqual([
      { repositoryId: "repo_payments", pullRequestIds: ["pr_184", "pr_179"] },
      { repositoryId: "repo_store", pullRequestIds: ["pr_92"] },
    ]);
    expect(result.dashboard.inbox.map((item) => item.actionItemId)).toEqual([
      "action_501",
      "action_502",
    ]);
    expect(JSON.stringify(result.dashboard)).not.toContain(
      "Could we cap the retry window",
    );
  });
});
```

- [ ] **Step 2: Run the focused test and verify the expected failure**

```bash
cd web
npm run test:unit -- src/features/dashboard/dashboardSource.spec.ts
```

Expected: FAIL because `DashboardSource` has `load()`, not `loadDashboard()`, and the current model has no revision or inbox.

- [ ] **Step 3: Define the complete browser-facing view models**

Replace `dashboard.models.ts` with definitions using these exact top-level shapes:

```ts
export type PollingState =
  { type: "idle" } | { type: "active"; afterMilliseconds: number };

export interface DashboardViewModel {
  dashboardRevision: string;
  generatedAt: string;
  workspaceDisplayName: string;
  polling: PollingState;
  repositoryGroups: readonly RepositoryGroupModel[];
  inbox: readonly ActionItemSummary[];
}

export interface RepositoryGroupModel {
  repositoryId: string;
  slug: string;
  displayName: string;
  webUrl: string;
  repositoryRevision: string;
  synchronization: SynchronizationState;
  freshness: FreshnessState;
  problem: SynchronizationProblemState;
  pullRequests: readonly PullRequestSummary[];
}

export interface PullRequestSummary {
  pullRequestId: string;
  repositoryId: string;
  displayNumber: number;
  title: string;
  authorDisplayName: string;
  updatedAt: string;
  webUrl: string;
  readiness: ReadinessState;
  buildState: BuildState;
  actionableItemCount: number;
  acknowledgedItemCount: number;
  actionItems: readonly ActionItemSummary[];
}

export interface ActionItemSummary {
  actionItemId: string;
  activityVersion: string;
  repositoryId: string;
  pullRequestId: string;
  kind: "comment" | "changesRequested";
  actorDisplayName: string;
  occurredAt: string;
  acknowledgmentState: "actionable" | "acknowledged";
  webUrl: string;
}

export interface PullRequestDetailModel {
  repositoryDisplayName: string;
  pullRequest: PullRequestSummary;
  readinessChecks: readonly ReadinessCheckModel[];
  actionItems: readonly ActionItemSummary[];
}

export interface ReadinessCheckModel {
  checkId: string;
  label: string;
  state: "passed" | "pending" | "failed" | "unavailable";
}

export type SynchronizationState =
  { type: "idle" } | { type: "queued" } | { type: "running" };

export type FreshnessState =
  | { type: "neverSynchronized" }
  | { type: "fresh"; ageDescription: string }
  | { type: "stale"; ageDescription: string; staleSince: string };

export type SynchronizationProblemState =
  | { type: "none" }
  | {
      type: "present";
      message: string;
      retryable: boolean;
      retryAfterDescription: string | null;
    };

export type ReadinessState =
  | { type: "available"; passed: number; total: 7 }
  | { type: "unavailable"; reason: string };

export type BuildState =
  | { type: "successful" }
  | { type: "failed"; failedCheckCount?: number }
  | { type: "inProgress" }
  | { type: "unavailable"; reason: string };
```

- [ ] **Step 4: Define every typed source result**

Replace `dashboardSource.ts` with the `DashboardSource` interface above plus:

```ts
export type DashboardSourceResult =
  | { type: "snapshotChanged"; dashboard: DashboardViewModel }
  | {
      type: "snapshotUnchanged";
      dashboardRevision: string;
      serverTime: string;
      polling: PollingState;
    }
  | { type: "workspaceNotConfigured"; setupCommand: string };

export type RefreshSourceResult =
  | { type: "refreshRunRegistered"; refreshRunId: string }
  | { type: "noRepositoriesConfigured"; setupCommand: string }
  | { type: "workspaceNotConfigured"; setupCommand: string };

export type PullRequestDetailSourceResult =
  | { type: "pullRequestAvailable"; detail: PullRequestDetailModel }
  | { type: "pullRequestNotFound" };

export type ActionContentSourceResult =
  | {
      type: "contentAvailable";
      actionItemId: string;
      activityVersion: string;
      markdownSource: string;
    }
  | { type: "contentUnavailable"; reason: string; retryable: boolean }
  | {
      type: "newerActivityObserved";
      repositoryId: string;
      requestedActivityVersion: string;
      currentActivityVersion: string;
    }
  | {
      type: "staleActivityVersion";
      requestedActivityVersion: string;
      currentActivityVersion: string;
    }
  | { type: "actionItemNotFound" };

export type AcknowledgmentSourceResult =
  | { type: "acknowledged"; actionItemId: string; activityVersion: string }
  | {
      type: "alreadyAcknowledged";
      actionItemId: string;
      activityVersion: string;
    }
  | {
      type: "staleActivityVersion";
      actionItemId: string;
      requestedActivityVersion: string;
      currentActivityVersion: string;
      hasNewerActivity: true;
    }
  | { type: "acknowledgmentRejected"; reason: string }
  | { type: "actionItemNotFound" };
```

Import only browser-facing model types. Do not add status codes, envelopes, request IDs, or generated DTO names.

- [ ] **Step 5: Create immutable fixture data with a separate raw-content catalog**

Create `fixtureDashboardData.ts` exporting immutable `action501`, `action502`, `paymentsRepository`, `storeRepository`, `baseDashboard`, and `pullRequestDetailsById`. `baseDashboard` uses revision `dash_18`, generated time `2026-08-15T10:00:00Z`, workspace `Mindtable`, and idle polling. Use these exact metadata rows (timestamps are ISO strings in the model; UI formats them):

| Object       | Required fixture values                                                                                                                                                                                      |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Payments API | `repo_payments`, slug `payments-api`, revision `repo_11`, running, fresh `1 minute ago`, URL `https://bitbucket.org/mindtable/payments-api`                                                                  |
| PR #184      | `pr_184`, `Add retry budget`, author `Mira`, updated `2026-08-15T09:48:00Z`, URL ending `/pull-requests/184`, successful build, readiness 6/7, 1 actionable, 0 acknowledged, action 501                      |
| PR #179      | `pr_179`, `Remove legacy token`, author `Noah`, updated `2026-08-15T08:00:00Z`, URL ending `/pull-requests/179`, successful build, readiness 7/7, 0 actionable, 1 acknowledged                               |
| Web Store    | `repo_store`, slug `web-store`, revision `repo_7`, queued, fresh `4 minutes ago`, URL `https://bitbucket.org/mindtable/web-store`                                                                            |
| PR #92       | `pr_92`, `Harden CSRF validation`, author `Iris`, updated `2026-08-15T09:40:00Z`, URL ending `/pull-requests/92`, failed build with 2 failed checks, readiness 5/7, 1 actionable, 0 acknowledged, action 502 |
| Action 501   | `action_501`, `av_42`, comment, actor `Alex Chen`, occurred `2026-08-15T09:57:00Z`, actionable, Payments API/PR #184 URL with `#comment-501`                                                                 |
| Action 502   | `action_502`, `av_18`, changes requested, actor `Sam Rivera`, occurred `2026-08-15T09:52:00Z`, actionable, Web Store/PR #92 URL with `#changes-requested-502`                                                |

Each detail has seven ordered checks: `Contract`, `Unit tests`, `Integration tests`, `Build`, `Security scan`, `Review approvals`, and `Unresolved tasks`. PR #184 has the first six passed and `Unresolved tasks` pending; PR #179 has all passed; PR #92 has `Build` and `Security scan` failed and the other five passed.

PR #92 uses:

```ts
buildState: { type: 'failed', failedCheckCount: 2 },
readiness: { type: 'available', passed: 5, total: 7 },
actionableItemCount: 1,
acknowledgedItemCount: 0,
```

Export raw bodies only from a separate catalog:

```ts
export const liveContentByActionVersion: Readonly<Record<string, string>> = {
  "action_501:av_42":
    "Could we cap the retry window and add a metric for exhausted attempts?",
  "action_501:av_43":
    "Please cap the retry window at 30 seconds and emit a metric for exhausted attempts.",
};
```

Dashboard objects must not reference this catalog or include `markdownSource`.

- [ ] **Step 6: Implement the initial healthy fixture source**

Replace `fixtureDashboardSource.ts` with a factory that clones metadata per instance and implements every `DashboardSource` method. The first or revision-mismatched `loadDashboard()` returns `snapshotChanged`; a matching `afterRevision` returns `snapshotUnchanged` with idle polling. Detail comes from `pullRequestDetailsById`; content comes from `liveContentByActionVersion`; acknowledgment returns `acknowledged`; both refresh methods return `refreshRunRegistered`.

Use this exact export boundary:

```ts
export function createFixtureDashboardSource(): DashboardSource {
  const dashboard = structuredClone(baseDashboard);
  return {
    loadDashboard: async (afterRevision) =>
      afterRevision === dashboard.dashboardRevision
        ? {
            type: "snapshotUnchanged",
            dashboardRevision: dashboard.dashboardRevision,
            serverTime: dashboard.generatedAt,
            polling: { type: "idle" },
          }
        : { type: "snapshotChanged", dashboard: structuredClone(dashboard) },
    startRefresh: async () => ({
      type: "refreshRunRegistered",
      refreshRunId: "refresh_1",
    }),
    loadPullRequest: async (pullRequestId) => {
      const detail = pullRequestDetailsById[pullRequestId];
      return detail
        ? { type: "pullRequestAvailable", detail: structuredClone(detail) }
        : { type: "pullRequestNotFound" };
    },
    loadActionContent: async (actionItemId, activityVersion) => {
      const markdownSource =
        liveContentByActionVersion[`${actionItemId}:${activityVersion}`];
      return markdownSource
        ? {
            type: "contentAvailable",
            actionItemId,
            activityVersion,
            markdownSource,
          }
        : {
            type: "contentUnavailable",
            reason: "Content unavailable",
            retryable: true,
          };
    },
    acknowledgeActionItem: async (actionItemId, activityVersion) => ({
      type: "acknowledged",
      actionItemId,
      activityVersion,
    }),
    startRepositoryRefresh: async () => ({
      type: "refreshRunRegistered",
      refreshRunId: "refresh_repository_1",
    }),
  };
}

export const fixtureDashboardSource = createFixtureDashboardSource();
```

- [ ] **Step 7: Add strict test factories and repair consumers**

Create `dashboardTestSource.ts` with `deferred<T>()` and `createDashboardSourceStub(overrides)`; every unoverridden method rejects with `new Error('Unexpected DashboardSource call: <method>')`. Create `dashboardTestData.ts` with `makeDashboard`, `makeRepository`, `makePullRequest`, `makeActionItem`, and `makePullRequestDetail` factories returning valid objects with shallow overrides. Add this DOM helper for focus tests:

```ts
export function button(): HTMLButtonElement {
  return document.createElement("button");
}
```

Give `makePullRequestDetail` this explicit convenience signature so later race tests compile:

```ts
export function makePullRequestDetail(
  options: { pullRequestId?: string } = {},
): PullRequestDetailModel;
```

Update existing component/composable test data for the new required fields and change `load()` stubs to `loadDashboard()`. Keep the existing grouping, safe-error, external-link, unavailable-state, and retry assertions.

- [ ] **Step 8: Run focused and affected tests**

```bash
cd web
npm run test:unit -- src/features/dashboard/dashboardSource.spec.ts src/features/dashboard/useDashboard.spec.ts src/features/dashboard/DashboardView.spec.ts
npm run type-check
```

Expected: all selected tests pass and TypeScript reports zero errors.

- [ ] **Step 9: Commit the model/fixture boundary**

```bash
git add web/src/features/dashboard
git commit -m "refactor: expand dashboard fixture boundary"
```

Confirm `git status --short` still shows the protected backlog/source entries and no staged file outside `web/src/features/dashboard`.

---

### Task 2: Add revision-aware dashboard refresh and polling

**Files:**

- Create: `web/src/features/dashboard/pollScheduler.ts`
- Modify: `web/src/features/dashboard/useDashboard.ts:1-38`
- Modify: `web/src/features/dashboard/useDashboard.spec.ts`
- Modify: `web/src/features/dashboard/DashboardView.vue:1-57`
- Modify: `web/src/features/dashboard/DashboardView.spec.ts`

**Interfaces:**

- Consumes: source/result types from Task 1.
- Produces: `useDashboard(source, scheduler?)` returning readonly `state`, `reload`, `refresh`, `pollNow`, and `dispose`. Task 7 adds `applyAcknowledgment` without changing existing names.

```ts
export interface PollScheduler {
  schedule(afterMilliseconds: number, task: () => void): () => void;
}

export type DashboardRefreshState =
  | { type: "idle" }
  | { type: "registering" }
  | { type: "active" }
  | {
      type: "failed";
      message: "Refresh unavailable";
      setupCommand?: string;
    };

export type DashboardUiState =
  | { type: "loading" }
  | {
      type: "ready";
      dashboard: DashboardViewModel;
      refresh: DashboardRefreshState;
    }
  | { type: "workspaceNotConfigured"; setupCommand: string }
  | { type: "failed" };
```

- [ ] **Step 1: Write failing polling/preservation tests**

Add tests using a scheduler with `delays` and `runNext()` plus strict source stubs:

```ts
it("publishes the persisted snapshot before refresh registration resolves", async () => {
  const refresh = deferred<RefreshSourceResult>();
  const source = createDashboardSourceStub({
    loadDashboard: () =>
      Promise.resolve({ type: "snapshotChanged", dashboard }),
    startRefresh: () => refresh.promise,
  });
  const { state } = useDashboard(source, scheduler);
  await flushPromises();
  expect(state.value).toMatchObject({
    type: "ready",
    dashboard,
    refresh: { type: "registering" },
  });
});

it("preserves the snapshot on snapshotUnchanged and schedules one poll", async () => {
  const source = createDashboardSourceStub({
    loadDashboard: vi
      .fn()
      .mockResolvedValueOnce({ type: "snapshotChanged", dashboard })
      .mockResolvedValueOnce({
        type: "snapshotUnchanged",
        dashboardRevision: "dash_17",
        serverTime: "2026-08-15T10:00:01Z",
        polling: { type: "active", afterMilliseconds: 25 },
      }),
    startRefresh: () =>
      Promise.resolve({
        type: "refreshRunRegistered",
        refreshRunId: "refresh_1",
      }),
  });
  const { state } = useDashboard(source, scheduler);
  await flushPromises();
  if (state.value.type !== "ready") throw new Error("expected ready");
  expect(state.value.dashboard).toBe(dashboard);
  expect(scheduler.delays).toEqual([25]);
});
```

Add changed-follow-up, initial workspace-not-configured, refresh-time `noRepositoriesConfigured`/`workspaceNotConfigured` setup guidance, initial rejection, background rejection preserving ready content, manual refresh joining one loop, and disposal tests.

- [ ] **Step 2: Run the focused test and verify orchestration failures**

```bash
cd web
npm run test:unit -- src/features/dashboard/useDashboard.spec.ts
```

Expected: FAIL because the current composable neither starts refresh nor accepts a scheduler/revision.

- [ ] **Step 3: Implement the one-shot browser scheduler**

```ts
export const browserPollScheduler: PollScheduler = {
  schedule(afterMilliseconds, task) {
    const timeoutId = window.setTimeout(task, afterMilliseconds);
    return () => window.clearTimeout(timeoutId);
  },
};
```

- [ ] **Step 4: Implement one complete-snapshot polling loop**

Use `shallowRef<DashboardUiState>`, one cancel callback, `disposed`, and a request-generation number. The shared result handler must contain this exact branch:

```ts
if (result.type === "snapshotChanged") {
  state.value = {
    type: "ready",
    dashboard: result.dashboard,
    refresh:
      result.dashboard.polling.type === "active"
        ? { type: "active" }
        : { type: "idle" },
  };
  scheduleFrom(result.dashboard.polling);
  return;
}
if (result.type === "snapshotUnchanged" && state.value.type === "ready") {
  state.value = {
    ...state.value,
    refresh:
      result.polling.type === "active" ? { type: "active" } : { type: "idle" },
  };
  scheduleFrom(result.polling);
}
```

`reload()` is the only operation that replaces ready content with `loading`. After the first changed snapshot is published, call `startRefresh()` and poll using its displayed revision. `refresh()` never clears ready content. `pollNow()` uses the current revision. Map `refreshRunRegistered` to polling; map `noRepositoriesConfigured`/`workspaceNotConfigured` to the ready-state failed refresh with the returned `setupCommand`; map a thrown registration/poll request to failed refresh without technical detail. Initial load rejection becomes page `failed`; background rejection preserves ready content and sets `Refresh unavailable`. `dispose()` invalidates requests and cancels one scheduled poll.

- [ ] **Step 5: Wire lifecycle disposal and real refresh intent**

In `DashboardView.vue`, call `onUnmounted(dispose)`, keep page-level expected states, and bind the ready header refresh intent to `refresh`. The revision is product metadata, not a request trace.

- [ ] **Step 6: Run focused, page, and type suites**

```bash
cd web
npm run test:unit -- src/features/dashboard/useDashboard.spec.ts src/features/dashboard/DashboardView.spec.ts
npm run type-check
```

Expected: all tests pass; ready content stays rendered through refresh/poll work.

- [ ] **Step 7: Commit polling orchestration**

```bash
git add web/src/features/dashboard/pollScheduler.ts web/src/features/dashboard/useDashboard.ts web/src/features/dashboard/useDashboard.spec.ts web/src/features/dashboard/DashboardView.vue web/src/features/dashboard/DashboardView.spec.ts
git commit -m "feat: add revision-aware dashboard refresh"
```

---

### Task 3: Render the product header and repository/PR hierarchy

**Files:**

- Create: `web/src/features/dashboard/components/ProductHeader.vue`
- Create: `web/src/features/dashboard/components/ProductHeader.spec.ts`
- Create: `web/src/features/dashboard/components/BuildStatus.vue`
- Create: `web/src/features/dashboard/components/BuildStatus.spec.ts`
- Modify: `web/src/features/dashboard/components/RepositoryGroup.vue:1-74`
- Create: `web/src/features/dashboard/components/RepositoryGroup.spec.ts`
- Modify: `web/src/features/dashboard/components/PullRequestCard.vue:1-58`
- Create: `web/src/features/dashboard/components/PullRequestCard.spec.ts`
- Modify: `web/src/features/dashboard/DashboardView.vue`
- Modify: `web/src/features/dashboard/DashboardView.spec.ts`
- Modify: `web/src/assets/main.css:1-223`

**Interfaces:**

- Consumes: repository/PR/build models and refresh state from Tasks 1–2.
- Produces: `ProductHeader` event `refresh`; `RepositoryGroup` and `PullRequestCard` event `review(pullRequestId, invoker)`; shared `BuildStatus` rendering.

```ts
export type ProductOverallStatus = "idle" | "active" | "problem";
```

- [ ] **Step 1: Write failing component tests for hierarchy and failed build**

```ts
it("renders native PR children under the owning repository", () => {
  const wrapper = mount(RepositoryGroup, {
    props: { repository: makeRepository() },
  });
  expect(wrapper.get("section").attributes("aria-labelledby")).toBe(
    "repository-repo_payments",
  );
  expect(
    wrapper.get("ul.pull-request-list").findAll("li.pull-request-branch"),
  ).toHaveLength(2);
  expect(wrapper.get('[data-pull-request-id="pr_184"]').text()).toContain(
    "Add retry budget",
  );
  expect(wrapper.get('[data-pull-request-id="pr_179"]').text()).toContain(
    "Remove legacy token",
  );
});

it("renders failed build details without changing readiness", () => {
  const wrapper = mount(PullRequestCard, {
    props: {
      pullRequest: makePullRequest({
        pullRequestId: "pr_92",
        displayNumber: 92,
        buildState: { type: "failed", failedCheckCount: 2 },
        readiness: { type: "available", passed: 5, total: 7 },
      }),
    },
  });
  expect(wrapper.text()).toContain("Build failed");
  expect(wrapper.text()).toContain("2 failed checks");
  expect(wrapper.text()).toContain("5 of 7 checks");
  expect(wrapper.get("[data-view-build]").attributes("aria-disabled")).toBe(
    "true",
  );
});
```

Add tests for successful/in-progress/unavailable build copy, acknowledged count, external PR link attributes, repository problem copy, and `review` emitting its `HTMLButtonElement`.

- [ ] **Step 2: Run the new component specs and verify structural failures**

```bash
cd web
npm run test:unit -- src/features/dashboard/components/ProductHeader.spec.ts src/features/dashboard/components/BuildStatus.spec.ts src/features/dashboard/components/RepositoryGroup.spec.ts src/features/dashboard/components/PullRequestCard.spec.ts
```

Expected: FAIL because the new components/list hierarchy and disabled build control do not exist.

- [ ] **Step 3: Implement `ProductHeader` and shared `BuildStatus`**

`ProductHeader.vue` receives `workspaceDisplayName`, `dashboardRevision`, `overallStatus: ProductOverallStatus`, and `refreshState: DashboardRefreshState`, and emits `refresh`. `DashboardView` derives `problem` when any repository has a problem, otherwise `active` when refresh or polling is active, otherwise `idle`. Render one `h1` named `Bitbucket Helper`, workspace metadata, a polite sync status, opaque revision in `code`, and native `Refresh` button disabled only while registering.

`BuildStatus.vue` exhaustively maps all build variants. Its failed branch is:

```vue
<span data-build-status="failed" class="build-status build-status--failed">
  <span class="failure-marker" aria-hidden="true">!</span>Build failed
</span>
<span v-if="buildState.failedCheckCount !== undefined">
  {{ buildState.failedCheckCount }} failed checks
</span>
<button
  type="button"
  data-view-build
  aria-disabled="true"
  :aria-describedby="descriptionId"
  @click.prevent
>
  View build
</button>
<span :id="descriptionId" class="visually-hidden">
  Build details are not available in Bitbucket Helper yet.
</span>
```

Require `controlId: string` and derive `descriptionId = 'build-details-' + controlId` so each instance is unique. `View build` emits nothing.

- [ ] **Step 4: Convert repositories and PRs to semantic parent/child rows**

Keep a labeled repository `section`, add a `data-tree-parent` header and explicit repository link, render local problem text when present, and replace the card grid with:

```vue
<ul
  v-if="repository.pullRequests.length > 0"
  class="pull-request-list"
  data-tree-children
>
  <li
    v-for="pullRequest in repository.pullRequests"
    :key="pullRequest.pullRequestId"
    class="pull-request-branch"
  >
    <PullRequestCard
      :pull-request="pullRequest"
      @review="(pullRequestId, invoker) => emit('review', pullRequestId, invoker)"
    />
  </li>
</ul>
```

`PullRequestCard.vue` renders identity, shared build status, readiness, actionable/acknowledged counts, `Review context`, and explicit `Open in Bitbucket`. The title is text. Emit review as:

```ts
const emit = defineEmits<{
  review: [pullRequestId: string, invoker: HTMLButtonElement];
}>();
function review(event: MouseEvent) {
  emit(
    "review",
    props.pullRequest.pullRequestId,
    event.currentTarget as HTMLButtonElement,
  );
}
```

- [ ] **Step 5: Compose the header/feed and add visible rails**

Use `ProductHeader` in the ready state and bind `refresh`. Continue repository order and render `RepositoryGroup` without a page-level `review` listener until Task 5 attaches drawer opening. Add list reset plus `.pull-request-branch::before/::after` rails and `.pull-request-branch:last-child::before { block-size: 2rem; }` so hierarchy is already visible.

- [ ] **Step 6: Run focused/page tests and type-check**

```bash
cd web
npm run test:unit -- src/features/dashboard/components src/features/dashboard/DashboardView.spec.ts
npm run type-check
```

Expected: all tests pass; PR #92 is failed/`5 of 7`, repository ownership is structural, and disabled `View build` emits nothing.

- [ ] **Step 7: Commit the product feed**

```bash
git add web/src/features/dashboard web/src/assets/main.css
git commit -m "feat: render product dashboard hierarchy"
```

---

### Task 4: Add the collapsible needs-attention inbox

**Files:**

- Create: `web/src/features/dashboard/components/AttentionItem.vue`
- Create: `web/src/features/dashboard/components/AttentionItem.spec.ts`
- Create: `web/src/features/dashboard/components/NeedsAttention.vue`
- Create: `web/src/features/dashboard/components/NeedsAttention.spec.ts`
- Modify: `web/src/features/dashboard/DashboardView.vue`
- Modify: `web/src/features/dashboard/DashboardView.spec.ts`
- Modify: `web/src/assets/main.css`

**Interfaces:**

- Consumes: `DashboardViewModel.inbox` and `ActionItemSummary`.
- Produces: event `review(actionItem, invoker)` and internal page-lifetime `expanded` state.

- [ ] **Step 1: Write failing disclosure/selection tests**

```ts
it("starts expanded, keeps the count visible while collapsed, and preserves collapse on prop update", async () => {
  const wrapper = mount(NeedsAttention, {
    props: { items: [action501, action502] },
  });
  const toggle = wrapper.get("button.needs-attention-toggle");
  expect(toggle.attributes("aria-expanded")).toBe("true");
  expect(wrapper.get("#needs-attention-body").isVisible()).toBe(true);
  expect(toggle.text()).toContain("2 open");
  await toggle.trigger("click");
  await wrapper.setProps({ items: [action502] });
  expect(toggle.attributes("aria-expanded")).toBe("false");
  expect(wrapper.find("#needs-attention-body").exists()).toBe(false);
  expect(toggle.text()).toContain("1 open");
});

it("emits the exact item and invoking button", async () => {
  const wrapper = mount(NeedsAttention, { props: { items: [action501] } });
  await wrapper.get('[data-action-item-id="action_501"]').trigger("click");
  const [item, invoker] = wrapper.emitted("review")?.[0] ?? [];
  expect(item).toEqual(action501);
  expect(invoker).toBeInstanceOf(HTMLButtonElement);
});
```

Add zero-item assertions for `0 open` and `You're all caught up.`

- [ ] **Step 2: Run focused specs and verify missing-component failures**

```bash
cd web
npm run test:unit -- src/features/dashboard/components/AttentionItem.spec.ts src/features/dashboard/components/NeedsAttention.spec.ts
```

Expected: FAIL because neither component exists.

- [ ] **Step 3: Implement native disclosure semantics**

`NeedsAttention.vue` owns `const expanded = ref(true)` and renders one full-width header button with `aria-expanded`, `aria-controls="needs-attention-body"`, title group, persistent count, and chevron. The controlled body uses `v-if="expanded"`, removing collapsed controls from DOM and accessibility traversal. It contains a native list of `AttentionItem` buttons or the expanded empty-state copy.

`AttentionItem.vue` renders repository/PR context, activity kind, actor, time, and acknowledgment state. Emit the full item plus `event.currentTarget as HTMLButtonElement`.

- [ ] **Step 4: Compose needs attention above the feed**

Add `NeedsAttention` as the first ready body region. Leave its emitted `review` event unhandled at page level until Task 5 attaches drawer opening. Because the component instance remains mounted across snapshot prop changes, its `expanded` ref persists without storage.

Add disclosure/button/count/chevron/attention-list CSS and visible focus. Do not animate required state.

- [ ] **Step 5: Verify collapse survives dashboard replacement**

Add a `DashboardView.spec.ts` test that collapses, resolves a changed dashboard through the source, and asserts `aria-expanded="false"` with the new count.

```bash
cd web
npm run test:unit -- src/features/dashboard/components/AttentionItem.spec.ts src/features/dashboard/components/NeedsAttention.spec.ts src/features/dashboard/DashboardView.spec.ts
npm run type-check
```

Expected: all tests pass with no browser-storage access.

- [ ] **Step 6: Commit needs attention**

```bash
git add web/src/features/dashboard web/src/assets/main.css
git commit -m "feat: add needs attention disclosure"
```

---

### Task 5: Add the context-first pull-request drawer

**Files:**

- Create: `web/src/features/dashboard/usePullRequestDrawer.ts`
- Create: `web/src/features/dashboard/usePullRequestDrawer.spec.ts`
- Create: `web/src/features/dashboard/components/ReadinessSummary.vue`
- Create: `web/src/features/dashboard/components/ReadinessSummary.spec.ts`
- Create: `web/src/features/dashboard/components/PullRequestDrawer.vue`
- Create: `web/src/features/dashboard/components/PullRequestDrawer.spec.ts`
- Modify: `web/src/features/dashboard/DashboardView.vue`
- Modify: `web/src/features/dashboard/DashboardView.spec.ts`
- Modify: `web/src/assets/main.css`

**Interfaces:**

- Consumes: `loadPullRequest`, repository/PR/action models, and `BuildStatus`.
- Produces: `DrawerUiState`, `statusMessage`, `openPullRequest`, `openActionItem`, `close`, and `reconcileDashboard`.

```ts
export interface DrawerContext {
  repositoryDisplayName: string;
  pullRequest: PullRequestSummary;
  selectedActionItem: ActionItemSummary | null;
  detail: PullRequestDetailModel | null;
}
export type DrawerUiState =
  | { type: "closed" }
  | { type: "detailLoading"; context: DrawerContext }
  | { type: "metadata"; context: DrawerContext }
  | { type: "detailUnavailable"; context: DrawerContext; message: string };

export interface PullRequestDrawerController {
  state: Readonly<ShallowRef<DrawerUiState>>;
  statusMessage: Readonly<Ref<string | null>>;
  openPullRequest(
    repository: RepositoryGroupModel,
    pullRequest: PullRequestSummary,
    invoker: HTMLButtonElement,
  ): Promise<void>;
  openActionItem(
    dashboard: DashboardViewModel,
    actionItem: ActionItemSummary,
    invoker: HTMLButtonElement,
  ): Promise<void>;
  close(): void;
  reconcileDashboard(dashboard: DashboardViewModel): void;
}
```

- [ ] **Step 1: Write failing orchestration/race tests**

```ts
it("opens from a PR summary immediately and enriches it with detail", async () => {
  const detail = makePullRequestDetail();
  const pending = deferred<PullRequestDetailSourceResult>();
  const source = createDashboardSourceStub({
    loadPullRequest: () => pending.promise,
  });
  const drawer = usePullRequestDrawer(source);
  const opening = drawer.openPullRequest(
    makeRepository(),
    detail.pullRequest,
    button(),
  );
  expect(drawer.state.value.type).toBe("detailLoading");
  pending.resolve({ type: "pullRequestAvailable", detail });
  await opening;
  expect(drawer.state.value).toMatchObject({
    type: "metadata",
    context: { detail },
  });
});

it("ignores detail returned for an older selection", async () => {
  const first = deferred<PullRequestDetailSourceResult>();
  const second = deferred<PullRequestDetailSourceResult>();
  const source = createDashboardSourceStub({
    loadPullRequest: vi
      .fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise),
  });
  const drawer = usePullRequestDrawer(source);
  void drawer.openPullRequest(
    makeRepository(),
    makePullRequest({ pullRequestId: "pr_184" }),
    button(),
  );
  void drawer.openPullRequest(
    makeRepository(),
    makePullRequest({ pullRequestId: "pr_179" }),
    button(),
  );
  first.resolve({
    type: "pullRequestAvailable",
    detail: makePullRequestDetail({ pullRequestId: "pr_184" }),
  });
  await flushPromises();
  if (drawer.state.value.type !== "detailLoading")
    throw new Error("expected loading");
  expect(drawer.state.value.context.pullRequest.pullRequestId).toBe("pr_179");
});
```

Add tests for exact action_501/av_42 selection, first actionable PR item in server-provided order, not-found context, close ignoring late detail, and focus return. Never sort activity versions.

- [ ] **Step 2: Run the drawer spec and verify missing-state-machine failure**

```bash
cd web
npm run test:unit -- src/features/dashboard/usePullRequestDrawer.spec.ts
```

Expected: FAIL because the composable does not exist.

- [ ] **Step 3: Implement selection and stale-response guards**

Use `shallowRef<DrawerUiState>({ type: 'closed' })`, an increasing generation, and private focus-return element. `openPullRequest` creates immediate context, selects the first actionable item in the source-provided `actionItems` order, sets `detailLoading`, awaits detail, and applies only a matching generation. `openActionItem` locates the owning repo/PR in a supplied dashboard and overrides the selected item with the exact inbox object.

`close()` invalidates requests, sets `closed`, clears `statusMessage`, and queues invoker focus. `reconcileDashboard(dashboard)` updates current PR/action metadata when IDs still exist. When the PR disappears, it invalidates requests, closes the drawer, and sets `statusMessage` to `That pull request is no longer in this dashboard.`. Return `statusMessage` as `Readonly<Ref<string | null>>` so `DashboardView` can announce it without inventing another state variant.

- [ ] **Step 4: Render non-modal drawer and shared readiness**

`ReadinessSummary.vue` receives `pullRequest` and `readinessChecks`, reuses `BuildStatus`, and renders readiness, individual checks, and counts.

`PullRequestDrawer.vue` renders nothing for `closed`; otherwise render an `aside` labeled by PR title, Close button, `ReadinessSummary`, activity heading/version, immediate metadata, loading copy, or no-action copy. Watch closed→open and focus Close after `nextTick`. Escape emits close. Do not use dialog/modal roles or focus trapping.

- [ ] **Step 5: Connect inbox/PR events and dashboard reconciliation**

Instantiate `usePullRequestDrawer(source)` in `DashboardView`. Attach the Task 3–4 `review` events to `openPullRequest`/`openActionItem`, render the drawer beside the feed, watch accepted dashboard snapshots and call `reconcileDashboard`, and pass `close`. Render `statusMessage` in a page-scoped polite live region for disappearing PR context. Unchanged snapshots/background status do not close the drawer.

- [ ] **Step 6: Verify focus, selection, integration, and types**

```bash
cd web
npm run test:unit -- src/features/dashboard/usePullRequestDrawer.spec.ts src/features/dashboard/components/ReadinessSummary.spec.ts src/features/dashboard/components/PullRequestDrawer.spec.ts src/features/dashboard/DashboardView.spec.ts
npm run type-check
```

Expected: all tests pass; inbox/PR controls open one context drawer and Close restores its invoker.

- [ ] **Step 7: Commit the detail drawer**

```bash
git add web/src/features/dashboard web/src/assets/main.css
git commit -m "feat: add pull request detail drawer"
```

---

### Task 6: Load and render exact-version activity content

**Files:**

- Modify: `web/src/features/dashboard/usePullRequestDrawer.ts`
- Modify: `web/src/features/dashboard/usePullRequestDrawer.spec.ts`
- Create: `web/src/features/dashboard/components/ActivityOutcome.vue`
- Create: `web/src/features/dashboard/components/ActivityOutcome.spec.ts`
- Modify: `web/src/features/dashboard/components/PullRequestDrawer.vue`
- Modify: `web/src/features/dashboard/components/PullRequestDrawer.spec.ts`
- Modify: `web/src/features/dashboard/DashboardView.vue`
- Modify: `web/src/features/dashboard/DashboardView.spec.ts`

**Interfaces:**

- Consumes: `loadActionContent(actionItemId, activityVersion)` and every `ActionContentSourceResult` member.
- Produces: exact-version content states, `retrySelectedContent()`, and a presentational activity-outcome component.

Extend the open drawer state with this discriminated union:

```ts
export type ActivityContentState =
  | { type: "contentLoading"; actionItemId: string; activityVersion: string }
  | {
      type: "contentAvailable";
      actionItemId: string;
      activityVersion: string;
      markdownSource: string;
    }
  | { type: "contentUnavailable"; message: string; retryable: boolean }
  | {
      type: "newerActivity";
      actionItemId: string;
      requestedActivityVersion: string;
      currentActivityVersion: string;
    };
```

Add `activityContent: ActivityContentState | null` to `DrawerContext`.

- [ ] **Step 1: Write failing exact-version and race tests**

In `usePullRequestDrawer.spec.ts`, construct sources explicitly with `createDashboardSourceStub`. One source resolves detail containing `action_501`/`av_42` and records the two arguments passed to `loadActionContent`; another returns two deferred content promises so the first result can arrive after a new selection. Assert:

```ts
expect(source.loadActionContent).toHaveBeenCalledWith("action_501", "av_42");
expect(drawer.state.value).toMatchObject({
  type: "metadata",
  context: {
    activityContent: {
      type: "contentAvailable",
      actionItemId: "action_501",
      activityVersion: "av_42",
    },
  },
});
```

Cover `contentUnavailable`, retrying the same exact action/version, `newerActivityObserved`, `staleActivityVersion`, `actionItemNotFound`, no selected action, close-before-content, and select-another-PR-before-content. The two stale-version outcomes both map to `newerActivity`; they never display the requested body.

In `ActivityOutcome.spec.ts`, mount with the literal string `<img src=x onerror=alert(1)> **review**` and assert the text is visible while `wrapper.find('img').exists()` is false.

- [ ] **Step 2: Run focused tests and verify the missing behavior**

```bash
cd web
npm run test:unit -- src/features/dashboard/usePullRequestDrawer.spec.ts src/features/dashboard/components/ActivityOutcome.spec.ts
```

Expected: FAIL because content orchestration and `ActivityOutcome.vue` do not exist.

- [ ] **Step 3: Implement version-keyed loading with generation guards**

After detail resolves or an inbox action opens, call `loadActionContent` only with the selected item's exact `actionItemId` and `activityVersion`. Set `contentLoading` first. Apply the result only when the drawer generation, PR ID, action ID, and requested version all still match. Add `retrySelectedContent()` to `PullRequestDrawerController`; it is a no-op unless the current state is retryable `contentUnavailable`, and it reloads the still-selected action/version through the same guarded function.

Map source results exhaustively:

```ts
function toActivityContentState(
  result: ActionContentSourceResult,
  actionItemId: string,
): ActivityContentState {
  switch (result.type) {
    case "contentAvailable":
      return {
        type: "contentAvailable",
        actionItemId: result.actionItemId,
        activityVersion: result.activityVersion,
        markdownSource: result.markdownSource,
      };
    case "contentUnavailable":
      return {
        type: "contentUnavailable",
        message: result.reason,
        retryable: result.retryable,
      };
    case "newerActivityObserved":
    case "staleActivityVersion":
      return {
        type: "newerActivity",
        actionItemId,
        requestedActivityVersion: result.requestedActivityVersion,
        currentActivityVersion: result.currentActivityVersion,
      };
    case "actionItemNotFound":
      return {
        type: "contentUnavailable",
        message: "This activity is no longer available.",
        retryable: false,
      };
  }
}
```

Do not cache content under action ID alone. Do not put raw content into dashboard state.

- [ ] **Step 4: Render activity content as safe text**

`ActivityOutcome.vue` accepts `activityContent`, emits `retry` only for retryable `contentUnavailable`, and renders loading, unavailable/retry copy, newer-activity copy, or `markdownSource` through normal Vue interpolation in a whitespace-preserving element. Do not use `v-html`, a Markdown renderer, or HTML parsing.

Update `PullRequestDrawer` to render `ActivityOutcome` under activity metadata and forward `retry`; `DashboardView` connects it to `retrySelectedContent`. Loading detail and loading body must remain distinguishable. Add component assertions for all four states, exact-version retry, and the malicious-string safety case.

- [ ] **Step 5: Connect and verify the complete content flow**

Update `DashboardView` wiring only as needed to pass the expanded drawer state. Add an integration test that clicks action 501, resolves detail then content, and sees the exact body; assert the dashboard snapshot object still has no `markdownSource` property.

```bash
cd web
npm run test:unit -- src/features/dashboard/usePullRequestDrawer.spec.ts src/features/dashboard/components/ActivityOutcome.spec.ts src/features/dashboard/components/PullRequestDrawer.spec.ts src/features/dashboard/DashboardView.spec.ts
npm run type-check
```

Expected: all selected tests pass and content is keyed by exact activity version.

- [ ] **Step 6: Commit exact-version content loading**

```bash
git add web/src/features/dashboard
git commit -m "feat: load exact-version activity content"
```

---

### Task 7: Reconcile acknowledgment and newer-activity outcomes

**Files:**

- Create: `web/src/features/dashboard/dashboardReconciliation.ts`
- Create: `web/src/features/dashboard/dashboardReconciliation.spec.ts`
- Modify: `web/src/features/dashboard/useDashboard.ts`
- Modify: `web/src/features/dashboard/useDashboard.spec.ts`
- Modify: `web/src/features/dashboard/usePullRequestDrawer.ts`
- Modify: `web/src/features/dashboard/usePullRequestDrawer.spec.ts`
- Modify: `web/src/features/dashboard/components/ActivityOutcome.vue`
- Modify: `web/src/features/dashboard/components/ActivityOutcome.spec.ts`
- Modify: `web/src/features/dashboard/components/PullRequestDrawer.vue`
- Modify: `web/src/features/dashboard/components/PullRequestDrawer.spec.ts`
- Modify: `web/src/features/dashboard/DashboardView.vue`
- Modify: `web/src/features/dashboard/DashboardView.spec.ts`

**Interfaces:**

- Consumes: `acknowledgeActionItem`, `startRepositoryRefresh`, `AcknowledgmentSourceResult`, and accepted dashboard polling.
- Produces: immutable optimistic reconciliation, guarded acknowledgment state, and repository-refresh recovery.

Replace `ActivityContentState` with the complete command-capable union:

```ts
export type ActivityContentState =
  | { type: "contentLoading"; actionItemId: string; activityVersion: string }
  | {
      type: "contentAvailable";
      actionItemId: string;
      activityVersion: string;
      markdownSource: string;
    }
  | { type: "contentUnavailable"; message: string; retryable: boolean }
  | {
      type: "newerActivity";
      actionItemId: string;
      requestedActivityVersion: string;
      currentActivityVersion: string;
    }
  | {
      type: "ackPending";
      actionItemId: string;
      activityVersion: string;
      markdownSource: string;
    }
  | { type: "acknowledged"; message: string }
  | {
      type: "acknowledgmentRejected";
      message: string;
      retryable: boolean;
      actionItemId: string;
      activityVersion: string;
    }
  | { type: "refreshing"; currentActivityVersion: string };
```

Expose these integration points:

```ts
export interface AcknowledgedActionRef {
  actionItemId: string
  activityVersion: string
  repositoryId: string
  pullRequestId: string
}

export function reconcileAcknowledgedAction(
  dashboard: DashboardViewModel,
  acknowledged: AcknowledgedActionRef,
): DashboardViewModel

// Added to useDashboard's return value.
applyAcknowledgment(acknowledged: AcknowledgedActionRef): void
pollDashboard(): Promise<void>

export interface PullRequestDrawerDependencies {
  applyAcknowledgment(acknowledged: AcknowledgedActionRef): void
  pollDashboard(): Promise<void>
}

export function usePullRequestDrawer(
  source: DashboardSource,
  dependencies: PullRequestDrawerDependencies,
): PullRequestDrawerController
```

- [ ] **Step 1: Write failing immutable-reducer tests**

In `dashboardReconciliation.spec.ts`, start from `makeDashboard()` containing action 501. Assert a matching ID/version:

- returns a new dashboard object without mutating the original;
- removes action 501 from `inbox` and the owning PR's `actionItems`;
- decrements `actionableItemCount` and increments `acknowledgedItemCount` exactly once;
- leaves `revision`, other repositories, other PRs, and different versions untouched;
- becomes an identity operation when repeated, missing, or version-mismatched.

- [ ] **Step 2: Write failing acknowledgment orchestration tests**

Build each test source explicitly with `createDashboardSourceStub`; provide a resolved detail containing action 501, a `contentAvailable` result, and only the acknowledgment/refresh methods exercised by that test. Drive `openActionItem`, await content, then call the new `acknowledgeSelected()` method. Assert exact arguments and cover:

- `acknowledged`: calls `applyAcknowledgment` once and shows success;
- `alreadyAcknowledged`: applies the same idempotent reconciliation and shows success;
- `staleActivityVersion`: does not reconcile, enters `refreshing`, calls `startRepositoryRefresh(repo_payments, av_42)`, then `pollDashboard()`;
- `acknowledgmentRejected`: preserve the action and show the result's `reason`; `actionItemNotFound`: preserve local counts and show `This activity is no longer available.`;
- double click while `ackPending`: sends one request;
- closing or changing selection never lets a late result reopen or overwrite the current drawer; a late `acknowledged`/`alreadyAcknowledged` result still calls the idempotent dashboard reconciler exactly once;
- content outcomes `newerActivityObserved`/`staleActivityVersion` expose a Refresh control that uses the same repository-refresh sequence.

- [ ] **Step 3: Run the reducer and orchestration tests to prove red**

```bash
cd web
npm run test:unit -- src/features/dashboard/dashboardReconciliation.spec.ts src/features/dashboard/usePullRequestDrawer.spec.ts
```

Expected: FAIL because the reducer and acknowledgment orchestration do not exist.

- [ ] **Step 4: Implement immutable local reconciliation**

Implement `reconcileAcknowledgedAction` with exact ID/version matching. Rebuild only the matching repository, PR, action list, and inbox array. Clamp the actionable count at zero and increment acknowledged only when an item was actually removed. Preserve the server-issued dashboard revision because this is a local projection, not a new snapshot.

Add `applyAcknowledgment` to `useDashboard`; it updates only a ready state's dashboard through the reducer. Add public `pollDashboard()` that reuses the single-flight polling path from Task 2 and still honors revision ordering.

- [ ] **Step 5: Implement guarded acknowledgment and refresh recovery**

Add `acknowledgeSelected()` and `refreshSelectedRepository()` to `PullRequestDrawerController`. Acknowledge is enabled only for `contentAvailable`; capture exact action/version/repository/PR plus current generation before awaiting. Exhaustively map every `AcknowledgmentSourceResult`:

- `acknowledged` and `alreadyAcknowledged`: call `applyAcknowledgment` with the captured IDs even if the drawer has since closed; update drawer success copy only if the generation/context still matches;
- `staleActivityVersion`: apply no dashboard mutation; while the same context is open, show `currentActivityVersion` and begin repository refresh;
- `acknowledgmentRejected`: keep the item actionable and show its `reason` with `retryable: false`;
- `actionItemNotFound`: keep local counts unchanged and show `This activity is no longer available.` with `retryable: false`.

For stale/newer outcomes, call:

```ts
await source.startRepositoryRefresh(repositoryId, observedActivityVersion);
await pollDashboard();
```

Exhaustively handle `startRepositoryRefresh`: `refreshRunRegistered` proceeds to `pollDashboard`; `noRepositoriesConfigured` and `workspaceNotConfigured` preserve context and surface their setup guidance; thrown request failure shows `Refresh unavailable`. Only a later accepted dashboard snapshot may update the selected version through `reconcileDashboard`; do not synthesize `av_43` in the UI. Closing the drawer does not cancel an already registered repository refresh.

- [ ] **Step 6: Wire controls and assert collapsed-state preservation**

Pass `applyAcknowledgment` and `pollDashboard` from `DashboardView` into the drawer composable. `ActivityOutcome` emits `acknowledge` or `refresh`; `PullRequestDrawer` forwards them. Render `Acknowledge av_42` (using the actual opaque displayed version) only for `contentAvailable`, disable it while pending, and replace the label with `Acknowledging av_42…` during the request.

In `DashboardView.spec.ts`, start with Needs attention collapsed and count 2, acknowledge action 501 through product controls, then assert count 1 and the disclosure remains collapsed. Add integration assertions for stale acknowledgment, repository refresh, a later `av_43` snapshot, and reloaded content.

- [ ] **Step 7: Verify domain paths and types**

```bash
cd web
npm run test:unit -- src/features/dashboard/dashboardReconciliation.spec.ts src/features/dashboard/useDashboard.spec.ts src/features/dashboard/usePullRequestDrawer.spec.ts src/features/dashboard/components/ActivityOutcome.spec.ts src/features/dashboard/components/PullRequestDrawer.spec.ts src/features/dashboard/DashboardView.spec.ts
npm run type-check
```

Expected: all tests pass; every valid business outcome stays in the typed body path and no component reasons about HTTP statuses.

- [ ] **Step 8: Commit acknowledgment behavior**

```bash
git add web/src/features/dashboard
git commit -m "feat: handle exact-version acknowledgment"
```

---

### Task 8: Apply responsive visual language and accessibility behavior

**Files:**

- Modify: `web/src/assets/main.css`
- Modify: `web/src/features/dashboard/DashboardView.vue`
- Modify: `web/src/features/dashboard/DashboardView.spec.ts`
- Modify: `web/src/features/dashboard/components/ProductHeader.vue`
- Modify: `web/src/features/dashboard/components/NeedsAttention.vue`
- Modify: `web/src/features/dashboard/components/RepositoryGroup.vue`
- Modify: `web/src/features/dashboard/components/PullRequestCard.vue`
- Modify: `web/src/features/dashboard/components/PullRequestDrawer.vue`
- Modify: `web/src/features/dashboard/components/PullRequestDrawer.spec.ts`
- Modify: `web/src/features/dashboard/components/ActivityOutcome.vue`

**Interfaces:**

- Consumes: all production components from Tasks 3–7.
- Produces: shared design tokens, responsive layout, keyboard behavior, and scoped live-region semantics.

- [ ] **Step 1: Add failing semantic and accessibility assertions**

Extend component tests to require:

- one `main` landmark and a logical page→section→repository→PR→drawer heading order;
- native button disclosure with `aria-expanded`, `aria-controls`, and collapsed content removed from the DOM;
- the non-modal drawer as a complementary `aside` named by the PR title, with Close initially focused;
- Escape closes and focus returns to the invoker;
- scoped `aria-live="polite"` regions for refresh, drawer, and acknowledgment outcomes;
- an unexpected page failure alone uses `role="alert"`;
- unavailable operations use real `disabled`; the intentionally discoverable `View build` button uses `aria-disabled="true"`, its accessible reason, and causes no side effect;
- build/freshness states include descriptive text, not color alone.

Run the focused specs and confirm the new assertions fail before template changes:

```bash
cd web
npm run test:unit -- src/features/dashboard/DashboardView.spec.ts src/features/dashboard/components/PullRequestDrawer.spec.ts
```

Expected: FAIL on missing semantics or focus behavior.

- [ ] **Step 2: Define the approved light/dark token system once**

In `main.css`, define semantic custom properties at `:root` and override them under `@media (prefers-color-scheme: dark)`. Use these exact approved values:

| Token   | Light              | Dark               |
| ------- | ------------------ | ------------------ |
| Canvas  | `rgb(244 247 252)` | `rgb(16 21 30)`    |
| Surface | `rgb(255 255 255)` | `rgb(29 37 50)`    |
| Raised  | `rgb(249 251 255)` | `rgb(36 45 60)`    |
| Text    | `rgb(27 36 52)`    | `rgb(235 239 247)` |
| Muted   | `rgb(91 105 127)`  | `rgb(174 185 204)` |
| Line    | `rgb(216 224 236)` | `rgb(61 73 93)`    |
| Accent  | `rgb(44 82 190)`   | `rgb(128 154 255)` |
| Success | `rgb(34 117 76)`   | `rgb(112 215 163)` |
| Warning | `rgb(121 78 16)`   | `rgb(242 195 109)` |
| Danger  | `rgb(158 48 54)`   | `rgb(255 143 148)` |

Use system sans-serif at `14px`/`1.45`, controls at least `36px` high, `7–10px` radii, borders rather than heavy shadows, and an accent focus ring. Add a reusable visually-hidden class and reduced-motion rule. Components consume variables rather than scoped literal colors.

- [ ] **Step 3: Implement wide, conversation-width, and narrow layouts**

Use a feed/drawer grid on wide screens with drawer width `clamp(20rem, 32vw, 28rem)`. Keep the header, attention disclosure, and feed aligned to the same page shell.

- From `760px` through `1180px`, allow header metadata, PR status, and controls to wrap while retaining the side drawer when space permits.
- Below `760px`, switch to one block flow; place the open drawer after the repository feed at full width and scroll its heading into view on open.
- At or below `420px`, stack action rows and allow long repository/PR titles and version strings to wrap.

Render hierarchy rails with pseudo-elements only; preserve semantic nested lists and terminate the rail on the last PR. Add `min-width: 0`, overflow-wrap, and grid/flex constraints so the document never scrolls horizontally at `1024px`, `736px`, or `360px`.

- [ ] **Step 4: Apply accessible state and outcome styling**

Style success, warning, danger, unavailable, selected, pending, and disabled states through text plus color/border/icon shape. Keep failed build prominent without making the whole row an alert. Make attention rows and drawer outcomes use Raised; repositories and drawer use Surface; canvas/feed use Canvas.

Ensure raw activity text preserves line breaks and wraps, all external links retain `target="_blank" rel="noopener noreferrer"`, and no state relies on animation.

- [ ] **Step 5: Verify templates, formatting, lint, types, and build**

```bash
cd web
npm run test:unit -- src/features/dashboard/DashboardView.spec.ts src/features/dashboard/components/ProductHeader.spec.ts src/features/dashboard/components/BuildStatus.spec.ts src/features/dashboard/components/NeedsAttention.spec.ts src/features/dashboard/components/RepositoryGroup.spec.ts src/features/dashboard/components/PullRequestCard.spec.ts src/features/dashboard/components/PullRequestDrawer.spec.ts src/features/dashboard/components/ActivityOutcome.spec.ts
npm run format
npm run lint
npm run type-check
npm run build-only
```

Expected: focused behavior tests pass, formatting/lint/types are clean, and Vite produces a production bundle.

- [ ] **Step 6: Commit responsive product styling**

```bash
git add web/src/assets/main.css web/src/features/dashboard
git commit -m "style: apply responsive dashboard design"
```

---

### Task 9: Add deterministic fixture journeys and browser acceptance coverage

**Files:**

- Modify: `web/src/features/dashboard/fixtures/fixtureDashboardData.ts`
- Modify: `web/src/features/dashboard/fixtures/fixtureDashboardSource.ts`
- Create: `web/src/features/dashboard/fixtures/fixtureDashboardSource.spec.ts`
- Modify: `web/src/main.ts`
- Modify: `web/e2e/dashboard.spec.ts`
- Modify: `web/README.md`

**Interfaces:**

- Consumes: the complete `DashboardSource` boundary and product controls.
- Produces: six isolated development journeys selected outside product chrome and a full browser-level acceptance suite.

```ts
export type FixtureJourney =
  | "healthy-refresh"
  | "partial-refresh"
  | "content-success"
  | "content-unavailable"
  | "newer-activity"
  | "stale-acknowledgment";

export function createFixtureDashboardSource(
  journey: FixtureJourney = "healthy-refresh",
): DashboardSource;
```

- [ ] **Step 1: Write failing source-sequence tests for all six journeys**

In `fixtures/fixtureDashboardSource.spec.ts`, create a fresh source per test and assert these deterministic sequences:

1. `healthy-refresh`: initial idle `dash_18`; automatic refresh registration followed by unchanged/idle `dash_18`; manual Refresh registration followed by changed/active `dash_19`; scheduled unchanged/idle `dash_19`.
2. `partial-refresh`: initial complete `dash_18`; automatic refresh produces unchanged/idle; manual Refresh produces `dash_19` marking Web Store partial/stale with a local problem while preserving PR #92 and its last-known-good data.
3. `content-success`: action 501/`av_42` returns its exact body, then exact-version acknowledgment succeeds.
4. `content-unavailable`: metadata/detail remain available while both the initial exact content request and a retry return retryable unavailable without changing selection.
5. `newer-activity`: loading `av_42` reports current `av_43`; repository refresh registers; the next changed dashboard contains `av_43`; loading `av_43` returns the newer body.
6. `stale-acknowledgment`: content for `av_42` loads, acknowledgment reports current `av_43`, and no dashboard count/action mutation occurs before refreshed metadata.

Also assert journey instances do not share counters or mutations, raw content is absent from dashboard snapshots, and all expected business outcomes are typed successful responses at this boundary rather than thrown request failures.

- [ ] **Step 2: Run the fixture spec and verify missing journeys**

```bash
cd web
npm run test:unit -- src/features/dashboard/fixtures/fixtureDashboardSource.spec.ts
```

Expected: FAIL because the journey argument and sequences do not exist.

- [ ] **Step 3: Implement per-instance journey state machines**

Keep immutable catalog values and clone per source instance. Track call indexes inside `createFixtureDashboardSource`; never mutate exported fixtures. Implement every result with the exact discriminated unions from Task 1.

For healthy `dash_19`, change only generated time to `2026-08-15T10:00:30Z`, Payments API revision to `repo_12`, synchronization to idle, freshness to fresh `Just now`, and polling to active after `25ms`; the following unchanged result makes polling idle. For partial `dash_19`, retain Web Store PR #92 exactly while setting synchronization idle, freshness to stale `18 minutes ago` since `2026-08-15T09:42:00Z`, and problem to `{ type: 'present', message: 'Refresh completed with stale Web Store data.', retryable: true, retryAfterDescription: 'Try Refresh again.' }`.

For newer activity, the automatic dashboard poll is unchanged/idle. Do not expose the `av_43` body until repository refresh has been registered and the next changed `dash_19` snapshot replaces action 501 with `av_43` consistently in the inbox, PR summary, and PR detail. For stale acknowledgment, return requested `av_42`/current `av_43` without changing inbox or counts; the automatic repository-refresh path may then publish the same consistent `dash_19` metadata.

- [ ] **Step 4: Add a development-only selector outside product chrome**

In `main.ts`, parse `?fixtureJourney=<FixtureJourney>` only when `import.meta.env.DEV`; validate against the six literal values and default to `healthy-refresh`. Pass it to `createFixtureDashboardSource`. Ignore the parameter in production builds.

Do not add a scenario selector, reset button, response-step control, API trace, or fixture wording to `DashboardView` or any product component. The query parameter is disposable test scaffolding, not product UI.

- [ ] **Step 5: Replace browser smoke coverage with six product-driven journeys**

In `e2e/dashboard.spec.ts`, navigate to each development fixture URL but drive state only through visible product controls. Cover:

- healthy refresh: initial data, Refresh, unchanged preservation, `dash_19`, and idle copy;
- partial refresh: Web Store problem treatment plus retained PR #92 hierarchy;
- success: open action 501, load exact content, Acknowledge, count `2 open`→`1 open`, collapsed state preserved;
- unavailable: metadata remains, Retry repeats the exact-version request, the retryable body failure remains scoped to the drawer, and Acknowledge is absent;
- newer activity: stale body withheld, repository refresh, `av_43` metadata, then newer body;
- stale acknowledgment: no success copy or count removal, repository refreshing appears, and only refreshed metadata advances to `av_43`.

Add shared browser assertions for:

- PR #92 shows `Build failed`, `2 failed checks`, and readiness `5 of 7`;
- external links have safe new-tab attributes and unavailable build controls cause no navigation;
- drawer focus enters Close, Escape/Close restores the invoking button, and collapsed attention content is not focusable;
- raw body is absent before load and after drawer close;
- no unexpected console errors, page errors, `fetch`, or XHR occur in fixture mode;
- `localStorage` and `sessionStorage` remain empty.

- [ ] **Step 6: Add responsive and appearance regression assertions**

For `1024px`, `736px`, and `360px`, run the healthy journey in light and dark color schemes. At each size assert `document.documentElement.scrollWidth <= document.documentElement.clientWidth`, controls are inside the viewport, and the drawer geometry matches the wide/narrow rule. Use semantic/geometry assertions and focused screenshots only when they provide useful failure artifacts; do not commit brittle full-page golden images.

- [ ] **Step 7: Document local fixture use and the real-adapter boundary**

Update `web/README.md` with the six development URLs, explain that the selector is outside product chrome and ignored by production builds, and state that fixtures implement the browser-facing port. Document the real SPA handoff: replace the fixture source with a generated same-origin Kotlin adapter, preserve the typed outcome mapping and HTTP status policy, obtain CSRF state from `browser-session` in memory, and run contract-fixture tests before switching adapters.

- [ ] **Step 8: Run the complete verification suite**

```bash
cd web
npm run check
npm run test:e2e
git status --short
git diff --check
```

Expected: unit/component checks, lint, type-check, build, and all Chromium journeys pass; diff check is silent. `git status` shows only the files intended for this task plus any explicitly preserved pre-existing user changes.

- [ ] **Step 9: Commit the fixture journeys and acceptance suite**

```bash
git add web/src/features/dashboard/fixtures/fixtureDashboardData.ts web/src/features/dashboard/fixtures/fixtureDashboardSource.ts web/src/features/dashboard/fixtures/fixtureDashboardSource.spec.ts web/src/main.ts web/e2e/dashboard.spec.ts web/README.md
git commit -m "test: cover dashboard drawer journeys"
```

---

## Completion checklist

- [ ] Every task followed red→green and each focused test failed for the intended missing behavior before implementation.
- [ ] The nine task commits are present and contain no unrelated pre-existing changes.
- [ ] `npm run check` and `npm run test:e2e` pass from `web/`.
- [ ] All six journeys work through normal product controls with no product-visible demo controller.
- [ ] Expected domain outcomes use typed response bodies; only request/transport failures use error paths.
- [ ] Raw activity content is exact-version, short-lived, safely rendered, absent from dashboard snapshots, and discarded when context changes.
- [ ] Needs attention remains collapsible, repository→PR hierarchy is semantic, and the failed-build example remains visible.
- [ ] Light/dark layouts at `1024px`, `736px`, and `360px` have no horizontal overflow and preserve keyboard/focus behavior.
- [ ] The fixture-to-real-adapter handoff is documented and protected by contract-fixture tests.
