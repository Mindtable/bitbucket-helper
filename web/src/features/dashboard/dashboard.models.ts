export type PollingState = { type: 'idle' } | { type: 'active'; afterMilliseconds: number }

export interface DashboardViewModel {
  dashboardRevision: string
  generatedAt: string
  workspaceDisplayName: string
  polling: PollingState
  repositoryGroups: readonly RepositoryGroupModel[]
  inbox: readonly ActionItemSummary[]
}

export interface RepositoryGroupModel {
  repositoryId: string
  slug: string
  displayName: string
  webUrl: string
  repositoryRevision: string
  synchronization: SynchronizationState
  freshness: FreshnessState
  problem: SynchronizationProblemState
  pullRequests: readonly PullRequestSummary[]
}

export interface PullRequestSummary {
  pullRequestId: string
  repositoryId: string
  displayNumber: number
  title: string
  authorDisplayName: string
  updatedAt: string
  webUrl: string
  readiness: ReadinessState
  buildState: BuildState
  actionableItemCount: number
  acknowledgedItemCount: number
  actionItems: readonly ActionItemSummary[]
}

export interface ActionItemSummary {
  actionItemId: string
  activityVersion: string
  repositoryId: string
  pullRequestId: string
  kind: 'comment' | 'changesRequested'
  actorDisplayName: string
  occurredAt: string
  acknowledgmentState: 'actionable' | 'acknowledged'
  webUrl: string
}

export interface PullRequestDetailModel {
  repositoryDisplayName: string
  pullRequest: PullRequestSummary
  readinessChecks: readonly ReadinessCheckModel[]
  actionItems: readonly ActionItemSummary[]
}

export interface ReadinessCheckModel {
  checkId: string
  label: string
  state: 'passed' | 'pending' | 'failed' | 'unavailable'
}

export type SynchronizationState = { type: 'idle' } | { type: 'queued' } | { type: 'running' }

export type FreshnessState =
  | { type: 'neverSynchronized' }
  | { type: 'fresh'; ageDescription: string }
  | { type: 'stale'; ageDescription: string; staleSince: string }

export type SynchronizationProblemState =
  | { type: 'none' }
  | {
      type: 'present'
      message: string
      retryable: boolean
      retryAfterDescription: string | null
    }

export type ReadinessState =
  { type: 'available'; passed: number; total: 7 } | { type: 'unavailable'; reason: string }

export type BuildState =
  | { type: 'successful' }
  | { type: 'failed'; failedCheckCount?: number }
  | { type: 'inProgress' }
  | { type: 'unavailable'; reason: string }
