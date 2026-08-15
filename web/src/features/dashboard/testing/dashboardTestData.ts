import type {
  ActionItemSummary,
  DashboardViewModel,
  PullRequestDetailModel,
  PullRequestSummary,
  RepositoryGroupModel,
} from '../dashboard.models'

export function makeActionItem(overrides: Partial<ActionItemSummary> = {}): ActionItemSummary {
  return {
    actionItemId: 'action_1',
    activityVersion: 'activity_version_1',
    repositoryId: 'repository_1',
    pullRequestId: 'pull_request_1',
    kind: 'comment',
    actorDisplayName: 'Avery',
    occurredAt: '2026-08-15T10:00:00Z',
    acknowledgmentState: 'actionable',
    webUrl: 'https://bitbucket.org/acme/repository/pull-requests/1#comment-1',
    ...overrides,
  }
}

export function makePullRequest(overrides: Partial<PullRequestSummary> = {}): PullRequestSummary {
  return {
    pullRequestId: 'pull_request_1',
    repositoryId: 'repository_1',
    displayNumber: 1,
    title: 'Example pull request',
    authorDisplayName: 'Avery',
    updatedAt: '2026-08-15T10:00:00Z',
    webUrl: 'https://bitbucket.org/acme/repository/pull-requests/1',
    readiness: { type: 'available', passed: 7, total: 7 },
    buildState: { type: 'successful' },
    actionableItemCount: 0,
    acknowledgedItemCount: 0,
    actionItems: [],
    ...overrides,
  }
}

export function makeRepository(
  overrides: Partial<RepositoryGroupModel> = {},
): RepositoryGroupModel {
  return {
    repositoryId: 'repository_1',
    slug: 'repository',
    displayName: 'Example repository',
    webUrl: 'https://bitbucket.org/acme/repository',
    repositoryRevision: 'repository_revision_1',
    synchronization: { type: 'idle' },
    freshness: { type: 'fresh', ageDescription: '1 minute ago' },
    problem: { type: 'none' },
    pullRequests: [],
    ...overrides,
  }
}

export function makeDashboard(overrides: Partial<DashboardViewModel> = {}): DashboardViewModel {
  return {
    dashboardRevision: 'dashboard_revision_1',
    generatedAt: '2026-08-15T10:00:00Z',
    workspaceDisplayName: 'Acme Engineering',
    polling: { type: 'idle' },
    repositoryGroups: [],
    inbox: [],
    ...overrides,
  }
}

export function makePullRequestDetail(
  options: { pullRequestId?: string } = {},
): PullRequestDetailModel {
  const pullRequest = makePullRequest({
    pullRequestId: options.pullRequestId ?? 'pull_request_1',
  })
  return {
    repositoryDisplayName: 'Example repository',
    pullRequest,
    readinessChecks: [],
    actionItems: [],
  }
}
