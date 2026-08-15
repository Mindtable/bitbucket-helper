# Interactive Mock SPA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify a disposable, product-first interactive dashboard and drawer that demonstrate the six approved SPA/API journeys without creating real frontend source.

**Architecture:** One self-contained HTML fragment in the task-owned visualization directory contains immutable representative fixtures, a deterministic in-memory transition model, scoped product rendering, and demo-only controls. Verification uses the bundled visualization renderer and the Codex in-app Browser against a loopback-only preview; there is no separate browser automation process and no tracked implementation source.

**Tech Stack:** Semantic HTML fragment, scoped CSS, browser-native JavaScript, bundled Python visualization renderer, Codex in-app Browser control.

## Global Constraints

- Follow `docs/superpowers/specs/2026-08-15-interactive-mock-spa-design.md`; defer to `docs/superpowers/specs/2026-08-15-spa-kotlin-api-contract-design.md` for contract semantics.
- Before implementation, invoke `superpowers:test-driven-development` and `visualize:visualize`; before browser work, invoke `browser:control-in-app-browser` and read its local-web-development guidance.
- Create no `web/`, Vue, Vite, TypeScript, Kotlin, Ktor, OpenAPI, or production application source.
- Keep all implementation and QA artifacts outside the repository under `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506`.
- The source must be an HTML fragment named `interactive-mock-spa.html`, with unique root ID `bbh-mock-spa`, no document wrapper, and a size below 1 MB.
- Select the root with `document.getElementById("bbh-mock-spa")`; never use `document.currentScript`.
- Perform no `fetch`, XHR, WebSocket, navigation, cookie, `localStorage`, or `sessionStorage` operation.
- Keep the demo controller outside `#bbh-product`; product actions and demo progression remain visually and structurally distinct.
- Provide exactly six journeys: healthy refresh, partial refresh, content and acknowledgment success, content unavailable, newer activity discovered, and stale acknowledgment.
- Show partial synchronization, unavailable content, newer activity, and stale acknowledgment as typed HTTP `200` business outcomes.
- Keep raw activity Markdown out of dashboard fixtures, traces, errors, and state except while the active drawer displays a live-content result.
- Display Bitbucket links as inert representative controls.
- Use native labeled controls, normal tab order, visible focus, textual status labels, light and dark appearance, and layouts usable at 1,024 px, 736 px, and 360 px.
- Do not commit the fragment, preview, QA copies, or screenshots. Each implementation task ends with a verified checkpoint rather than an implementation commit.

## File Structure

### Disposable files outside the repository

- Create: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html` — the only editable visualization source.
- Generate: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa-preview.html` — standalone browser preview.
- Generate during final QA: `interactive-mock-spa-light-qa.html`, `interactive-mock-spa-dark-qa.html`, `interactive-mock-spa-light-preview.html`, and `interactive-mock-spa-dark-preview.html` in the same directory — disposable theme-forcing copies and previews.

### Project files

- Read only: `docs/superpowers/specs/2026-08-15-interactive-mock-spa-design.md`.
- Read only: `docs/superpowers/specs/2026-08-15-spa-kotlin-api-contract-design.md`.
- Modify no tracked project file during prototype implementation.

## Shared verification setup

Render the current fragment after every implementation change:

```bash
/Users/mindtable/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 /Users/mindtable/.codex/plugins/cache/openai-bundled/visualize/1.0.21/skills/visualize/scripts/render.py /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa-preview.html
```

Serve the visualization directory on loopback for the implementation session:

```bash
/Users/mindtable/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 -m http.server 41731 --bind 127.0.0.1 --directory /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506
```

The preview URL is `http://127.0.0.1:41731/interactive-mock-spa-preview.html`. Keep the server in its execution session until QA finishes.

Use the Browser skill's required bootstrap and complete documentation read. Then create one reusable background tab and frame locator:

```javascript
globalThis.mockSpaTab = await browser.tabs.new();
await mockSpaTab.goto("http://127.0.0.1:41731/interactive-mock-spa-preview.html");
globalThis.mockSpaUi = mockSpaTab.playwright.frameLocator("iframe");
await mockSpaUi.locator("#bbh-mock-spa").waitFor({ state: "visible" });
```

After every fragment update and preview render, call `await mockSpaTab.reload()` before collecting a fresh DOM snapshot or running locators.

---

### Task 1: Deterministic scenario shell

**Files:**

