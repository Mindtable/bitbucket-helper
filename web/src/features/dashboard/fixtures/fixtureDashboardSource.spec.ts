import { describe, expect, it } from 'vitest'

import type { DashboardViewModel } from '../dashboard.models'
import type {
  DashboardSourceResult,
  PullRequestDetailSourceModel,
  PullRequestDetailSourceResult,
} from '../dashboardSource'
import { baseDashboard, liveContentByActionVersion } from './fixtureDashboardData'
import { createFixtureDashboardSource } from './fixtureDashboardSource'

const action501Body = 'Could we cap the retry window and add a metric for exhausted attempts?'
const action501NewerBody =
  'Please cap the retry window at 30 seconds and emit a metric for exhausted attempts.'

function changed(result: DashboardSourceResult): DashboardViewModel {
  expect(result.type).toBe('snapshotChanged')
  if (result.type !== 'snapshotChanged') throw new Error('expected snapshotChanged')
  return result.dashboard
}

function availableDetail(result: PullRequestDetailSourceResult): PullRequestDetailSourceModel {
  expect(result.type).toBe('pullRequestAvailable')
  if (result.type !== 'pullRequestAvailable') throw new Error('expected pullRequestAvailable')
  return result.detail
}

function actionVersionInDashboard(dashboard: DashboardViewModel) {
  const repository = dashboard.repositoryGroups.find(
    (candidate) => candidate.repositoryId === 'repo_payments',
  )
  const pullRequest = repository?.pullRequests.find(
    (candidate) => candidate.pullRequestId === 'pr_184',
  )
  return {
    inbox: dashboard.inbox.find((item) => item.actionItemId === 'action_501')?.activityVersion,
    summary: pullRequest?.actionItems.find((item) => item.actionItemId === 'action_501')
      ?.activityVersion,
  }
}

function expectRawContentAbsent(value: unknown) {
  const serialized = JSON.stringify(value)
  expect(serialized).not.toContain('markdownSource')
  expect(serialized).not.toContain(action501Body)
  expect(serialized).not.toContain(action501NewerBody)
}

