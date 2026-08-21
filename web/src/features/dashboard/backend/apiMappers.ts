import type {
  AcknowledgeActionItemResult,
  DashboardResult,
  LiveActivityContentResult,
  PullRequestDetailResult,
  StartRefreshRunResult,
} from '@/generated/api-v1/src'
import type {
  ActionItemSummary, BuildState, DashboardViewModel, FreshnessState, PollingState,
  PullRequestSummary, ReadinessCheckModel, ReadinessState, RepositoryGroupModel,
  SynchronizationProblemState, SynchronizationState,
} from '../dashboard.models'
import type {
  AcknowledgmentSourceResult, ActionContentSourceResult, DashboardSourceResult,
  PullRequestDetailSourceModel, PullRequestDetailSourceResult, RefreshSourceResult,
} from '../dashboardSource'

const NO_REPOSITORIES_SETUP_COMMAND = 'bitbucket-helper repository add <slug>'

const CONTENT_REASON_COPY = {
  authentication: 'Bitbucket authentication failed.', authorization: 'Bitbucket authorization failed.',
  rateLimited: 'Bitbucket rate limiting delayed this content.', timeout: 'Bitbucket content loading timed out.',
  network: 'Bitbucket content is unavailable because of a network failure.', upstream: 'Bitbucket could not provide this content.',
  malformedUpstream: 'Bitbucket returned content in an unsupported form.', deleted: 'This activity was deleted.',
} as const

type ModelKind = 'dashboard' | 'pull request' | 'action content' | 'acknowledgment' | 'refresh'

function invalid(kind: ModelKind, category: string): never { throw new Error(`Invalid ${kind} API model: ${category}`) }
function string(value: unknown, kind: ModelKind): string { if (typeof value !== 'string' || value.length === 0) invalid(kind, 'string'); return value }
function integer(value: unknown, kind: ModelKind, positive = false): number { if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < (positive ? 1 : 0)) invalid(kind, 'integer'); return value }
function instant(value: unknown, kind: ModelKind): string { const text = string(value, kind); if (!text.endsWith('Z') || Number.isNaN(Date.parse(text))) invalid(kind, 'instant'); return text }
function url(value: unknown, kind: ModelKind): string { const text = string(value, kind); try { const parsed = new URL(text); if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password) invalid(kind, 'url') } catch { invalid(kind, 'url') }; return text }
function boolean(value: unknown, kind: ModelKind): boolean { if (typeof value !== 'boolean') invalid(kind, 'boolean'); return value }
function array<T>(value: unknown, kind: ModelKind): readonly T[] { if (!Array.isArray(value)) invalid(kind, 'array'); return value as readonly T[] }
function record(value: unknown, kind: ModelKind): Record<string, unknown> { if (!value || typeof value !== 'object') invalid(kind, 'object'); return value as Record<string, unknown> }
function assertNever(value: never): never { throw new Error(`Unsupported API result: ${String(value)}`) }

function mapPolling(value: unknown, kind: ModelKind): PollingState {
  const polling = record(value, kind)
  switch (polling.type) {
    case 'idle': return { type: 'idle' }
    case 'active': return { type: 'active', afterMilliseconds: integer(polling.afterMilliseconds, kind, true) }
    default: return invalid(kind, 'polling')
  }
}

function mapFreshness(value: unknown, kind: ModelKind): FreshnessState {
  const freshness = record(value, kind)
  switch (freshness.type) {
    case 'neverSynchronized': return { type: 'neverSynchronized' }
    case 'fresh': instant(freshness.snapshotAt, kind); return { type: 'fresh', ageDescription: age(integer(freshness.ageMilliseconds, kind)) }
    case 'stale': instant(freshness.snapshotAt, kind); return { type: 'stale', ageDescription: age(integer(freshness.ageMilliseconds, kind)), staleSince: instant(freshness.staleSince, kind) }
    default: return invalid(kind, 'freshness')
  }
}

function age(milliseconds: number): string {
  if (milliseconds < 60_000) return 'Just now'
  const minutes = Math.floor(milliseconds / 60_000)
  if (minutes < 60) return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'} ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} ${hours === 1 ? 'hour' : 'hours'} ago`
  const days = Math.floor(hours / 24)
  return `${days} ${days === 1 ? 'day' : 'days'} ago`
}

