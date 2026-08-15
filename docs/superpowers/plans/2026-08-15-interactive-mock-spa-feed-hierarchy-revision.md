# Interactive Mock SPA Feed Hierarchy Revision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Revise the disposable Bitbucket Helper mock so needs attention collapses, repositories visibly parent their pull requests, and pull request #92 demonstrates a failed build in the dashboard and drawer.

**Architecture:** Extend the existing self-contained HTML fragment in place. One presentation-only state field owns the inbox disclosure, existing repository grouping drives semantic list markup and CSS tree rails, and one pull-request fixture feeds shared build rendering in the card and drawer; no scenario or API state machine is replaced.

**Tech Stack:** Semantic HTML fragment, root-scoped CSS, browser-native JavaScript, loopback preview wrapper, Chrome browser control.

## Global Constraints

- Follow `docs/superpowers/specs/2026-08-15-interactive-mock-spa-feed-hierarchy-revision-design.md` and the original `docs/superpowers/specs/2026-08-15-interactive-mock-spa-design.md`.
- Before editing, invoke `superpowers:test-driven-development` and reload `visualize:visualize`; before Chrome work, invoke `browser:control-in-app-browser`; before completion, invoke `superpowers:verification-before-completion`.
- Modify only `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html` for product behavior and styling.
- Create no Vue, Vite, TypeScript, Kotlin, Ktor, OpenAPI, `web/`, or other real SPA source.
- Keep the source as an HTML fragment with root `#bbh-mock-spa`, no document wrapper, and a byte size below `1048576`.
- Keep all state in memory; perform no `fetch`, XHR, WebSocket, navigation, cookie, `localStorage`, or `sessionStorage` operation.
- Keep exactly the existing six journeys and preserve their deterministic transitions and typed HTTP `200` business outcomes.
- Keep `#bbh-demo-controller` outside `#bbh-product` and do not add demo controls to product chrome.
- Needs attention starts expanded, survives ordinary rerenders in its selected state, and returns to expanded only after scenario selection or deterministic reset.
- Repository hierarchy is visual and structural only; do not introduce repository accordions or ARIA tree keyboard behavior.
- Pull request #92 alone displays `Build failed` and `2 failed checks`; readiness remains `5 of 7`.
- `View build` uses a native button with `aria-disabled="true"` and must not navigate, request, advance a journey, change the trace, or mutate product state.
- Preserve native tab order and focus indication; do not add `tabindex`.
- Pair build failure color with explicit text and keep light/dark layouts usable at approximately 1024 px, 736 px, and 360 px.
- Do not commit visualization or QA files. End each task with a verified disposable checkpoint and preserve unrelated repository changes.

---

## File Structure

### Disposable visualization files

- Modify: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html` — fixtures, state, rendering, interactions, and scoped styling.
- Reuse: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa-preview.html` — standalone functional preview whose iframe reads the source fragment directly.
- Reuse: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa-narrow-preview.html` — fixed 360 px functional preview.
- Regenerate for final QA: `interactive-mock-spa-light-qa.html` and `interactive-mock-spa-dark-qa.html` in the same directory — mechanical source copies with one forced color-scheme rule.

### Tracked project files

- Read only during implementation: `docs/superpowers/specs/2026-08-15-interactive-mock-spa-feed-hierarchy-revision-design.md`.
- Read only during implementation: `docs/superpowers/specs/2026-08-15-interactive-mock-spa-design.md`.
- Modify no tracked project file while changing the disposable mock.

## Shared Verification Setup

Start a loopback-only server and leave its execution session running until final QA finishes:

```bash
/Users/mindtable/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 -m http.server 41731 --bind 127.0.0.1 --directory /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506
```

The functional preview URL is `http://127.0.0.1:41731/interactive-mock-spa-preview.html`.

After reading the Browser skill, expose the Node `js` tool, initialize the bundled browser runtime, select Chrome because the reviewer requested Chrome, and read Chrome's complete documentation before interaction:

```javascript
if (globalThis.agent?.browsers == null) {
  const { setupBrowserRuntime } = await import("/Users/mindtable/.codex/plugins/cache/openai-bundled/browser/26.810.41047/scripts/browser-client.mjs");
  globalThis.agent = await setupBrowserRuntime();
}
if (globalThis.chrome == null) {
  globalThis.chrome = await agent.browsers.get("chrome");
  nodeRepl.write(await chrome.documentation());
}
globalThis.mockSpaTab = await chrome.tabs.new();
await mockSpaTab.goto("http://127.0.0.1:41731/interactive-mock-spa-preview.html");
globalThis.mockSpaUi = mockSpaTab.playwright.frameLocator("iframe");
await mockSpaUi.locator("#bbh-mock-spa").waitFor({ state: "visible" });
globalThis.assertStrict = (await import("node:assert/strict")).default;
```

