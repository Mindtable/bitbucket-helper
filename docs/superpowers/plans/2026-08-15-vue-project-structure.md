# Vue Project Structure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a reproducible Vue 3 workspace under `web/` that renders a tested, fixture-backed, repository-grouped pull-request dashboard without calling the Kotlin service.

**Architecture:** Application startup injects a narrow `DashboardSource` into a feature-oriented dashboard. A composable maps typed source outcomes into UI states; focused components render those states. The first source is an in-process fixture, while the future generated OpenAPI client remains outside this slice.

**Tech Stack:** Node.js 24.19.0, npm 11.17.0, create-vue 3.23.0, Vue 3.5.40, Vite 8.1.5, TypeScript 6.0.3, Vitest 4.1.10, Vue Test Utils 2.4.11, ESLint 10.7.0, Prettier 3.9.5, and Playwright 1.61.1 with Chromium.

## Global Constraints

- Work only in the new `web/` workspace plus the root README documentation named by this plan.
- Keep the untracked `source/` prototype byte-for-byte unchanged and unstaged.
- Preserve the pre-existing unstaged `docs/project-backlog.md` edit concerning `terminal-notifier`; never stage it with this work.
- Use create-vue 3.23.0 exactly; do not resolve a newer generator during execution.
- Keep direct npm dependency versions exact and commit `package-lock.json`.
- Use Vue Composition API with `<script setup>` and plain CSS.
- Do not add Vue Router, Pinia, MSW, a component library, or a CSS framework.
- Do not create handwritten API transport DTOs or an empty generated-client directory.
- Do not call Kotlin, Bitbucket, or any other network service from browser code.
- Treat `workspaceNotConfigured` as a typed business outcome, not an HTTP or request failure.
- Map only request, decoding, unknown-result, or unexpected failures to the failed UI state.
- Keep generated `web/dist/`, `node_modules/`, coverage, and Playwright artifacts untracked.
- This plan's generator and configuration work is approved scaffolding and introduces no product behavior. All product TypeScript and Vue code begins with a failing test.

---

## File map

### Generator and toolchain files

- `web/package.json`: exact dependency baseline and stable development commands.
- `web/package-lock.json`: reproducible full npm dependency graph.
- `web/.gitignore`: ignores dependencies, output, caches, and browser-test artifacts.
- `web/.editorconfig` and `web/.gitattributes`: generator-owned text conventions.
- `web/.prettierrc.json` and `web/.prettierignore`: formatting policy and generated-output exclusions.
- `web/eslint.config.ts`: Vue, TypeScript, Vitest, and Playwright lint configuration.
- `web/vite.config.ts`: Vue compilation and the `@` source alias.
- `web/vitest.config.ts`: jsdom unit/component tests and shared setup.
- `web/playwright.config.ts`: one deterministic Chromium project on loopback.
- `web/tsconfig*.json`, `web/e2e/tsconfig.json`, and `web/env.d.ts`: application, Node, Vitest, and browser-test type boundaries.
- `web/index.html`: the production HTML entrypoint; finalized only after the runtime entrypoint has a failing browser test.
- `web/tests/setup.ts`: deterministic DOM cleanup after each unit/component test.

### Dashboard core

- `web/src/features/dashboard/dashboard.models.ts`: browser-facing view models and discriminated states.
- `web/src/features/dashboard/dashboardSource.ts`: narrow asynchronous data-source interface.
- `web/src/features/dashboard/useDashboard.ts`: loading, result mapping, failure containment, and retry orchestration.
- `web/src/features/dashboard/useDashboard.spec.ts`: observable composable-state behavior.

### Dashboard presentation

- `web/src/features/dashboard/DashboardView.vue`: screen-state selection and dashboard header.
- `web/src/features/dashboard/DashboardView.spec.ts`: loading, grouping, business outcome, safe failure, and retry behavior.
- `web/src/features/dashboard/components/RepositoryGroup.vue`: repository identity, synchronization, freshness, and nested PR list.
- `web/src/features/dashboard/components/PullRequestCard.vue`: PR metadata, readiness, build, and actionable count.

### Runtime fixture and acceptance

- `web/src/features/dashboard/fixtures/fixtureDashboardSource.ts`: deterministic two-repository data source.
- `web/src/app/App.vue`: application frame and explicit dashboard-source forwarding.
- `web/src/main.ts`: root dependency composition and Vue mount.
- `web/src/assets/main.css`: functional responsive styling and visible non-color status treatment.
- `web/e2e/dashboard.spec.ts`: real-browser fixture rendering and no-service-boundary smoke test.
- `web/README.md`: truthful workspace commands and deferred integration boundary.
- `README.md`: root-level pointer to the new Vue workspace.

---

### Task 1: Reproducible Vue Toolchain

**Files:**
- Create: `web/package.json`
- Create: `web/package-lock.json`
- Create: `web/.editorconfig`
- Create: `web/.gitattributes`
- Create: `web/.gitignore`
- Create: `web/.prettierrc.json`
- Create: `web/.prettierignore`
- Create: `web/eslint.config.ts`
- Create: `web/vite.config.ts`
- Create: `web/vitest.config.ts`
- Create: `web/playwright.config.ts`
- Create: `web/tsconfig.json`
- Create: `web/tsconfig.app.json`
- Create: `web/tsconfig.node.json`
- Create: `web/tsconfig.vitest.json`
- Create: `web/e2e/tsconfig.json`
- Create: `web/env.d.ts`
- Create: `web/index.html`
- Create: `web/tests/setup.ts`

**Interfaces:**
- Consumes: Node.js 24.19.0, npm 11.17.0, and create-vue 3.23.0.
- Produces: `npm run dev`, `format:check`, `lint`, `type-check`, `test:unit`, `test:e2e`, `build`, and `check` commands used by every later task.

- [ ] **Step 1: Confirm repository safety before generation**

Run:

```bash
git status --short
test ! -e web
```

Expected: `web/` does not exist. Status includes the protected untracked `source/` directory and may include the pre-existing unstaged `docs/project-backlog.md` edit. Record those entries and do not stage them.

- [ ] **Step 2: Generate the approved bare toolchain**

Run from the repository root:

```bash
npm exec --yes create-vue@3.23.0 -- --typescript --vitest --playwright --eslint --prettier --bare web
```

