// @ts-ignore DOM-only application type configuration does not expose Node's test-only APIs.
import { readFileSync } from 'node:fs'
// @ts-ignore DOM-only application type configuration does not expose Node's test-only APIs.
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  AcknowledgeActionItemResponseFromJSON, DashboardResponseFromJSON,
  LiveActivityContentResponseFromJSON, WorkspaceNotConfiguredResultSetupCommandEnum,
} from '@/generated/api-v1/src'
import { acknowledgmentResult, contentResult, dashboardChangedResult, pullRequestFoundResult, refreshResult } from './apiTestData'
import { mapAcknowledgmentResult, mapDashboardResult, mapLiveContentResult, mapPullRequestDetailResult, mapRefreshResult } from './apiMappers'

function contractFixture(relativePath: string): unknown {
  // @ts-ignore Vitest provides process even though the application target is the browser.
  return JSON.parse(readFileSync(join(process.cwd(), '../openapi/fixtures/v1', relativePath), 'utf8'))
}

describe('API result mappers', () => {
  it('maps a changed snapshot and filters closed action metadata', () => {
    const mapped = mapDashboardResult(dashboardChangedResult())
    expect(mapped.type).toBe('snapshotChanged')
    if (mapped.type !== 'snapshotChanged') throw new Error('expected snapshotChanged')
    expect(mapped.dashboard.workspaceDisplayName).toBe('Mindtable')
    expect(mapped.dashboard.repositoryGroups[0]?.pullRequests[0]?.displayNumber).toBe(42)
    expect(mapped.dashboard.repositoryGroups[0]?.pullRequests[0]?.actionItems).toHaveLength(1)
    expect(JSON.stringify(mapped.dashboard)).not.toContain('closed_action')
  })

  it('maps all top-level result variants', () => {
    expect(mapDashboardResult({ type: 'snapshotUnchanged', dashboardRevision: 'dr_1', serverTime: '2026-08-15T10:00:00Z', polling: { type: 'idle' } } as never)).toMatchObject({ type: 'snapshotUnchanged' })
    expect(mapDashboardResult({ type: 'workspaceNotConfigured', setupCommand: WorkspaceNotConfiguredResultSetupCommandEnum.bitbucket_helper_workspace_configure } as never)).toMatchObject({ type: 'workspaceNotConfigured' })
    expect(mapRefreshResult(refreshResult())).toEqual({ type: 'refreshRunRegistered', refreshRunId: 'rr_1' })
    expect(mapRefreshResult({ type: 'noRepositoriesConfigured' } as never)).toEqual({ type: 'noRepositoriesConfigured', setupCommand: 'bitbucket-helper repository add <slug>' })
    expect(mapRefreshResult({ type: 'workspaceNotConfigured', setupCommand: WorkspaceNotConfiguredResultSetupCommandEnum.bitbucket_helper_workspace_configure } as never)).toMatchObject({ type: 'workspaceNotConfigured' })
    expect(mapPullRequestDetailResult({ type: 'pullRequestNotFound', pullRequestId: 'pr_expected' } as never, 'pr_expected')).toEqual({ type: 'pullRequestNotFound' })
  })

  it('keeps workspace setup distinct from pull-request not found', () => {
    expect(mapPullRequestDetailResult({ type: 'workspaceNotConfigured', setupCommand: WorkspaceNotConfiguredResultSetupCommandEnum.bitbucket_helper_workspace_configure } as never, 'pr_expected')).toEqual({ type: 'workspaceNotConfigured', setupCommand: 'bitbucket-helper workspace configure' })
  })

  it('maps all content and acknowledgment variants', () => {
    for (const type of ['contentAvailable', 'contentUnavailable', 'newerActivityObserved', 'staleActivityVersion', 'actionItemNotFound'] as const) expect(mapLiveContentResult(contentResult(type), { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' }).type).toBe(type)
    for (const type of ['acknowledged', 'alreadyAcknowledged', 'staleActivityVersion', 'acknowledgmentRejected', 'actionItemNotFound'] as const) expect(mapAcknowledgmentResult(acknowledgmentResult(type), { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' }).type).toBe(type)
  })

  it('rejects mismatched echoes and invalid nested wire values', () => {
    expect(() => mapPullRequestDetailResult(pullRequestFoundResult('pr_other'), 'pr_expected')).toThrow('pull request response did not match the request')
    expect(() => mapLiveContentResult(contentResult('contentAvailable'), { actionItemId: 'ai_other', activityVersion: 'av_comment_1' })).toThrow('action item response did not match the request')
    const invalid = dashboardChangedResult() as any
    invalid.snapshot.repositoryGroups[0].pullRequests[0].readiness.total = 6
    expect(() => mapDashboardResult(invalid)).toThrow('Invalid dashboard API model: integer')
    invalid.snapshot.repositoryGroups[0].pullRequests[0].readiness.total = 7
    invalid.snapshot.repositoryGroups[0].pullRequests[0].actionItems[0].kind = 'UNKNOWN'
    expect(() => mapDashboardResult(invalid)).toThrow('Invalid dashboard API model: action kind')
  })

  it('maps generated contract fixtures and refuses decoded omissions', () => {
    expect(mapDashboardResult(DashboardResponseFromJSON(contractFixture('valid/dashboard-snapshot-unchanged.json')).result)).toEqual({ type: 'snapshotUnchanged', dashboardRevision: 'dr_dashboard_fixture', serverTime: '2026-08-15T17:30:00Z', polling: { type: 'idle' } })
    expect(mapLiveContentResult(LiveActivityContentResponseFromJSON(contractFixture('valid/live-content-available.json')).result, { actionItemId: 'ai_comment_fixture', activityVersion: 'av_comment_fixture_1' }).type).toBe('contentAvailable')
    expect(mapAcknowledgmentResult(AcknowledgeActionItemResponseFromJSON(contractFixture('valid/acknowledgment-already-applied.json')).result, { actionItemId: 'ai_comment_fixture', activityVersion: 'av_comment_fixture_1' }).type).toBe('alreadyAcknowledged')
    expect(() => DashboardResponseFromJSON(contractFixture('invalid/unknown-discriminator.json'))).toThrow()
    expect(() => mapDashboardResult({ type: 'snapshotUnchanged', dashboardRevision: undefined, serverTime: '2026-08-15T10:00:00Z', polling: { type: 'idle' } } as any)).toThrow('Invalid dashboard API model: string')
  })
})