Acquire the documented viewport capability only when responsive QA begins:

```javascript
globalThis.mockSpaViewport = await chrome.capabilities.get("viewport");
nodeRepl.write(await mockSpaViewport.documentation());
```

After each source edit, reload `mockSpaTab` before running assertions. The preview iframe points at the live fragment, so no render or wrapper regeneration is required for functional checks.

---

### Task 1: Needs-attention disclosure

**Files:**

- Modify: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html:238-296,689-705,1201-1242,1368-1382`

**Interfaces:**

- Consumes: existing `freshState(journeyKey)`, `renderDashboard()`, delegated root click handling, and dashboard inbox data.
- Produces: state property `inboxExpanded: boolean`, function `toggleInbox(): void`, and selectors `#bbh-inbox-toggle`, `#bbh-inbox-body`, `[data-inbox-count]`, and `.bbh-inbox-chevron`.

- [ ] **Step 1: Run the failing disclosure assertion**

```javascript
await mockSpaTab.reload();
assertStrict.equal(await mockSpaUi.locator("#bbh-inbox-toggle").count(), 1);
```

Expected: FAIL because the current inbox heading is not a disclosure button.

- [ ] **Step 2: Add presentation-only disclosure state**

Use `apply_patch` to add the exact field to the object created by `freshState` and the exact transition beside `resetJourney`:

```javascript
return initializeJourney({
  journeyKey,
  journeyStep: 0,
  pendingRequest: null,
  dashboardRevision: "dash_17",
  repositoryRevisions: { repo_payments: "repo_11", repo_store: "repo_7" },
  selectedPullRequestId: null,
  selectedActionItemId: null,
  inboxExpanded: true,
  drawer: { type: "closed", displayedVersion: null, liveText: null },
  trace: { ...EMPTY_TRACE },
  dashboard: structuredClone(BASE_DASHBOARD_FIXTURE)
});

function toggleInbox() {
  state.inboxExpanded = !state.inboxExpanded;
  render();
}
```

Do not write to `inboxExpanded` in drawer, refresh, polling, live-content, or acknowledgment transitions. Existing `selectJourney` and `resetJourney` already call `freshState`, so both restore `true`.

- [ ] **Step 3: Render the complete header as one semantic disclosure**

Replace the current `#bbh-inbox` contents with this exact structure:

```javascript
<section id="bbh-inbox" class="bbh-inbox" aria-labelledby="bbh-inbox-title">
  <h2 class="bbh-inbox-heading">
    <button id="bbh-inbox-toggle" class="bbh-inbox-toggle" type="button"
      aria-expanded="${state.inboxExpanded}"
      aria-controls="bbh-inbox-body">
      <span class="bbh-inbox-title-group">
        <span class="bbh-eyebrow">Needs attention</span>
        <span id="bbh-inbox-title">Actionable activity</span>
      </span>
      <span class="bbh-inbox-summary">
        <span data-inbox-count>${state.dashboard.inbox.length} open</span>
        <span class="bbh-inbox-chevron" aria-hidden="true"></span>
      </span>
    </button>
  </h2>
  <div id="bbh-inbox-body" ${state.inboxExpanded ? "" : "hidden"}>
    ${state.dashboard.inbox.length
      ? `<ul>${state.dashboard.inbox.map(renderInboxItem).join("")}</ul>`
      : `<p data-empty-inbox>You're all caught up.</p>`}
  </div>
</section>
```

Add the delegated transition before refresh, inbox-item, and pull-request handling:

```javascript
const inboxToggle = event.target.closest("#bbh-inbox-toggle");
if (inboxToggle) {
  toggleInbox();
  return;
}
```

- [ ] **Step 4: Add scoped disclosure styling**

Add these rules next to the existing inbox rules, retaining native focus outlines:

```css
#bbh-mock-spa .bbh-inbox-heading {
  margin-block-end: 0;
}

