import type {
  AcknowledgeActionItemResult,
  DashboardResult,
  LiveActivityContentResult,
  PullRequestDetailResult,
  StartRefreshRunResult,
} from '@/generated/api-v1/src'

export const synchronizationActivities = ['idle', 'queued', 'running'] as const
export const buildStates = ['noBuilds', 'inProgress', 'successful', 'failed', 'unknown'] as const
export const actionKinds = ['COMMENT', 'reply', 'Thread', 'CHANGES_REQUESTED'] as const
export const contentUnavailableReasons = [
  'authentication', 'authorization', 'rateLimited', 'timeout', 'network', 'upstream',
  'malformedUpstream', 'deleted',
] as const

const checks = [
  { name: 'Contract', passed: true, safeReason: null },
  { name: 'Unit tests', passed: true, safeReason: null },
  { name: 'Integration tests', passed: true, safeReason: null },
  { name: 'Build', passed: true, safeReason: null },
  { name: 'Security scan', passed: true, safeReason: null },
  { name: 'Review approvals', passed: true, safeReason: null },
  { name: 'Unresolved tasks', passed: false, safeReason: null },
]

const action = {
  actionItemId: 'ai_comment', pullRequestId: 'pr_expected', repositoryId: 'repo_payments',
  repositoryDisplayName: 'Payments', pullRequestNumber: 42, pullRequestTitle: 'Improve mapper',
  activityVersion: 'av_comment_1', kind: 'COMMENT', actor: { stableId: 'u_mira', displayName: 'Mira' },
  activityAt: '2026-08-15T10:00:00Z', state: 'open', acknowledgedAt: null,
  webUrl: 'https://bitbucket.org/mindtable/payments/pull-requests/42#comment-1',
}

const closedAction = { ...action, actionItemId: 'ai_closed_action', state: 'closed' }

const card = {
  pullRequestId: 'pr_expected', repositoryId: 'repo_payments', upstreamNumber: 42,
  title: 'Improve mapper', author: { stableId: 'u_mira', displayName: 'Mira' }, draft: false,
  createdAt: '2026-08-15T09:00:00Z', updatedAt: '2026-08-15T10:00:00Z',
  webUrl: 'https://bitbucket.org/mindtable/payments/pull-requests/42',
  readiness: { type: 'available', passed: 6, total: 7, checks }, buildState: 'successful',
  actionableItemCount: 1, acknowledgedItemCount: 0, actionItems: [action, closedAction],
}

const snapshot = {
  dashboardRevision: 'dr_dashboard_1', generatedAt: '2026-08-15T10:01:00Z',
  workspace: {
    workspaceId: 'ws_mindtable', bitbucketApiBaseUrl: 'https://api.bitbucket.org', workspaceSlug: 'mindtable',
    workspaceDisplayName: 'Mindtable', workspaceWebUrl: 'https://bitbucket.org/mindtable', retentionDays: 30,
    repositories: [],
  },
  repositoryGroups: [{
    repositoryId: 'repo_payments', slug: 'payments', displayName: 'Payments',
    webUrl: 'https://bitbucket.org/mindtable/payments', repositoryRevision: 'rrev_1',
    synchronization: {
      repositoryId: 'repo_payments', activity: 'running', lastAttemptAt: '2026-08-15T10:00:00Z',
      lastAttemptOutcome: null, lastSuccessAt: '2026-08-15T09:00:00Z',
      freshness: { type: 'fresh', snapshotAt: '2026-08-15T10:00:00Z', ageMilliseconds: 60_000 },
      problem: { type: 'none' },
    }, readinessSummary: { readyPullRequestCount: 0, availablePullRequestCount: 1, unavailablePullRequestCount: 0 },
    pullRequests: [card],
  }],
  inbox: { items: [action, closedAction] }, polling: { type: 'active', afterMilliseconds: 25 },
}

export function dashboardChangedResult(): DashboardResult {
  return { type: 'snapshotChanged', snapshot: structuredClone(snapshot) } as unknown as DashboardResult
}

export function pullRequestFoundResult(pullRequestId = 'pr_expected'): PullRequestDetailResult {
  const pullRequest = structuredClone(card)
  pullRequest.pullRequestId = pullRequestId
  pullRequest.actionItems = pullRequest.actionItems.map((action) => ({
    ...action,
    pullRequestId,
  }))
  return {
    type: 'pullRequestFound',
    pullRequest: { pullRequest, headCommit: 'abc123', builds: [{ key: 'build_1', state: 'successful' }], freshness: { type: 'fresh', snapshotAt: '2026-08-15T10:00:00Z', ageMilliseconds: 0 } },
  } as unknown as PullRequestDetailResult
}

export function refreshResult(): StartRefreshRunResult {
  return { type: 'refreshRunRegistered', refreshRun: { refreshRunId: 'rr_1', createdAt: '2026-08-15T10:00:00Z', expiresAt: '2026-08-15T11:00:00Z', repositories: [] }, dispositions: [] } as unknown as StartRefreshRunResult
}

export function contentResult(type: 'contentAvailable' | 'contentUnavailable' | 'newerActivityObserved' | 'staleActivityVersion' | 'actionItemNotFound' = 'contentAvailable'): LiveActivityContentResult {
  const base = { actionItemId: 'ai_comment', requestedVersion: 'av_comment_1' }
  switch (type) {
    case 'contentAvailable': return { type, ...base, markdown: '# Hello', fetchedAt: '2026-08-15T10:02:00Z' } as unknown as LiveActivityContentResult
    case 'contentUnavailable': return { type, ...base, reason: 'network', retryable: true, retryAt: null } as unknown as LiveActivityContentResult
    case 'newerActivityObserved': return { type, ...base, observedVersion: 'av_comment_2', repositoryId: 'repo_payments' } as unknown as LiveActivityContentResult
    case 'staleActivityVersion': return { type, ...base, current: { ...action, activityVersion: 'av_comment_2' } } as unknown as LiveActivityContentResult
    case 'actionItemNotFound': return { type, ...base } as unknown as LiveActivityContentResult
  }
}

export function acknowledgmentResult(type: 'acknowledged' | 'alreadyAcknowledged' | 'staleActivityVersion' | 'acknowledgmentRejected' | 'actionItemNotFound' = 'acknowledged'): AcknowledgeActionItemResult {
  const base = { actionItemId: 'ai_comment', requestedVersion: 'av_comment_1' }
  switch (type) {
    case 'acknowledged': return { type, ...base, acknowledgedAt: '2026-08-15T10:02:00Z' } as unknown as AcknowledgeActionItemResult
    case 'alreadyAcknowledged': return { type, ...base } as unknown as AcknowledgeActionItemResult
    case 'staleActivityVersion': return { type, ...base, currentActivityVersion: 'av_comment_2', hasNewerActivity: true } as unknown as AcknowledgeActionItemResult
    case 'acknowledgmentRejected': return { type, ...base, reason: 'untrusted' } as unknown as AcknowledgeActionItemResult
    case 'actionItemNotFound': return { type, ...base } as unknown as AcknowledgeActionItemResult
  }
}
