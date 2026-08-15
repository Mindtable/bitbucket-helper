import type { DashboardSource } from '../dashboardSource'
import {
  baseDashboard,
  liveContentByActionVersion,
  pullRequestDetailsById,
} from './fixtureDashboardData'

export function createFixtureDashboardSource(): DashboardSource {
  const dashboard = structuredClone(baseDashboard)
  return {
    loadDashboard: async (afterRevision) =>
      afterRevision === dashboard.dashboardRevision
        ? {
            type: 'snapshotUnchanged',
            dashboardRevision: dashboard.dashboardRevision,
            serverTime: dashboard.generatedAt,
            polling: { type: 'idle' },
          }
        : { type: 'snapshotChanged', dashboard: structuredClone(dashboard) },
    startRefresh: async () => ({
      type: 'refreshRunRegistered',
      refreshRunId: 'refresh_1',
    }),
    loadPullRequest: async (pullRequestId) => {
      const detail = pullRequestDetailsById[pullRequestId]
      return detail
        ? { type: 'pullRequestAvailable', detail: structuredClone(detail) }
        : { type: 'pullRequestNotFound' }
    },
    loadActionContent: async (actionItemId, activityVersion) => {
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
    acknowledgeActionItem: async (actionItemId, activityVersion) => ({
      type: 'acknowledged',
      actionItemId,
      activityVersion,
    }),
    startRepositoryRefresh: async () => ({
      type: 'refreshRunRegistered',
      refreshRunId: 'refresh_repository_1',
    }),
  }
}

export const fixtureDashboardSource = createFixtureDashboardSource()