Expected: the generator reports that it scaffolded `web/` without an interactive prompt.

Delete only these generator examples and unneeded additions:

```text
web/src/App.vue
web/src/main.ts
web/src/__tests__/App.spec.ts
web/e2e/vue.spec.ts
web/public/favicon.ico
web/README.md
web/.vscode/extensions.json
web/.vscode/settings.json
web/.oxlintrc.json
```

Remove empty example directories after their files are gone. Also remove the generated `vite-plugin-vue-devtools`, `oxlint`, and `eslint-plugin-oxlint` configuration and dependencies; this slice does not need them.

- [ ] **Step 3: Pin package metadata and stable commands**

Replace `web/package.json` with:

```json
{
  "name": "bitbucket-helper-web",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "packageManager": "npm@11.17.0",
  "engines": {
    "node": "^22.22.2 || ^24.15.0 || >=26.0.0"
  },
  "scripts": {
    "dev": "vite --host 127.0.0.1",
    "build": "run-s type-check build-only",
    "build-only": "vite build",
    "preview": "vite preview --host 127.0.0.1",
    "test:unit": "vitest run",
    "test:unit:watch": "vitest",
    "test:e2e": "playwright test --project=chromium",
    "type-check:app": "vue-tsc --build",
    "type-check:e2e": "tsc --noEmit -p e2e/tsconfig.json",
    "type-check": "run-s type-check:app type-check:e2e",
    "lint": "eslint . --cache --max-warnings 0",
    "lint:fix": "eslint . --fix --cache",
    "format": "prettier --write .",
    "format:check": "prettier --check .",
    "check": "run-s format:check lint type-check test:unit build-only"
  },
  "dependencies": {
    "vue": "3.5.40"
  },
  "devDependencies": {
    "@playwright/test": "1.61.1",
    "@tsconfig/node24": "24.0.4",
    "@types/jsdom": "28.0.3",
    "@types/node": "24.13.3",
    "@vitejs/plugin-vue": "6.0.8",
    "@vitest/eslint-plugin": "1.6.23",
    "@vue/eslint-config-typescript": "14.9.0",
    "@vue/test-utils": "2.4.11",
    "@vue/tsconfig": "0.9.1",
    "eslint": "10.7.0",
    "eslint-config-prettier": "10.1.8",
    "eslint-plugin-playwright": "2.10.5",
    "eslint-plugin-vue": "10.9.2",
    "jiti": "2.7.0",
    "jsdom": "29.1.1",
    "npm-run-all2": "9.0.2",
    "prettier": "3.9.5",
    "typescript": "6.0.3",
    "vite": "8.1.5",
    "vitest": "4.1.10",
    "vue-eslint-parser": "10.4.1",
    "vue-tsc": "3.3.7"
  }
}
```

Retain the pinned generator's `.editorconfig`, `.gitattributes`, `.gitignore`, `.prettierrc.json`, `tsconfig.json`, `tsconfig.app.json`, `tsconfig.node.json`, `e2e/tsconfig.json`, and `env.d.ts`. Confirm `.gitignore` contains `node_modules`, `dist`, `coverage`, `test-results`, `playwright-report`, `*.tsbuildinfo`, and `.eslintcache`.

Create `web/.prettierignore`:

```text
dist
coverage
node_modules
playwright-report
test-results
package-lock.json
```

- [ ] **Step 4: Configure Vite, Vitest, ESLint, and Playwright**

Replace `web/vite.config.ts` with:

```ts
import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
```

Replace `web/vitest.config.ts` with:

```ts
import { fileURLToPath, URL } from 'node:url'

import { configDefaults, defineConfig, mergeConfig } from 'vitest/config'

import viteConfig from './vite.config'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      exclude: [...configDefaults.exclude, 'e2e/**'],
      root: fileURLToPath(new URL('./', import.meta.url)),
      setupFiles: ['./tests/setup.ts'],
    },
  }),
)
```

Replace `web/eslint.config.ts` with:

```ts
import pluginVitest from '@vitest/eslint-plugin'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import skipFormatting from 'eslint-config-prettier/flat'
import { globalIgnores } from 'eslint/config'
import pluginPlaywright from 'eslint-plugin-playwright'
import pluginVue from 'eslint-plugin-vue'

export default defineConfigWithVueTs(
  {
    name: 'app/files-to-lint',
    files: ['**/*.{vue,ts,mts,tsx}'],
  },
  globalIgnores([
    '**/coverage/**',
    '**/dist/**',
    '**/node_modules/**',
    '**/playwright-report/**',
    '**/test-results/**',
  ]),
  ...pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
  {
    ...pluginPlaywright.configs['flat/recommended'],
    files: ['e2e/**/*.{test,spec}.{ts,tsx}'],
  },
  {
    ...pluginVitest.configs.recommended,
    files: ['src/**/*.{test,spec}.{ts,tsx}', 'tests/**/*.ts'],
  },
  skipFormatting,
)
```

Replace `web/playwright.config.ts` with:

```ts
import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: {
    timeout: 5_000,
  },
  forbidOnly: true,
  fullyParallel: false,
  reporter: 'line',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    headless: true,
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
      },
    },
  ],
  webServer: {
    command: 'npm run dev -- --port 5173 --strictPort',
    reuseExistingServer: false,
    url: 'http://127.0.0.1:5173',
  },
})
```

Change `web/tsconfig.vitest.json` so its `include` array is:

```json
["src/**/*.spec.ts", "tests/**/*.ts", "env.d.ts"]
```

Create `web/tests/setup.ts`:

```ts
import { afterEach } from 'vitest'

afterEach(() => {
  document.body.innerHTML = ''
})
```

- [ ] **Step 5: Install the exact dependency graph**

Run:

```bash
cd web
npm install
```

Expected: npm creates `package-lock.json` with no peer-dependency or engine errors.

Run:

```bash
npm ls --depth=0
```

Expected: every direct version equals `package.json` and npm reports no invalid or extraneous package.

- [ ] **Step 6: Verify the configuration-only scaffold**

Run:

```bash
npm run format
npm run format:check
npm run lint
npm run type-check
```