#bbh-mock-spa .bbh-inbox-toggle {
  align-items: center;
  background: transparent;
  border: 0;
  display: flex;
  justify-content: space-between;
  min-height: 0;
  padding: 0;
  text-align: start;
  width: 100%;
}

#bbh-mock-spa .bbh-inbox-toggle:hover:not(:disabled) {
  background: transparent;
  border-color: transparent;
}

#bbh-mock-spa .bbh-inbox-title-group {
  display: grid;
}

#bbh-mock-spa .bbh-inbox-summary {
  align-items: center;
  color: var(--bbh-muted);
  display: flex;
  font-size: 0.82rem;
  gap: 0.55rem;
}

#bbh-mock-spa .bbh-inbox-chevron {
  block-size: 0.48rem;
  border-block-end: 2px solid currentColor;
  border-inline-end: 2px solid currentColor;
  inline-size: 0.48rem;
  transform: rotate(45deg);
}

#bbh-mock-spa .bbh-inbox-toggle[aria-expanded="false"] .bbh-inbox-chevron {
  transform: rotate(-45deg);
}

#bbh-mock-spa #bbh-inbox-body {
  margin-block-start: 0.75rem;
}
```

- [ ] **Step 5: Verify state preservation, reset, and acknowledgment count**

Reload and run this full assertion block:

```javascript
var inboxToggle = mockSpaUi.locator("#bbh-inbox-toggle");
assertStrict.equal(await inboxToggle.getAttribute("aria-expanded"), "true");
assertStrict.equal(await mockSpaUi.locator("#bbh-inbox-body").isVisible(), true);
assertStrict.equal((await mockSpaUi.locator("[data-inbox-count]").textContent()).trim(), "2 open");

await inboxToggle.click();
assertStrict.equal(await inboxToggle.getAttribute("aria-expanded"), "false");
assertStrict.equal(await mockSpaUi.locator("#bbh-inbox-body").isVisible(), false);
assertStrict.equal((await mockSpaUi.locator("[data-inbox-count]").textContent()).trim(), "2 open");

await mockSpaUi.locator('[data-open-pr="pr_92"]').click();
assertStrict.equal(await inboxToggle.getAttribute("aria-expanded"), "false");
await mockSpaUi.locator("#bbh-close-drawer").click();
assertStrict.equal(await inboxToggle.getAttribute("aria-expanded"), "false");
await mockSpaUi.locator("#bbh-refresh").click();
assertStrict.equal(await inboxToggle.getAttribute("aria-expanded"), "false");
await mockSpaUi.locator("#bbh-step").click();
assertStrict.equal(await inboxToggle.getAttribute("aria-expanded"), "false");

await mockSpaUi.locator("#bbh-reset").click();
assertStrict.equal(await inboxToggle.getAttribute("aria-expanded"), "true");
await inboxToggle.click();
await mockSpaUi.locator("#bbh-scenario").selectOption("content-unavailable");
assertStrict.equal(await inboxToggle.getAttribute("aria-expanded"), "true");

await mockSpaUi.locator("#bbh-scenario").selectOption("content-success");
await mockSpaUi.locator('[data-action-id="action_501"]').click();
await mockSpaUi.locator("#bbh-load-content").click();
await mockSpaUi.locator("#bbh-step").click();
await inboxToggle.click();
await mockSpaUi.locator("#bbh-acknowledge").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.equal(await inboxToggle.getAttribute("aria-expanded"), "false");
assertStrict.equal((await mockSpaUi.locator("[data-inbox-count]").textContent()).trim(), "1 open");
assertStrict.equal(await mockSpaUi.locator("#bbh-inbox-body").isVisible(), false);
```

Expected: every assertion passes; ordinary renders preserve collapse and successful acknowledgment updates the hidden body's persistent header count.

- [ ] **Step 6: Record the disposable checkpoint**

Run `git status --short`. Confirm the visualization path is absent and the pre-existing `docs/project-backlog.md` and `source/` changes are untouched.

---

### Task 2: Repository-to-pull-request tree hierarchy

**Files:**

- Modify: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html:297-378,470-550,1061-1110`

**Interfaces:**

- Consumes: existing repository order, `renderRepositoryGroup(repository)`, and `renderPullRequestCard(pullRequest)`.
- Produces: native `.bbh-pr-list` lists, `.bbh-pr-branch` list items, `data-tree-parent`, and `data-tree-children`; keeps `data-pr-id` on each article and preserves every existing action selector.

