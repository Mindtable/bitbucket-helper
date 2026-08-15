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
