import { describe, expect, it } from 'vitest'

import { fixtureDashboardSource } from './fixtures/fixtureDashboardSource'

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
})