- [ ] **Step 1: Run the failing hierarchy assertions**

```javascript
await mockSpaUi.locator("#bbh-scenario").selectOption("healthy-refresh");
assertStrict.equal(await mockSpaUi.locator("ul.bbh-pr-list").count(), 2);
assertStrict.equal(await mockSpaUi.locator(".bbh-pr-branch").count(), 3);
```

Expected: FAIL because repository children currently use unindented `div` containers and no branch elements.

- [ ] **Step 2: Wrap every pull request in semantic child-list markup**

Change `renderPullRequestCard` to return the exact list-item and article structure while retaining its existing content and action semantics:

```javascript
function renderPullRequestCard(pullRequest) {
  const isExpanded = state.drawer.type !== "closed" && state.selectedPullRequestId === pullRequest.pullRequestId;
  return `
    <li class="bbh-pr-branch">
      <article class="bbh-pr-card" data-pr-id="${escapeHtml(pullRequest.pullRequestId)}">
        <div class="bbh-pr-main">
          <p class="bbh-eyebrow">Pull request #${pullRequest.number}</p>
          <h4 data-pr-title>${escapeHtml(pullRequest.title)}</h4>
          <p>By ${escapeHtml(pullRequest.author)} &middot; Updated ${escapeHtml(pullRequest.updatedAt)}</p>
        </div>
        <div class="bbh-pr-health" aria-label="Pull request status">
          <span>${escapeHtml(buildLabel(pullRequest.build))}</span>
          <span>${pullRequest.readiness.passed} of ${pullRequest.readiness.total} checks</span>
          <span data-actionable-count>${pullRequest.actionableCount} actionable</span>
          <span data-acknowledged-count>${pullRequest.acknowledgedCount} acknowledged</span>
        </div>
        <div class="bbh-pr-actions">
          <button type="button" data-open-pr="${escapeHtml(pullRequest.pullRequestId)}" aria-expanded="${isExpanded}" aria-controls="bbh-drawer">Review context</button>
          <button type="button" aria-disabled="true">Open in Bitbucket</button>
        </div>
      </article>
    </li>`;
}
```

In `renderRepositoryGroup`, mark the parent header and replace the child container:

```javascript
<header class="bbh-repository-header" data-tree-parent>
  <div>
    <p class="bbh-eyebrow">${escapeHtml(repository.slug)}</p>
    <h3 id="${escapeHtml(repository.repositoryId)}-title">${escapeHtml(repository.displayName)}</h3>
  </div>
  <div class="bbh-repository-status">
    <span>${activity}</span>
    <span data-snapshot-freshness>${freshness}</span>
    <code data-repository-revision>${escapeHtml(repository.repositoryRevision)}</code>
  </div>
</header>
${problem}
<ul class="bbh-pr-list" data-tree-children>
  ${repository.pullRequests.map(renderPullRequestCard).join("")}
</ul>
```

- [ ] **Step 3: Draw the continuous rail, elbows, and final-child termination**

Replace the existing `.bbh-pr-list` margin rule and card final-child rule with:

```css
#bbh-mock-spa .bbh-pr-list {
  list-style: none;
  margin: 0.75rem 0 0;
  padding: 0 0 0 1.5rem;
}

#bbh-mock-spa .bbh-pr-branch {
  position: relative;
}

#bbh-mock-spa .bbh-pr-branch::before {
  border-inline-start: 1px solid var(--bbh-line);
  content: "";
  inset-block: -0.75rem;
  inset-inline-start: -1.1rem;
  position: absolute;
}

#bbh-mock-spa .bbh-pr-branch:last-child::before {
  block-size: 2rem;
  inset-block-end: auto;
}

#bbh-mock-spa .bbh-pr-branch::after {
  border-block-start: 1px solid var(--bbh-line);
  content: "";
  inline-size: 1.1rem;
  inset-block-start: 1.25rem;
  inset-inline-start: -1.1rem;
  position: absolute;
}

#bbh-mock-spa .bbh-pr-branch:last-child .bbh-pr-card {
  padding-block-end: 0;
}
```

Remove the old `.bbh-pr-card:last-child` rule because every article becomes the only child of its list item.

Inside the existing `@media (max-width: 759px)` block, add the narrower connector geometry:

