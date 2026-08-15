import type {
  ActionItemSummary,
  DashboardViewModel,
  PullRequestDetailModel,
  PullRequestSummary,
  ReadinessCheckModel,
  RepositoryGroupModel,
} from '../dashboard.models'

export const action501: ActionItemSummary = {
  actionItemId: 'action_501',
  activityVersion: 'av_42',
  repositoryId: 'repo_payments',
  pullRequestId: 'pr_184',
  kind: 'comment',
  actorDisplayName: 'Alex Chen',
  occurredAt: '2026-08-15T09:57:00Z',
  acknowledgmentState: 'actionable',
  webUrl: 'https://bitbucket.org/mindtable/payments-api/pull-requests/184#comment-501',
}

export const action502: ActionItemSummary = {
  actionItemId: 'action_502',
  activityVersion: 'av_18',
  repositoryId: 'repo_store',
  pullRequestId: 'pr_92',
  kind: 'changesRequested',
  actorDisplayName: 'Sam Rivera',
  occurredAt: '2026-08-15T09:52:00Z',
  acknowledgmentState: 'actionable',
  webUrl: 'https://bitbucket.org/mindtable/web-store/pull-requests/92#changes-requested-502',
}

const paymentsPullRequests: readonly PullRequestSummary[] = [
  {
    pullRequestId: 'pr_184',
    repositoryId: 'repo_payments',
    displayNumber: 184,
    title: 'Add retry budget',
    authorDisplayName: 'Mira',
    updatedAt: '2026-08-15T09:48:00Z',
    webUrl: 'https://bitbucket.org/mindtable/payments-api/pull-requests/184',
    readiness: { type: 'available', passed: 6, total: 7 },
    buildState: { type: 'successful' },
    actionableItemCount: 1,
    acknowledgedItemCount: 0,
    actionItems: [action501],
  },
  {
    pullRequestId: 'pr_179',
    repositoryId: 'repo_payments',
    displayNumber: 179,
    title: 'Remove legacy token',
    authorDisplayName: 'Noah',
    updatedAt: '2026-08-15T08:00:00Z',
    webUrl: 'https://bitbucket.org/mindtable/payments-api/pull-requests/179',
    readiness: { type: 'available', passed: 7, total: 7 },
    buildState: { type: 'successful' },
    actionableItemCount: 0,
    acknowledgedItemCount: 1,
    actionItems: [],
  },
]

const storePullRequests: readonly PullRequestSummary[] = [
  {
    pullRequestId: 'pr_92',
    repositoryId: 'repo_store',
    displayNumber: 92,
    title: 'Harden CSRF validation',
    authorDisplayName: 'Iris',
    updatedAt: '2026-08-15T09:40:00Z',
    webUrl: 'https://bitbucket.org/mindtable/web-store/pull-requests/92',
    readiness: { type: 'available', passed: 5, total: 7 },
    buildState: { type: 'failed', failedCheckCount: 2 },
    actionableItemCount: 1,
    acknowledgedItemCount: 0,
    actionItems: [action502],
  },
]

export const paymentsRepository: RepositoryGroupModel = {
  repositoryId: 'repo_payments',
  slug: 'payments-api',
  displayName: 'Payments API',
  webUrl: 'https://bitbucket.org/mindtable/payments-api',
  repositoryRevision: 'repo_11',
  synchronization: { type: 'running' },
  freshness: { type: 'fresh', ageDescription: '1 minute ago' },
  problem: { type: 'none' },
  pullRequests: paymentsPullRequests,
}

export const storeRepository: RepositoryGroupModel = {
  repositoryId: 'repo_store',
  slug: 'web-store',
  displayName: 'Web Store',
  webUrl: 'https://bitbucket.org/mindtable/web-store',
  repositoryRevision: 'repo_7',
  synchronization: { type: 'queued' },
  freshness: { type: 'fresh', ageDescription: '4 minutes ago' },
  problem: { type: 'none' },
  pullRequests: storePullRequests,
}

export const baseDashboard: DashboardViewModel = {
  dashboardRevision: 'dash_18',
  generatedAt: '2026-08-15T10:00:00Z',
  workspaceDisplayName: 'Mindtable',
  polling: { type: 'idle' },
  repositoryGroups: [paymentsRepository, storeRepository],
  inbox: [action501, action502],
}

const checkLabels = [
  'Contract',
  'Unit tests',
  'Integration tests',
  'Build',
  'Security scan',
  'Review approvals',
  'Unresolved tasks',
] as const

function checksFor(
  states: readonly ReadinessCheckModel['state'][],
): readonly ReadinessCheckModel[] {
  return checkLabels.map((label, index) => ({
    checkId: `check_${index + 1}`,
    label,
    state: states[index] ?? 'unavailable',
  }))
}

export const pullRequestDetailsById: Readonly<Record<string, PullRequestDetailModel>> = {
  pr_184: {
    repositoryDisplayName: 'Payments API',
    pullRequest: paymentsPullRequests[0]!,
    readinessChecks: checksFor([
      'passed',
      'passed',
      'passed',
      'passed',
      'passed',
      'passed',
      'pending',
    ]),
    actionItems: [action501],
  },
  pr_179: {
    repositoryDisplayName: 'Payments API',
    pullRequest: paymentsPullRequests[1]!,
    readinessChecks: checksFor([
      'passed',
      'passed',
      'passed',
      'passed',
      'passed',
      'passed',
      'passed',
    ]),
    actionItems: [],
  },
  pr_92: {
    repositoryDisplayName: 'Web Store',
    pullRequest: storePullRequests[0]!,
    readinessChecks: checksFor([
      'passed',
      'passed',
      'passed',
      'failed',
      'failed',
      'passed',
      'passed',
    ]),
    actionItems: [action502],
  },
}

export const liveContentByActionVersion: Readonly<Record<string, string>> = {
  'action_501:av_42': 'Could we cap the retry window and add a metric for exhausted attempts?',
  'action_501:av_43':
    'Please cap the retry window at 30 seconds and emit a metric for exhausted attempts.',
}
