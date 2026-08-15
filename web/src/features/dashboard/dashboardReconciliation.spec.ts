import { describe, expect, it } from 'vitest'

import {
  makeActionItem,
  makeDashboard,
  makePullRequest,
  makeRepository,
} from './testing/dashboardTestData'
import { reconcileAcknowledgedAction, type AcknowledgedActionRef } from './dashboardReconciliation'

const acknowledged: AcknowledgedActionRef = {
  actionItemId: 'action_501',
  activityVersion: 'av_42',
  repositoryId: 'repo_payments',
  pullRequestId: 'pr_184',
}

function dashboardWithAction() {
  const action = makeActionItem({
    ...acknowledged,
    actorDisplayName: 'Alex Chen',
  })
  const siblingAction = makeActionItem({
    actionItemId: 'action_502',
    activityVersion: 'av_9',
    repositoryId: 'repo_payments',
    pullRequestId: 'pr_184',
  })
  const siblingPullRequest = makePullRequest({
    pullRequestId: 'pr_179',
    repositoryId: 'repo_payments',
  })
  const pullRequest = makePullRequest({
    pullRequestId: 'pr_184',
    repositoryId: 'repo_payments',
    actionableItemCount: 2,
    acknowledgedItemCount: 3,
    actionItems: [action, siblingAction],
  })
  const repository = makeRepository({
    repositoryId: 'repo_payments',
    pullRequests: [pullRequest, siblingPullRequest],
  })
  const otherRepository = makeRepository({
    repositoryId: 'repo_other',
    pullRequests: [makePullRequest({ pullRequestId: 'pr_other', repositoryId: 'repo_other' })],
  })
  const dashboard = makeDashboard({
    dashboardRevision: 'dash_17',
    repositoryGroups: [repository, otherRepository],
    inbox: [action, siblingAction],
  })
  return {
    action,
    dashboard,
    otherRepository,
    pullRequest,
    repository,
    siblingAction,
    siblingPullRequest,
  }
}

describe('reconcileAcknowledgedAction', () => {
  it('immutably removes the exact action and adjusts its owning pull-request counts once', () => {
    const fixture = dashboardWithAction()

    const result = reconcileAcknowledgedAction(fixture.dashboard, acknowledged)

    expect(result).not.toBe(fixture.dashboard)
    expect(result.dashboardRevision).toBe('dash_17')
    expect(result.inbox).toEqual([fixture.siblingAction])
    expect(result.inbox).not.toBe(fixture.dashboard.inbox)
    expect(result.repositoryGroups).not.toBe(fixture.dashboard.repositoryGroups)
    expect(result.repositoryGroups[0]).not.toBe(fixture.repository)
    expect(result.repositoryGroups[1]).toBe(fixture.otherRepository)
    const reconciledPullRequest = result.repositoryGroups[0]?.pullRequests[0]
    expect(reconciledPullRequest).toMatchObject({
      pullRequestId: 'pr_184',
      actionableItemCount: 1,
      acknowledgedItemCount: 4,
      actionItems: [fixture.siblingAction],
    })
    expect(reconciledPullRequest).not.toBe(fixture.pullRequest)
    expect(result.repositoryGroups[0]?.pullRequests[1]).toBe(fixture.siblingPullRequest)
    expect(fixture.dashboard.inbox).toEqual([fixture.action, fixture.siblingAction])
    expect(fixture.pullRequest).toMatchObject({
      actionableItemCount: 2,
      acknowledgedItemCount: 3,
      actionItems: [fixture.action, fixture.siblingAction],
    })
  })

  it('clamps an inconsistent actionable count at zero when removing a real item', () => {
    const fixture = dashboardWithAction()
    const dashboard = {
      ...fixture.dashboard,
      repositoryGroups: [
        {
          ...fixture.repository,
          pullRequests: [
            { ...fixture.pullRequest, actionableItemCount: 0 },
            fixture.siblingPullRequest,
          ],
        },
        fixture.otherRepository,
      ],
    }

    const result = reconcileAcknowledgedAction(dashboard, acknowledged)

    expect(result.repositoryGroups[0]?.pullRequests[0]).toMatchObject({
      actionableItemCount: 0,
      acknowledgedItemCount: 4,
    })
  })

  it.each([
    ['a repeated acknowledgment', acknowledged],
    ['a missing action', { ...acknowledged, actionItemId: 'action_missing' }],
    ['a different opaque version', { ...acknowledged, activityVersion: 'av_43' }],
    ['a different repository', { ...acknowledged, repositoryId: 'repo_other' }],
    ['a different pull request', { ...acknowledged, pullRequestId: 'pr_179' }],
  ])('is an identity operation for %s', (_name, candidate) => {
    const fixture = dashboardWithAction()
    const dashboard =
      candidate === acknowledged
        ? reconcileAcknowledgedAction(fixture.dashboard, acknowledged)
        : fixture.dashboard

    expect(reconcileAcknowledgedAction(dashboard, candidate)).toBe(dashboard)
  })
})
