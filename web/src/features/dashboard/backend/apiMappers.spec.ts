// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore DOM-only application type configuration does not expose Node's test-only APIs.
import { readFileSync } from 'node:fs'
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore DOM-only application type configuration does not expose Node's test-only APIs.
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  AcknowledgeActionItemResponseFromJSON,
  DashboardResponseFromJSON,
  LiveActivityContentResponseFromJSON,
  StartRefreshRunResponseFromJSON,
  WorkspaceNotConfiguredResultSetupCommandEnum,
} from '@/generated/api-v1/src'
import {
  acknowledgmentResult,
  actionKinds,
  buildStates,
  contentResult,
  contentUnavailableReasons,
  dashboardChangedResult,
  pullRequestFoundResult,
  refreshResult,
  synchronizationActivities,
} from './apiTestData'
import {
  mapAcknowledgmentResult,
  mapDashboardResult,
  mapLiveContentResult,
  mapPullRequestDetailResult,
  mapRefreshResult,
} from './apiMappers'

function contractFixture(relativePath: string): unknown {
  const currentDirectory = (globalThis as unknown as { process: { cwd(): string } }).process.cwd()
  return JSON.parse(
    readFileSync(join(currentDirectory, '../openapi/fixtures/v1', relativePath), 'utf8'),
  )
}

interface MutableActionWire {
  actionItemId: string
  kind: string
  pullRequestId: string
  repositoryId: string
  state: string
}

interface MutablePullRequestWire {
  actionItems: [MutableActionWire, MutableActionWire]
  actionableItemCount: number
  acknowledgedItemCount: number
  author: unknown
  buildState: string
  pullRequestId: string
  readiness: { total: number }
  repositoryId: string
  upstreamNumber: number
}

interface MutableDashboardWire {
  snapshot: {
    generatedAt: string
    polling: { afterMilliseconds: number }
    repositoryGroups: [
      {
        pullRequests: [MutablePullRequestWire]
        readinessSummary: { readyPullRequestCount: number }
        synchronization: { activity: string; freshness: unknown; problem: unknown }
      },
    ]
    workspace: { workspaceWebUrl: string }
  }
}

function changedWire(): MutableDashboardWire {
  return structuredClone(dashboardChangedResult()) as unknown as MutableDashboardWire
}

function mapChanged(wire: MutableDashboardWire) {
  return mapDashboardResult(wire as never)
}

function captureError(action: () => unknown): unknown {
  try {
    action()
    return undefined
  } catch (error) {
    return error
  }
}

const allConfiguredRepositories = { type: 'allConfiguredRepositories' } as const