- Create: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html`

**Interfaces:**

- Produces DOM controls `#bbh-scenario`, `#bbh-reset`, `#bbh-step`, `#bbh-trace`, and product root `#bbh-product`.
- Produces functions `freshState(journeyKey)`, `selectJourney(journeyKey)`, `resetJourney()`, `advanceJourney()`, `setTrace(trace)`, and `render()`.
- Produces state properties `journeyKey`, `journeyStep`, `pendingRequest`, `dashboardRevision`, `repositoryRevisions`, `selectedPullRequestId`, `selectedActionItemId`, `drawer`, and `trace`; trace contains `method`, `operation`, `requestValue`, `resultType`, `detail`, and `status`.
- Tasks 2–4 add behavior without renaming these interfaces.

- [ ] **Step 1: Establish the failing file and fragment contract**

```bash
test -f /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html
```

Expected: FAIL because the fragment does not exist.

- [ ] **Step 2: Create the minimal semantic shell**

Use `apply_patch` to create this literal fragment structure:

```html
<div id="bbh-mock-spa">
  <section id="bbh-demo-controller" aria-labelledby="bbh-demo-title">
    <h2 id="bbh-demo-title">Demo scenario</h2>
    <label for="bbh-scenario">Journey</label>
    <select id="bbh-scenario">
      <option value="healthy-refresh">Healthy refresh</option>
      <option value="partial-refresh">Partial refresh</option>
      <option value="content-success">Content and acknowledgment success</option>
      <option value="content-unavailable">Content unavailable</option>
      <option value="newer-activity">Newer activity discovered</option>
      <option value="stale-acknowledgment">Stale acknowledgment</option>
    </select>
    <button id="bbh-reset" type="button">Reset journey</button>
    <button id="bbh-step" type="button">Advance response</button>
    <p id="bbh-trace" aria-live="polite">No request yet</p>
  </section>
  <main id="bbh-product" aria-label="Bitbucket Helper dashboard"></main>
</div>
```

Add the base model with exact names and shapes:

```javascript
const JOURNEY_KEYS = Object.freeze([
  "healthy-refresh", "partial-refresh", "content-success",
  "content-unavailable", "newer-activity", "stale-acknowledgment"
]);

const EMPTY_TRACE = Object.freeze({ method: null, operation: null, requestValue: null, resultType: null, detail: null, status: null });

function freshState(journeyKey) {
  if (!JOURNEY_KEYS.includes(journeyKey)) throw new Error(`Unknown journey: ${journeyKey}`);
  return {
    journeyKey,
    journeyStep: 0,
    pendingRequest: null,
    dashboardRevision: "dash_17",
    repositoryRevisions: { repo_payments: "repo_11", repo_store: "repo_7" },
    selectedPullRequestId: null,
    selectedActionItemId: null,
    drawer: { type: "closed" },
    trace: { ...EMPTY_TRACE }
  };
}

let state = freshState("healthy-refresh");

function selectJourney(journeyKey) { state = freshState(journeyKey); render(); }
function resetJourney() { state = freshState(state.journeyKey); render(); }
function advanceJourney() { state.journeyStep += 1; render(); }
function setTrace(trace) { state.trace = { ...EMPTY_TRACE, ...trace }; }
```

Resolve the root only through `document.getElementById("bbh-mock-spa")`, bind change/click listeners, keep the step button disabled while `pendingRequest` is `null`, and render the selected journey plus trace on every state change. Render trace fields in this order: method/operation, request value, result type, optional nested detail, then HTTP status.

- [ ] **Step 3: Render, serve, and verify the shell**

Use the shared setup. Then run this browser assertion block:

```javascript
var assertStrict = (await import("node:assert/strict")).default;
assertStrict.equal(await mockSpaUi.locator("#bbh-scenario option").count(), 6);
assertStrict.equal(await mockSpaUi.locator("#bbh-product #bbh-scenario").count(), 0);
assertStrict.equal(await mockSpaUi.locator("#bbh-reset").getAttribute("type"), "button");
assertStrict.equal(await mockSpaUi.locator("#bbh-step").getAttribute("type"), "button");
await mockSpaUi.locator("#bbh-scenario").selectOption("partial-refresh");
assertStrict.equal(await mockSpaUi.locator("#bbh-scenario").evaluate(element => element.value), "partial-refresh");
await mockSpaUi.locator("#bbh-reset").click();
assertStrict.equal(await mockSpaUi.locator("#bbh-scenario").evaluate(element => element.value), "partial-refresh");
```

