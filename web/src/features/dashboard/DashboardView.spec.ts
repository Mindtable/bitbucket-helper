import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import type { DashboardSourceResult } from './dashboardSource'
import {
  makeActionItem,
  makeDashboard,
  makePullRequest,
  makePullRequestDetail,
  makeRepository,
} from './testing/dashboardTestData'
import { createDashboardSourceStub, deferred } from './testing/dashboardTestSource'
import DashboardView from './DashboardView.vue'

const groupedDashboard = makeDashboard({
  repositoryGroups: [
    makeRepository({
      repositoryId: 'repo_api',
      displayName: 'Payments API',
      webUrl: 'https://bitbucket.org/acme/payments-api',
      freshness: { type: 'fresh', ageDescription: '2 minutes ago' },
      pullRequests: [
        makePullRequest({
          pullRequestId: 'pr_17',
          repositoryId: 'repo_api',
          displayNumber: 17,
          title: 'Keep dashboard revisions opaque',
          authorDisplayName: 'Ari',
          updatedAt: '2026-08-15T09:58:00Z',
          webUrl: 'https://bitbucket.org/acme/payments-api/pull-requests/17',
          readiness: { type: 'available', passed: 5, total: 7 },
          actionableItemCount: 2,
        }),
      ],
    }),
    makeRepository({
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
        makePullRequest({
          pullRequestId: 'pr_23',
          repositoryId: 'repo_web',
          displayNumber: 23,
          title: 'Surface stale acknowledgment',
          authorDisplayName: 'Morgan',
          updatedAt: '2026-08-15T09:45:00Z',
          webUrl: 'https://bitbucket.org/acme/developer-portal/pull-requests/23',
          readiness: { type: 'available', passed: 4, total: 7 },
          buildState: { type: 'inProgress' },
          actionableItemCount: 1,
        }),
      ],
    }),
  ],
})

const drawerAction = makeActionItem({
  actionItemId: 'action_501',
  activityVersion: 'av_42',
  repositoryId: 'repo_payments',
  pullRequestId: 'pr_184',
})
const drawerPullRequest = makePullRequest({
  pullRequestId: 'pr_184',
  repositoryId: 'repo_payments',
  displayNumber: 184,
  title: 'Add retry budget',
  actionItems: [drawerAction],
  actionableItemCount: 1,
})
const drawerRepository = makeRepository({
  repositoryId: 'repo_payments',
  displayName: 'Payments API',
  pullRequests: [drawerPullRequest],
})
const drawerDashboard = makeDashboard({
  repositoryGroups: [drawerRepository],
  inbox: [drawerAction],
})

function sourceReturning(result: DashboardSourceResult) {
  return createDashboardSourceStub({
    loadDashboard: () => Promise.resolve(result),
  })
}

