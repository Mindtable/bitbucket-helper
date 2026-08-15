import { describe, expect, it } from 'vitest'

import { baseDashboard } from './fixtures/fixtureDashboardData'
import { fixtureDashboardSource } from './fixtures/fixtureDashboardSource'
import { createFixtureDashboardSource } from './fixtures/fixtureDashboardSource'

describe('fixtureDashboardSource', () => {
  it('returns the approved repository hierarchy without embedding raw content', async () => {
    const result = await fixtureDashboardSource.loadDashboard()
    expect(result.type).toBe('snapshotChanged')
    if (result.type !== 'snapshotChanged') throw new Error('expected snapshotChanged')

    expect(
      result.dashboard.repositoryGroups.map((repository) => ({
        repositoryId: repository.repositoryId,
        pullRequestIds: repository.pullRequests.map((pullRequest) => pullRequest.pullRequestId),
      })),
    ).toEqual([
      { repositoryId: 'repo_payments', pullRequestIds: ['pr_184', 'pr_179'] },
      { repositoryId: 'repo_store', pullRequestIds: ['pr_92'] },
    ])
    expect(result.dashboard.inbox.map((item) => item.actionItemId)).toEqual([
      'action_501',
      'action_502',
    ])
    expect(JSON.stringify(result.dashboard)).not.toContain('Could we cap the retry window')
  })

  it('prevents exported fixture metadata from contaminating a new source instance', async () => {
    try {
      baseDashboard.repositoryGroups[0]!.pullRequests[0]!.title = 'Contaminated title'
    } catch {
      // Immutable fixtures may reject the mutation.
    }

    const result = await createFixtureDashboardSource().loadDashboard()
    expect(result.type).toBe('snapshotChanged')
    if (result.type !== 'snapshotChanged') throw new Error('expected snapshotChanged')

    expect(result.dashboard.repositoryGroups[0]!.pullRequests[0]!.title).toBe('Add retry budget')
  })
})