Expected: all assertions pass.

- [ ] **Step 4: Record the disposable checkpoint**

Run `git status --short`. Confirm no visualization path appears and preserve unrelated repository changes exactly as found.

---

### Task 2: Repository dashboard and revision-aware refresh

**Files:**

- Modify: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html`

**Interfaces:**

- Consumes Task 1 state, rendering, and demo controls.
- Produces immutable `BASE_DASHBOARD_FIXTURE` containing `repo_payments`, `repo_store`, `pr_184`, `pr_179`, `pr_92`, `action_501`, and `action_502`.
- Produces `initializeJourney(nextState)`, `requestRefresh()`, `renderDashboard()`, `renderRepositoryGroup(repository)`, `renderPullRequestCard(pullRequest)`, `advanceHealthyRefresh()`, and `advancePartialRefresh()`.
- Produces selectors `#bbh-refresh`, `#bbh-inbox`, `[data-dashboard-revision]`, `[data-repository-id]`, `[data-repository-revision]`, `[data-pr-id]`, `[data-open-pr]`, `[data-pr-title]`, `[data-action-id]`, `[data-sync-problem]`, and `[data-snapshot-freshness]`.

- [ ] **Step 1: Run the failing dashboard assertion**

```javascript
assertStrict.equal(await mockSpaUi.locator("[data-dashboard-revision]").count(), 1);
```

Expected: FAIL against the Task 1 preview because the dashboard is absent.

- [ ] **Step 2: Add metadata-only fixtures and dashboard rendering**

Create `BASE_DASHBOARD_FIXTURE` with two repositories, three pull requests, and two body-free inbox items:

```javascript
const BASE_DASHBOARD_FIXTURE = Object.freeze({
  workspace: { slug: "mindtable", displayName: "Mindtable" },
  inbox: [
    { actionItemId: "action_501", activityVersion: "av_42", repositoryId: "repo_payments", pullRequestId: "pr_184", kind: "comment", actor: "Alex Chen", occurredAt: "3 minutes ago", acknowledgmentState: "actionable" },
    { actionItemId: "action_502", activityVersion: "av_18", repositoryId: "repo_store", pullRequestId: "pr_92", kind: "changesRequested", actor: "Sam Rivera", occurredAt: "8 minutes ago", acknowledgmentState: "actionable" }
  ],
  repositories: [
    {
      repositoryId: "repo_payments", slug: "payments-api", displayName: "Payments API", repositoryRevision: "repo_11",
      synchronization: { activity: "running", freshness: "fresh", age: "1 minute", problem: null },
      pullRequests: [
        { pullRequestId: "pr_184", number: 184, title: "Add retry budget", author: "Mira", updatedAt: "12 minutes ago", build: "successful", readiness: { type: "available", passed: 6, total: 7 }, actionableCount: 1, acknowledgedCount: 0 },
        { pullRequestId: "pr_179", number: 179, title: "Remove legacy token", author: "Noah", updatedAt: "2 hours ago", build: "successful", readiness: { type: "available", passed: 7, total: 7 }, actionableCount: 0, acknowledgedCount: 1 }
      ]
    },
    {
      repositoryId: "repo_store", slug: "web-store", displayName: "Web Store", repositoryRevision: "repo_7",
      synchronization: { activity: "queued", freshness: "fresh", age: "4 minutes", problem: null },
      pullRequests: [
        { pullRequestId: "pr_92", number: 92, title: "Harden CSRF validation", author: "Iris", updatedAt: "20 minutes ago", build: "inProgress", readiness: { type: "available", passed: 5, total: 7 }, actionableCount: 1, acknowledgedCount: 0 }
      ]
    }
  ]
});
```

Clone with `structuredClone(BASE_DASHBOARD_FIXTURE)` inside `freshState`; never mutate the frozen fixture. Render a compact header, complete actionable inbox, and stable repository feed. Render Bitbucket actions as inert `<button type="button">Open in Bitbucket</button>` controls.

The header's `#bbh-refresh` product action calls `requestRefresh()`. It registers a refresh in the trace, queues one `manualDashboardPoll` with the current revision, and resolves that poll as `snapshotUnchanged` without rewinding fixture revisions or journey history.

- [ ] **Step 3: Implement automatic refresh registration and deterministic polling**

Call `initializeJourney` from `freshState`. For refresh journeys, it sets a dashboard-poll pending request and this trace after the persisted snapshot is created:

```javascript
function initializeJourney(nextState) {
  if (["healthy-refresh", "partial-refresh"].includes(nextState.journeyKey)) {
    nextState.pendingRequest = { type: "dashboardPoll", afterRevision: nextState.dashboardRevision };
    nextState.trace = { method: "POST", operation: "/api/v1/refresh-runs", requestValue: "allConfiguredRepositories", status: 200, resultType: "refreshRunRegistered" };
  }
  return nextState;
}
```

Healthy progression is exact:

1. `snapshotUnchanged` keeps dashboard revision `dash_17` and both repository revisions unchanged.
2. Payments API completes, advances to `repo_12`, advances the dashboard to `dash_18`, and becomes idle while Web Store remains active.
3. Web Store completes, advances to `repo_8`, advances the dashboard to `dash_19`, and makes overall polling idle.

Partial progression is exact:

1. Payments API succeeds and advances independently.
2. Web Store returns `partialFailure`, keeps `pr_92` unchanged, sets freshness to `stale`, sets `staleSince` to `6 minutes ago`, and shows `3 details unavailable · retry in 2 minutes`.

Every trace shows `GET /api/v1/dashboard`, the request's `afterRevision`, HTTP `200`, and `snapshotChanged` or `snapshotUnchanged`.

After the unchanged response and the first changed response, queue the next `dashboardPoll` with the currently displayed revision so `#bbh-step` remains enabled. Clear `pendingRequest` only after the final repository response.

- [ ] **Step 4: Verify healthy refresh**

Render and reload, then run:

```javascript
await mockSpaUi.locator("#bbh-scenario").selectOption("healthy-refresh");
assertStrict.equal(await mockSpaUi.locator("[data-dashboard-revision]").textContent(), "dash_17");
assertStrict.match(await mockSpaUi.locator("#bbh-trace").textContent(), /POST.*refreshRunRegistered.*200/);
await mockSpaUi.locator("#bbh-step").click();
assertStrict.equal(await mockSpaUi.locator("[data-dashboard-revision]").textContent(), "dash_17");
assertStrict.match(await mockSpaUi.locator("#bbh-trace").textContent(), /afterRevision=dash_17.*snapshotUnchanged.*200/);
await mockSpaUi.locator("#bbh-step").click();
assertStrict.equal(await mockSpaUi.locator("[data-dashboard-revision]").textContent(), "dash_18");
assertStrict.equal(await mockSpaUi.locator('[data-repository-id="repo_payments"] [data-repository-revision]').textContent(), "repo_12");
assertStrict.equal(await mockSpaUi.locator('[data-repository-id="repo_store"] [data-repository-revision]').textContent(), "repo_7");
await mockSpaUi.locator("#bbh-step").click();
assertStrict.equal(await mockSpaUi.locator("[data-dashboard-revision]").textContent(), "dash_19");
assertStrict.equal(await mockSpaUi.locator('[data-repository-id="repo_store"] [data-repository-revision]').textContent(), "repo_8");
assertStrict.match(await mockSpaUi.locator("#bbh-overall-sync").textContent(), /Up to date/);
await mockSpaUi.locator("#bbh-reset").click();
assertStrict.equal(await mockSpaUi.locator("[data-dashboard-revision]").textContent(), "dash_17");
await mockSpaUi.locator("#bbh-refresh").click();
assertStrict.match(await mockSpaUi.locator("#bbh-trace").textContent(), /POST.*refreshRunRegistered.*200/);
await mockSpaUi.locator("#bbh-step").click();
assertStrict.match(await mockSpaUi.locator("#bbh-trace").textContent(), /snapshotUnchanged.*200/);
```

- [ ] **Step 5: Verify partial refresh preserves data**

```javascript
await mockSpaUi.locator("#bbh-scenario").selectOption("partial-refresh");
var originalPr92Title = await mockSpaUi.locator('[data-pr-id="pr_92"] [data-pr-title]').textContent();
await mockSpaUi.locator("#bbh-step").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.equal(await mockSpaUi.locator('[data-pr-id="pr_92"] [data-pr-title]').textContent(), originalPr92Title);
assertStrict.match(await mockSpaUi.locator('[data-repository-id="repo_store"] [data-sync-problem]').textContent(), /3 details unavailable/);
assertStrict.match(await mockSpaUi.locator('[data-repository-id="repo_store"] [data-snapshot-freshness]').textContent(), /Stale since/);
assertStrict.match(await mockSpaUi.locator("#bbh-trace").textContent(), /snapshotChanged.*partialFailure.*200/);
```

