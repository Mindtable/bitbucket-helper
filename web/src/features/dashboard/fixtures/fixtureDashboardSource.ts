import type {
  ActionItemSummary,
  DashboardViewModel,
  PullRequestDetailModel,
  PullRequestSummary,
  RepositoryGroupModel,
} from '../dashboard.models'
import type { DashboardSource } from '../dashboardSource'
import {
  action501Newer,
  baseDashboard,
  liveContentByActionVersion,
  pullRequestDetailsById,
} from './fixtureDashboardData'

export type FixtureJourney =
  | 'healthy-refresh'
  | 'partial-refresh'
  | 'content-success'
  | 'content-unavailable'
  | 'newer-activity'
  | 'stale-acknowledgment'

function mapPaymentsRepository(
  dashboard: DashboardViewModel,
  transform: (repository: RepositoryGroupModel) => RepositoryGroupModel,
) {
  return dashboard.repositoryGroups.map((repository) =>
    repository.repositoryId === 'repo_payments' ? transform(repository) : repository,
  )
}

function refreshedDashboard(polling: DashboardViewModel['polling']): DashboardViewModel {
  const dashboard = structuredClone(baseDashboard)
  return {
    ...dashboard,
    dashboardRevision: 'dash_19',
    generatedAt: '2026-08-15T10:00:30Z',
    polling,
    repositoryGroups: mapPaymentsRepository(dashboard, (repository) => ({
      ...repository,
      repositoryRevision: 'repo_12',
      synchronization: { type: 'idle' },
      freshness: { type: 'fresh', ageDescription: 'Just now' },
    })),
  }
}

function partialDashboard(): DashboardViewModel {
  const dashboard = structuredClone(baseDashboard)
  return {
    ...dashboard,
    dashboardRevision: 'dash_19',
    generatedAt: '2026-08-15T10:00:30Z',
    repositoryGroups: dashboard.repositoryGroups.map((repository) =>
      repository.repositoryId === 'repo_store'
        ? {
            ...repository,
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
          }
        : repository,
    ),
  }
}

function replaceAction501(items: readonly ActionItemSummary[]) {
  return items.map((item) =>
    item.actionItemId === action501Newer.actionItemId ? structuredClone(action501Newer) : item,
  )
}

function replacePullRequestAction(pullRequest: PullRequestSummary): PullRequestSummary {
  return pullRequest.pullRequestId === 'pr_184'
    ? { ...pullRequest, actionItems: replaceAction501(pullRequest.actionItems) }
    : pullRequest
}

function newerActivityDashboard(): DashboardViewModel {
  const dashboard = refreshedDashboard({ type: 'idle' })
  return {
    ...dashboard,
    inbox: replaceAction501(dashboard.inbox),
    repositoryGroups: dashboard.repositoryGroups.map((repository) => ({
      ...repository,
      pullRequests: repository.pullRequests.map(replacePullRequestAction),
    })),
  }
}

function detailsForDashboard(
  dashboard: DashboardViewModel,
): Readonly<Record<string, PullRequestDetailModel>> {
  const details = structuredClone(pullRequestDetailsById)
  const pullRequest = dashboard.repositoryGroups
    .flatMap((repository) => repository.pullRequests)
    .find((candidate) => candidate.pullRequestId === 'pr_184')
  if (!pullRequest || !details.pr_184) return details
  return {
    ...details,
    pr_184: {
      ...details.pr_184,
      pullRequest: structuredClone(pullRequest),
      actionItems: structuredClone(pullRequest.actionItems),
    },
  }
}

