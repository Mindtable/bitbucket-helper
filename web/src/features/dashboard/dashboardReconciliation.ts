import type { DashboardViewModel } from './dashboard.models'

export interface AcknowledgedActionRef {
  actionItemId: string
  activityVersion: string
  repositoryId: string
  pullRequestId: string
}

export function reconcileAcknowledgedAction(
  dashboard: DashboardViewModel,
  acknowledged: AcknowledgedActionRef,
): DashboardViewModel {
  const repositoryIndex = dashboard.repositoryGroups.findIndex(
    (repository) => repository.repositoryId === acknowledged.repositoryId,
  )
  if (repositoryIndex < 0) return dashboard

  const repository = dashboard.repositoryGroups[repositoryIndex]
  if (!repository) return dashboard
  const pullRequestIndex = repository.pullRequests.findIndex(
    (pullRequest) => pullRequest.pullRequestId === acknowledged.pullRequestId,
  )
  if (pullRequestIndex < 0) return dashboard

  const pullRequest = repository.pullRequests[pullRequestIndex]
  if (!pullRequest) return dashboard
  const matchesAcknowledged = (actionItem: (typeof pullRequest.actionItems)[number]) =>
    actionItem.actionItemId === acknowledged.actionItemId &&
    actionItem.activityVersion === acknowledged.activityVersion &&
    actionItem.repositoryId === acknowledged.repositoryId &&
    actionItem.pullRequestId === acknowledged.pullRequestId
  if (!pullRequest.actionItems.some(matchesAcknowledged)) return dashboard

  const reconciledPullRequest = {
    ...pullRequest,
    actionableItemCount: Math.max(0, pullRequest.actionableItemCount - 1),
    acknowledgedItemCount: pullRequest.acknowledgedItemCount + 1,
    actionItems: pullRequest.actionItems.filter((actionItem) => !matchesAcknowledged(actionItem)),
  }
  const pullRequests = [...repository.pullRequests]
  pullRequests[pullRequestIndex] = reconciledPullRequest
  const repositoryGroups = [...dashboard.repositoryGroups]
  repositoryGroups[repositoryIndex] = { ...repository, pullRequests }

  return {
    ...dashboard,
    repositoryGroups,
    inbox: dashboard.inbox.filter((actionItem) => !matchesAcknowledged(actionItem)),
  }
}