```css
#bbh-mock-spa .bbh-pr-list {
  padding-inline-start: 1rem;
}

#bbh-mock-spa .bbh-pr-branch::before {
  inset-inline-start: -0.72rem;
}

#bbh-mock-spa .bbh-pr-branch::after {
  inline-size: 0.72rem;
  inset-inline-start: -0.72rem;
}
```

- [ ] **Step 4: Verify ownership, rail styles, and unchanged actions**

Reload and run:

```javascript
var hierarchy = await mockSpaUi.locator(".bbh-repository").evaluateAll(repositories =>
  repositories.map(repository => ({
    repositoryId: repository.dataset.repositoryId,
    pullRequestIds: [...repository.querySelectorAll(":scope > .bbh-pr-list > .bbh-pr-branch > [data-pr-id]")]
      .map(pullRequest => pullRequest.dataset.prId)
  }))
);
assertStrict.deepEqual(hierarchy, [
  { repositoryId: "repo_payments", pullRequestIds: ["pr_184", "pr_179"] },
  { repositoryId: "repo_store", pullRequestIds: ["pr_92"] }
]);
assertStrict.equal(await mockSpaUi.locator("[data-tree-parent]").count(), 2);
assertStrict.equal(await mockSpaUi.locator("[data-tree-children]").count(), 2);

var branchGeometry = await mockSpaUi.locator(".bbh-pr-branch").first().evaluate(branch => ({
  rail: getComputedStyle(branch, "::before").borderInlineStartStyle,
  elbow: getComputedStyle(branch, "::after").borderBlockStartStyle
}));
assertStrict.deepEqual(branchGeometry, { rail: "solid", elbow: "solid" });

var lastBranch = mockSpaUi.locator('[data-repository-id="repo_payments"] .bbh-pr-branch').last();
var lastRail = await lastBranch.evaluate(branch => ({
  height: getComputedStyle(branch, "::before").height,
  bottom: getComputedStyle(branch, "::before").bottom
}));
assertStrict.equal(lastRail.height, "28px");
assertStrict.equal(lastRail.bottom, "auto");

await mockSpaUi.locator('[data-open-pr="pr_179"]').click();
assertStrict.equal(await mockSpaUi.locator("#bbh-drawer").getAttribute("data-open"), "true");
assertStrict.match(await mockSpaUi.locator("#bbh-drawer-title").textContent(), /Remove legacy token/);
```

If Chrome reports the computed `2rem` height in device-adjusted pixels rather than `28px`, assert it equals twice the computed root font size instead; do not loosen the ownership or solid-rail assertions.

- [ ] **Step 5: Record the disposable checkpoint**

Run `git status --short`. Confirm no tracked implementation path appears.

---

### Task 3: Failed-build fixture and shared rendering

**Files:**

- Modify: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html:34-35,368-389,643-652,1027-1029,1061-1081,1151-1198,1368-1382`

**Interfaces:**

- Consumes: pull-request fixture objects, `buildLabel(build)`, card health rendering, drawer readiness rendering, and delegated clicks.
- Produces: `pr_92.build === "failed"`, `pr_92.failedCheckCount === 2`, function `renderBuildStatus(pullRequest, showControl): string`, and selectors `[data-build-status]`, `[data-failed-checks]`, and `[data-view-build]`.

- [ ] **Step 1: Run the failing failed-build assertion**

```javascript
await mockSpaUi.locator("#bbh-scenario").selectOption("healthy-refresh");
var pr92 = mockSpaUi.locator('[data-pr-id="pr_92"]');
assertStrict.match(await pr92.textContent(), /Build failed/);
assertStrict.match(await pr92.textContent(), /2 failed checks/);
assertStrict.equal(await pr92.locator("[data-view-build]").count(), 1);
```

Expected: FAIL because pull request #92 currently reports an in-progress build.

- [ ] **Step 2: Make the fixture explicit and extend the label mapping**

Change only the pull request #92 fixture fields:

```javascript
build: "failed",
failedCheckCount: 2,
readiness: { type: "available", passed: 5, total: 7 },
```

Replace `buildLabel` and add the shared renderer:

```javascript
function buildLabel(build) {
  if (build === "failed") return "Build failed";
  if (build === "inProgress") return "Build in progress";
  return "Build successful";
}