function mapProblem(value: unknown): SynchronizationProblemState {
  const problem = record(value, 'dashboard')
  switch (problem.type) {
    case 'none': return { type: 'none' }
    case 'present': {
      const partial = record(problem.partialFailure, 'dashboard')
      const attempted = integer(partial.attemptedCount, 'dashboard', true)
      const succeeded = integer(partial.succeededCount, 'dashboard')
      const failed = integer(partial.failedCount, 'dashboard', true)
      const failures = array<Record<string, unknown>>(partial.failures, 'dashboard')
      if (attempted !== succeeded + failed || failures.length !== failed) invalid('dashboard', 'count')
      const categories = failures.map((failure) => {
        const item = record(failure, 'dashboard'); const category = string(item.category, 'dashboard')
        if (!Object.prototype.hasOwnProperty.call(CONTENT_REASON_COPY, category)) invalid('dashboard', 'failure category')
        boolean(item.retryable, 'dashboard'); if (item.retryAt !== null) instant(item.retryAt, 'dashboard')
        return category as keyof typeof CONTENT_REASON_COPY
      })
      const retryAt = failures.some((failure) => record(failure, 'dashboard').retryAt !== null)
      return { type: 'present', message: CONTENT_REASON_COPY[categories[0]!], retryable: failures.some((failure) => boolean(record(failure, 'dashboard').retryable, 'dashboard')), retryAfterDescription: retryAt ? 'Retry after the service backoff expires.' : null }
    }
    default: return invalid('dashboard', 'synchronization problem')
  }
}

function mapSynchronization(value: unknown, repositoryId: string): { synchronization: SynchronizationState; freshness: FreshnessState; problem: SynchronizationProblemState } {
  const source = record(value, 'dashboard')
  if (string(source.repositoryId, 'dashboard') !== repositoryId) invalid('dashboard', 'repository response')
  if (source.lastAttemptAt !== null) instant(source.lastAttemptAt, 'dashboard')
  if (source.lastSuccessAt !== null) instant(source.lastSuccessAt, 'dashboard')
  const activity = string(source.activity, 'dashboard')
  const synchronization: SynchronizationState = activity === 'idle' || activity === 'queued' || activity === 'running' ? { type: activity } : invalid('dashboard', 'synchronization')
  return { synchronization, freshness: mapFreshness(source.freshness, 'dashboard'), problem: mapProblem(source.problem) }
}

function mapReadiness(value: unknown, kind: ModelKind): ReadinessState {
  const readiness = record(value, kind)
  switch (readiness.type) {
    case 'unavailable': return { type: 'unavailable', reason: string(readiness.safeReason, kind) }
    case 'available': {
      const total = integer(readiness.total, kind); const passed = integer(readiness.passed, kind)
      const checks = array<unknown>(readiness.checks, kind)
      if (total !== 7 || passed > total || checks.length !== total || checks.filter((check) => boolean(record(check, kind).passed, kind)).length !== passed) invalid(kind, 'integer')
      return { type: 'available', passed, total: 7 }
    }
    default: return invalid(kind, 'readiness')
  }
}

function mapChecks(value: unknown): readonly ReadinessCheckModel[] {
  const readiness = record(value, 'pull request')
  if (readiness.type === 'unavailable') { string(readiness.safeReason, 'pull request'); return [] }
  if (readiness.type !== 'available') return invalid('pull request', 'readiness')
  const total = integer(readiness.total, 'pull request'); const passed = integer(readiness.passed, 'pull request'); const checks = array<unknown>(readiness.checks, 'pull request')
  if (total !== 7 || checks.length !== 7 || passed !== checks.filter((check) => boolean(record(check, 'pull request').passed, 'pull request')).length) invalid('pull request', 'integer')
  return checks.map((check, index) => { const source = record(check, 'pull request'); const isPassed = boolean(source.passed, 'pull request'); const reason = source.safeReason; if (reason !== null) string(reason, 'pull request'); return { checkId: `${index + 1}`, label: string(source.name, 'pull request'), state: isPassed ? 'passed' : reason ? 'unavailable' : 'failed' } })
}