describe('DashboardView', () => {
  it('uses one main landmark and a semantic page, section, repository, and pull-request hierarchy', async () => {
    const wrapper = mount(DashboardView, {
      props: {
        source: sourceReturning({ type: 'snapshotChanged', dashboard: groupedDashboard }),
      },
    })

    await flushPromises()

    expect(wrapper.findAll('main')).toHaveLength(1)
    expect(wrapper.get('main > .dashboard-content .product-header h1').text()).toBe(
      'Bitbucket Helper',
    )
    expect(wrapper.get('section.needs-attention > header h2').text()).toBe('Needs attention')
    expect(wrapper.get('section[aria-labelledby="repository-repo_api"] h3').text()).toBe(
      'Payments API',
    )
    expect(wrapper.get('[data-pull-request-id="pr_17"] h4').text()).toBe(
      'Keep dashboard revisions opaque',
    )
    const repositoryList = wrapper.get('ul.repository-list')
    expect(
      Array.from(repositoryList.element.children).every((child) => child.tagName === 'LI'),
    ).toBe(true)
  })

  it('names the native repository feed with its section heading', async () => {
    const wrapper = mount(DashboardView, {
      props: {
        source: sourceReturning({ type: 'snapshotChanged', dashboard: groupedDashboard }),
      },
    })

    await flushPromises()

    expect(wrapper.get('h2#repository-feed-heading').text()).toBe('Configured repositories')
    expect(wrapper.get('ul.repository-list').attributes('aria-labelledby')).toBe(
      'repository-feed-heading',
    )
  })

  it('names each repository link and announces that it opens in a new tab', async () => {
    const wrapper = mount(DashboardView, {
      props: {
        source: sourceReturning({ type: 'snapshotChanged', dashboard: groupedDashboard }),
      },
    })

    await flushPromises()

    expect(
      wrapper.get('a[href="https://bitbucket.org/acme/payments-api"]').attributes('aria-label'),
    ).toBe('Open Payments API repository in a new tab')
  })

  it('renders pull requests beneath their owning repositories', async () => {
    const wrapper = mount(DashboardView, {
      props: {
        source: sourceReturning({ type: 'snapshotChanged', dashboard: groupedDashboard }),
      },
    })
    expect(wrapper.get('[role="status"]').text()).toContain('Loading')
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('Bitbucket Helper')
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

  it('surfaces a repository problem in the product header status', async () => {
    const problemDashboard = makeDashboard({
      repositoryGroups: [
        makeRepository({
          problem: {
            type: 'present',
            message: 'Credentials need attention',
            retryable: true,
            retryAfterDescription: null,
          },
        }),
      ],
    })
    const wrapper = mount(DashboardView, {
      props: {
        source: sourceReturning({ type: 'snapshotChanged', dashboard: problemDashboard }),
      },
    })

    await flushPromises()

    const header = wrapper.get('[data-overall-status="problem"]')
    expect(header.get('[role="status"]').text()).toBe('Sync needs attention')
  })

  it('renders unavailable, failed, queued, and empty states explicitly', async () => {
    const edgeStateDashboard = makeDashboard({
      repositoryGroups: [
        makeRepository({
          repositoryId: 'repo_edge',
          displayName: 'Edge Cases',
          webUrl: 'https://bitbucket.org/acme/edge-cases',
          synchronization: { type: 'queued' },
          freshness: { type: 'neverSynchronized' },
          pullRequests: [
            makePullRequest({
              pullRequestId: 'pr_unavailable',
              repositoryId: 'repo_edge',
              displayNumber: 31,
              title: 'Keep unavailable states explicit',
              authorDisplayName: 'Sam',
              updatedAt: '2026-08-15T09:40:00Z',
              webUrl: 'https://bitbucket.org/acme/edge-cases/pull-requests/31',
              readiness: {
                type: 'unavailable',
                reason: 'Malformed upstream input',
              },
              buildState: { type: 'unavailable', reason: 'No build observed' },
            }),
            makePullRequest({
              pullRequestId: 'pr_failed',
              repositoryId: 'repo_edge',
              displayNumber: 32,
              title: 'Report a failed build',
              authorDisplayName: 'Lee',
              updatedAt: '2026-08-15T09:35:00Z',
              webUrl: 'https://bitbucket.org/acme/edge-cases/pull-requests/32',
              readiness: { type: 'available', passed: 3, total: 7 },
              buildState: { type: 'failed' },
              actionableItemCount: 1,
            }),
          ],
        }),
        makeRepository({
          repositoryId: 'repo_empty',
          displayName: 'No Open Work',
          webUrl: 'https://bitbucket.org/acme/no-open-work',
          pullRequests: [],
        }),
      ],
    })
    const wrapper = mount(DashboardView, {
      props: {
        source: sourceReturning({ type: 'snapshotChanged', dashboard: edgeStateDashboard }),
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
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(0)

    const viewBuild = wrapper.get('[data-view-build]')
    expect(viewBuild.attributes('aria-disabled')).toBe('true')
    expect(viewBuild.attributes('disabled')).toBeUndefined()
    const reasonId = viewBuild.attributes('aria-describedby')
    expect(wrapper.get(`#${reasonId}`).text()).toBe(
      'Build details are not available in Bitbucket Helper yet.',
    )
    await viewBuild.trigger('click')
    expect(wrapper.find('aside.pull-request-drawer').exists()).toBe(false)
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
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(0)
  })

  it('hides failure details and retries into a ready dashboard', async () => {
    let firstAttempt = true
    const source = createDashboardSourceStub({
      loadDashboard: () => {
        if (firstAttempt) {
          firstAttempt = false
          return Promise.reject(new Error('credential=do-not-display'))
        }
        return Promise.resolve({ type: 'snapshotChanged', dashboard: groupedDashboard })
      },
    })
    const wrapper = mount(DashboardView, { props: { source } })
    await flushPromises()
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('Dashboard unavailable')
    expect(wrapper.text()).not.toContain('do-not-display')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Acme Engineering')
  })

  it('binds the ready header refresh action to dashboard refresh', async () => {
    const startRefresh = vi.fn(() =>
      Promise.resolve({ type: 'refreshRunRegistered' as const, refreshRunId: 'refresh_1' }),
    )
    const wrapper = mount(DashboardView, {
      props: {
        source: createDashboardSourceStub({
          loadDashboard: () =>
            Promise.resolve({ type: 'snapshotChanged', dashboard: groupedDashboard }),
          startRefresh,
        }),
      },
    })
    await flushPromises()

    await wrapper.get('button[aria-label="Refresh dashboard"]').trigger('click')
    await flushPromises()

    expect(startRefresh).toHaveBeenCalledTimes(2)
  })

  it('preserves a collapsed needs-attention inbox when a changed dashboard replaces the snapshot', async () => {
    const changedDashboard = makeDashboard({
      dashboardRevision: 'dashboard_revision_2',
      inbox: [makeActionItem({ actionItemId: 'action_502' })],
    })
    const changedSnapshot = deferred<DashboardSourceResult>()
    const source = createDashboardSourceStub({
      loadDashboard: vi
        .fn()
        .mockResolvedValueOnce({ type: 'snapshotChanged', dashboard: groupedDashboard })
        .mockReturnValueOnce(changedSnapshot.promise),
      startRefresh: () =>
        Promise.resolve({ type: 'refreshRunRegistered', refreshRunId: 'refresh_1' }),
    })
    const wrapper = mount(DashboardView, { props: { source } })

    await flushPromises()
    await wrapper.get('button.needs-attention-toggle').trigger('click')
    changedSnapshot.resolve({ type: 'snapshotChanged', dashboard: changedDashboard })
    await flushPromises()

    const toggle = wrapper.get('button.needs-attention-toggle')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.attributes('aria-controls')).toBe('needs-attention-body')
    expect(wrapper.find('#needs-attention-body').exists()).toBe(false)
    expect(toggle.text()).toContain('1 open')
  })

  it('keeps refresh and drawer announcements in persistent scoped polite live regions', async () => {
    const wrapper = mount(DashboardView, {
      props: {
        source: sourceReturning({ type: 'snapshotChanged', dashboard: groupedDashboard }),
      },
    })

    await flushPromises()

    expect(wrapper.get('.product-header [data-refresh-status]').attributes('aria-live')).toBe(
      'polite',
    )
    const drawerStatus = wrapper.get('main > [data-drawer-status]')
    expect(drawerStatus.attributes('aria-live')).toBe('polite')
    expect(drawerStatus.text()).toBe('')
  })

  it('uses native disabled for an unavailable refresh operation', async () => {
    const registration = deferred<{
      type: 'refreshRunRegistered'
      refreshRunId: string
    }>()
    const wrapper = mount(DashboardView, {
      props: {
        source: createDashboardSourceStub({
          loadDashboard: () =>
            Promise.resolve({ type: 'snapshotChanged', dashboard: groupedDashboard }),
          startRefresh: () => registration.promise,
        }),
      },
    })

    await flushPromises()

    expect(wrapper.get('button[aria-label="Refresh dashboard"]').attributes()).toHaveProperty(
      'disabled',
    )
    registration.resolve({ type: 'refreshRunRegistered', refreshRunId: 'refresh_1' })
    await flushPromises()
    wrapper.unmount()
  })

  it('disposes dashboard polling when the view unmounts', async () => {
    const registration = deferred<{
      type: 'refreshRunRegistered'
      refreshRunId: string
    }>()
    const loadDashboard = vi.fn(() =>
      Promise.resolve({
        type: 'snapshotChanged' as const,
        dashboard: makeDashboard({ polling: { type: 'active', afterMilliseconds: 25 } }),
      }),
    )
    const wrapper = mount(DashboardView, {
      props: {
        source: createDashboardSourceStub({
          loadDashboard,
          startRefresh: () => registration.promise,
        }),
      },
    })
    await flushPromises()
    wrapper.unmount()
    registration.resolve({ type: 'refreshRunRegistered', refreshRunId: 'refresh_1' })
    await flushPromises()

    expect(loadDashboard).toHaveBeenCalledTimes(1)
  })

  it('opens one named complementary drawer, focuses Close, and returns focus after Close or Escape', async () => {
    const detail = makePullRequestDetail({ pullRequestId: 'pr_184' })
    const wrapper = mount(DashboardView, {
      attachTo: document.body,
      props: {
        source: createDashboardSourceStub({
          loadDashboard: () =>
            Promise.resolve({ type: 'snapshotChanged', dashboard: drawerDashboard }),
          loadPullRequest: () =>
            Promise.resolve({
              type: 'pullRequestAvailable',
              detail: { ...detail, pullRequest: drawerPullRequest },
            }),
        }),
      },
    })
    await flushPromises()
    const invoker = wrapper.get('[data-pull-request-id="pr_184"] [data-review-context]')

    await invoker.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('aside.pull-request-drawer')).toHaveLength(1)
    const aside = wrapper.get('aside.pull-request-drawer')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(aside.attributes('aria-labelledby')).toBe('pull-request-drawer-heading')
    expect(aside.get('h2#pull-request-drawer-heading').text()).toBe('Add retry budget')
    expect(document.activeElement).toBe(wrapper.get('[data-close-drawer]').element)
    await wrapper.get('[data-close-drawer]').trigger('click')
    await flushPromises()
    expect(wrapper.find('aside.pull-request-drawer').exists()).toBe(false)
    expect(document.activeElement).toBe(invoker.element)

    await invoker.trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('[data-close-drawer]').element)
    const outsideDrawer = wrapper.get('button[aria-label="Refresh dashboard"]')
    const outsideDrawerElement = outsideDrawer.element as HTMLElement
    outsideDrawerElement.focus()
    expect(document.activeElement).toBe(outsideDrawer.element)
    await outsideDrawer.trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(wrapper.find('aside.pull-request-drawer').exists()).toBe(false)
    expect(document.activeElement).toBe(invoker.element)
    wrapper.unmount()
  })

  it('opens the exact inbox activity context without sorting its opaque version', async () => {
    const pending = deferred<ReturnType<typeof makePullRequestDetail>>()
    const wrapper = mount(DashboardView, {
      props: {
        source: createDashboardSourceStub({
          loadDashboard: () =>
            Promise.resolve({ type: 'snapshotChanged', dashboard: drawerDashboard }),
          loadPullRequest: () =>
            pending.promise.then((detail) => ({
              type: 'pullRequestAvailable' as const,
              detail,
            })),
        }),
      },
    })
    await flushPromises()

    await wrapper.get('[data-action-item-id="action_501"]').trigger('click')

    expect(wrapper.get('aside.pull-request-drawer').text()).toContain('Activity version av_42')
  })

  it('loads and renders exact-version activity content without adding it to the dashboard snapshot', async () => {
    const detail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
      pullRequest: drawerPullRequest,
      actionItems: [drawerAction],
    }
    const loadActionContent = vi.fn(() =>
      Promise.resolve({
        type: 'contentAvailable' as const,
        actionItemId: 'action_501',
        activityVersion: 'av_42',
        markdownSource: 'Exact body for activity version 42',
      }),
    )
    const wrapper = mount(DashboardView, {
      props: {
        source: createDashboardSourceStub({
          loadDashboard: () =>
            Promise.resolve({ type: 'snapshotChanged', dashboard: drawerDashboard }),
          loadPullRequest: () => Promise.resolve({ type: 'pullRequestAvailable', detail }),
          loadActionContent,
        }),
      },
    })
    await flushPromises()

    await wrapper.get('[data-action-item-id="action_501"]').trigger('click')
    await flushPromises()

    expect(loadActionContent).toHaveBeenCalledWith('action_501', 'av_42')
    expect(wrapper.get('aside.pull-request-drawer').text()).toContain(
      'Exact body for activity version 42',
    )
    expect(JSON.stringify(drawerDashboard)).not.toContain('markdownSource')
  })

  it('acknowledges through the drawer while preserving a collapsed Needs attention disclosure', async () => {
    const otherAction = makeActionItem({
      actionItemId: 'action_502',
      activityVersion: 'av_9',
      repositoryId: 'repo_payments',
      pullRequestId: 'pr_184',
    })
    const pullRequest = {
      ...drawerPullRequest,
      actionableItemCount: 2,
      acknowledgedItemCount: 3,
      actionItems: [drawerAction, otherAction],
    }
    const repository = { ...drawerRepository, pullRequests: [pullRequest] }
    const dashboard = makeDashboard({
      dashboardRevision: 'dash_17',
      repositoryGroups: [repository],
      inbox: [drawerAction, otherAction],
    })
    const detail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
      pullRequest,
      actionItems: [drawerAction, otherAction],
    }
    const acknowledgeActionItem = vi.fn(() =>
      Promise.resolve({
        type: 'acknowledged' as const,
        actionItemId: 'action_501',
        activityVersion: 'av_42',
      }),
    )
    const wrapper = mount(DashboardView, {
      props: {
        source: createDashboardSourceStub({
          loadDashboard: () => Promise.resolve({ type: 'snapshotChanged', dashboard }),
          startRefresh: () =>
            Promise.resolve({
              type: 'noRepositoriesConfigured',
              setupCommand: 'bitbucket-helper repository add',
            }),
          loadPullRequest: () => Promise.resolve({ type: 'pullRequestAvailable', detail }),
          loadActionContent: () =>
            Promise.resolve({
              type: 'contentAvailable',
              actionItemId: 'action_501',
              activityVersion: 'av_42',
              markdownSource: 'Exact activity body',
            }),
          acknowledgeActionItem,
        }),
      },
    })
    await flushPromises()
    await wrapper.get('[data-action-item-id="action_501"]').trigger('click')
    await flushPromises()
    await wrapper.get('button.needs-attention-toggle').trigger('click')
    const acknowledge = wrapper
      .findAll('button')
      .find((candidate) => candidate.text() === 'Acknowledge av_42')
    if (!acknowledge) throw new Error('expected acknowledgment control')

    await acknowledge.trigger('click')
    await flushPromises()

    expect(acknowledgeActionItem).toHaveBeenCalledWith('action_501', 'av_42')
    const toggle = wrapper.get('button.needs-attention-toggle')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.text()).toContain('1 open')
    expect(wrapper.get('[data-pull-request-id="pr_184"]').text()).toContain('1 actionable item')
    expect(wrapper.get('aside.pull-request-drawer').text()).toContain('Activity acknowledged.')
    const acknowledgmentStatus = wrapper.get('[data-activity-announcement]')
    expect(acknowledgmentStatus.attributes('aria-live')).toBe('polite')
    expect(acknowledgmentStatus.text()).toBe('Activity acknowledged.')
    expect(wrapper.get('.drawer-activity').findAll('[aria-live]')).toHaveLength(1)
  })

  it('recovers a stale acknowledgment through repository refresh and an accepted newer snapshot', async () => {
    const updatedAction = { ...drawerAction, activityVersion: 'av_43' }
    const updatedPullRequest = { ...drawerPullRequest, actionItems: [updatedAction] }
    const updatedRepository = { ...drawerRepository, pullRequests: [updatedPullRequest] }
    const updatedDashboard = makeDashboard({
      dashboardRevision: 'dash_18',
      repositoryGroups: [updatedRepository],
      inbox: [updatedAction],
    })
    const detail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
      pullRequest: drawerPullRequest,
      actionItems: [drawerAction],
    }
    const loadDashboard = vi
      .fn()
      .mockResolvedValueOnce({ type: 'snapshotChanged', dashboard: drawerDashboard })
      .mockResolvedValueOnce({
        type: 'snapshotUnchanged',
        dashboardRevision: 'dashboard_revision_1',
        serverTime: '2026-08-15T10:00:01Z',
        polling: { type: 'idle' },
      })
      .mockResolvedValueOnce({ type: 'snapshotChanged', dashboard: updatedDashboard })
    const loadActionContent = vi
      .fn()
      .mockResolvedValueOnce({
        type: 'contentAvailable',
        actionItemId: 'action_501',
        activityVersion: 'av_42',
        markdownSource: 'Older exact body',
      })
      .mockResolvedValueOnce({
        type: 'contentAvailable',
        actionItemId: 'action_501',
        activityVersion: 'av_43',
        markdownSource: 'Reloaded exact newer body',
      })
    const acknowledgeActionItem = vi.fn(() =>
      Promise.resolve({
        type: 'staleActivityVersion' as const,
        actionItemId: 'action_501',
        requestedActivityVersion: 'av_42',
        currentActivityVersion: 'av_43',
        hasNewerActivity: true as const,
      }),
    )
    const startRepositoryRefresh = vi.fn(() =>
      Promise.resolve({ type: 'refreshRunRegistered' as const, refreshRunId: 'refresh_repo_1' }),
    )
    const wrapper = mount(DashboardView, {
      props: {
        source: createDashboardSourceStub({
          loadDashboard,
          startRefresh: () =>
            Promise.resolve({ type: 'refreshRunRegistered', refreshRunId: 'refresh_global_1' }),
          loadPullRequest: () => Promise.resolve({ type: 'pullRequestAvailable', detail }),
          loadActionContent,
          acknowledgeActionItem,
          startRepositoryRefresh,
        }),
      },
    })
    await flushPromises()
    await wrapper.get('[data-action-item-id="action_501"]').trigger('click')
    await flushPromises()
    const acknowledge = wrapper
      .findAll('button')
      .find((candidate) => candidate.text() === 'Acknowledge av_42')
    if (!acknowledge) throw new Error('expected acknowledgment control')

    await acknowledge.trigger('click')
    await flushPromises()

    expect(acknowledgeActionItem).toHaveBeenCalledWith('action_501', 'av_42')
    expect(startRepositoryRefresh).toHaveBeenCalledWith('repo_payments', 'av_43')
    expect(loadDashboard).toHaveBeenNthCalledWith(3, 'dashboard_revision_1')
    expect(loadActionContent).toHaveBeenNthCalledWith(2, 'action_501', 'av_43')
    expect(wrapper.get('aside.pull-request-drawer').text()).toContain('Activity version av_43')
    expect(wrapper.get('aside.pull-request-drawer').text()).toContain('Reloaded exact newer body')
  })

  it('reconciles an accepted snapshot and politely closes a disappeared PR', async () => {
    const changedSnapshot = deferred<DashboardSourceResult>()
    const detail = deferred<ReturnType<typeof makePullRequestDetail>>()
    const loadDashboard = vi
      .fn()
      .mockResolvedValueOnce({ type: 'snapshotChanged', dashboard: drawerDashboard })
      .mockReturnValueOnce(changedSnapshot.promise)
    const wrapper = mount(DashboardView, {
      props: {
        source: createDashboardSourceStub({
          loadDashboard,
          startRefresh: () =>
            Promise.resolve({ type: 'refreshRunRegistered', refreshRunId: 'refresh_1' }),
          loadPullRequest: () =>
            detail.promise.then((value) => ({
              type: 'pullRequestAvailable' as const,
              detail: value,
            })),
        }),
      },
    })
    await flushPromises()
    await wrapper.get('[data-pull-request-id="pr_184"] [data-review-context]').trigger('click')
    expect(wrapper.find('aside.pull-request-drawer').exists()).toBe(true)

    changedSnapshot.resolve({
      type: 'snapshotChanged',
      dashboard: makeDashboard({ dashboardRevision: 'dashboard_revision_2' }),
    })
    await flushPromises()

    expect(wrapper.find('aside.pull-request-drawer').exists()).toBe(false)
    expect(wrapper.get('[data-drawer-status]').attributes('aria-live')).toBe('polite')
    expect(wrapper.get('[data-drawer-status]').text()).toBe(
      'That pull request is no longer in this dashboard.',
    )
  })
})