Expected: all four commands exit 0 without warnings. `type-check` runs both the Vue application build check and the E2E-only `tsc --noEmit -p e2e/tsconfig.json` check. Do not run `build` or `test:unit` yet; product entrypoints and the first test intentionally do not exist.

- [ ] **Step 7: Commit only the toolchain**

Run from the repository root:

```bash
git diff --check
git status --short
git add web
git commit -m "build: scaffold Vue test workspace"
```

Expected: the commit contains only `web/` generator and configuration files. The protected `source/` directory and `docs/project-backlog.md` remain unstaged.

---

### Task 2: Dashboard Load-State Core

**Files:**
- Create: `web/src/features/dashboard/useDashboard.spec.ts`
- Create: `web/src/features/dashboard/dashboard.models.ts`
- Create: `web/src/features/dashboard/dashboardSource.ts`
- Create: `web/src/features/dashboard/useDashboard.ts`

**Interfaces:**
- Consumes: Vue `shallowRef`/`readonly` and the Vitest environment from Task 1.
- Produces: `DashboardViewModel`, `RepositoryGroupModel`, `PullRequestSummary`, `DashboardSourceResult`, `DashboardSource.load(): Promise<DashboardSourceResult>`, and `useDashboard(source)`.

- [ ] **Step 1: Write the failing composable-state tests**

Create `web/src/features/dashboard/useDashboard.spec.ts`:

```ts
import { flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { DashboardViewModel } from './dashboard.models'
import type { DashboardSource, DashboardSourceResult } from './dashboardSource'
import { useDashboard } from './useDashboard'

const dashboard: DashboardViewModel = {
  workspaceDisplayName: 'Acme Engineering',
  generatedAt: '2026-08-15T10:00:00Z',
  repositoryGroups: [],
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })

  return { promise, resolve }
}

describe('useDashboard', () => {
  it('publishes a dashboard only after the source resolves', async () => {
    const pending = deferred<DashboardSourceResult>()
    const source: DashboardSource = {
      load: () => pending.promise,
    }

    const { state } = useDashboard(source)

    expect(state.value.type).toBe('loading')

    pending.resolve({ type: 'dashboardAvailable', dashboard })
    await flushPromises()

    expect(state.value.type).toBe('ready')
    if (state.value.type === 'ready') {
      expect(state.value.dashboard.workspaceDisplayName).toBe('Acme Engineering')
    }
  })

  it('keeps workspace-not-configured as an expected business state', async () => {
    const source: DashboardSource = {
      load: () =>
        Promise.resolve({
          type: 'workspaceNotConfigured',
          setupCommand: 'bitbucket-helper workspace configure',
        }),
    }

    const { state } = useDashboard(source)
    await flushPromises()

    expect(state.value).toEqual({
      type: 'workspaceNotConfigured',
      setupCommand: 'bitbucket-helper workspace configure',
    })
  })

  it('does not expose rejection details in the failed state', async () => {
    const source: DashboardSource = {
      load: () => Promise.reject(new Error('credential=do-not-display')),
    }

    const { state } = useDashboard(source)
    await flushPromises()

    expect(state.value).toEqual({ type: 'failed' })
    expect(JSON.stringify(state.value)).not.toContain('do-not-display')
  })

  it('reloads after a failure and can recover', async () => {
    let firstAttempt = true
    const source: DashboardSource = {
      load: () => {
        if (firstAttempt) {
          firstAttempt = false
          return Promise.reject(new Error('temporary failure'))
        }
        return Promise.resolve({ type: 'dashboardAvailable', dashboard })
      },
    }

    const { reload, state } = useDashboard(source)
    await flushPromises()
    expect(state.value.type).toBe('failed')

    const reloading = reload()
    expect(state.value.type).toBe('loading')
    await reloading

    expect(state.value.type).toBe('ready')
    if (state.value.type === 'ready') {
      expect(state.value.dashboard.workspaceDisplayName).toBe('Acme Engineering')
    }
  })
})
```

- [ ] **Step 2: Run the focused test to verify RED**

Run:

```bash
cd web
npm run test:unit -- src/features/dashboard/useDashboard.spec.ts
```

Expected: FAIL because `dashboard.models`, `dashboardSource`, and `useDashboard` do not exist. This is the intended missing-production-code failure.

- [ ] **Step 3: Add the minimal browser-facing models and source**

Create `web/src/features/dashboard/dashboard.models.ts`:

```ts
export interface DashboardViewModel {
  workspaceDisplayName: string
  generatedAt: string
  repositoryGroups: readonly RepositoryGroupModel[]
}

export interface RepositoryGroupModel {
  repositoryId: string
  displayName: string
  webUrl: string
  synchronization: SynchronizationState
  freshness: FreshnessState
  pullRequests: readonly PullRequestSummary[]
}

export type SynchronizationState =
  | { type: 'idle' }
  | { type: 'queued' }
  | { type: 'running' }

export type FreshnessState =
  | { type: 'neverSynchronized' }
  | { type: 'fresh'; ageDescription: string }
  | { type: 'stale'; ageDescription: string; staleSince: string }

export interface PullRequestSummary {
  pullRequestId: string
  displayNumber: number
  title: string
  authorDisplayName: string
  updatedAt: string
  webUrl: string
  readiness: ReadinessState
  buildState: BuildState
  actionableItemCount: number
}

export type ReadinessState =
  | { type: 'available'; passed: number; total: 7 }
  | { type: 'unavailable'; reason: string }

export type BuildState =
  | { type: 'successful' }
  | { type: 'failed' }
  | { type: 'inProgress' }
  | { type: 'unavailable'; reason: string }
```

Create `web/src/features/dashboard/dashboardSource.ts`:

```ts
import type { DashboardViewModel } from './dashboard.models'

export interface DashboardSource {
  load(): Promise<DashboardSourceResult>
}

export type DashboardSourceResult =
  | { type: 'dashboardAvailable'; dashboard: DashboardViewModel }
  | { type: 'workspaceNotConfigured'; setupCommand: string }
```

- [ ] **Step 4: Implement the minimal load-state orchestration**

Create `web/src/features/dashboard/useDashboard.ts`:

```ts
import { readonly, shallowRef } from 'vue'

import type { DashboardViewModel } from './dashboard.models'
import type { DashboardSource } from './dashboardSource'

export type DashboardUiState =
  | { type: 'loading' }
  | { type: 'ready'; dashboard: DashboardViewModel }
  | { type: 'workspaceNotConfigured'; setupCommand: string }
  | { type: 'failed' }

export function useDashboard(source: DashboardSource) {
  const state = shallowRef<DashboardUiState>({ type: 'loading' })

  const reload = async (): Promise<void> => {
    state.value = { type: 'loading' }

    try {
      const result = await source.load()
      state.value =
        result.type === 'dashboardAvailable'
          ? { type: 'ready', dashboard: result.dashboard }
          : {
              type: 'workspaceNotConfigured',
              setupCommand: result.setupCommand,
            }
    } catch {
      state.value = { type: 'failed' }
    }
  }

  void reload()

  return {
    state: readonly(state),
    reload,
  }
}
```

- [ ] **Step 5: Verify GREEN and the surrounding checks**

Run:

```bash
cd web
npm run test:unit -- src/features/dashboard/useDashboard.spec.ts
npm run type-check
npm run lint
```

Expected: all four `useDashboard` tests pass; type-check and lint exit 0 without warnings.

- [ ] **Step 6: Mutation-check and commit**

Mentally verify that the tests fail if loading is skipped, `workspaceNotConfigured` is thrown, rejection details are retained, or reload does not invoke the source again.

Run from the repository root:

```bash
git diff --check
git add web/src/features/dashboard/dashboard.models.ts web/src/features/dashboard/dashboardSource.ts web/src/features/dashboard/useDashboard.ts web/src/features/dashboard/useDashboard.spec.ts
git commit -m "feat: add dashboard load-state boundary"
```

Expected: only the four Task 2 files are committed.

---

### Task 3: Repository-Grouped Dashboard Presentation

**Files:**
- Create: `web/src/features/dashboard/DashboardView.spec.ts`
- Create: `web/src/features/dashboard/DashboardView.vue`
- Create: `web/src/features/dashboard/components/RepositoryGroup.vue`
- Create: `web/src/features/dashboard/components/PullRequestCard.vue`

**Interfaces:**
- Consumes: `DashboardSource` and all browser-facing models from Task 2.
- Produces: `DashboardView` with a required `source` prop, `RepositoryGroup` with a required `repository` prop, and `PullRequestCard` with a required `pullRequest` prop.

- [ ] **Step 1: Write failing component behavior tests**

Create `web/src/features/dashboard/DashboardView.spec.ts`:

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { DashboardViewModel } from './dashboard.models'
import type { DashboardSource, DashboardSourceResult } from './dashboardSource'
import DashboardView from './DashboardView.vue'

const groupedDashboard: DashboardViewModel = {
  workspaceDisplayName: 'Acme Engineering',
  generatedAt: '2026-08-15T10:00:00Z',
  repositoryGroups: [
    {
      repositoryId: 'repo_api',
      displayName: 'Payments API',
      webUrl: 'https://bitbucket.org/acme/payments-api',
      synchronization: { type: 'idle' },
      freshness: { type: 'fresh', ageDescription: '2 minutes ago' },
      pullRequests: [
        {
          pullRequestId: 'pr_17',
          displayNumber: 17,
          title: 'Keep dashboard revisions opaque',
          authorDisplayName: 'Ari',
          updatedAt: '2026-08-15T09:58:00Z',
          webUrl: 'https://bitbucket.org/acme/payments-api/pull-requests/17',
          readiness: { type: 'available', passed: 5, total: 7 },
          buildState: { type: 'successful' },
          actionableItemCount: 2,
        },
      ],
    },
    {
      repositoryId: 'repo_web',
      displayName: 'Developer Portal',
      webUrl: 'https://bitbucket.org/acme/developer-portal',
      synchronization: { type: 'running' },
      freshness: {
        type: 'stale',
        ageDescription: '18 minutes ago',
        staleSince: '2026-08-15T09:50:00Z',
      },
      pullRequests: [
        {
          pullRequestId: 'pr_23',
          displayNumber: 23,
          title: 'Surface stale acknowledgment',
          authorDisplayName: 'Morgan',
          updatedAt: '2026-08-15T09:45:00Z',
          webUrl: 'https://bitbucket.org/acme/developer-portal/pull-requests/23',
          readiness: { type: 'available', passed: 4, total: 7 },
          buildState: { type: 'inProgress' },
          actionableItemCount: 1,
        },
      ],
    },
  ],
}

function sourceReturning(result: DashboardSourceResult): DashboardSource {
  return {
    load: () => Promise.resolve(result),
  }
}