function renderBuildStatus(pullRequest, showControl = false) {
  const failed = pullRequest.build === "failed";
  return `
    <span data-build-status="${escapeHtml(pullRequest.build)}" class="${failed ? "bbh-build-failed" : ""}">${failed ? `<span class="bbh-build-failed-marker" aria-hidden="true">!</span>` : ""}${escapeHtml(buildLabel(pullRequest.build))}</span>
    ${failed ? `<span data-failed-checks class="bbh-build-failed">${pullRequest.failedCheckCount} failed checks</span>` : ""}
    ${failed && showControl ? `<button type="button" data-view-build aria-disabled="true">View build</button>` : ""}`;
}
```

- [ ] **Step 3: Consume the same renderer in card and drawer**

In `renderPullRequestCard`, replace the single build label span with:

```javascript
${renderBuildStatus(pullRequest, true)}
```

In `renderDrawer`, replace the build readiness list item with:

```javascript
<li data-drawer-build>Build &middot; ${renderBuildStatus(pullRequest)}</li>
```

This gives both surfaces the same status and failed-count source while keeping `View build` only on the dashboard card.

Add the inert-control guard before every state-changing delegated action:

```javascript
const viewBuildButton = event.target.closest("[data-view-build]");
if (viewBuildButton) {
  event.preventDefault();
  return;
}
```

- [ ] **Step 4: Style failure text and the inert control without color-only meaning**

Add next to `.bbh-pr-health` rules:

```css
#bbh-mock-spa .bbh-build-failed {
  color: var(--bbh-danger);
  font-weight: 500;
}

#bbh-mock-spa .bbh-build-failed-marker {
  align-items: center;
  block-size: 1rem;
  border: 1px solid currentColor;
  border-radius: 50%;
  display: inline-flex;
  font-size: 0.72rem;
  inline-size: 1rem;
  justify-content: center;
  margin-inline-end: 0.3rem;
}

#bbh-mock-spa [data-view-build] {
  background: transparent;
  border: 0;
  color: var(--bbh-danger);
  cursor: not-allowed;
  justify-self: start;
  min-height: 0;
  opacity: 0.72;
  padding: 0;
  text-decoration: underline;
}

#bbh-mock-spa [data-view-build]:hover:not(:disabled) {
  background: transparent;
  border-color: transparent;
}
```

Do not change readiness colors or derive readiness from build state.

- [ ] **Step 5: Verify exact copy, shared drawer data, and inertness**

Reload and run:

```javascript
await mockSpaUi.locator("#bbh-scenario").selectOption("healthy-refresh");
var pr92 = mockSpaUi.locator('[data-pr-id="pr_92"]');
assertStrict.equal((await pr92.locator('[data-build-status="failed"]').textContent()).trim(), "Build failed");
assertStrict.equal((await pr92.locator("[data-failed-checks]").textContent()).trim(), "2 failed checks");
assertStrict.match(await pr92.textContent(), /5 of 7 checks/);

var viewBuild = pr92.locator("[data-view-build]");
assertStrict.equal(await viewBuild.getAttribute("aria-disabled"), "true");
var traceBeforeBuildClick = await mockSpaUi.locator("#bbh-trace").textContent();
var drawerBeforeBuildClick = await mockSpaUi.locator("#bbh-drawer").getAttribute("data-open");
await viewBuild.click();
assertStrict.equal(await mockSpaUi.locator("#bbh-trace").textContent(), traceBeforeBuildClick);
assertStrict.equal(await mockSpaUi.locator("#bbh-drawer").getAttribute("data-open"), drawerBeforeBuildClick);

await pr92.locator('[data-open-pr="pr_92"]').click();
var drawerBuild = mockSpaUi.locator("[data-drawer-build]");
assertStrict.equal((await drawerBuild.locator('[data-build-status="failed"]').textContent()).trim(), "Build failed");
assertStrict.equal((await drawerBuild.locator("[data-failed-checks]").textContent()).trim(), "2 failed checks");
assertStrict.match(await mockSpaUi.locator(".bbh-drawer-readiness").textContent(), /5 of 7/);
assertStrict.equal(await mockSpaUi.locator("[data-view-build]").count(), 1);
```

Expected: the dashboard and drawer agree, readiness stays independent, and clicking `View build` has no observable side effect.

- [ ] **Step 6: Record the disposable checkpoint**

Run `git status --short`. Confirm only the pre-existing tracked/untracked user changes remain.

---

### Task 4: Full regression, responsive QA, and delivery

**Files:**

- Verify: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html`
- Regenerate: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa-light-qa.html`
- Regenerate: `/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa-dark-qa.html`

**Interfaces:**

- Consumes: the complete revised fragment and all established scenario selectors.
- Produces: verified functional behavior, light/dark responsive evidence, a clean browser console, and the final inline visualization reference.

- [ ] **Step 1: Run the six-journey regression suite**

Reload the functional preview and run:

```javascript
await mockSpaUi.locator("#bbh-scenario").selectOption("healthy-refresh");
await mockSpaUi.locator("#bbh-step").click();
assertStrict.match(await mockSpaUi.locator("#bbh-trace").textContent(), /snapshotUnchanged.*200/);
await mockSpaUi.locator("#bbh-step").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.equal((await mockSpaUi.locator("[data-dashboard-revision]").textContent()).trim(), "dash_19");