Expected: all refresh assertions pass.

For the partial response, set `resultType` to `snapshotChanged` and `detail` to `repo_store: partialFailure`; `partialFailure` is a nested repository outcome, not the dashboard result discriminator.

- [ ] **Step 6: Record the disposable checkpoint**

Run `git status --short`; no implementation path may appear.

---

### Task 3: Context-first drawer, live content, and exact-version acknowledgment

**Files:**

- Modify: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html`

**Interfaces:**

- Consumes Task 2 fixtures and dashboard rendering.
- Produces `LIVE_CONTENT_FIXTURES`, `openPullRequest(pullRequestId, actionItemId)`, `closeDrawer()`, `requestLiveContent()`, `requestAcknowledgment()`, `resolvePendingRequest()`, `resolveLiveContentRequest()`, `resolveAcknowledgmentRequest()`, and `resolveNewerActivityRefresh()`.
- Produces drawer states `closed`, `metadata`, `contentLoading`, `contentAvailable`, `contentUnavailable`, `newerActivityObserved`, `acknowledgmentPending`, `acknowledged`, and `staleActivityVersion`.
- Produces selectors `#bbh-drawer`, `#bbh-close-drawer`, `#bbh-load-content`, `#bbh-acknowledge`, `#bbh-live-content`, `#bbh-refresh-newer`, `[data-activity-version]`, and `[data-drawer-result]`.

- [ ] **Step 1: Run the failing drawer assertion**

```javascript
assertStrict.equal(await mockSpaUi.locator("#bbh-drawer").count(), 1);
```

Expected: FAIL against the Task 2 preview because the drawer is absent.

- [ ] **Step 2: Add isolated live-content fixtures and context-first rendering**

```javascript
const LIVE_CONTENT_FIXTURES = Object.freeze({
  "action_501:av_42": "Could we cap the retry window and add a metric for exhausted attempts?",
  "action_501:av_43": "Please cap the retry window at 30 seconds and emit a metric for exhausted attempts."
});
```

The drawer first renders repository/PR identity, `N of 7`, actionable count, readiness checks, ordered action metadata, actor, time, and exact displayed version. It renders no raw body until a `contentAvailable` result. Insert live text with `textContent`, never `innerHTML`, and remove it when the drawer closes, the selected item changes, or the journey resets.

Bind inbox items and PR cards to `openPullRequest`. If a PR has action items, select the newest; otherwise show PR context without fabricated activity.

Use `[data-open-pr]` on the PR-opening button and `[data-action-id]` on inbox-opening buttons. Both set `aria-expanded` and `aria-controls="bbh-drawer"` consistently.

- [ ] **Step 3: Queue exact-version requests**

```javascript
function requestLiveContent() {
  const version = currentActionItem().activityVersion;
  state.drawer = { type: "contentLoading", displayedVersion: version, liveText: null };
  state.pendingRequest = { type: "liveContent", actionItemId: state.selectedActionItemId, activityVersion: version };
  setTrace({ method: "GET", operation: `/api/v1/action-items/${state.selectedActionItemId}/content`, requestValue: `activityVersion=${version}` });
  render();
}

function requestAcknowledgment() {
  const version = state.drawer.displayedVersion;
  state.drawer = { ...state.drawer, type: "acknowledgmentPending" };
  state.pendingRequest = { type: "acknowledgment", actionItemId: state.selectedActionItemId, activityVersion: version };
  setTrace({ method: "PUT", operation: `/api/v1/action-items/${state.selectedActionItemId}/acknowledgment`, requestValue: `activityVersion=${version}` });
  render();
}
```

`advanceJourney()` dispatches every pending request through `resolvePendingRequest()` before using refresh-journey progression.

Use this exhaustive dispatch shape so Task 2 transitions keep working after the drawer is added:

```javascript
function resolvePendingRequest() {
  if (!state.pendingRequest) return;
  switch (state.pendingRequest.type) {
    case "dashboardPoll":
      state.journeyKey === "partial-refresh" ? advancePartialRefresh() : advanceHealthyRefresh();
      break;
    case "manualDashboardPoll":
      setTrace({ method: "GET", operation: "/api/v1/dashboard", requestValue: `afterRevision=${state.dashboardRevision}`, resultType: "snapshotUnchanged", status: 200 });
      state.pendingRequest = null;
      break;
    case "liveContent":
      resolveLiveContentRequest();
      break;
    case "acknowledgment":
      resolveAcknowledgmentRequest();
      break;
    case "repositoryRefresh":
      resolveNewerActivityRefresh();
      break;
    default:
      throw new Error(`Unknown pending request: ${state.pendingRequest.type}`);
  }
  render();
}
```

