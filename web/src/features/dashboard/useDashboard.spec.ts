import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import type { DashboardSourceResult, RefreshSourceResult } from './dashboardSource'
import { makeDashboard } from './testing/dashboardTestData'
import { createDashboardSourceStub, deferred } from './testing/dashboardTestSource'
import { useDashboard } from './useDashboard'

const dashboard = makeDashboard()

function createScheduler() {
  const delays: number[] = []
  const tasks: Array<() => void> = []
  const cancellations: Array<() => void> = []

  return {
    delays,
    schedule(afterMilliseconds: number, task: () => void) {
      delays.push(afterMilliseconds)
      let cancelled = false
      const cancel = () => {
        cancelled = true
      }
      cancellations.push(cancel)
      tasks.push(() => {
        if (!cancelled) task()
      })
      return cancel
    },
    runNext() {
      tasks.shift()?.()
    },
    scheduledCount() {
      return cancellations.length
    },
  }
}

describe('useDashboard', () => {
  it('publishes a dashboard only after the source resolves', async () => {
    const pending = deferred<DashboardSourceResult>()
    const source = createDashboardSourceStub({
      loadDashboard: () => pending.promise,
    })

    const { state } = useDashboard(source)

    expect(state.value.type).toBe('loading')

    pending.resolve({ type: 'snapshotChanged', dashboard })
    await flushPromises()

    expect(state.value.type).toBe('ready')
    if (state.value.type === 'ready') {
      // eslint-disable-next-line vitest/no-conditional-expect
      expect(state.value.dashboard.workspaceDisplayName).toBe('Acme Engineering')
    }
  })

  it('keeps workspace-not-configured as an expected business state', async () => {
    const source = createDashboardSourceStub({
      loadDashboard: () =>
        Promise.resolve({
          type: 'workspaceNotConfigured',
          setupCommand: 'bitbucket-helper workspace configure',
        }),
    })

    const { state } = useDashboard(source)
    await flushPromises()

    expect(state.value).toEqual({
      type: 'workspaceNotConfigured',
      setupCommand: 'bitbucket-helper workspace configure',
    })
  })

  it('does not expose rejection details in the failed state', async () => {
    const source = createDashboardSourceStub({
      loadDashboard: () => Promise.reject(new Error('credential=do-not-display')),
    })

    const { state } = useDashboard(source)
    await flushPromises()

    expect(state.value).toEqual({ type: 'failed' })
    expect(JSON.stringify(state.value)).not.toContain('do-not-display')
  })

  it('reloads after a failure and can recover', async () => {
    let firstAttempt = true
    const source = createDashboardSourceStub({
      loadDashboard: () => {
        if (firstAttempt) {
          firstAttempt = false
          return Promise.reject(new Error('temporary failure'))
        }
        return Promise.resolve({ type: 'snapshotChanged', dashboard })
      },
    })

    const { reload, state } = useDashboard(source)
    await flushPromises()
    expect(state.value.type).toBe('failed')

    const reloading = reload()
    expect(state.value.type).toBe('loading')
    await reloading

    expect(state.value.type).toBe('ready')
    if (state.value.type === 'ready') {
      // eslint-disable-next-line vitest/no-conditional-expect
      expect(state.value.dashboard.workspaceDisplayName).toBe('Acme Engineering')
    }
  })

  it('publishes the persisted snapshot before refresh registration resolves', async () => {
    const refresh = deferred<RefreshSourceResult>()
    const scheduler = createScheduler()
    const source = createDashboardSourceStub({
      loadDashboard: () => Promise.resolve({ type: 'snapshotChanged', dashboard }),
      startRefresh: () => refresh.promise,
    })

    const { state } = useDashboard(source, scheduler)
    await flushPromises()

    expect(state.value).toMatchObject({
      type: 'ready',
      dashboard,
      refresh: { type: 'registering' },
    })
  })

  it('preserves the snapshot on snapshotUnchanged and schedules one poll', async () => {
    const scheduler = createScheduler()
    const source = createDashboardSourceStub({
      loadDashboard: vi
        .fn()
        .mockResolvedValueOnce({ type: 'snapshotChanged', dashboard })
        .mockResolvedValueOnce({
          type: 'snapshotUnchanged',
          dashboardRevision: 'dash_17',
          serverTime: '2026-08-15T10:00:01Z',
          polling: { type: 'active', afterMilliseconds: 25 },
        }),
      startRefresh: () =>
        Promise.resolve({
          type: 'refreshRunRegistered',
          refreshRunId: 'refresh_1',
        }),
    })

    const { state } = useDashboard(source, scheduler)
    await flushPromises()

    if (state.value.type !== 'ready') throw new Error('expected ready')
    expect(state.value.dashboard).toBe(dashboard)
    expect(scheduler.delays).toEqual([25])
  })

  it('replaces the snapshot when a revision-aware poll reports a change', async () => {
    const initialDashboard = makeDashboard({ dashboardRevision: 'dash_17' })
    const changedDashboard = makeDashboard({
      dashboardRevision: 'dash_18',
      workspaceDisplayName: 'Changed workspace',
    })
    const scheduler = createScheduler()
    const loadDashboard = vi
      .fn()
      .mockResolvedValueOnce({ type: 'snapshotChanged', dashboard: initialDashboard })
      .mockResolvedValueOnce({ type: 'snapshotChanged', dashboard: changedDashboard })
    const source = createDashboardSourceStub({
      loadDashboard,
      startRefresh: () =>
        Promise.resolve({ type: 'refreshRunRegistered', refreshRunId: 'refresh_1' }),
    })

    const { state } = useDashboard(source, scheduler)
    await flushPromises()

    expect(state.value).toMatchObject({
      type: 'ready',
      dashboard: changedDashboard,
      refresh: { type: 'idle' },
    })
    expect(loadDashboard).toHaveBeenNthCalledWith(2, 'dash_17')
  })

  it('keeps setup guidance in ready content when refresh registration cannot proceed', async () => {
    const source = createDashboardSourceStub({
      loadDashboard: () => Promise.resolve({ type: 'snapshotChanged', dashboard }),
      startRefresh: () =>
        Promise.resolve({
          type: 'noRepositoriesConfigured',
          setupCommand: 'bitbucket-helper repository add',
        }),
    })

    const { state } = useDashboard(source, createScheduler())
    await flushPromises()

    expect(state.value).toEqual({
      type: 'ready',
      dashboard,
      refresh: {
        type: 'failed',
        message: 'Refresh unavailable',
        setupCommand: 'bitbucket-helper repository add',
      },
    })
  })

  it('keeps ready content after a background polling rejection', async () => {
    const source = createDashboardSourceStub({
      loadDashboard: vi
        .fn()
        .mockResolvedValueOnce({ type: 'snapshotChanged', dashboard })
        .mockRejectedValueOnce(new Error('credential=do-not-display')),
      startRefresh: () =>
        Promise.resolve({ type: 'refreshRunRegistered', refreshRunId: 'refresh_1' }),
    })

    const { state } = useDashboard(source, createScheduler())
    await flushPromises()

    expect(state.value).toEqual({
      type: 'ready',
      dashboard,
      refresh: { type: 'failed', message: 'Refresh unavailable' },
    })
    expect(JSON.stringify(state.value)).not.toContain('credential=do-not-display')
  })

  it('joins manual refresh requests to the active loop', async () => {
    const registration = deferred<RefreshSourceResult>()
    const startRefresh = vi.fn(() => registration.promise)
    const source = createDashboardSourceStub({
      loadDashboard: () => Promise.resolve({ type: 'snapshotChanged', dashboard }),
      startRefresh,
    })

    const { refresh } = useDashboard(source, createScheduler())
    await flushPromises()
    const first = refresh()
    const second = refresh()

    expect(first).toBe(second)
    expect(startRefresh).toHaveBeenCalledTimes(1)

    registration.resolve({ type: 'noRepositoriesConfigured', setupCommand: 'setup repositories' })
    await first
  })

  it('starts a fresh refresh loop when reload replaces a snapshot during registration', async () => {
    const oldRegistration = deferred<RefreshSourceResult>()
    const newRegistration = deferred<RefreshSourceResult>()
    const oldDashboard = makeDashboard({ dashboardRevision: 'old_revision' })
    const newDashboard = makeDashboard({ dashboardRevision: 'new_revision' })
    const startRefresh = vi
      .fn()
      .mockReturnValueOnce(oldRegistration.promise)
      .mockReturnValueOnce(newRegistration.promise)
    const source = createDashboardSourceStub({
      loadDashboard: vi
        .fn()
        .mockResolvedValueOnce({ type: 'snapshotChanged', dashboard: oldDashboard })
        .mockResolvedValueOnce({ type: 'snapshotChanged', dashboard: newDashboard }),
      startRefresh,
    })

    const { reload, state } = useDashboard(source, createScheduler())
    await flushPromises()
    void reload()
    await flushPromises()

    expect(startRefresh).toHaveBeenCalledTimes(2)
    expect(state.value).toMatchObject({
      type: 'ready',
      dashboard: newDashboard,
      refresh: { type: 'registering' },
    })
  })

  it('uses the current opaque revision for a manual poll', async () => {
    const currentDashboard = makeDashboard({ dashboardRevision: 'opaque_revision' })
    const loadDashboard = vi
      .fn()
      .mockResolvedValueOnce({ type: 'snapshotChanged', dashboard: currentDashboard })
      .mockResolvedValueOnce({
        type: 'snapshotUnchanged',
        dashboardRevision: 'opaque_revision',
        serverTime: '2026-08-15T10:00:01Z',
        polling: { type: 'idle' },
      })
      .mockResolvedValueOnce({
        type: 'snapshotUnchanged',
        dashboardRevision: 'opaque_revision',
        serverTime: '2026-08-15T10:00:02Z',
        polling: { type: 'idle' },
      })
    const source = createDashboardSourceStub({
      loadDashboard,
      startRefresh: () =>
        Promise.resolve({ type: 'refreshRunRegistered', refreshRunId: 'refresh_1' }),
    })

    const { pollNow } = useDashboard(source, createScheduler())
    await flushPromises()
    await pollNow()

    expect(loadDashboard).toHaveBeenNthCalledWith(3, 'opaque_revision')
  })

  it('cancels its scheduled poll and ignores late results after disposal', async () => {
    const pendingPoll = deferred<DashboardSourceResult>()
    const scheduler = createScheduler()
    const source = createDashboardSourceStub({
      loadDashboard: vi
        .fn()
        .mockResolvedValueOnce({
          type: 'snapshotChanged',
          dashboard: makeDashboard({
            polling: { type: 'active', afterMilliseconds: 25 },
          }),
        })
        .mockReturnValueOnce(pendingPoll.promise),
      startRefresh: () =>
        Promise.resolve({ type: 'refreshRunRegistered', refreshRunId: 'refresh_1' }),
    })

    const { dispose, state } = useDashboard(source, scheduler)
    await flushPromises()
    dispose()
    scheduler.runNext()
    pendingPoll.resolve({ type: 'snapshotChanged', dashboard })
    await flushPromises()

    expect(scheduler.scheduledCount()).toBe(1)
    expect(state.value).toMatchObject({ type: 'ready', refresh: { type: 'active' } })
  })
})