export function createFixtureDashboardSource(
  journey: FixtureJourney = 'healthy-refresh',
): DashboardSource {
  let dashboard = structuredClone(baseDashboard)
  let pullRequestDetails = detailsForDashboard(dashboard)
  let refreshCount = 0
  let repositoryRefreshCount = 0
  let repositoryRefreshRegistered = false
  let newerMetadataPublished = false

  const publishDashboard = (nextDashboard: DashboardViewModel) => {
    dashboard = nextDashboard
    pullRequestDetails = detailsForDashboard(dashboard)
    return { type: 'snapshotChanged' as const, dashboard: structuredClone(dashboard) }
  }

  return {
    loadDashboard: async (afterRevision) => {
      if (afterRevision === 'dash_18' && dashboard.dashboardRevision === 'dash_18') {
        if ((journey === 'healthy-refresh' || journey === 'partial-refresh') && refreshCount >= 2) {
          return publishDashboard(
            journey === 'healthy-refresh'
              ? refreshedDashboard({ type: 'active', afterMilliseconds: 25 })
              : partialDashboard(),
          )
        }
        if (
          (journey === 'newer-activity' || journey === 'stale-acknowledgment') &&
          repositoryRefreshRegistered
        ) {
          newerMetadataPublished = true
          return publishDashboard(newerActivityDashboard())
        }
      }

      return afterRevision === dashboard.dashboardRevision
        ? {
            type: 'snapshotUnchanged',
            dashboardRevision: dashboard.dashboardRevision,
            serverTime: dashboard.generatedAt,
            polling: { type: 'idle' },
          }
        : { type: 'snapshotChanged', dashboard: structuredClone(dashboard) }
    },
    startRefresh: async () => {
      refreshCount += 1
      return {
        type: 'refreshRunRegistered',
        refreshRunId: `refresh_${refreshCount}`,
      }
    },
    loadPullRequest: async (pullRequestId) => {
      const detail = pullRequestDetails[pullRequestId]
      return detail
        ? { type: 'pullRequestAvailable', detail: structuredClone(detail) }
        : { type: 'pullRequestNotFound' }
    },
    loadActionContent: async (actionItemId, activityVersion) => {
      if (journey === 'content-unavailable') {
        return {
          type: 'contentUnavailable',
          reason: 'Activity content is temporarily unavailable.',
          retryable: true,
        }
      }
      if (
        journey === 'newer-activity' &&
        actionItemId === 'action_501' &&
        activityVersion === 'av_42'
      ) {
        return {
          type: 'newerActivityObserved',
          repositoryId: 'repo_payments',
          requestedActivityVersion: 'av_42',
          currentActivityVersion: 'av_43',
        }
      }
      if (actionItemId === 'action_501' && activityVersion === 'av_43' && !newerMetadataPublished) {
        return {
          type: 'contentUnavailable',
          reason: 'Newer activity content is unavailable until refreshed metadata is published.',
          retryable: true,
        }
      }
      const markdownSource = liveContentByActionVersion[`${actionItemId}:${activityVersion}`]
      return markdownSource
        ? {
            type: 'contentAvailable',
            actionItemId,
            activityVersion,
            markdownSource,
          }
        : {
            type: 'contentUnavailable',
            reason: 'Content unavailable',
            retryable: true,
          }
    },
    acknowledgeActionItem: async (actionItemId, activityVersion) => {
      if (
        journey === 'stale-acknowledgment' &&
        actionItemId === 'action_501' &&
        activityVersion === 'av_42'
      ) {
        return {
          type: 'staleActivityVersion',
          actionItemId,
          requestedActivityVersion: activityVersion,
          currentActivityVersion: 'av_43',
          hasNewerActivity: true,
        }
      }
      return { type: 'acknowledged', actionItemId, activityVersion }
    },
    startRepositoryRefresh: async (repositoryId, observedActivityVersion) => {
      repositoryRefreshCount += 1
      if (
        repositoryId === 'repo_payments' &&
        observedActivityVersion === 'av_43' &&
        (journey === 'newer-activity' || journey === 'stale-acknowledgment')
      ) {
        repositoryRefreshRegistered = true
      }
      return {
        type: 'refreshRunRegistered',
        refreshRunId: `refresh_repository_${repositoryRefreshCount}`,
      }
    },
  }
}

export const fixtureDashboardSource = createFixtureDashboardSource()