await mockSpaUi.locator("#bbh-scenario").selectOption("partial-refresh");
var originalPr92Title = await mockSpaUi.locator('[data-pr-id="pr_92"] [data-pr-title]').textContent();
await mockSpaUi.locator("#bbh-step").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.equal(await mockSpaUi.locator('[data-pr-id="pr_92"] [data-pr-title]').textContent(), originalPr92Title);
assertStrict.match(await mockSpaUi.locator('[data-repository-id="repo_store"] [data-sync-problem]').textContent(), /3 details unavailable/);
assertStrict.match(await mockSpaUi.locator("#bbh-trace").textContent(), /partialFailure.*200/);

await mockSpaUi.locator("#bbh-scenario").selectOption("content-success");
await mockSpaUi.locator('[data-action-id="action_501"]').click();
await mockSpaUi.locator("#bbh-load-content").click();
await mockSpaUi.locator("#bbh-step").click();
await mockSpaUi.locator("#bbh-acknowledge").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.equal(await mockSpaUi.locator('#bbh-inbox [data-action-id="action_501"]').count(), 0);
assertStrict.match(await mockSpaUi.locator('[data-pr-id="pr_184"]').textContent(), /0 actionable/);

await mockSpaUi.locator("#bbh-scenario").selectOption("content-unavailable");
await mockSpaUi.locator('[data-action-id="action_501"]').click();
await mockSpaUi.locator("#bbh-load-content").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.match(await mockSpaUi.locator("[data-drawer-result]").textContent(), /Temporarily unavailable.*Retryable/);
assertStrict.match(await mockSpaUi.locator("#bbh-trace").textContent(), /contentUnavailable.*200/);

await mockSpaUi.locator("#bbh-scenario").selectOption("newer-activity");
await mockSpaUi.locator('[data-action-id="action_501"]').click();
await mockSpaUi.locator("#bbh-load-content").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.match(await mockSpaUi.locator("[data-drawer-result]").textContent(), /Newer activity av_43 observed/);
assertStrict.equal(await mockSpaUi.locator("#bbh-live-content").count(), 0);
await mockSpaUi.locator("#bbh-refresh-newer").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.equal((await mockSpaUi.locator("[data-activity-version]").textContent()).trim(), "av_43");