function mapBuild(value: unknown, kind: ModelKind): BuildState {
  switch (value) {
    case 'successful': return { type: 'successful' }
    case 'failed': return { type: 'failed' }
    case 'inProgress': return { type: 'inProgress' }
    case 'noBuilds': return { type: 'unavailable', reason: 'No builds are available.' }
    case 'unknown': return { type: 'unavailable', reason: 'Build status is unavailable.' }
    default: return invalid(kind, 'build state')
  }
}

function mapAction(value: unknown, kind: ModelKind): ActionItemSummary | null {
  const action = record(value, kind); const state = string(action.state, kind)
  const actionItemId = string(action.actionItemId, kind); const activityVersion = string(action.activityVersion, kind)
  const repositoryId = string(action.repositoryId, kind); const pullRequestId = string(action.pullRequestId, kind)
  string(action.repositoryDisplayName, kind); integer(action.pullRequestNumber, kind, true); string(action.pullRequestTitle, kind)
  const actor = record(action.actor, kind); string(actor.stableId, kind); const actorDisplayName = string(actor.displayName, kind)
  const occurredAt = instant(action.activityAt, kind); if (action.acknowledgedAt !== null) instant(action.acknowledgedAt, kind)
  const webUrl = url(action.webUrl, kind)
  const rawKind = string(action.kind, kind).toUpperCase()
  const mappedKind = rawKind === 'COMMENT' || rawKind === 'REPLY' || rawKind === 'THREAD' ? 'comment' : rawKind === 'CHANGES_REQUESTED' ? 'changesRequested' : invalid(kind, 'action kind')
  if (state === 'closed') return null
  if (state !== 'open' && state !== 'acknowledged') invalid(kind, 'action state')
  return { actionItemId, activityVersion, repositoryId, pullRequestId, kind: mappedKind, actorDisplayName, occurredAt, acknowledgmentState: state === 'open' ? 'actionable' : 'acknowledged', webUrl }
}

function mapPullRequest(value: unknown, kind: ModelKind): PullRequestSummary {
  const card = record(value, kind); const pullRequestId = string(card.pullRequestId, kind); const repositoryId = string(card.repositoryId, kind)
  const author = record(card.author, kind); string(author.stableId, kind)
  boolean(card.draft, kind); instant(card.createdAt, kind)
  const sourceActions = array<unknown>(card.actionItems, kind); const actionItems = sourceActions.map((action) => mapAction(action, kind)).filter((action): action is ActionItemSummary => action !== null)
  const actionable = integer(card.actionableItemCount, kind); const acknowledged = integer(card.acknowledgedItemCount, kind)
  const rawStates = sourceActions.map((action) => string(record(action, kind).state, kind))
  if (actionable !== rawStates.filter((state) => state === 'open').length || acknowledged !== rawStates.filter((state) => state === 'acknowledged').length) invalid(kind, 'count')
  return { pullRequestId, repositoryId, displayNumber: integer(card.upstreamNumber, kind, true), title: string(card.title, kind), authorDisplayName: string(author.displayName, kind), updatedAt: instant(card.updatedAt, kind), webUrl: url(card.webUrl, kind), readiness: mapReadiness(card.readiness, kind), buildState: mapBuild(card.buildState, kind), actionableItemCount: actionable, acknowledgedItemCount: acknowledged, actionItems }
}

function mapRepository(value: unknown): RepositoryGroupModel {
  const repository = record(value, 'dashboard'); const repositoryId = string(repository.repositoryId, 'dashboard')
  const sync = mapSynchronization(repository.synchronization, repositoryId)
  const summary = record(repository.readinessSummary, 'dashboard'); const ready = integer(summary.readyPullRequestCount, 'dashboard'); const available = integer(summary.availablePullRequestCount, 'dashboard'); const unavailable = integer(summary.unavailablePullRequestCount, 'dashboard')
  const pullRequests = array<unknown>(repository.pullRequests, 'dashboard').map((item) => mapPullRequest(item, 'dashboard'))
  if (available + unavailable !== pullRequests.length || ready > available) invalid('dashboard', 'count')
  return { repositoryId, slug: string(repository.slug, 'dashboard'), displayName: string(repository.displayName, 'dashboard'), webUrl: url(repository.webUrl, 'dashboard'), repositoryRevision: string(repository.repositoryRevision, 'dashboard'), ...sync, pullRequests }
}

