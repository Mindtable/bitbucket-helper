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
  return { load: () => Promise.resolve(result) }
}

describe('DashboardView', () => {
  it('renders pull requests beneath their owning repositories', async () => {
    const wrapper = mount(DashboardView, {
      props: {
        source: sourceReturning({ type: 'dashboardAvailable', dashboard: groupedDashboard }),
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
        source: sourceReturning({ type: 'dashboardAvailable', dashboard: edgeStateDashboard }),
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
        return Promise.resolve({ type: 'dashboardAvailable', dashboard: groupedDashboard })
      },
    }
    const wrapper = mount(DashboardView, { props: { source } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('Dashboard unavailable')
    expect(wrapper.text()).not.toContain('do-not-display')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Acme Engineering')
  })
})
