// @ts-ignore DOM-only application type configuration does not expose Node's test-only APIs.
import { readFileSync } from 'node:fs'
// @ts-ignore DOM-only application type configuration does not expose Node's test-only APIs.
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  AcknowledgeActionItemResponseFromJSON, DashboardResponseFromJSON,
  LiveActivityContentResponseFromJSON, WorkspaceNotConfiguredResultSetupCommandEnum,
} from '@/generated/api-v1/src'
import {
  acknowledgmentResult, actionKinds, buildStates, contentResult, contentUnavailableReasons,
  dashboardChangedResult, pullRequestFoundResult, refreshResult, synchronizationActivities,
} from './apiTestData'
import { mapAcknowledgmentResult, mapDashboardResult, mapLiveContentResult, mapPullRequestDetailResult, mapRefreshResult } from './apiMappers'

function contractFixture(relativePath: string): unknown {
  // @ts-ignore Vitest provides process even though the application target is the browser.
  return JSON.parse(readFileSync(join(process.cwd(), '../openapi/fixtures/v1', relativePath), 'utf8'))
}

function changedWire(): any {
  return structuredClone(dashboardChangedResult())
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

  it('rejects cards and actions whose identities do not echo their containers', () => {
    const cardRepositoryMismatch = changedWire()
    cardRepositoryMismatch.snapshot.repositoryGroups[0].pullRequests[0].repositoryId = 'repo_other'
    expect(() => mapDashboardResult(cardRepositoryMismatch)).toThrow('Invalid dashboard API model: repository response')

    const actionRepositoryMismatch = changedWire()
    actionRepositoryMismatch.snapshot.repositoryGroups[0].pullRequests[0].actionItems[0].repositoryId = 'repo_other'
    expect(() => mapDashboardResult(actionRepositoryMismatch)).toThrow('Invalid dashboard API model: action response')

    const actionPullRequestMismatch = changedWire()
    actionPullRequestMismatch.snapshot.repositoryGroups[0].pullRequests[0].actionItems[0].pullRequestId = 'pr_other'
    expect(() => mapDashboardResult(actionPullRequestMismatch)).toThrow('Invalid dashboard API model: action response')

    const closedActionMismatch = changedWire()
    closedActionMismatch.snapshot.repositoryGroups[0].pullRequests[0].actionItems[1].repositoryId = 'repo_other'
    expect(() => mapDashboardResult(closedActionMismatch)).toThrow('Invalid dashboard API model: action response')
  })

  it('rejects readiness summaries that do not match the mapped cards', () => {
    const invalid = changedWire()
    invalid.snapshot.repositoryGroups[0].readinessSummary.readyPullRequestCount = 1
    expect(() => mapDashboardResult(invalid)).toThrow('Invalid dashboard API model: count')
  })

  it('maps every nested presentation variant with fixed copy', () => {
    const activePolling = mapDashboardResult(changedWire())
    expect(activePolling).toMatchObject({
      type: 'snapshotChanged',
      dashboard: { polling: { type: 'active', afterMilliseconds: 25 } },
    })
    for (const activity of synchronizationActivities) {
      const wire = changedWire()
      wire.snapshot.repositoryGroups[0].synchronization.activity = activity
      const mapped = mapDashboardResult(wire)
      expect(mapped.type === 'snapshotChanged' && mapped.dashboard.repositoryGroups[0]?.synchronization.type).toBe(activity)
    }
    const problemWire = changedWire()
    problemWire.snapshot.repositoryGroups[0].synchronization.problem = {
      type: 'present',
      partialFailure: {
        attemptedCount: 2,
        succeededCount: 1,
        failedCount: 1,
        failures: [{ category: 'rateLimited', retryable: true, retryAt: '2026-08-15T11:00:00Z' }],
      },
    }
    const problemMapped = mapDashboardResult(problemWire)
    expect(problemMapped).toMatchObject({
      type: 'snapshotChanged',
      dashboard: { repositoryGroups: [{ problem: {
        type: 'present',
        message: 'Bitbucket rate limiting delayed this content.',
        retryable: true,
        retryAfterDescription: 'Retry after the service backoff expires.',
      } }] },
    })
    for (const [freshness, expected] of [
      [{ type: 'neverSynchronized' }, { type: 'neverSynchronized' }],
      [{ type: 'fresh', snapshotAt: '2026-08-15T10:00:00Z', ageMilliseconds: 0 }, { type: 'fresh', ageDescription: 'Just now' }],
      [{ type: 'fresh', snapshotAt: '2026-08-15T10:00:00Z', ageMilliseconds: 60_000 }, { type: 'fresh', ageDescription: '1 minute ago' }],
      [{ type: 'fresh', snapshotAt: '2026-08-15T10:00:00Z', ageMilliseconds: 120_000 }, { type: 'fresh', ageDescription: '2 minutes ago' }],
      [{ type: 'fresh', snapshotAt: '2026-08-15T10:00:00Z', ageMilliseconds: 3_600_000 }, { type: 'fresh', ageDescription: '1 hour ago' }],
      [{ type: 'stale', snapshotAt: '2026-08-15T10:00:00Z', ageMilliseconds: 172_800_000, staleSince: '2026-08-15T09:00:00Z' }, { type: 'stale', ageDescription: '2 days ago', staleSince: '2026-08-15T09:00:00Z' }],
    ] as const) {
      const wire = changedWire()
      wire.snapshot.repositoryGroups[0].synchronization.freshness = freshness
      const mapped = mapDashboardResult(wire)
      expect(mapped).toMatchObject({ type: 'snapshotChanged', dashboard: { repositoryGroups: [{ freshness: expected }] } })
    }
    for (const [buildState, expected] of buildStates.map((state) => [state, state === 'noBuilds' ? { type: 'unavailable', reason: 'No builds are available.' } : state === 'unknown' ? { type: 'unavailable', reason: 'Build status is unavailable.' } : { type: state }] as const)) {
      const wire = changedWire()
      wire.snapshot.repositoryGroups[0].pullRequests[0].buildState = buildState
      const mapped = mapDashboardResult(wire)
      expect(mapped).toMatchObject({ type: 'snapshotChanged', dashboard: { repositoryGroups: [{ pullRequests: [{ buildState: expected }] }] } })
    }
    for (const [kind, expected] of actionKinds.map((kind) => [kind, kind === 'CHANGES_REQUESTED' ? 'changesRequested' : 'comment'] as const)) {
      const wire = changedWire()
      wire.snapshot.repositoryGroups[0].pullRequests[0].actionItems[0].kind = kind
      const mapped = mapDashboardResult(wire)
      expect(mapped.type === 'snapshotChanged' && mapped.dashboard.repositoryGroups[0]?.pullRequests[0]?.actionItems[0]?.kind).toBe(expected)
    }
    for (const [state, actionable, acknowledged, expected] of [['open', 1, 0, 'actionable'], ['acknowledged', 0, 1, 'acknowledged'], ['closed', 0, 0, undefined]] as const) {
      const wire = changedWire()
      wire.snapshot.repositoryGroups[0].pullRequests[0].actionItems[0].state = state
      wire.snapshot.repositoryGroups[0].pullRequests[0].actionableItemCount = actionable
      wire.snapshot.repositoryGroups[0].pullRequests[0].acknowledgedItemCount = acknowledged
      const mapped = mapDashboardResult(wire)
      expect(mapped.type === 'snapshotChanged' && mapped.dashboard.repositoryGroups[0]?.pullRequests[0]?.actionItems[0]?.acknowledgmentState).toBe(expected)
    }
    const detail = mapPullRequestDetailResult({ type: 'pullRequestFound', pullRequest: { pullRequest: { ...changedWire().snapshot.repositoryGroups[0].pullRequests[0], readiness: { type: 'unavailable', safeReason: 'Checks are unavailable.' } }, headCommit: 'abc123', builds: [], freshness: { type: 'neverSynchronized' } } } as never, 'pr_expected')
    expect(detail).toMatchObject({ type: 'pullRequestAvailable', detail: { readinessChecks: [] } })
    const unavailableCheck = pullRequestFoundResult() as any
    unavailableCheck.pullRequest.pullRequest.readiness.checks[6].safeReason = 'The check service is unavailable.'
    const unavailableCheckMapped = mapPullRequestDetailResult(unavailableCheck, 'pr_expected')
    expect(unavailableCheckMapped).toMatchObject({ type: 'pullRequestAvailable', detail: { readinessChecks: [expect.anything(), expect.anything(), expect.anything(), expect.anything(), expect.anything(), expect.anything(), { state: 'unavailable' }] } })
    const failedCheck = pullRequestFoundResult() as any
    const failedCheckMapped = mapPullRequestDetailResult(failedCheck, 'pr_expected')
    expect(failedCheckMapped).toMatchObject({ type: 'pullRequestAvailable', detail: { readinessChecks: [expect.anything(), expect.anything(), expect.anything(), expect.anything(), expect.anything(), expect.anything(), { state: 'failed' }] } })
  })

  it('maps every content-unavailable reason and rejects malformed values and version echoes', () => {
    const copy = {
      authentication: 'Bitbucket authentication failed.', authorization: 'Bitbucket authorization failed.', rateLimited: 'Bitbucket rate limiting delayed this content.', timeout: 'Bitbucket content loading timed out.', network: 'Bitbucket content is unavailable because of a network failure.', upstream: 'Bitbucket could not provide this content.', malformedUpstream: 'Bitbucket returned content in an unsupported form.', deleted: 'This activity was deleted.',
    } as const
    for (const reason of contentUnavailableReasons) {
      const result = contentResult('contentUnavailable') as any
      result.reason = reason
      expect(mapLiveContentResult(result, { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' })).toMatchObject({ type: 'contentUnavailable', reason: copy[reason] })
    }
    expect(() => mapLiveContentResult({ ...contentResult('contentAvailable') as any, requestedVersion: 'av_other' }, { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' })).toThrow('activity version response did not match the request')
    expect(() => mapAcknowledgmentResult({ ...acknowledgmentResult('acknowledged') as any, requestedVersion: 'av_other' }, { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' })).toThrow('activity version response did not match the request')
    expect(() => mapAcknowledgmentResult({ ...acknowledgmentResult('acknowledged') as any, actionItemId: 'ai_other' }, { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' })).toThrow('action item response did not match the request')
    expect(() => mapAcknowledgmentResult({ ...acknowledgmentResult('staleActivityVersion') as any, hasNewerActivity: false }, { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' })).toThrow('Invalid acknowledgment API model: stale activity')
    expect(() => mapLiveContentResult({ ...contentResult('contentAvailable') as any, markdown: undefined }, { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' })).toThrow('Invalid action content API model: string')
    expect(() => mapAcknowledgmentResult({ ...acknowledgmentResult('acknowledged') as any, acknowledgedAt: undefined }, { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' })).toThrow('Invalid acknowledgment API model: string')
    for (const [mutate, expected] of [
      [(wire: any) => { wire.snapshot.generatedAt = 'not-an-instant' }, 'instant'],
      [(wire: any) => { wire.snapshot.workspace.workspaceWebUrl = 'https://user:pass@bitbucket.org/mindtable' }, 'url'],
      [(wire: any) => { wire.snapshot.repositoryGroups[0].pullRequests[0].upstreamNumber = -1 }, 'integer'],
      [(wire: any) => { wire.snapshot.polling.afterMilliseconds = 0 }, 'integer'],
      [(wire: any) => { wire.snapshot.repositoryGroups[0].pullRequests[0].author = undefined }, 'object'],
    ] as const) {
      const wire = changedWire()
      mutate(wire)
      expect(() => mapDashboardResult(wire)).toThrow(`Invalid dashboard API model: ${expected}`)
    }
  })

  it('maps a positive pull-request detail with checks and actions', () => {
    const result = mapPullRequestDetailResult(pullRequestFoundResult(), 'pr_expected')
    expect(result.type).toBe('pullRequestAvailable')
    if (result.type !== 'pullRequestAvailable') throw new Error('expected pull-request detail')
    expect(result.detail.pullRequest.pullRequestId).toBe('pr_expected')
    expect(result.detail.readinessChecks[0]).toMatchObject({ state: 'passed' })
    expect(result.detail.actionItems[0]).toMatchObject({ actionItemId: 'ai_comment' })
  })

  it('maps generated contract fixtures and refuses decoded omissions', () => {
    expect(mapDashboardResult(DashboardResponseFromJSON(contractFixture('valid/dashboard-snapshot-unchanged.json')).result)).toEqual({ type: 'snapshotUnchanged', dashboardRevision: 'dr_dashboard_fixture', serverTime: '2026-08-15T17:30:00Z', polling: { type: 'idle' } })
    expect(mapLiveContentResult(LiveActivityContentResponseFromJSON(contractFixture('valid/live-content-available.json')).result, { actionItemId: 'ai_comment_fixture', activityVersion: 'av_comment_fixture_1' }).type).toBe('contentAvailable')
    expect(mapAcknowledgmentResult(AcknowledgeActionItemResponseFromJSON(contractFixture('valid/acknowledgment-already-applied.json')).result, { actionItemId: 'ai_comment_fixture', activityVersion: 'av_comment_fixture_1' }).type).toBe('alreadyAcknowledged')
    expect(() => DashboardResponseFromJSON(contractFixture('invalid/unknown-discriminator.json'))).toThrow()
    expect(() => mapDashboardResult({ type: 'snapshotUnchanged', dashboardRevision: undefined, serverTime: '2026-08-15T10:00:00Z', polling: { type: 'idle' } } as any)).toThrow('Invalid dashboard API model: string')
  })
})