- [ ] **Step 4: Resolve the five required typed outcomes**

```javascript
const OUTCOMES = Object.freeze({
  contentSuccess: { status: 200, resultType: "contentAvailable" },
  contentUnavailable: { status: 200, resultType: "contentUnavailable" },
  newerActivity: { status: 200, resultType: "newerActivityObserved" },
  acknowledged: { status: 200, resultType: "acknowledged" },
  staleAcknowledgment: { status: 200, resultType: "staleActivityVersion" }
});
```

- Content success exposes only the requested version's fixture.
- Content unavailable preserves all metadata and shows `Temporarily unavailable · Retryable`.
- Newer activity reveals no body, reports `av_43`, and offers `#bbh-refresh-newer`; resolving that refresh advances the item and dashboard before another content request succeeds.
- Successful acknowledgment atomically removes `action_501`, changes `pr_184` from `1 actionable` to `0 actionable`, changes acknowledged count from `0` to `1`, and advances `dash_17` to `dash_18`.
- Stale acknowledgment preserves every dashboard value, reports requested `av_42`, current `av_43`, and `hasNewerActivity: true`, and never renders success copy.

- [ ] **Step 5: Verify content success and unavailable content**

Render and reload, then run:

```javascript
await mockSpaUi.locator("#bbh-scenario").selectOption("content-success");
await mockSpaUi.locator('[data-open-pr="pr_184"]').click();
assertStrict.equal(await mockSpaUi.locator("#bbh-drawer").getAttribute("data-open"), "true");
await mockSpaUi.locator("#bbh-close-drawer").click();
await mockSpaUi.locator('[data-action-id="action_501"]').click();
assertStrict.equal(await mockSpaUi.locator("[data-activity-version]").textContent(), "av_42");
assertStrict.equal(await mockSpaUi.locator("#bbh-live-content").count(), 0);
await mockSpaUi.locator("#bbh-load-content").click();
assertStrict.match(await mockSpaUi.locator("[data-drawer-result]").textContent(), /Loading live content/);
await mockSpaUi.locator("#bbh-step").click();
assertStrict.match(await mockSpaUi.locator("#bbh-live-content").textContent(), /cap the retry window/);
await mockSpaUi.locator("#bbh-close-drawer").click();
assertStrict.equal(await mockSpaUi.locator("#bbh-live-content").count(), 0);
await mockSpaUi.locator('[data-action-id="action_501"]').click();
await mockSpaUi.locator("#bbh-load-content").click();
await mockSpaUi.locator("#bbh-step").click();
await mockSpaUi.locator("#bbh-acknowledge").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.match(await mockSpaUi.locator("[data-drawer-result]").textContent(), /Acknowledged av_42/);
assertStrict.equal(await mockSpaUi.locator('#bbh-inbox [data-action-id="action_501"]').count(), 0);
assertStrict.match(await mockSpaUi.locator('[data-pr-id="pr_184"]').textContent(), /0 actionable/);

await mockSpaUi.locator("#bbh-scenario").selectOption("content-unavailable");
await mockSpaUi.locator('[data-action-id="action_501"]').click();
await mockSpaUi.locator("#bbh-load-content").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.match(await mockSpaUi.locator("[data-drawer-result]").textContent(), /Temporarily unavailable.*Retryable/);
assertStrict.equal(await mockSpaUi.locator("[data-activity-version]").textContent(), "av_42");
assertStrict.match(await mockSpaUi.locator("#bbh-trace").textContent(), /contentUnavailable.*200/);
```

- [ ] **Step 6: Verify newer activity and stale acknowledgment**

