import { describe, expect, it, vi } from 'vitest'
import {
  AllConfiguredRepositoriesTargetTypeEnum,
  ApiVersion,
  RepositoriesTargetTypeEnum,
} from '@/generated/api-v1/src'
import {
  acknowledgmentResult,
  contentResult,
  dashboardChangedResult,
  pullRequestFoundResult,
  refreshResult,
} from './apiTestData'
import { BrowserSessionManager } from './browserSession'
import {
  createKotlinApiDashboardSource,
  KotlinApiDashboardSource,
  type KotlinApiClients,
} from './kotlinApiDashboardSource'

const browserSession = {
  apiVersion: ApiVersion._1,
  requestId: 'req_test',
  result: {
    type: 'browserSession' as const,
    serviceInstanceId: 'svc_test',
    csrfToken: 'csrf_test',
  },
}

function refreshRegisteredResponse(refreshRunId: string, repositoryId: string) {
  const result = refreshResult([repositoryId]) as unknown as {
    type: 'refreshRunRegistered'
    refreshRun: { refreshRunId: string }
  }
  result.refreshRun.refreshRunId = refreshRunId
  return { apiVersion: ApiVersion._1, requestId: 'req_refresh', result }
}

function sourceWith(overrides: Partial<KotlinApiClients> = {}) {
  const clients: KotlinApiClients = {
    dashboard: { getDashboard: vi.fn().mockResolvedValue({ result: dashboardChangedResult() }) },
    refresh: { startRefreshRun: vi.fn().mockResolvedValue({ result: refreshResult() }) },
    pullRequests: {
      getPullRequest: vi.fn().mockResolvedValue({ result: pullRequestFoundResult() }),
    },
    actionItems: {
      getLiveActivityContent: vi.fn().mockResolvedValue({ result: contentResult() }),
      acknowledgeActionItem: vi.fn().mockResolvedValue({ result: acknowledgmentResult() }),
    },
    ...overrides,
  }
  const session = new BrowserSessionManager({
    getBrowserSession: vi.fn().mockResolvedValue(browserSession),
  })
  return { source: new KotlinApiDashboardSource(clients, session), clients }
}

describe('KotlinApiDashboardSource', () => {
  it('loads a dashboard through the generated read operation without a CSRF token', async () => {
    const getDashboard = vi.fn().mockResolvedValue({ result: dashboardChangedResult() })
    const { source } = sourceWith({ dashboard: { getDashboard } })

    await expect(source.loadDashboard('dash_1')).resolves.toMatchObject({ type: 'snapshotChanged' })
    expect(getDashboard).toHaveBeenCalledWith({ afterRevision: 'dash_1' })
  })

  it('registers all configured repositories through the canonical refresh target', async () => {
    const startRefreshRun = vi
      .fn()
      .mockResolvedValue(refreshRegisteredResponse('rr_all', 'repo_all'))
    const { source } = sourceWith({ refresh: { startRefreshRun } })

    await expect(source.startRefresh()).resolves.toEqual({
      type: 'refreshRunRegistered',
      refreshRunId: 'rr_all',
    })
    expect(startRefreshRun).toHaveBeenCalledWith({
      startRefreshRunRequest: {
        apiVersion: ApiVersion._1,
        target: { type: AllConfiguredRepositoriesTargetTypeEnum.allConfiguredRepositories },
      },
      xCSRFToken: 'csrf_test',
    })
  })

  it('loads a pull request through the generated read operation without a CSRF token', async () => {
    const getPullRequest = vi.fn().mockResolvedValue({ result: pullRequestFoundResult('pr_one') })
    const { source } = sourceWith({ pullRequests: { getPullRequest } })

    await expect(source.loadPullRequest('pr_one')).resolves.toMatchObject({
      type: 'pullRequestAvailable',
      detail: { pullRequest: { pullRequestId: 'pr_one' } },
    })
    expect(getPullRequest).toHaveBeenCalledWith({ pullRequestId: 'pr_one' })
  })

  it('loads action content through the generated read operation without a CSRF token', async () => {
    const getLiveActivityContent = vi.fn().mockResolvedValue({ result: contentResult() })
    const { source } = sourceWith({
      actionItems: {
        getLiveActivityContent,
        acknowledgeActionItem: vi.fn(),
      },
    })

    await expect(source.loadActionContent('ai_comment', 'av_comment_1')).resolves.toEqual({
      type: 'contentAvailable',
      actionItemId: 'ai_comment',
      activityVersion: 'av_comment_1',
      markdownSource: '# Hello',
    })
    expect(getLiveActivityContent).toHaveBeenCalledWith({
      actionItemId: 'ai_comment',
      activityVersion: 'av_comment_1',
    })
  })

  it('acknowledges an action item with the canonical request and CSRF token', async () => {
    const acknowledgeActionItem = vi.fn().mockResolvedValue({ result: acknowledgmentResult() })
    const { source } = sourceWith({
      actionItems: {
        getLiveActivityContent: vi.fn(),
        acknowledgeActionItem,
      },
    })

    await expect(source.acknowledgeActionItem('ai_comment', 'av_comment_1')).resolves.toEqual({
      type: 'acknowledged',
      actionItemId: 'ai_comment',
      activityVersion: 'av_comment_1',
    })
    expect(acknowledgeActionItem).toHaveBeenCalledWith({
      actionItemId: 'ai_comment',
      acknowledgeActionItemRequest: {
        apiVersion: ApiVersion._1,
        activityVersion: 'av_comment_1',
      },
      xCSRFToken: 'csrf_test',
    })
  })

  it('registers one repository through the canonical refresh target', async () => {
    const startRefreshRun = vi
      .fn()
      .mockResolvedValue(refreshRegisteredResponse('rr_one', 'repo_one'))
    const { source } = sourceWith({ refresh: { startRefreshRun } })

    await expect(source.startRepositoryRefresh('repo_one')).resolves.toEqual({
      type: 'refreshRunRegistered',
      refreshRunId: 'rr_one',
    })
    expect(startRefreshRun).toHaveBeenCalledWith({
      startRefreshRunRequest: {
        apiVersion: ApiVersion._1,
        target: {
          type: RepositoriesTargetTypeEnum.repositories,
          repositoryIds: new Set(['repo_one']),
        },
      },
      xCSRFToken: 'csrf_test',
    })
  })

  it('rejects a repository refresh registration that does not echo the requested target', async () => {
    const startRefreshRun = vi
      .fn()
      .mockResolvedValue(refreshRegisteredResponse('rr_other', 'repo_other'))
    const { source } = sourceWith({ refresh: { startRefreshRun } })

    await expect(source.startRepositoryRefresh('repo_requested')).rejects.toThrow(
      'Invalid refresh API model: requested repository set',
    )
  })

  it('propagates generated-client failures without mapping them', async () => {
    const failure = new Error('network failed')
    const getDashboard = vi.fn().mockRejectedValue(failure)
    const { source } = sourceWith({ dashboard: { getDashboard } })

    await expect(source.loadDashboard()).rejects.toBe(failure)
  })

  it('configures the generated clients with the relative API base path', async () => {
    const fetchApi = vi.fn((url: string) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            url.endsWith('/browser-session')
              ? browserSession
              : {
                  apiVersion: ApiVersion._1,
                  requestId: 'req_dashboard',
                  result: dashboardChangedResult(),
                },
          ),
          { headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )
    vi.stubGlobal('fetch', fetchApi)

    const source = createKotlinApiDashboardSource()
    await source.loadDashboard()

    expect(fetchApi.mock.calls.map(([url]) => url)).toContain('/api/v1/dashboard')
    vi.unstubAllGlobals()
  })
})