await mockSpaUi.locator("#bbh-scenario").selectOption("stale-acknowledgment");
await mockSpaUi.locator('[data-action-id="action_501"]').click();
await mockSpaUi.locator("#bbh-load-content").click();
await mockSpaUi.locator("#bbh-step").click();
await mockSpaUi.locator("#bbh-acknowledge").click();
await mockSpaUi.locator("#bbh-step").click();
assertStrict.match(await mockSpaUi.locator("[data-drawer-result]").textContent(), /Newer activity is available.*av_43/);
assertStrict.equal(await mockSpaUi.locator('#bbh-inbox [data-action-id="action_501"]').count(), 1);
assertStrict.doesNotMatch(await mockSpaUi.locator("#bbh-drawer").textContent(), /Acknowledged av_42/);
assertStrict.match(await mockSpaUi.locator("#bbh-trace").textContent(), /staleActivityVersion.*200/);
```

Expected: all original journeys pass with their existing typed outcomes.

- [ ] **Step 2: Run structural and accessibility safety checks**

```bash
! rg -n '<!doctype|<html|<head|<body|document\.currentScript|fetch\(|XMLHttpRequest|WebSocket|localStorage|sessionStorage|document\.cookie|window\.location' /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html
wc -c /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html
```

Expected: the search prints nothing and succeeds through `!`; the byte count is below `1048576`.

Run these browser assertions:

```javascript
assertStrict.equal(await mockSpaUi.locator("button:not([type])").count(), 0);
assertStrict.equal(await mockSpaUi.locator("[tabindex]").count(), 0);
assertStrict.equal(await mockSpaUi.locator('#bbh-inbox-toggle[aria-controls="bbh-inbox-body"]').count(), 1);
assertStrict.equal(await mockSpaUi.locator('[data-view-build][aria-disabled="true"]').count(), 1);
assertStrict.equal(await mockSpaUi.locator("#bbh-product #bbh-demo-controller").count(), 0);
```

- [ ] **Step 3: Regenerate forced light and dark QA copies**

Mechanically copy the final source to both disposable QA paths:

```bash
cp /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa-light-qa.html
cp /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html /Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa-dark-qa.html
```

Use `apply_patch` to prepend exactly one scheme override to each copy:

```html
<style>#bbh-mock-spa{color-scheme:light!important}</style>
```

```html
<style>#bbh-mock-spa{color-scheme:dark!important}</style>
```

- [ ] **Step 4: Inspect both themes at all required widths**

For each theme URL, navigate directly to the QA fragment and inspect 1024, 736, and 360 px viewports:

```javascript
for (const theme of ["light", "dark"]) {
  await mockSpaTab.goto(`http://127.0.0.1:41731/interactive-mock-spa-${theme}-qa.html`);
  for (const width of [1024, 736, 360]) {
    await mockSpaViewport.set({ width, height: 1000 });
    await mockSpaTab.reload();
    var root = mockSpaTab.playwright.locator("#bbh-mock-spa");
    await root.waitFor({ state: "visible" });
    var dimensions = await root.evaluate(element => ({
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth
    }));
    assertStrict.ok(dimensions.scrollWidth <= dimensions.clientWidth + 1, `${theme} ${width}: ${JSON.stringify(dimensions)}`);
    await mockSpaTab.screenshot({ fullPage: true });
    await mockSpaTab.playwright.locator("#bbh-inbox-toggle").click();
    await mockSpaTab.playwright.locator('[data-open-pr="pr_92"]').click();
    assertStrict.equal(await mockSpaTab.playwright.locator("#bbh-inbox-toggle").getAttribute("aria-expanded"), "false");
    var drawerBuildText = (await mockSpaTab.playwright.locator("[data-drawer-build]").textContent()).replace(/\s+/g, " ");
    assertStrict.match(drawerBuildText, /Build failed 2 failed checks/);
    await mockSpaTab.screenshot({ fullPage: true });
  }
}
```

Inspect all twelve returned screenshots. Confirm the needs-attention count and chevron remain aligned when open and closed; repository rails connect each parent to the correct children and stop at the last child; PR #92's failure text and control fit without crowding; the drawer repeats the failed state; and no text, action, rail, or drawer clips or overlaps. If a defect appears, adjust only the scoped fragment, rerun Tasks 1–3 assertions and the six-journey suite, regenerate both QA copies, and repeat all twelve screenshots.

- [ ] **Step 5: Verify the final browser console and repository isolation**

Navigate back to the functional preview, recreate the iframe locator, and inspect errors:

```javascript
await mockSpaTab.goto("http://127.0.0.1:41731/interactive-mock-spa-preview.html");
globalThis.mockSpaUi = mockSpaTab.playwright.frameLocator("iframe");
await mockSpaUi.locator("#bbh-mock-spa").waitFor({ state: "visible" });
var browserErrors = await mockSpaTab.dev.logs({ levels: ["error"] });
assertStrict.equal(browserErrors.length, 0, JSON.stringify(browserErrors));
```

Run `git status --short`. Expected repository state remains:

```text
 M docs/project-backlog.md
?? source/
```

Do not stage, modify, or remove either existing user change.

- [ ] **Step 6: Clean up and deliver the revised mock**

Reset the temporary viewport override, close the agent-created Chrome tab, and send `Ctrl-C` to the loopback server session:

```javascript
await mockSpaViewport.reset();
await mockSpaTab.close();
```

Return the updated visualization reference on its own line and without wide mode:

```text
visualize{"path":"/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html","title":"Bitbucket Helper interactive mock SPA"}
```

State briefly that the disposable mock—not the real SPA—was revised, and repeat the Chrome opening command:

```bash
open -a "Google Chrome" "/Users/mindtable/.codex/visualizations/2026/08/15/01a004e9-ec90-7521-add1-d2a630ae2506/interactive-mock-spa.html"
```