function refreshWire(repositoryIds: readonly string[] = ['repo_refresh']) {
  return structuredClone(refreshResult(repositoryIds)) as unknown as {
    type: 'refreshRunRegistered'
    refreshRun: {
      refreshRunId: string
      createdAt: unknown
      expiresAt: unknown
      repositories: Array<Record<string, unknown>>
    }
    dispositions: Array<Record<string, unknown>>
  }
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
    expect(
      mapDashboardResult({
        type: 'snapshotUnchanged',
        dashboardRevision: 'dr_1',
        serverTime: '2026-08-15T10:00:00Z',
        polling: { type: 'idle' },
      } as never),
    ).toMatchObject({ type: 'snapshotUnchanged' })
    expect(
      mapDashboardResult({
        type: 'workspaceNotConfigured',
        setupCommand:
          WorkspaceNotConfiguredResultSetupCommandEnum.bitbucket_helper_workspace_configure,
      } as never),
    ).toMatchObject({ type: 'workspaceNotConfigured' })
    expect(mapRefreshResult(refreshResult(), allConfiguredRepositories)).toEqual({
      type: 'refreshRunRegistered',
      refreshRunId: 'rr_1',
    })
    expect(
      mapRefreshResult({ type: 'noRepositoriesConfigured' } as never, allConfiguredRepositories),
    ).toEqual({
      type: 'noRepositoriesConfigured',
      setupCommand: 'bitbucket-helper repository add <slug>',
    })
    expect(
      mapRefreshResult(
        {
          type: 'workspaceNotConfigured',
          setupCommand:
            WorkspaceNotConfiguredResultSetupCommandEnum.bitbucket_helper_workspace_configure,
        } as never,
        allConfiguredRepositories,
      ),
    ).toMatchObject({ type: 'workspaceNotConfigured' })
    expect(
      mapPullRequestDetailResult(
        { type: 'pullRequestNotFound', pullRequestId: 'pr_expected' } as never,
        'pr_expected',
      ),
    ).toEqual({ type: 'pullRequestNotFound' })
  })

  it('keeps workspace setup distinct from pull-request not found', () => {
    expect(
      mapPullRequestDetailResult(
        {
          type: 'workspaceNotConfigured',
          setupCommand:
            WorkspaceNotConfiguredResultSetupCommandEnum.bitbucket_helper_workspace_configure,
        } as never,
        'pr_expected',
      ),
    ).toEqual({
      type: 'workspaceNotConfigured',
      setupCommand: 'bitbucket-helper workspace configure',
    })
  })

  it('accepts only the fixed generated workspace setup command', () => {
    const unexpected = 'bitbucket-helper workspace configure --token secret-value'
    const assertions = [
      () =>
        mapDashboardResult({
          type: 'workspaceNotConfigured',
          setupCommand: unexpected,
        } as never),
      () =>
        mapRefreshResult(
          { type: 'workspaceNotConfigured', setupCommand: unexpected } as never,
          allConfiguredRepositories,
        ),
      () =>
        mapPullRequestDetailResult(
          { type: 'workspaceNotConfigured', setupCommand: unexpected } as never,
          'pr_expected',
        ),
    ]

    for (const assertion of assertions) {
      expect(assertion).toThrow('Invalid')
      expect(String(captureError(assertion))).not.toContain('secret-value')
    }
  })

  it('maps all content and acknowledgment variants', () => {
    for (const type of [
      'contentAvailable',
      'contentUnavailable',
      'newerActivityObserved',
      'staleActivityVersion',
      'actionItemNotFound',
    ] as const)
      expect(
        mapLiveContentResult(contentResult(type), {
          actionItemId: 'ai_comment',
          activityVersion: 'av_comment_1',
        }).type,
      ).toBe(type)
    for (const type of [
      'acknowledged',
      'alreadyAcknowledged',
      'staleActivityVersion',
      'acknowledgmentRejected',
      'actionItemNotFound',
    ] as const)
      expect(
        mapAcknowledgmentResult(acknowledgmentResult(type), {
          actionItemId: 'ai_comment',
          activityVersion: 'av_comment_1',
        }).type,
      ).toBe(type)
  })

  it('rejects mismatched echoes and invalid nested wire values', () => {
    expect(() =>
      mapPullRequestDetailResult(pullRequestFoundResult('pr_other'), 'pr_expected'),
    ).toThrow('pull request response did not match the request')
    expect(() =>
      mapLiveContentResult(contentResult('contentAvailable'), {
        actionItemId: 'ai_other',
        activityVersion: 'av_comment_1',
      }),
    ).toThrow('action item response did not match the request')
    const invalid = changedWire()
    invalid.snapshot.repositoryGroups[0].pullRequests[0].readiness.total = 6
    expect(() => mapChanged(invalid)).toThrow('Invalid dashboard API model: integer')
    invalid.snapshot.repositoryGroups[0].pullRequests[0].readiness.total = 7
    invalid.snapshot.repositoryGroups[0].pullRequests[0].actionItems[0].kind = 'UNKNOWN'
    expect(() => mapChanged(invalid)).toThrow('Invalid dashboard API model: action kind')
  })

  it('rejects cards and actions whose identities do not echo their containers', () => {
    const cardRepositoryMismatch = changedWire()
    cardRepositoryMismatch.snapshot.repositoryGroups[0].pullRequests[0].repositoryId = 'repo_other'
    expect(() => mapChanged(cardRepositoryMismatch)).toThrow(
      'Invalid dashboard API model: repository response',
    )

    const actionRepositoryMismatch = changedWire()
    actionRepositoryMismatch.snapshot.repositoryGroups[0].pullRequests[0].actionItems[0].repositoryId =
      'repo_other'
    expect(() => mapChanged(actionRepositoryMismatch)).toThrow(
      'Invalid dashboard API model: action response',
    )

    const actionPullRequestMismatch = changedWire()
    actionPullRequestMismatch.snapshot.repositoryGroups[0].pullRequests[0].actionItems[0].pullRequestId =
      'pr_other'
    expect(() => mapChanged(actionPullRequestMismatch)).toThrow(
      'Invalid dashboard API model: action response',
    )

    const closedActionMismatch = changedWire()
    closedActionMismatch.snapshot.repositoryGroups[0].pullRequests[0].actionItems[1].repositoryId =
      'repo_other'
    expect(() => mapChanged(closedActionMismatch)).toThrow(
      'Invalid dashboard API model: action response',
    )
  })

  it('rejects readiness summaries that do not match the mapped cards', () => {
    const invalid = changedWire()
    invalid.snapshot.repositoryGroups[0].readinessSummary.readyPullRequestCount = 1
    expect(() => mapChanged(invalid)).toThrow('Invalid dashboard API model: count')
  })

  it('maps every nested presentation variant with fixed copy', () => {
    const activePolling = mapChanged(changedWire())
    expect(activePolling).toMatchObject({
      type: 'snapshotChanged',
      dashboard: { polling: { type: 'active', afterMilliseconds: 25 } },
    })
    for (const activity of synchronizationActivities) {
      const wire = changedWire()
      wire.snapshot.repositoryGroups[0].synchronization.activity = activity
      const mapped = mapChanged(wire)
      expect(
        mapped.type === 'snapshotChanged' &&
          mapped.dashboard.repositoryGroups[0]?.synchronization.type,
      ).toBe(activity)
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
    const problemMapped = mapChanged(problemWire)
    expect(problemMapped).toMatchObject({
      type: 'snapshotChanged',
      dashboard: {
        repositoryGroups: [
          {
            problem: {
              type: 'present',
              message: 'Bitbucket rate limiting delayed this content.',
              retryable: true,
              retryAfterDescription: 'Retry after the service backoff expires.',
            },
          },
        ],
      },
    })
    for (const [freshness, expected] of [
      [{ type: 'neverSynchronized' }, { type: 'neverSynchronized' }],
      [
        { type: 'fresh', snapshotAt: '2026-08-15T10:00:00Z', ageMilliseconds: 0 },
        { type: 'fresh', ageDescription: 'Just now' },
      ],
      [
        { type: 'fresh', snapshotAt: '2026-08-15T10:00:00Z', ageMilliseconds: 60_000 },
        { type: 'fresh', ageDescription: '1 minute ago' },
      ],
      [
        { type: 'fresh', snapshotAt: '2026-08-15T10:00:00Z', ageMilliseconds: 120_000 },
        { type: 'fresh', ageDescription: '2 minutes ago' },
      ],
      [
        { type: 'fresh', snapshotAt: '2026-08-15T10:00:00Z', ageMilliseconds: 3_600_000 },
        { type: 'fresh', ageDescription: '1 hour ago' },
      ],
      [
        {
          type: 'stale',
          snapshotAt: '2026-08-15T10:00:00Z',
          ageMilliseconds: 172_800_000,
          staleSince: '2026-08-15T09:00:00Z',
        },
        { type: 'stale', ageDescription: '2 days ago', staleSince: '2026-08-15T09:00:00Z' },
      ],
    ] as const) {
      const wire = changedWire()
      wire.snapshot.repositoryGroups[0].synchronization.freshness = freshness
      const mapped = mapChanged(wire)
      expect(mapped).toMatchObject({
        type: 'snapshotChanged',
        dashboard: { repositoryGroups: [{ freshness: expected }] },
      })
    }
    for (const [buildState, expected] of buildStates.map(
      (state) =>
        [
          state,
          state === 'noBuilds'
            ? { type: 'unavailable', reason: 'No builds are available.' }
            : state === 'unknown'
              ? { type: 'unavailable', reason: 'Build status is unavailable.' }
              : { type: state },
        ] as const,
    )) {
      const wire = changedWire()
      wire.snapshot.repositoryGroups[0].pullRequests[0].buildState = buildState
      const mapped = mapChanged(wire)
      expect(mapped).toMatchObject({
        type: 'snapshotChanged',
        dashboard: { repositoryGroups: [{ pullRequests: [{ buildState: expected }] }] },
      })
    }
    for (const [kind, expected] of actionKinds.map(
      (kind) => [kind, kind === 'CHANGES_REQUESTED' ? 'changesRequested' : 'comment'] as const,
    )) {
      const wire = changedWire()
      wire.snapshot.repositoryGroups[0].pullRequests[0].actionItems[0].kind = kind
      const mapped = mapChanged(wire)
      expect(
        mapped.type === 'snapshotChanged' &&
          mapped.dashboard.repositoryGroups[0]?.pullRequests[0]?.actionItems[0]?.kind,
      ).toBe(expected)
    }
    for (const [state, actionable, acknowledged, expected] of [
      ['open', 1, 0, 'actionable'],
      ['acknowledged', 0, 1, 'acknowledged'],
      ['closed', 0, 0, undefined],
    ] as const) {
      const wire = changedWire()
      wire.snapshot.repositoryGroups[0].pullRequests[0].actionItems[0].state = state
      wire.snapshot.repositoryGroups[0].pullRequests[0].actionableItemCount = actionable
      wire.snapshot.repositoryGroups[0].pullRequests[0].acknowledgedItemCount = acknowledged
      const mapped = mapChanged(wire)
      expect(
        mapped.type === 'snapshotChanged' &&
          mapped.dashboard.repositoryGroups[0]?.pullRequests[0]?.actionItems[0]
            ?.acknowledgmentState,
      ).toBe(expected)
    }
    const detail = mapPullRequestDetailResult(
      {
        type: 'pullRequestFound',
        pullRequest: {
          pullRequest: {
            ...changedWire().snapshot.repositoryGroups[0].pullRequests[0],
            readiness: { type: 'unavailable', safeReason: 'Checks are unavailable.' },
          },
          headCommit: 'abc123',
          builds: [],
          freshness: { type: 'neverSynchronized' },
        },
      } as never,
      'pr_expected',
    )
    expect(detail).toMatchObject({ type: 'pullRequestAvailable', detail: { readinessChecks: [] } })
    const unavailableCheck = pullRequestFoundResult() as unknown as {
      pullRequest: { pullRequest: { readiness: { checks: Array<{ safeReason: string | null }> } } }
    }
    unavailableCheck.pullRequest.pullRequest.readiness.checks[6]!.safeReason =
      'The check service is unavailable.'
    const unavailableCheckMapped = mapPullRequestDetailResult(
      unavailableCheck as never,
      'pr_expected',
    )
    expect(unavailableCheckMapped).toMatchObject({
      type: 'pullRequestAvailable',
      detail: {
        readinessChecks: [
          expect.anything(),
          expect.anything(),
          expect.anything(),
          expect.anything(),
          expect.anything(),
          expect.anything(),
          { state: 'unavailable' },
        ],
      },
    })
    const failedCheck = pullRequestFoundResult()
    const failedCheckMapped = mapPullRequestDetailResult(failedCheck, 'pr_expected')
    expect(failedCheckMapped).toMatchObject({
      type: 'pullRequestAvailable',
      detail: {
        readinessChecks: [
          expect.anything(),
          expect.anything(),
          expect.anything(),
          expect.anything(),
          expect.anything(),
          expect.anything(),
          { state: 'failed' },
        ],
      },
    })
  })

  it('rejects live-content-only deleted as a synchronization failure category', () => {
    const wire = changedWire()
    wire.snapshot.repositoryGroups[0].synchronization.problem = {
      type: 'present',
      partialFailure: {
        attemptedCount: 1,
        succeededCount: 0,
        failedCount: 1,
        failures: [{ category: 'deleted', retryable: false, retryAt: null }],
      },
    }

    expect(() => mapChanged(wire)).toThrow('Invalid dashboard API model: failure category')
  })

  it('validates every refresh repository and disposition variant', () => {
    const wire = refreshWire([
      'repo_queued',
      'repo_running',
      'repo_succeeded',
      'repo_partial',
      'repo_failed',
      'repo_deferred',
      'repo_not_configured',
    ])
    wire.refreshRun.repositories = [
      { type: 'queued', repositoryId: 'repo_queued' },
      { type: 'running', repositoryId: 'repo_running' },
      {
        type: 'succeeded',
        repositoryId: 'repo_succeeded',
        completedAt: '2026-08-15T10:05:00Z',
      },
      {
        type: 'partialFailure',
        repositoryId: 'repo_partial',
        completedAt: '2026-08-15T10:06:00Z',
        partialFailure: {
          attemptedCount: 2,
          succeededCount: 1,
          failedCount: 1,
          failures: [
            {
              category: 'rateLimited',
              retryable: true,
              retryAt: '2026-08-15T10:10:00Z',
            },
          ],
        },
      },
      {
        type: 'failed',
        repositoryId: 'repo_failed',
        completedAt: '2026-08-15T10:07:00Z',
        failure: { category: 'network', retryable: false, retryAt: null },
      },
      {
        type: 'deferredByBackoff',
        repositoryId: 'repo_deferred',
        retryAt: '2026-08-15T10:11:00Z',
      },
    ]
    wire.dispositions = [
      { type: 'started', repositoryId: 'repo_queued' },
      { type: 'joinedExisting', repositoryId: 'repo_running' },
      {
        type: 'deferredByBackoff',
        repositoryId: 'repo_succeeded',
        retryAt: '2026-08-15T10:12:00Z',
      },
      { type: 'started', repositoryId: 'repo_partial' },
      { type: 'started', repositoryId: 'repo_failed' },
      { type: 'joinedExisting', repositoryId: 'repo_deferred' },
      { type: 'repositoryNotConfigured', repositoryId: 'repo_not_configured' },
    ]

    expect(mapRefreshResult(wire as never, allConfiguredRepositories)).toEqual({
      type: 'refreshRunRegistered',
      refreshRunId: 'rr_1',
    })
  })

  it('maps an explicit repository-not-configured disposition without a run entry', () => {
    const wire = refreshWire(['repo_missing'])
    wire.refreshRun.repositories = []
    wire.dispositions = [{ type: 'repositoryNotConfigured', repositoryId: 'repo_missing' }]

    expect(
      mapRefreshResult(wire as never, {
        type: 'repositories',
        repositoryIds: ['repo_missing'],
      }),
    ).toEqual({ type: 'refreshRunRegistered', refreshRunId: 'rr_1' })
  })

  it('rejects omitted run entries for every configured disposition', () => {
    const dispositions = [
      { type: 'started', repositoryId: 'repo_configured' },
      { type: 'joinedExisting', repositoryId: 'repo_configured' },
      {
        type: 'deferredByBackoff',
        repositoryId: 'repo_configured',
        retryAt: '2026-08-15T10:12:00Z',
      },
    ]

    for (const disposition of dispositions) {
      const wire = refreshWire(['repo_configured'])
      wire.refreshRun.repositories = []
      wire.dispositions = [disposition]
      expect(() => mapRefreshResult(wire as never, allConfiguredRepositories)).toThrow(
        'Invalid refresh API model: repository set',
      )
    }
  })

  it('rejects empty and duplicate refresh repository registrations', () => {
    const empty = refreshWire([])
    expect(() => mapRefreshResult(empty as never, allConfiguredRepositories)).toThrow(
      'Invalid refresh API model: registration repositories',
    )

    const duplicateDisposition = refreshWire()
    duplicateDisposition.dispositions.push({
      ...duplicateDisposition.dispositions[0]!,
    })
    expect(() =>
      mapRefreshResult(duplicateDisposition as never, allConfiguredRepositories),
    ).toThrow('Invalid refresh API model: disposition repository IDs')

    const duplicateRepository = refreshWire()
    duplicateRepository.refreshRun.repositories.push({
      ...duplicateRepository.refreshRun.repositories[0]!,
    })
    expect(() => mapRefreshResult(duplicateRepository as never, allConfiguredRepositories)).toThrow(
      'Invalid refresh API model: refresh repository IDs',
    )
  })

  it('rejects malformed refresh variants and required nested fields', () => {
    const mutations: Array<(wire: ReturnType<typeof refreshWire>) => void> = [
      (wire) => {
        wire.refreshRun.repositories[0]!.type = 'unknown'
      },
      (wire) => {
        wire.dispositions[0]!.type = 'unknown'
      },
      (wire) => {
        wire.refreshRun.repositories[0]!.repositoryId = ''
      },
      (wire) => {
        wire.refreshRun.repositories[0] = {
          type: 'succeeded',
          repositoryId: 'repo_refresh',
          completedAt: 'not-an-instant',
        }
      },
      (wire) => {
        wire.refreshRun.repositories[0] = {
          type: 'partialFailure',
          repositoryId: 'repo_refresh',
          completedAt: '2026-08-15T10:06:00Z',
          partialFailure: {
            attemptedCount: 1,
            succeededCount: 0,
            failedCount: 1,
            failures: [{ category: 'deleted', retryable: false, retryAt: null }],
          },
        }
      },
      (wire) => {
        wire.refreshRun.repositories[0] = {
          type: 'failed',
          repositoryId: 'repo_refresh',
          completedAt: '2026-08-15T10:07:00Z',
          failure: { category: 'network', retryable: 'yes', retryAt: null },
        }
      },
      (wire) => {
        wire.dispositions[0] = {
          type: 'deferredByBackoff',
          repositoryId: 'repo_refresh',
          retryAt: undefined,
        }
      },
    ]

    for (const mutate of mutations) {
      const wire = refreshWire()
      mutate(wire)
      expect(() => mapRefreshResult(wire as never, allConfiguredRepositories)).toThrow(
        'Invalid refresh API model:',
      )
    }
  })

  it('rejects internal and requested repository ID mismatches', () => {
    const internalMismatch = refreshWire()
    internalMismatch.dispositions[0]!.repositoryId = 'repo_other'
    expect(() => mapRefreshResult(internalMismatch as never, allConfiguredRepositories)).toThrow(
      'Invalid refresh API model: repository set',
    )

    expect(() =>
      mapRefreshResult(refreshResult(['repo_actual']), {
        type: 'repositories',
        repositoryIds: ['repo_requested'],
      }),
    ).toThrow('Invalid refresh API model: requested repository set')
  })

  it('maps every content-unavailable reason and rejects malformed values and version echoes', () => {
    const copy = {
      authentication: 'Bitbucket authentication failed.',
      authorization: 'Bitbucket authorization failed.',
      rateLimited: 'Bitbucket rate limiting delayed this content.',
      timeout: 'Bitbucket content loading timed out.',
      network: 'Bitbucket content is unavailable because of a network failure.',
      upstream: 'Bitbucket could not provide this content.',
      malformedUpstream: 'Bitbucket returned content in an unsupported form.',
      deleted: 'This activity was deleted.',
    } as const
    for (const reason of contentUnavailableReasons) {
      const result = contentResult('contentUnavailable') as unknown as Record<string, unknown>
      result.reason = reason
      expect(
        mapLiveContentResult(result as never, {
          actionItemId: 'ai_comment',
          activityVersion: 'av_comment_1',
        }),
      ).toMatchObject({ type: 'contentUnavailable', reason: copy[reason] })
    }
    expect(() =>
      mapLiveContentResult(
        {
          ...(contentResult('contentAvailable') as unknown as Record<string, unknown>),
          requestedVersion: 'av_other',
        } as never,
        { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' },
      ),
    ).toThrow('activity version response did not match the request')
    expect(() =>
      mapAcknowledgmentResult(
        {
          ...(acknowledgmentResult('acknowledged') as unknown as Record<string, unknown>),
          requestedVersion: 'av_other',
        } as never,
        { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' },
      ),
    ).toThrow('activity version response did not match the request')
    expect(() =>
      mapAcknowledgmentResult(
        {
          ...(acknowledgmentResult('acknowledged') as unknown as Record<string, unknown>),
          actionItemId: 'ai_other',
        } as never,
        { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' },
      ),
    ).toThrow('action item response did not match the request')
    expect(() =>
      mapAcknowledgmentResult(
        {
          ...(acknowledgmentResult('staleActivityVersion') as unknown as Record<string, unknown>),
          hasNewerActivity: false,
        } as never,
        { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' },
      ),
    ).toThrow('Invalid acknowledgment API model: stale activity')
    expect(() =>
      mapLiveContentResult(
        {
          ...(contentResult('contentAvailable') as unknown as Record<string, unknown>),
          markdown: undefined,
        } as never,
        { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' },
      ),
    ).toThrow('Invalid action content API model: string')
    expect(() =>
      mapAcknowledgmentResult(
        {
          ...(acknowledgmentResult('acknowledged') as unknown as Record<string, unknown>),
          acknowledgedAt: undefined,
        } as never,
        { actionItemId: 'ai_comment', activityVersion: 'av_comment_1' },
      ),
    ).toThrow('Invalid acknowledgment API model: string')
    for (const [mutate, expected] of [
      [
        (wire: MutableDashboardWire) => {
          wire.snapshot.generatedAt = 'not-an-instant'
        },
        'instant',
      ],
      [
        (wire: MutableDashboardWire) => {
          wire.snapshot.workspace.workspaceWebUrl = 'https://user:pass@bitbucket.org/mindtable'
        },
        'url',
      ],
      [
        (wire: MutableDashboardWire) => {
          wire.snapshot.repositoryGroups[0]!.pullRequests[0]!.upstreamNumber = -1
        },
        'integer',
      ],
      [
        (wire: MutableDashboardWire) => {
          wire.snapshot.polling.afterMilliseconds = 0
        },
        'integer',
      ],
      [
        (wire: MutableDashboardWire) => {
          wire.snapshot.repositoryGroups[0]!.pullRequests[0]!.author = undefined
        },
        'object',
      ],
    ] as const) {
      const wire = changedWire()
      mutate(wire)
      expect(() => mapChanged(wire)).toThrow(`Invalid dashboard API model: ${expected}`)
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
    expect(
      mapDashboardResult(
        DashboardResponseFromJSON(contractFixture('valid/dashboard-snapshot-unchanged.json'))
          .result,
      ),
    ).toEqual({
      type: 'snapshotUnchanged',
      dashboardRevision: 'dr_dashboard_fixture',
      serverTime: '2026-08-15T17:30:00Z',
      polling: { type: 'idle' },
    })
    expect(
      mapRefreshResult(
        StartRefreshRunResponseFromJSON(contractFixture('valid/refresh-run-registered.json'))
          .result,
        allConfiguredRepositories,
      ),
    ).toEqual({
      type: 'refreshRunRegistered',
      refreshRunId: 'rr_refresh_fixture',
    })
    expect(
      mapLiveContentResult(
        LiveActivityContentResponseFromJSON(contractFixture('valid/live-content-available.json'))
          .result,
        { actionItemId: 'ai_comment_fixture', activityVersion: 'av_comment_fixture_1' },
      ).type,
    ).toBe('contentAvailable')
    expect(
      mapAcknowledgmentResult(
        AcknowledgeActionItemResponseFromJSON(
          contractFixture('valid/acknowledgment-already-applied.json'),
        ).result,
        { actionItemId: 'ai_comment_fixture', activityVersion: 'av_comment_fixture_1' },
      ).type,
    ).toBe('alreadyAcknowledged')
    expect(() =>
      DashboardResponseFromJSON(contractFixture('invalid/unknown-discriminator.json')),
    ).toThrow()
    expect(() =>
      mapDashboardResult({
        type: 'snapshotUnchanged',
        dashboardRevision: undefined,
        serverTime: '2026-08-15T10:00:00Z',
        polling: { type: 'idle' },
      } as never),
    ).toThrow('Invalid dashboard API model: string')
  })
})
