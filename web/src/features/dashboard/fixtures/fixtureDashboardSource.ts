import type { DashboardSource } from '../dashboardSource'

export const fixtureDashboardSource: DashboardSource = {
  load: () =>
    Promise.resolve({
      type: 'dashboardAvailable',
      dashboard: {
        workspaceDisplayName: 'Mindtable',
        generatedAt: '2026-08-15T10:00:00Z',
        repositoryGroups: [
          {
            repositoryId: 'repo_payments',
            displayName: 'Payments API',
            webUrl: 'https://bitbucket.org/mindtable/payments-api',
            synchronization: { type: 'idle' },
            freshness: { type: 'fresh', ageDescription: '2 minutes ago' },
            pullRequests: [
              {
                pullRequestId: 'pr_184',
                displayNumber: 184,
                title: 'Keep dashboard revisions opaque',
                authorDisplayName: 'Artyom',
                updatedAt: '2026-08-15T09:58:00Z',
                webUrl: 'https://bitbucket.org/mindtable/payments-api/pull-requests/184',
                readiness: { type: 'available', passed: 6, total: 7 },
                buildState: { type: 'successful' },
                actionableItemCount: 2,
              },
            ],
          },
          {
            repositoryId: 'repo_portal',
            displayName: 'Developer Portal',
            webUrl: 'https://bitbucket.org/mindtable/developer-portal',
            synchronization: { type: 'running' },
            freshness: {
              type: 'stale',
              ageDescription: '18 minutes ago',
              staleSince: '2026-08-15T09:42:00Z',
            },
            pullRequests: [
              {
                pullRequestId: 'pr_52',
                displayNumber: 52,
                title: 'Surface stale acknowledgment',
                authorDisplayName: 'Morgan',
                updatedAt: '2026-08-15T09:45:00Z',
                webUrl: 'https://bitbucket.org/mindtable/developer-portal/pull-requests/52',
                readiness: { type: 'available', passed: 4, total: 7 },
                buildState: { type: 'inProgress' },
                actionableItemCount: 1,
              },
            ],
          },
        ],
      },
    }),
}