function mapDashboard(snapshot: unknown): DashboardViewModel {
  const source = record(snapshot, 'dashboard'); const workspace = record(source.workspace, 'dashboard')
  string(workspace.workspaceId, 'dashboard'); url(workspace.bitbucketApiBaseUrl, 'dashboard'); string(workspace.workspaceSlug, 'dashboard'); url(workspace.workspaceWebUrl, 'dashboard'); integer(workspace.retentionDays, 'dashboard', true)
  array<unknown>(workspace.repositories, 'dashboard')
  return { dashboardRevision: string(source.dashboardRevision, 'dashboard'), generatedAt: instant(source.generatedAt, 'dashboard'), workspaceDisplayName: string(workspace.workspaceDisplayName, 'dashboard'), repositoryGroups: array<unknown>(source.repositoryGroups, 'dashboard').map(mapRepository), inbox: array<unknown>(record(source.inbox, 'dashboard').items, 'dashboard').map((item) => mapAction(item, 'dashboard')).filter((item): item is ActionItemSummary => item !== null), polling: mapPolling(source.polling, 'dashboard') }
}

function expectEcho(actualAction: unknown, actualVersion: unknown, requested: { actionItemId: string; activityVersion: string }, kind: ModelKind) {
  if (string(actualAction, kind) !== requested.actionItemId) throw new Error('action item response did not match the request')
  if (string(actualVersion, kind) !== requested.activityVersion) throw new Error('activity version response did not match the request')
}

export function mapDashboardResult(result: DashboardResult): DashboardSourceResult {
  const wire = result as unknown as any
  switch (wire.type) {
    case 'snapshotChanged': return { type: 'snapshotChanged', dashboard: mapDashboard(wire.snapshot) }
    case 'snapshotUnchanged': return { type: 'snapshotUnchanged', dashboardRevision: string(wire.dashboardRevision, 'dashboard'), serverTime: instant(wire.serverTime, 'dashboard'), polling: mapPolling(wire.polling, 'dashboard') }
    case 'workspaceNotConfigured': return { type: 'workspaceNotConfigured', setupCommand: string(wire.setupCommand, 'dashboard') }
    default: return assertNever(wire.type as never)
  }
}

export function mapRefreshResult(result: StartRefreshRunResult): RefreshSourceResult {
  const wire = result as unknown as any
  switch (wire.type) {
    case 'refreshRunRegistered': { const run = record(wire.refreshRun, 'refresh'); instant(run.createdAt, 'refresh'); instant(run.expiresAt, 'refresh'); array<unknown>(run.repositories, 'refresh'); array<unknown>(wire.dispositions, 'refresh'); return { type: 'refreshRunRegistered', refreshRunId: string(run.refreshRunId, 'refresh') } }
    case 'noRepositoriesConfigured': return { type: 'noRepositoriesConfigured', setupCommand: NO_REPOSITORIES_SETUP_COMMAND }
    case 'workspaceNotConfigured': return { type: 'workspaceNotConfigured', setupCommand: string(wire.setupCommand, 'refresh') }
    default: return assertNever(wire.type as never)
  }
}

export function mapPullRequestDetailResult(result: PullRequestDetailResult, requestedPullRequestId: string): PullRequestDetailSourceResult {
  const wire = result as unknown as any
  switch (wire.type) {
    case 'pullRequestFound': { const detail = record(wire.pullRequest, 'pull request'); const pullRequest = mapPullRequest(detail.pullRequest, 'pull request'); if (pullRequest.pullRequestId !== requestedPullRequestId) throw new Error('pull request response did not match the request'); string(detail.headCommit, 'pull request'); array<unknown>(detail.builds, 'pull request'); mapFreshness(detail.freshness, 'pull request'); return { type: 'pullRequestAvailable', detail: { pullRequest, readinessChecks: mapChecks(record(detail.pullRequest, 'pull request').readiness), actionItems: pullRequest.actionItems } } }
    case 'pullRequestNotFound': if (string(wire.pullRequestId, 'pull request') !== requestedPullRequestId) throw new Error('pull request response did not match the request'); return { type: 'pullRequestNotFound' }
    case 'workspaceNotConfigured': return { type: 'workspaceNotConfigured', setupCommand: string(wire.setupCommand, 'pull request') }
    default: return assertNever(wire.type as never)
  }
}