describe('createFixtureDashboardSource', () => {
  it('runs the healthy automatic, manual, and scheduled refresh sequence', async () => {
    const source = createFixtureDashboardSource('healthy-refresh')

    const initial = changed(await source.loadDashboard())
    expect(initial).toMatchObject({
      dashboardRevision: 'dash_18',
      generatedAt: '2026-08-15T10:00:00Z',
      polling: { type: 'idle' },
    })

    await expect(source.startRefresh()).resolves.toEqual({
      type: 'refreshRunRegistered',
      refreshRunId: 'refresh_1',
    })
    await expect(source.loadDashboard('dash_18')).resolves.toEqual({
      type: 'snapshotUnchanged',
      dashboardRevision: 'dash_18',
      serverTime: '2026-08-15T10:00:00Z',
      polling: { type: 'idle' },
    })

    await expect(source.startRefresh()).resolves.toEqual({
      type: 'refreshRunRegistered',
      refreshRunId: 'refresh_2',
    })
    const initialSnapshot = structuredClone(initial)
    const expectedRefreshed: DashboardViewModel = {
      ...initialSnapshot,
      dashboardRevision: 'dash_19',
      generatedAt: '2026-08-15T10:00:30Z',
      polling: { type: 'active', afterMilliseconds: 25 },
      repositoryGroups: initialSnapshot.repositoryGroups.map((repository) =>
        repository.repositoryId === 'repo_payments'
          ? {
              ...repository,
              repositoryRevision: 'repo_12',
              synchronization: { type: 'idle' },
              freshness: { type: 'fresh', ageDescription: 'Just now' },
            }
          : repository,
      ),
    }
    const refreshedResult = await source.loadDashboard('dash_18')
    if (refreshedResult.type !== 'snapshotChanged') {
      throw new Error('expected snapshotChanged')
    }
    expect(refreshedResult).toEqual({
      type: 'snapshotChanged',
      dashboard: expectedRefreshed,
    })

    await expect(source.loadDashboard('dash_19')).resolves.toEqual({
      type: 'snapshotUnchanged',
      dashboardRevision: 'dash_19',
      serverTime: '2026-08-15T10:00:30Z',
      polling: { type: 'idle' },
    })
  })

  it('preserves the last-known-good Web Store hierarchy in a partial refresh', async () => {
    const source = createFixtureDashboardSource('partial-refresh')
    const initial = changed(await source.loadDashboard())
    const initialStore = initial.repositoryGroups[1]

    await source.startRefresh()
    await expect(source.loadDashboard('dash_18')).resolves.toMatchObject({
      type: 'snapshotUnchanged',
      dashboardRevision: 'dash_18',
      polling: { type: 'idle' },
    })
    await source.startRefresh()

    const refreshed = changed(await source.loadDashboard('dash_18'))
    const refreshedStore = refreshed.repositoryGroups[1]
    expect(refreshed.dashboardRevision).toBe('dash_19')
    expect(refreshedStore).toMatchObject({
      repositoryId: 'repo_store',
      repositoryRevision: 'repo_7',
      synchronization: { type: 'idle' },
      freshness: {
        type: 'stale',
        ageDescription: '18 minutes ago',
        staleSince: '2026-08-15T09:42:00Z',
      },
      problem: {
        type: 'present',
        message: 'Refresh completed with stale Web Store data.',
        retryable: true,
        retryAfterDescription: 'Try Refresh again.',
      },
    })
    expect(refreshedStore?.pullRequests).toEqual(initialStore?.pullRequests)
    expect(refreshedStore?.pullRequests[0]).toMatchObject({
      pullRequestId: 'pr_92',
      buildState: { type: 'failed', failedCheckCount: 2 },
      readiness: { type: 'available', passed: 5, total: 7 },
      actionableItemCount: 1,
    })
  })

  it('returns exact content and a typed exact-version acknowledgment', async () => {
    const source = createFixtureDashboardSource('content-success')

    await expect(source.loadActionContent('action_501', 'av_42')).resolves.toEqual({
      type: 'contentAvailable',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      markdownSource: action501Body,
    })
    await expect(source.acknowledgeActionItem('action_501', 'av_42')).resolves.toEqual({
      type: 'acknowledged',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
    })
  })

  it('keeps metadata available while exact content retries remain unavailable', async () => {
    const source = createFixtureDashboardSource('content-unavailable')
    const initial = changed(await source.loadDashboard())
    const detailBeforeRetry = availableDetail(await source.loadPullRequest('pr_184'))

    expect(actionVersionInDashboard(initial)).toEqual({ inbox: 'av_42', summary: 'av_42' })
    expect(detailBeforeRetry.actionItems[0]).toMatchObject({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
    })
    const unavailable = {
      type: 'contentUnavailable',
      reason: 'Activity content is temporarily unavailable.',
      retryable: true,
    }
    await expect(source.loadActionContent('action_501', 'av_42')).resolves.toEqual(unavailable)
    await expect(source.loadActionContent('action_501', 'av_42')).resolves.toEqual(unavailable)

    const detailAfterRetry = availableDetail(await source.loadPullRequest('pr_184'))
    expect(detailAfterRetry.actionItems[0]).toEqual(detailBeforeRetry.actionItems[0])
  })

  it('withholds newer content until repository refresh publishes consistent metadata', async () => {
    const source = createFixtureDashboardSource('newer-activity')
    changed(await source.loadDashboard())
    await source.startRefresh()
    await expect(source.loadDashboard('dash_18')).resolves.toMatchObject({
      type: 'snapshotUnchanged',
      dashboardRevision: 'dash_18',
      polling: { type: 'idle' },
    })

    await expect(source.loadActionContent('action_501', 'av_42')).resolves.toEqual({
      type: 'newerActivityObserved',
      repositoryId: 'repo_payments',
      requestedActivityVersion: 'av_42',
      currentActivityVersion: 'av_43',
    })
    await expect(source.loadActionContent('action_501', 'av_43')).resolves.toMatchObject({
      type: 'contentUnavailable',
      retryable: true,
    })

    await expect(source.startRepositoryRefresh('repo_payments')).resolves.toEqual({
      type: 'refreshRunRegistered',
      refreshRunId: 'refresh_repository_1',
    })
    await expect(source.loadActionContent('action_501', 'av_43')).resolves.toMatchObject({
      type: 'contentUnavailable',
      retryable: true,
    })

    const refreshed = changed(await source.loadDashboard('dash_18'))
    expect(refreshed.dashboardRevision).toBe('dash_19')
    expect(actionVersionInDashboard(refreshed)).toEqual({ inbox: 'av_43', summary: 'av_43' })
    const refreshedDetail = availableDetail(await source.loadPullRequest('pr_184'))
    expect(refreshedDetail.actionItems[0]).toMatchObject({
      actionItemId: 'action_501',
      activityVersion: 'av_43',
    })
    expect(refreshedDetail.pullRequest.actionItems[0]).toMatchObject({
      actionItemId: 'action_501',
      activityVersion: 'av_43',
    })
    await expect(source.loadActionContent('action_501', 'av_43')).resolves.toEqual({
      type: 'contentAvailable',
      actionItemId: 'action_501',
      activityVersion: 'av_43',
      markdownSource: action501NewerBody,
    })
  })

  it('does not mutate counts or actions before stale acknowledgment metadata refreshes', async () => {
    const source = createFixtureDashboardSource('stale-acknowledgment')
    const initial = changed(await source.loadDashboard())
    await source.startRefresh()
    await source.loadDashboard('dash_18')

    await expect(source.loadActionContent('action_501', 'av_42')).resolves.toEqual({
      type: 'contentAvailable',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      markdownSource: action501Body,
    })
    await expect(source.acknowledgeActionItem('action_501', 'av_42')).resolves.toEqual({
      type: 'staleActivityVersion',
      actionItemId: 'action_501',
      requestedActivityVersion: 'av_42',
      currentActivityVersion: 'av_43',
      hasNewerActivity: true,
    })

    await expect(source.loadDashboard('dash_18')).resolves.toMatchObject({
      type: 'snapshotUnchanged',
      dashboardRevision: 'dash_18',
    })
    const beforeRefresh = changed(await source.loadDashboard())
    expect(beforeRefresh.inbox).toEqual(initial.inbox)
    expect(beforeRefresh.repositoryGroups[0]?.pullRequests[0]).toMatchObject({
      actionableItemCount: 1,
      acknowledgedItemCount: 0,
      actionItems: [{ actionItemId: 'action_501', activityVersion: 'av_42' }],
    })

    await source.startRepositoryRefresh('repo_payments')
    const refreshed = changed(await source.loadDashboard('dash_18'))
    expect(actionVersionInDashboard(refreshed)).toEqual({ inbox: 'av_43', summary: 'av_43' })
    expect(refreshed.inbox).toHaveLength(2)
    expect(refreshed.repositoryGroups[0]?.pullRequests[0]).toMatchObject({
      actionableItemCount: 1,
      acknowledgedItemCount: 0,
    })
  })

  it('keeps counters and mutable snapshots isolated between source instances', async () => {
    const first = createFixtureDashboardSource('healthy-refresh')
    const second = createFixtureDashboardSource('healthy-refresh')
    const firstInitial = changed(await first.loadDashboard())

    ;(firstInitial as { generatedAt: string }).generatedAt = 'mutated by consumer'
    await first.startRefresh()
    await first.loadDashboard('dash_18')
    await first.startRefresh()
    expect(changed(await first.loadDashboard('dash_18')).dashboardRevision).toBe('dash_19')

    const secondInitial = changed(await second.loadDashboard())
    expect(secondInitial).toMatchObject({
      dashboardRevision: 'dash_18',
      generatedAt: '2026-08-15T10:00:00Z',
    })
    await expect(second.startRefresh()).resolves.toEqual({
      type: 'refreshRunRegistered',
      refreshRunId: 'refresh_1',
    })
    await expect(second.loadDashboard('dash_18')).resolves.toMatchObject({
      type: 'snapshotUnchanged',
      dashboardRevision: 'dash_18',
    })
  })

  it('keeps fixture catalogs deeply immutable and raw bodies outside snapshots', async () => {
    expect(Object.isFrozen(baseDashboard)).toBe(true)
    expect(Object.isFrozen(baseDashboard.repositoryGroups)).toBe(true)
    expect(Object.isFrozen(baseDashboard.repositoryGroups[0])).toBe(true)
    expect(Object.isFrozen(baseDashboard.repositoryGroups[0]?.pullRequests)).toBe(true)
    expect(Object.isFrozen(liveContentByActionVersion)).toBe(true)

    expectRawContentAbsent(baseDashboard)
    const healthy = createFixtureDashboardSource('healthy-refresh')
    expectRawContentAbsent(changed(await healthy.loadDashboard()))
    await healthy.startRefresh()
    await healthy.loadDashboard('dash_18')
    await healthy.startRefresh()
    expectRawContentAbsent(changed(await healthy.loadDashboard('dash_18')))

    const newer = createFixtureDashboardSource('newer-activity')
    await newer.loadDashboard()
    await newer.startRepositoryRefresh('repo_payments')
    expectRawContentAbsent(changed(await newer.loadDashboard('dash_18')))
  })
})
