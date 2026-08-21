import {
  ActionItemsApi,
  AllConfiguredRepositoriesTargetTypeEnum,
  ApiVersion,
  BrowserSecurityApi,
  Configuration,
  DashboardApi,
  PullRequestsApi,
  RefreshApi,
  RepositoriesTargetTypeEnum,
} from '@/generated/api-v1/src'
import type { DashboardSource } from '../dashboardSource'
import {
  mapAcknowledgmentResult,
  mapDashboardResult,
  mapLiveContentResult,
  mapPullRequestDetailResult,
  mapRefreshResult,
} from './apiMappers'
import { BrowserSessionManager } from './browserSession'

export interface KotlinApiClients {
  dashboard: Pick<DashboardApi, 'getDashboard'>
  refresh: Pick<RefreshApi, 'startRefreshRun'>
  pullRequests: Pick<PullRequestsApi, 'getPullRequest'>
  actionItems: Pick<ActionItemsApi, 'getLiveActivityContent' | 'acknowledgeActionItem'>
}

const allTarget = {
  apiVersion: ApiVersion._1,
  target: { type: AllConfiguredRepositoriesTargetTypeEnum.allConfiguredRepositories },
}

function repositoryTarget(repositoryId: string) {
  return {
    apiVersion: ApiVersion._1,
    target: {
      type: RepositoriesTargetTypeEnum.repositories,
      repositoryIds: new Set([repositoryId]),
    },
  }
}

function acknowledgment(activityVersion: string) {
  return {
    apiVersion: ApiVersion._1,
    activityVersion,
  }
}

export class KotlinApiDashboardSource implements DashboardSource {
  constructor(
    private readonly clients: KotlinApiClients,
    private readonly session: BrowserSessionManager,
  ) {}

  async loadDashboard(afterRevision?: string) {
    const response = await this.clients.dashboard.getDashboard({ afterRevision })
    return mapDashboardResult(response.result)
  }

  async startRefresh() {
    return this.session.runMutation(async (csrfToken) => {
      const response = await this.clients.refresh.startRefreshRun({
        startRefreshRunRequest: allTarget as never,
        xCSRFToken: csrfToken,
      })
      return mapRefreshResult(response.result, { type: 'allConfiguredRepositories' })
    })
  }

  async loadPullRequest(pullRequestId: string) {
    const response = await this.clients.pullRequests.getPullRequest({ pullRequestId })
    return mapPullRequestDetailResult(response.result, pullRequestId)
  }

  async loadActionContent(actionItemId: string, activityVersion: string) {
    const response = await this.clients.actionItems.getLiveActivityContent({
      actionItemId,
      activityVersion,
    })
    return mapLiveContentResult(response.result, { actionItemId, activityVersion })
  }

  async acknowledgeActionItem(actionItemId: string, activityVersion: string) {
    return this.session.runMutation(async (csrfToken) => {
      const response = await this.clients.actionItems.acknowledgeActionItem({
        actionItemId,
        acknowledgeActionItemRequest: acknowledgment(activityVersion),
        xCSRFToken: csrfToken,
      })
      return mapAcknowledgmentResult(response.result, { actionItemId, activityVersion })
    })
  }

  async startRepositoryRefresh(repositoryId: string) {
    return this.session.runMutation(async (csrfToken) => {
      const response = await this.clients.refresh.startRefreshRun({
        startRefreshRunRequest: repositoryTarget(repositoryId) as never,
        xCSRFToken: csrfToken,
      })
      return mapRefreshResult(response.result, {
        type: 'repositories',
        repositoryIds: [repositoryId],
      })
    })
  }
}

export function createKotlinApiDashboardSource(): DashboardSource {
  const configuration = new Configuration({ basePath: '/api/v1' })
  const session = new BrowserSessionManager(new BrowserSecurityApi(configuration))
  session.prefetch()
  return new KotlinApiDashboardSource(
    {
      dashboard: new DashboardApi(configuration),
      refresh: new RefreshApi(configuration),
      pullRequests: new PullRequestsApi(configuration),
      actionItems: new ActionItemsApi(configuration),
    },
    session,
  )
}