describe('DashboardView', () => {
  it('names each repository link and announces that it opens in a new tab', async () => {
    const wrapper = mount(DashboardView, {
      props: {
        source: sourceReturning({
          type: 'dashboardAvailable',
          dashboard: groupedDashboard,
        }),
      },
    })

    await flushPromises()

    expect(
      wrapper
        .get('a[href="https://bitbucket.org/acme/payments-api"]')
        .attributes('aria-label'),
    ).toBe('Open Payments API repository in a new tab')
  })

  it('renders pull requests beneath their owning repositories', async () => {
    const wrapper = mount(DashboardView, {
      props: {
        source: sourceReturning({
          type: 'dashboardAvailable',
          dashboard: groupedDashboard,
        }),
      },
    })

    expect(wrapper.get('[role="status"]').text()).toContain('Loading')
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('Pull requests')
    expect(wrapper.text()).toContain('Acme Engineering')

    const repositorySections = wrapper.findAll('section.repository-group')
    expect(repositorySections).toHaveLength(2)
    const paymentsSection = wrapper.get('[aria-labelledby="repository-repo_api"]')
    const portalSection = wrapper.get('[aria-labelledby="repository-repo_web"]')
    expect(paymentsSection.text()).toContain('Payments API')
    expect(paymentsSection.text()).toContain('#17')
    expect(paymentsSection.text()).not.toContain('#23')
    expect(portalSection.text()).toContain('Developer Portal')
    expect(portalSection.text()).toContain('#23')
    expect(portalSection.text()).not.toContain('#17')

    expect(wrapper.text()).toContain('5 of 7 checks')
    expect(wrapper.text()).toContain('Build successful')
    expect(wrapper.text()).toContain('Build in progress')
    expect(wrapper.text()).toContain('2 actionable items')
    expect(wrapper.text()).toContain('1 actionable item')
    expect(wrapper.text()).toContain('Synchronization idle')
    expect(wrapper.text()).toContain('Synchronization running')
    expect(wrapper.text()).toContain('Fresh · 2 minutes ago')
    expect(wrapper.text()).toContain('Stale · 18 minutes ago')

    const pullRequestLink = wrapper.get(
      'a[href="https://bitbucket.org/acme/payments-api/pull-requests/17"]',
    )
    expect(pullRequestLink.attributes('rel')).toContain('noopener')
  })

  it('renders unavailable, failed, queued, and empty states explicitly', async () => {
    const edgeStateDashboard: DashboardViewModel = {
      workspaceDisplayName: 'Acme Engineering',
      generatedAt: '2026-08-15T10:00:00Z',
      repositoryGroups: [
        {
          repositoryId: 'repo_edge',
          displayName: 'Edge Cases',
          webUrl: 'https://bitbucket.org/acme/edge-cases',
          synchronization: { type: 'queued' },
          freshness: { type: 'neverSynchronized' },
          pullRequests: [
            {
              pullRequestId: 'pr_unavailable',
              displayNumber: 31,
              title: 'Keep unavailable states explicit',
              authorDisplayName: 'Sam',
              updatedAt: '2026-08-15T09:40:00Z',
              webUrl: 'https://bitbucket.org/acme/edge-cases/pull-requests/31',
              readiness: { type: 'unavailable', reason: 'Malformed upstream input' },
              buildState: { type: 'unavailable', reason: 'No build observed' },
              actionableItemCount: 0,
            },
            {
              pullRequestId: 'pr_failed',
              displayNumber: 32,
              title: 'Report a failed build',
              authorDisplayName: 'Lee',
              updatedAt: '2026-08-15T09:35:00Z',
              webUrl: 'https://bitbucket.org/acme/edge-cases/pull-requests/32',
              readiness: { type: 'available', passed: 3, total: 7 },
              buildState: { type: 'failed' },
              actionableItemCount: 1,
            },
          ],
        },
        {
          repositoryId: 'repo_empty',
          displayName: 'No Open Work',
          webUrl: 'https://bitbucket.org/acme/no-open-work',
          synchronization: { type: 'idle' },
          freshness: { type: 'fresh', ageDescription: '1 minute ago' },
          pullRequests: [],
        },
      ],
    }
    const wrapper = mount(DashboardView, {
      props: {
        source: sourceReturning({
          type: 'dashboardAvailable',
          dashboard: edgeStateDashboard,
        }),
      },
    })

    await flushPromises()

    expect(wrapper.text()).toContain('Synchronization queued')
    expect(wrapper.text()).toContain('Never synchronized')
    expect(wrapper.text()).toContain('Readiness unavailable: Malformed upstream input')
    expect(wrapper.text()).toContain('Build unavailable: No build observed')
    expect(wrapper.text()).toContain('Build failed')
    expect(wrapper.text()).toContain('0 actionable items')
    expect(wrapper.text()).toContain('No open pull requests.')
  })

  it('renders workspace setup as a normal business outcome', async () => {
    const wrapper = mount(DashboardView, {
      props: {
        source: sourceReturning({
          type: 'workspaceNotConfigured',
          setupCommand: 'bitbucket-helper workspace configure',
        }),
      },
    })

    await flushPromises()

    expect(wrapper.text()).toContain('Workspace not configured')
    expect(wrapper.get('code').text()).toBe('bitbucket-helper workspace configure')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('hides failure details and retries into a ready dashboard', async () => {
    let firstAttempt = true
    const source: DashboardSource = {
      load: () => {
        if (firstAttempt) {
          firstAttempt = false
          return Promise.reject(new Error('credential=do-not-display'))
        }
        return Promise.resolve({
          type: 'dashboardAvailable',
          dashboard: groupedDashboard,
        })
      },
    }
    const wrapper = mount(DashboardView, {
      props: { source },
    })

    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('Dashboard unavailable')
    expect(wrapper.text()).not.toContain('do-not-display')

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Acme Engineering')
  })
})
```

- [ ] **Step 2: Run the focused component test to verify RED**

Run:

```bash
cd web
npm run test:unit -- src/features/dashboard/DashboardView.spec.ts
```

Expected: FAIL because `DashboardView.vue` does not exist.

- [ ] **Step 3: Implement the dashboard screen**

Create `web/src/features/dashboard/DashboardView.vue`:

```vue
<script setup lang="ts">
import RepositoryGroup from './components/RepositoryGroup.vue'
import type { DashboardSource } from './dashboardSource'
import { useDashboard } from './useDashboard'

const props = defineProps<{
  source: DashboardSource
}>()

const { reload, state } = useDashboard(props.source)
</script>

<template>
  <main class="dashboard-shell">
    <header class="dashboard-header">
      <p class="eyebrow">Bitbucket Helper</p>
      <h1>Pull requests</h1>
    </header>

    <p v-if="state.type === 'loading'" class="state-panel" role="status" aria-live="polite">
      Loading dashboard…
    </p>

    <section
      v-else-if="state.type === 'ready'"
      class="dashboard-content"
      aria-labelledby="workspace-heading"
    >
      <header class="workspace-header">
        <div>
          <p class="eyebrow">Workspace</p>
          <h2 id="workspace-heading">{{ state.dashboard.workspaceDisplayName }}</h2>
        </div>
        <p class="snapshot-time">
          Snapshot
          <time :datetime="state.dashboard.generatedAt">{{ state.dashboard.generatedAt }}</time>
        </p>
      </header>

      <div class="repository-list">
        <RepositoryGroup
          v-for="repository in state.dashboard.repositoryGroups"
          :key="repository.repositoryId"
          :repository="repository"
        />
      </div>
    </section>

    <section
      v-else-if="state.type === 'workspaceNotConfigured'"
      class="state-panel"
      aria-labelledby="workspace-setup-heading"
    >
      <h2 id="workspace-setup-heading">Workspace not configured</h2>
      <p>Configure the workspace from the product CLI:</p>
      <code>{{ state.setupCommand }}</code>
    </section>

    <section v-else class="state-panel" role="alert" aria-labelledby="failure-heading">
      <h2 id="failure-heading">Dashboard unavailable</h2>
      <p>Bitbucket Helper could not load the dashboard.</p>
      <button type="button" @click="reload">Try again</button>
    </section>
  </main>
</template>
```

- [ ] **Step 4: Implement repository and pull-request presentation**

Create `web/src/features/dashboard/components/RepositoryGroup.vue`:

```vue
<script setup lang="ts">
import { computed } from 'vue'

import type { RepositoryGroupModel } from '../dashboard.models'
import PullRequestCard from './PullRequestCard.vue'

const props = defineProps<{
  repository: RepositoryGroupModel
}>()

const headingId = computed(() => 'repository-' + props.repository.repositoryId)

function assertNever(state: never): never {
  throw new Error('Unexpected repository state: ' + JSON.stringify(state))
}

const synchronizationLabel = computed(() => {
  const synchronization = props.repository.synchronization
  switch (synchronization.type) {
    case 'idle':
      return 'Synchronization idle'
    case 'queued':
      return 'Synchronization queued'
    case 'running':
      return 'Synchronization running'
  }
  return assertNever(synchronization)
})

const freshnessLabel = computed(() => {
  const freshness = props.repository.freshness
  switch (freshness.type) {
    case 'neverSynchronized':
      return 'Never synchronized'
    case 'fresh':
      return 'Fresh · ' + freshness.ageDescription
    case 'stale':
      return 'Stale · ' + freshness.ageDescription
  }
  return assertNever(freshness)
})
</script>

<template>
  <section class="repository-group" :aria-labelledby="headingId">
    <header class="repository-header">
      <div>
        <p class="eyebrow">Repository</p>
        <h3 :id="headingId">{{ repository.displayName }}</h3>
      </div>
      <a
        :href="repository.webUrl"
        :aria-label="'Open ' + repository.displayName + ' repository in a new tab'"
        target="_blank"
        rel="noopener noreferrer"
      >
        Open repository
      </a>
    </header>

    <dl class="repository-status">
      <div>
        <dt>Synchronization</dt>
        <dd>{{ synchronizationLabel }}</dd>
      </div>
      <div>
        <dt>Freshness</dt>
        <dd>{{ freshnessLabel }}</dd>
      </div>
    </dl>

    <p v-if="repository.pullRequests.length === 0" class="empty-state">
      No open pull requests.
    </p>
    <div v-else class="pull-request-list">
      <PullRequestCard
        v-for="pullRequest in repository.pullRequests"
        :key="pullRequest.pullRequestId"
        :pull-request="pullRequest"
      />
    </div>
  </section>
</template>
```

Create `web/src/features/dashboard/components/PullRequestCard.vue`:

```vue
<script setup lang="ts">
import { computed } from 'vue'

import type { PullRequestSummary } from '../dashboard.models'

const props = defineProps<{
  pullRequest: PullRequestSummary
}>()

function assertNever(state: never): never {
  throw new Error('Unexpected build state: ' + JSON.stringify(state))
}

const readinessLabel = computed(() =>
  props.pullRequest.readiness.type === 'available'
    ? props.pullRequest.readiness.passed +
      ' of ' +
      props.pullRequest.readiness.total +
      ' checks'
    : 'Readiness unavailable: ' + props.pullRequest.readiness.reason,
)

const buildLabel = computed(() => {
  const buildState = props.pullRequest.buildState
  switch (buildState.type) {
    case 'successful':
      return 'Build successful'
    case 'failed':
      return 'Build failed'
    case 'inProgress':
      return 'Build in progress'
    case 'unavailable':
      return 'Build unavailable: ' + buildState.reason
  }
  return assertNever(buildState)
})

const actionItemLabel = computed(
  () =>
    props.pullRequest.actionableItemCount +
    ' actionable item' +
    (props.pullRequest.actionableItemCount === 1 ? '' : 's'),
)
</script>

<template>
  <article class="pull-request-card">
    <header>
      <p class="pull-request-number">#{{ pullRequest.displayNumber }}</p>
      <h4>
        <a :href="pullRequest.webUrl" target="_blank" rel="noopener noreferrer">
          {{ pullRequest.title }}
        </a>
      </h4>
      <p class="pull-request-meta">
        By {{ pullRequest.authorDisplayName }} · Updated
        <time :datetime="pullRequest.updatedAt">{{ pullRequest.updatedAt }}</time>
      </p>
    </header>

    <ul class="pull-request-status">
      <li>{{ readinessLabel }}</li>
      <li>{{ buildLabel }}</li>
      <li>{{ actionItemLabel }}</li>
    </ul>
  </article>
</template>
```

- [ ] **Step 5: Verify GREEN and all unit-level behavior**

Run:

```bash
cd web
npm run test:unit -- src/features/dashboard/DashboardView.spec.ts
npm run test:unit
npm run type-check
npm run lint
```

Expected: all component and composable tests pass; type-check (including E2E TypeScript) and lint exit 0 without warnings. The `never` fallback means a future synchronization, freshness, or build-state variant becomes a type error until it receives an explicit visible label.

- [ ] **Step 6: Mutation-check and commit**

Mentally verify that tests fail if PRs are flattened, the two repositories are merged, readiness/build/action fields are omitted, `workspaceNotConfigured` becomes an alert, a rejection message leaks, or retry does nothing.

Run from the repository root:

```bash
git diff --check
git add web/src/features/dashboard/DashboardView.vue web/src/features/dashboard/DashboardView.spec.ts web/src/features/dashboard/components
git commit -m "feat: render repository-grouped dashboard"
```

Expected: only Task 3 component and test files are committed.

---

### Task 4: Runtime Fixture, Browser Acceptance, and Documentation

**Files:**
- Create: `web/e2e/dashboard.spec.ts`
- Create: `web/src/features/dashboard/fixtures/fixtureDashboardSource.ts`
- Create: `web/src/app/App.vue`
- Create: `web/src/main.ts`
- Create: `web/src/assets/main.css`
- Modify: `web/index.html`
- Create: `web/README.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: `DashboardSource`, `DashboardView`, and repository presentation from Tasks 2–3.
- Produces: a runnable fixture-backed application at `http://127.0.0.1:5173`, production assets under `web/dist/`, and documented local commands.

- [ ] **Step 1: Write the failing real-browser acceptance test**

Create `web/e2e/dashboard.spec.ts`:

```ts
import { expect, test } from '@playwright/test'

test('renders the fixture-backed repository dashboard without service requests', async ({ page }) => {
  const viteOrigin = 'http://127.0.0.1:5173'
  const unexpectedRequests: string[] = []
  page.on('request', (request) => {
    const requestUrl = request.url()
    const isViteRequest = requestUrl === viteOrigin || requestUrl.startsWith(viteOrigin + '/')
    const isDataRequest = requestUrl.startsWith('data:')
    if (
      request.resourceType() === 'fetch' ||
      request.resourceType() === 'xhr' ||
      (!isDataRequest && !isViteRequest)
    ) {
      unexpectedRequests.push(requestUrl)
    }
  })

  await page.goto('/')

  await expect(page.getByRole('heading', { level: 1, name: 'Pull requests' })).toBeVisible()

  const payments = page.getByRole('region', { name: 'Payments API' })
  const portal = page.getByRole('region', { name: 'Developer Portal' })

  await expect(payments).toContainText('#184')
  await expect(payments).toContainText('Keep dashboard revisions opaque')
  await expect(portal).toContainText('#52')
  await expect(portal).toContainText('Surface stale acknowledgment')
  await expect(page.getByText('6 of 7 checks')).toBeVisible()
  await expect(page.getByText('Synchronization running')).toBeVisible()

  await page.setViewportSize({ width: 360, height: 800 })
  const fitsNarrowViewport = await page.locator('body').evaluate(
    (body) => body.scrollWidth <= body.clientWidth,
  )
  expect(fitsNarrowViewport).toBe(true)
  expect(unexpectedRequests).toEqual([])
})
```

- [ ] **Step 2: Install Chromium and verify RED**

Run:

```bash
cd web
npx playwright install chromium
npm run test:e2e
```

Expected: Playwright starts Vite but FAILS because `src/main.ts` does not exist and the `Pull requests` heading never renders. Confirm the browser itself launches successfully before proceeding.

- [ ] **Step 3: Add the deterministic fixture**

Create `web/src/features/dashboard/fixtures/fixtureDashboardSource.ts`:

```ts
import type { DashboardSource } from '../dashboardSource'

export const fixtureDashboardSource: DashboardSource = {
  load: () =>
    Promise.resolve({
      type: 'dashboardAvailable',
      dashboard: {
        workspaceDisplayName: 'Mindtable',
        generatedAt: '2026-08-15T10:00:00Z',
        repositoryGroups: [
          {
            repositoryId: 'repo_payments',
            displayName: 'Payments API',
            webUrl: 'https://bitbucket.org/mindtable/payments-api',
            synchronization: { type: 'idle' },
            freshness: { type: 'fresh', ageDescription: '2 minutes ago' },
            pullRequests: [
              {
                pullRequestId: 'pr_184',
                displayNumber: 184,
                title: 'Keep dashboard revisions opaque',
                authorDisplayName: 'Artyom',
                updatedAt: '2026-08-15T09:58:00Z',
                webUrl: 'https://bitbucket.org/mindtable/payments-api/pull-requests/184',
                readiness: { type: 'available', passed: 6, total: 7 },
                buildState: { type: 'successful' },
                actionableItemCount: 2,
              },
            ],
          },
          {
            repositoryId: 'repo_portal',
            displayName: 'Developer Portal',
            webUrl: 'https://bitbucket.org/mindtable/developer-portal',
            synchronization: { type: 'running' },
            freshness: {
              type: 'stale',
              ageDescription: '18 minutes ago',
              staleSince: '2026-08-15T09:42:00Z',
            },
            pullRequests: [
              {
                pullRequestId: 'pr_52',
                displayNumber: 52,
                title: 'Surface stale acknowledgment',
                authorDisplayName: 'Morgan',
                updatedAt: '2026-08-15T09:45:00Z',
                webUrl: 'https://bitbucket.org/mindtable/developer-portal/pull-requests/52',
                readiness: { type: 'available', passed: 4, total: 7 },
                buildState: { type: 'inProgress' },
                actionableItemCount: 1,
              },
            ],
          },
        ],
      },
    }),
}
```

- [ ] **Step 4: Compose and mount the application**

Create `web/src/app/App.vue`:

```vue
<script setup lang="ts">
import DashboardView from '@/features/dashboard/DashboardView.vue'
import type { DashboardSource } from '@/features/dashboard/dashboardSource'

defineProps<{
  dashboardSource: DashboardSource
}>()
</script>

<template>
  <DashboardView :source="dashboardSource" />
</template>
```

Create `web/src/main.ts`:

```ts
import { createApp } from 'vue'

import App from './app/App.vue'
import './assets/main.css'
import { fixtureDashboardSource } from './features/dashboard/fixtures/fixtureDashboardSource'

createApp(App, {
  dashboardSource: fixtureDashboardSource,
}).mount('#app')
```

Replace `web/index.html` with:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <meta
      name="description"
      content="A local dashboard for authored Bitbucket pull requests and actionable activity."
    />
    <title>Bitbucket Helper</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

- [ ] **Step 5: Add the functional responsive style baseline**

Create `web/src/assets/main.css`:

```css
:root {
  color: #172b4d;
  background: #f4f5f7;
  font-family:
    Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  font-synthesis: none;
  text-rendering: optimizeLegibility;
  --surface: #ffffff;
  --surface-muted: #f7f8f9;
  --border: #dfe1e6;
  --text-muted: #5e6c84;
  --accent: #0c66e4;
  --focus: #0055cc;
  --radius: 0.75rem;
  --shadow: 0 0.25rem 1rem rgb(9 30 66 / 8%);
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  min-width: 20rem;
  min-height: 100vh;
  background: #f4f5f7;
}

button,
a {
  font: inherit;
}

a {
  color: var(--accent);
}

a:focus-visible,
button:focus-visible {
  outline: 0.2rem solid var(--focus);
  outline-offset: 0.2rem;
}

button {
  padding: 0.65rem 0.9rem;
  border: 0;
  border-radius: 0.4rem;
  color: #ffffff;
  background: var(--accent);
  cursor: pointer;
}

h1,
h2,
h3,
h4,
p {
  margin-top: 0;
}

.dashboard-shell {
  width: min(76rem, calc(100% - 2rem));
  margin: 0 auto;
  padding: 2.5rem 0 4rem;
}

.dashboard-header {
  margin-bottom: 2rem;
}

.dashboard-header h1 {
  margin-bottom: 0;
  font-size: clamp(2rem, 5vw, 3.5rem);
}

.eyebrow {
  margin-bottom: 0.35rem;
  color: var(--text-muted);
  font-size: 0.75rem;
  font-weight: 700;
  letter-spacing: 0.09em;
  text-transform: uppercase;
}

.workspace-header,
.repository-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.workspace-header {
  margin-bottom: 1.25rem;
}

.snapshot-time,
.pull-request-meta {
  color: var(--text-muted);
  font-size: 0.875rem;
}

.repository-list {
  display: grid;
  gap: 1.25rem;
}

.repository-group,
.state-panel {
  border: 0.0625rem solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow);
}

.repository-group {
  padding: 1.25rem;
}

.state-panel {
  padding: 1.5rem;
}

.repository-header h3 {
  margin-bottom: 0;
}

.repository-status {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin: 1rem 0;
}

.repository-status div {
  padding: 0.6rem 0.75rem;
  border-radius: 0.5rem;
  background: var(--surface-muted);
}

.repository-status dt {
  color: var(--text-muted);
  font-size: 0.75rem;
  font-weight: 700;
}

.repository-status dd {
  margin: 0.2rem 0 0;
}

.pull-request-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 19rem), 1fr));
  gap: 0.9rem;
}

.pull-request-card {
  padding: 1rem;
  border: 0.0625rem solid var(--border);
  border-radius: 0.55rem;
  background: var(--surface-muted);
}

.pull-request-number {
  margin-bottom: 0.25rem;
  color: var(--text-muted);
  font-size: 0.8rem;
  font-weight: 700;
}

.pull-request-card h4 {
  margin-bottom: 0.45rem;
  font-size: 1rem;
}

.pull-request-status {
  display: flex;
  flex-wrap: wrap;
  gap: 0.45rem;
  margin: 0.9rem 0 0;
  padding: 0;
  list-style: none;
}

.pull-request-status li {
  padding: 0.35rem 0.5rem;
  border: 0.0625rem solid var(--border);
  border-radius: 999px;
  background: var(--surface);
  font-size: 0.8rem;
}

.empty-state {
  margin-bottom: 0;
  color: var(--text-muted);
}

code {
  display: inline-block;
  max-width: 100%;
  padding: 0.35rem 0.5rem;
  overflow-wrap: anywhere;
  border-radius: 0.35rem;
  background: var(--surface-muted);
}

@media (max-width: 40rem) {
  .dashboard-shell {
    width: min(100% - 1rem, 76rem);
    padding-top: 1.5rem;
  }

  .workspace-header,
  .repository-header {
    align-items: stretch;
    flex-direction: column;
  }
}
```

- [ ] **Step 6: Verify GREEN in a real browser**

Run:

```bash
cd web
npm run test:e2e
```

Expected: the one Chromium test passes, both repositories contain only their own fixture PR, and the captured unexpected-request array remains empty. Any fetch/XHR request and every non-data request outside `http://127.0.0.1:5173` fails the no-service boundary.

- [ ] **Step 7: Replace generator documentation with truthful project guidance**

Create `web/README.md`:

```markdown
# Bitbucket Helper Web

Vue 3, Vite, and TypeScript frontend for Bitbucket Helper.

The current slice renders a deterministic in-process dashboard fixture. It does
not call the Kotlin service. A later slice will generate the TypeScript API client
from openapi/api-v1.yaml and replace the fixture through DashboardSource.

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
```

Add this paragraph to the root `README.md` after the approved SPA contract paragraph:

```markdown
The approved fixture-backed Vue project structure is documented in the
[Vue project structure specification](docs/superpowers/specs/2026-08-15-vue-project-structure-design.md).
The runnable workspace lives under `web/` and remains disconnected from Kotlin
until the canonical OpenAPI document and generated client are available.
```

- [ ] **Step 8: Run clean final verification**

Run:

```bash
cd web
npm run format
npm ci
npm run check
npm run test:e2e
```

Expected:

- `npm ci` succeeds from `package-lock.json`.
- Formatting verification, ESLint, Vue/TypeScript checking, all unit/component tests, and the production Vite build pass without warnings.
- The Chromium smoke test passes.
- `web/dist/` exists locally but remains ignored.

Run from the repository root:

```bash
git diff --check
git status --short
```

Expected: only Task 4 files and the root README are changed by this task. `docs/project-backlog.md` and `source/` remain unstaged. No `node_modules`, `dist`, coverage, `test-results`, or `playwright-report` entry appears.

- [ ] **Step 9: Commit the completed working scaffold**

Run:

```bash
git add README.md web
git commit -m "feat: add fixture-backed Vue dashboard"
```

Expected: the commit excludes `docs/project-backlog.md`, `source/`, dependency directories, generated production assets, and test artifacts.

Run:

```bash
git status --short
git log -4 --oneline
```

Expected: the four implementation commits are visible. Only pre-existing user changes remain in status.

---

## Plan self-review

- Spec coverage: Tasks 1–4 cover the approved workspace, dependency injection, two-repository fixture, typed business outcomes, safe failure/retry, component tests, Chromium smoke test, responsive plain CSS, reproducible build, and documentation.
- Boundary check: no task creates OpenAPI wire DTOs, calls Kotlin, adds Router/Pinia, or changes Gradle packaging.
- Type consistency: `DashboardSource.load()`, `DashboardSourceResult`, `DashboardViewModel`, source props, and fixture fields use the same names in every task.
- TDD check: product code in Tasks 2–4 is preceded by a focused failing test and an observed RED run; Task 1 contains generator/configuration only and deletes generator demonstration product code.
- Workspace safety: every commit stages explicit in-scope paths and preserves `source/` plus the unrelated `docs/project-backlog.md` edit.
