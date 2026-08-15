import type { DashboardViewModel, PollingState, PullRequestDetailModel } from './dashboard.models'

export interface DashboardSource {
  loadDashboard(afterRevision?: string): Promise<DashboardSourceResult>
  startRefresh(): Promise<RefreshSourceResult>
  loadPullRequest(pullRequestId: string): Promise<PullRequestDetailSourceResult>
  loadActionContent(
    actionItemId: string,
    activityVersion: string,
  ): Promise<ActionContentSourceResult>
  acknowledgeActionItem(
    actionItemId: string,
    activityVersion: string,
  ): Promise<AcknowledgmentSourceResult>
  startRepositoryRefresh(
    repositoryId: string,
    observedActivityVersion: string,
  ): Promise<RefreshSourceResult>
}

export type DashboardSourceResult =
  | { type: 'snapshotChanged'; dashboard: DashboardViewModel }
  | {
      type: 'snapshotUnchanged'
      dashboardRevision: string
      serverTime: string
      polling: PollingState
    }
  | { type: 'workspaceNotConfigured'; setupCommand: string }

export type RefreshSourceResult =
  | { type: 'refreshRunRegistered'; refreshRunId: string }
  | { type: 'noRepositoriesConfigured'; setupCommand: string }
  | { type: 'workspaceNotConfigured'; setupCommand: string }

export type PullRequestDetailSourceResult =
  { type: 'pullRequestAvailable'; detail: PullRequestDetailModel } | { type: 'pullRequestNotFound' }

export type ActionContentSourceResult =
  | {
      type: 'contentAvailable'
      actionItemId: string
      activityVersion: string
      markdownSource: string
    }
  | { type: 'contentUnavailable'; reason: string; retryable: boolean }
  | {
      type: 'newerActivityObserved'
      repositoryId: string
      requestedActivityVersion: string
      currentActivityVersion: string
    }
  | {
      type: 'staleActivityVersion'
      requestedActivityVersion: string
      currentActivityVersion: string
    }
  | { type: 'actionItemNotFound' }

export type AcknowledgmentSourceResult =
  | { type: 'acknowledged'; actionItemId: string; activityVersion: string }
  | {
      type: 'alreadyAcknowledged'
      actionItemId: string
      activityVersion: string
    }
  | {
      type: 'staleActivityVersion'
      actionItemId: string
      requestedActivityVersion: string
      currentActivityVersion: string
      hasNewerActivity: true
    }
  | { type: 'acknowledgmentRejected'; reason: string }
  | { type: 'actionItemNotFound' }