```javascript
await mockSpaUi.locator("#bbh-scenario").selectOption("newer-activity");
await mockSpaUi.locator('[data-action-id="action_501"]').click();
await mockSpaUi.locator("#bbh-load-content").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.match(await mockSpaUi.locator("[data-drawer-result]").textContent(), /Newer activity av_43 observed/);
assertStrict.equal(await mockSpaUi.locator("#bbh-live-content").count(), 0);
await mockSpaUi.locator("#bbh-refresh-newer").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.equal(await mockSpaUi.locator("[data-activity-version]").textContent(), "av_43");

await mockSpaUi.locator("#bbh-scenario").selectOption("stale-acknowledgment");
await mockSpaUi.locator('[data-action-id="action_501"]').click();
await mockSpaUi.locator("#bbh-load-content").click();
await mockSpaUi.locator("#bbh-step").click();
await mockSpaUi.locator("#bbh-acknowledge").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.match(await mockSpaUi.locator("[data-drawer-result]").textContent(), /Newer activity is available.*av_43/);
assertStrict.equal(await mockSpaUi.locator('#bbh-inbox [data-action-id="action_501"]').count(), 1);
assertStrict.match(await mockSpaUi.locator('[data-pr-id="pr_184"]').textContent(), /1 actionable/);
assertStrict.doesNotMatch(await mockSpaUi.locator("#bbh-drawer").textContent(), /Acknowledged av_42/);
assertStrict.match(await mockSpaUi.locator("#bbh-trace").textContent(), /staleActivityVersion.*200/);
```

Expected: all drawer assertions pass.

- [ ] **Step 7: Record the disposable checkpoint**

Run `git status --short`; no implementation path may appear.

---

### Task 4: Product styling, accessibility, responsive QA, and delivery

**Files:**

- Modify: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html`
- Generate: the four disposable light/dark QA files listed in File Structure.

**Interfaces:**

- Consumes the complete scenario engine, dashboard, and drawer from Tasks 1–3.
- Produces final classes `bbh-app-shell`, `bbh-inbox`, `bbh-repository`, `bbh-pr-card`, and `bbh-drawer-panel`.
- Produces the final inline visualization reference to `interactive-mock-spa.html` without wide mode.

- [ ] **Step 1: Run failing semantic and overflow checks**

After reading the Browser viewport capability documentation, set `360 × 900`, reload, and run:

```javascript
var viewportControl = await browser.capabilities.get("viewport");
await viewportControl.set({ width: 360, height: 900 });
await mockSpaTab.reload();
var narrowDimensions = await mockSpaUi.locator("#bbh-mock-spa").evaluate(element => ({ clientWidth: element.clientWidth, scrollWidth: element.scrollWidth }));
assertStrict.ok(narrowDimensions.scrollWidth <= narrowDimensions.clientWidth + 1, JSON.stringify(narrowDimensions));
assertStrict.equal(await mockSpaUi.locator('label[for="bbh-scenario"]').count(), 1);
assertStrict.equal(await mockSpaUi.locator("button:not([type])").count(), 0);
assertStrict.equal(await mockSpaUi.locator("[tabindex]").count(), 0);
```

Expected before final styling: the overflow assertion fails or the unfinished structure is visually unsuitable at 360 px.

- [ ] **Step 2: Apply scoped product-first styling**

Scope every rule below `#bbh-mock-spa`. Define product-specific theme values with `light-dark()` and weights `400` and `500` only:

```css
#bbh-mock-spa {
  color-scheme: light dark;
  --bbh-canvas: light-dark(rgb(244 247 252), rgb(16 21 30));
  --bbh-surface: light-dark(rgb(255 255 255), rgb(29 37 50));
  --bbh-raised: light-dark(rgb(249 251 255), rgb(36 45 60));
  --bbh-text: light-dark(rgb(27 36 52), rgb(235 239 247));
  --bbh-muted: light-dark(rgb(91 105 127), rgb(174 185 204));
  --bbh-line: light-dark(rgb(216 224 236), rgb(61 73 93));
  --bbh-accent: light-dark(rgb(44 82 190), rgb(128 154 255));
  --bbh-accent-text: light-dark(rgb(255 255 255), rgb(14 20 31));
  --bbh-warning: light-dark(rgb(121 78 16), rgb(242 195 109));
  --bbh-danger: light-dark(rgb(158 48 54), rgb(255 143 148));
  --bbh-success: light-dark(rgb(34 117 76), rgb(112 215 163));
  --bbh-font-size: 14px;
  color: var(--bbh-text);
  font: 400 var(--bbh-font-size)/1.45 ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}
```

Use an opaque app shell on `--bbh-canvas`, an opaque drawer on `--bbh-surface`, restrained repository grouping, and compact PR rows. The demo controller remains outside the shell. Use the dashboard/drawer two-column composition only from `760px` upward; below it, render the drawer as a full-width panel after the product header. Avoid fixed viewport heights, horizontal page overflow, nested card chrome, decorative KPI tiles, and whole-mock internal scrolling.