export function mapLiveContentResult(result: LiveActivityContentResult, requested: { actionItemId: string; activityVersion: string }): ActionContentSourceResult {
  const wire = result as unknown as any
  switch (wire.type) {
    case 'contentAvailable': expectEcho(wire.actionItemId, wire.requestedVersion, requested, 'action content'); instant(wire.fetchedAt, 'action content'); return { type: 'contentAvailable', actionItemId: requested.actionItemId, activityVersion: requested.activityVersion, markdownSource: string(wire.markdown, 'action content') }
    case 'contentUnavailable': { expectEcho(wire.actionItemId, wire.requestedVersion, requested, 'action content'); const reason = string(wire.reason, 'action content'); if (!Object.prototype.hasOwnProperty.call(CONTENT_REASON_COPY, reason)) invalid('action content', 'content reason'); if (wire.retryAt !== null) instant(wire.retryAt, 'action content'); return { type: 'contentUnavailable', reason: CONTENT_REASON_COPY[reason as keyof typeof CONTENT_REASON_COPY], retryable: boolean(wire.retryable, 'action content') } }
    case 'newerActivityObserved': expectEcho(wire.actionItemId, wire.requestedVersion, requested, 'action content'); return { type: 'newerActivityObserved', repositoryId: string(wire.repositoryId, 'action content'), requestedActivityVersion: requested.activityVersion, currentActivityVersion: string(wire.observedVersion, 'action content') }
    case 'staleActivityVersion': { expectEcho(wire.actionItemId, wire.requestedVersion, requested, 'action content'); const current = mapAction(wire.current, 'action content'); if (!current || current.actionItemId !== requested.actionItemId) invalid('action content', 'action item'); return { type: 'staleActivityVersion', requestedActivityVersion: requested.activityVersion, currentActivityVersion: current.activityVersion } }
    case 'actionItemNotFound': expectEcho(wire.actionItemId, wire.requestedVersion, requested, 'action content'); return { type: 'actionItemNotFound' }
    default: return assertNever(wire.type as never)
  }
}

export function mapAcknowledgmentResult(result: AcknowledgeActionItemResult, requested: { actionItemId: string; activityVersion: string }): AcknowledgmentSourceResult {
  const wire = result as unknown as any
  switch (wire.type) {
    case 'acknowledged': expectEcho(wire.actionItemId, wire.requestedVersion, requested, 'acknowledgment'); instant(wire.acknowledgedAt, 'acknowledgment'); return { type: 'acknowledged', actionItemId: requested.actionItemId, activityVersion: requested.activityVersion }
    case 'alreadyAcknowledged': expectEcho(wire.actionItemId, wire.requestedVersion, requested, 'acknowledgment'); return { type: 'alreadyAcknowledged', actionItemId: requested.actionItemId, activityVersion: requested.activityVersion }
    case 'staleActivityVersion': expectEcho(wire.actionItemId, wire.requestedVersion, requested, 'acknowledgment'); if (wire.hasNewerActivity !== true) invalid('acknowledgment', 'stale activity'); return { type: 'staleActivityVersion', actionItemId: requested.actionItemId, requestedActivityVersion: requested.activityVersion, currentActivityVersion: string(wire.currentActivityVersion, 'acknowledgment'), hasNewerActivity: true }
    case 'acknowledgmentRejected': expectEcho(wire.actionItemId, wire.requestedVersion, requested, 'acknowledgment'); return { type: 'acknowledgmentRejected', reason: 'This activity can no longer be acknowledged.' }
    case 'actionItemNotFound': expectEcho(wire.actionItemId, wire.requestedVersion, requested, 'acknowledgment'); return { type: 'actionItemNotFound' }
    default: return assertNever(wire.type as never)
  }
}