Do not override native focus styles. Add `aria-expanded` and `aria-controls="bbh-drawer"` to drawer-opening controls and `aria-live="polite"` to trace and drawer outcomes. Pair every synchronization, freshness, build, and acknowledgment color with explicit text.

- [ ] **Step 3: Verify behavior, keyboard reachability, and narrow layout**

Render and reload. Repeat the 360 px checks, then run:

```javascript
assertStrict.equal(await mockSpaUi.locator("#bbh-scenario").isEnabled(), true);
await mockSpaUi.locator("#bbh-scenario").press("Tab");
assertStrict.equal(await mockSpaUi.locator("#bbh-reset").evaluate(element => element === document.activeElement), true);
await mockSpaUi.locator("#bbh-scenario").selectOption("content-success");
assertStrict.equal(await mockSpaUi.locator("#bbh-product").getByText(/cap the retry window/).count(), 0);
await mockSpaUi.locator('[data-action-id="action_501"]').click();
await mockSpaUi.locator("#bbh-load-content").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.equal(await mockSpaUi.locator("#bbh-live-content").getByText(/cap the retry window/).count(), 1);
assertStrict.equal(await mockSpaUi.locator("#bbh-trace").getByText(/cap the retry window/).count(), 0);
```

Expected: all assertions pass. Repeat the complete Task 2 and Task 3 interaction blocks after styling.

- [ ] **Step 4: Generate forced light and dark QA previews**

Create mechanical QA copies outside the repository and prepend a root scheme override:

```bash
cp /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa-light-qa.html
cp /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa-dark-qa.html
perl -0pi -e 's/<div id="bbh-mock-spa">/<style>#bbh-mock-spa{color-scheme:light!important}<\/style>\n<div id="bbh-mock-spa">/' /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa-light-qa.html
perl -0pi -e 's/<div id="bbh-mock-spa">/<style>#bbh-mock-spa{color-scheme:dark!important}<\/style>\n<div id="bbh-mock-spa">/' /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa-dark-qa.html
```

Render both with `render.py` into their corresponding `*-preview.html` paths.

- [ ] **Step 5: Inspect 1,024 px, 736 px, and 360 px visuals**

Navigate the reusable tab to each loopback QA preview. Use the viewport capability at `1,024 × 1,000`, `736 × 1,000`, and `360 × 900`. At each required width, call `await mockSpaTab.screenshot({ fullPage: true })` and emit the returned image for inspection.

Confirm:

- no label, control, status, drawer content, or trace overlaps or clips;
- the demo controller is separate from the product shell;
- the actionable inbox leads the repository feed;
- repository groups remain complete and scannable;
- the drawer preserves PR context above live content;
- light and dark appearances both have readable contrast; and
- the 360 px layout stacks without horizontal overflow.

If any defect is visible, change only scoped CSS or rendering markup, regenerate all previews, reload the tab, rerun every interaction assertion, and inspect again. Reset the viewport capability when QA finishes.

- [ ] **Step 6: Run structural safety checks**

```bash
rg -n '<!doctype|<html|<head|<body|document\.currentScript|fetch\(|XMLHttpRequest|WebSocket|localStorage|sessionStorage|window\.location' /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html
```

Expected: no matches.

```bash
wc -c /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html
```

Expected: byte count below `1048576`.

Read the fragment back and confirm literal markup rather than escaped `\"` or `\n` sequences. Read browser console logs with `mockSpaTab.dev.logs({ levels: ["error"] })`; expected: no errors. Rerun the complete Task 1–3 assertions against the final source preview.

- [ ] **Step 7: Confirm repository isolation**

Run `git status --short`. No visualization implementation file may appear. Preserve the unrelated `docs/project-backlog.md` and `source/` state exactly as found.

- [ ] **Step 8: Clean up preview infrastructure**

Reset the Browser viewport capability, close the agent-created QA tab, and send `Ctrl-C` to the loopback preview server's execution session. Keep the source fragment and disposable QA files in the task-owned visualization directory; they are not project files.

- [ ] **Step 9: Present the verified mock in conversation**

Return this reference on its own line, without a Markdown file link and without wide mode:

```text
visualize{"path":"/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html","title":"Bitbucket Helper interactive mock SPA"}
```

Add at most one short sentence inviting the reviewer to select a journey and advance responses. Do not claim that the real SPA has been implemented.
