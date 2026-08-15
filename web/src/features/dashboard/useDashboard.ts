import { shallowReadonly, shallowRef } from 'vue'

import type { DashboardViewModel } from './dashboard.models'
import type { DashboardSource, DashboardSourceResult, RefreshSourceResult } from './dashboardSource'
import { reconcileAcknowledgedAction, type AcknowledgedActionRef } from './dashboardReconciliation'
import { browserPollScheduler, type PollScheduler } from './pollScheduler'

export type DashboardRefreshState =
  | { type: 'idle' }
  | { type: 'registering' }
  | { type: 'active' }
  | { type: 'failed'; message: 'Refresh unavailable'; setupCommand?: string }

export type DashboardUiState =
  | { type: 'loading' }
  | { type: 'ready'; dashboard: DashboardViewModel; refresh: DashboardRefreshState }
  | { type: 'workspaceNotConfigured'; setupCommand: string }
  | { type: 'failed' }

export function useDashboard(
  source: DashboardSource,
  scheduler: PollScheduler = browserPollScheduler,
) {
  const state = shallowRef<DashboardUiState>({ type: 'loading' })
  let cancelScheduledPoll: (() => void) | undefined
  let disposed = false
  let requestGeneration = 0
  let activeLoop: Promise<void> | undefined
  let queuedPoll: Promise<void> | undefined
  let pendingAcknowledgments: readonly AcknowledgedActionRef[] = []

  const cancelPolling = () => {
    cancelScheduledPoll?.()
    cancelScheduledPoll = undefined
  }

  const isCurrent = (generation: number) => !disposed && generation === requestGeneration

  const setReadyRefresh = (refresh: DashboardRefreshState) => {
    if (state.value.type === 'ready') {
      state.value = { ...state.value, refresh }
    }
  }

  const scheduleFrom = (polling: DashboardViewModel['polling']) => {
    cancelPolling()
    if (disposed || polling.type !== 'active') return
    cancelScheduledPoll = scheduler.schedule(polling.afterMilliseconds, () => {
      cancelScheduledPoll = undefined
      void pollNow()
    })
  }

  const applyDashboardResult = (
    result: DashboardSourceResult,
    generation: number,
    background: boolean,
  ) => {
    if (!isCurrent(generation)) return
    if (result.type === 'snapshotChanged') {
      let dashboard = result.dashboard
      const stillPending: AcknowledgedActionRef[] = []
      for (const acknowledged of pendingAcknowledgments) {
        const reconciled = reconcileAcknowledgedAction(dashboard, acknowledged)
        if (reconciled !== dashboard) stillPending.push(acknowledged)
        dashboard = reconciled
      }
      pendingAcknowledgments = stillPending
      state.value = {
        type: 'ready',
        dashboard,
        refresh: dashboard.polling.type === 'active' ? { type: 'active' } : { type: 'idle' },
      }
      scheduleFrom(dashboard.polling)
      return
    }
    if (result.type === 'snapshotUnchanged' && state.value.type === 'ready') {
      state.value = {
        ...state.value,
        refresh: result.polling.type === 'active' ? { type: 'active' } : { type: 'idle' },
      }
      scheduleFrom(result.polling)
      return
    }
    if (result.type === 'workspaceNotConfigured') {
      state.value = background
        ? state.value.type === 'ready'
          ? {
              ...state.value,
              refresh: {
                type: 'failed',
                message: 'Refresh unavailable',
                setupCommand: result.setupCommand,
              },
            }
          : state.value
        : { type: 'workspaceNotConfigured', setupCommand: result.setupCommand }
      return
    }
    if (!background) state.value = { type: 'failed' }
  }

  const refreshFailure = (setupCommand?: string) => {
    setReadyRefresh({ type: 'failed', message: 'Refresh unavailable', setupCommand })
  }

  const handleRefreshResult = (result: RefreshSourceResult) => {
    if (result.type === 'refreshRunRegistered') {
      setReadyRefresh({ type: 'active' })
      return true
    }
    refreshFailure(result.setupCommand)
    return false
  }

  const pollAt = async (revision: string, generation: number) => {
    try {
      const result = await source.loadDashboard(revision)
      applyDashboardResult(result, generation, true)
    } catch {
      if (isCurrent(generation)) refreshFailure()
    }
  }

  const trackLoop = (loop: Promise<void>) => {
    activeLoop = loop
    void loop.then(() => {
      if (activeLoop === loop) activeLoop = undefined
    })
    return loop
  }

  const refresh = (): Promise<void> => {
    if (disposed || state.value.type !== 'ready') return Promise.resolve()
    if (activeLoop) return activeLoop

    cancelPolling()
    const generation = ++requestGeneration
    setReadyRefresh({ type: 'registering' })
    const loop = (async () => {
      try {
        const result = await source.startRefresh()
        if (!isCurrent(generation)) return
        if (!handleRefreshResult(result)) return
        if (state.value.type === 'ready') {
          await pollAt(state.value.dashboard.dashboardRevision, generation)
        }
      } catch {
        if (isCurrent(generation)) refreshFailure()
      }
    })()
    return trackLoop(loop)
  }

  const pollNow = (): Promise<void> => {
    if (disposed || state.value.type !== 'ready') return Promise.resolve()
    if (activeLoop) return activeLoop

    cancelPolling()
    const generation = ++requestGeneration
    return trackLoop(pollAt(state.value.dashboard.dashboardRevision, generation))
  }

  const pollDashboard = (): Promise<void> => {
    if (disposed || state.value.type !== 'ready') return Promise.resolve()
    if (!activeLoop) return pollNow()
    if (queuedPoll) return queuedPoll

    const loopBeforeRegistration = activeLoop
    const loop = loopBeforeRegistration.then(() => {
      if (queuedPoll === loop) queuedPoll = undefined
      return pollNow()
    })
    queuedPoll = loop
    return loop
  }

  const applyAcknowledgment = (acknowledged: AcknowledgedActionRef) => {
    if (state.value.type !== 'ready') return
    const dashboard = reconcileAcknowledgedAction(state.value.dashboard, acknowledged)
    if (dashboard === state.value.dashboard) return
    pendingAcknowledgments = [...pendingAcknowledgments, acknowledged]
    state.value = { ...state.value, dashboard }
  }

  const reload = async (): Promise<void> => {
    cancelPolling()
    activeLoop = undefined
    const generation = ++requestGeneration
    state.value = { type: 'loading' }

    try {
      const result = await source.loadDashboard()
      if (!isCurrent(generation)) return
      applyDashboardResult(result, generation, false)
      if (result.type === 'snapshotChanged') await refresh()
    } catch {
      if (isCurrent(generation)) state.value = { type: 'failed' }
    }
  }

  void reload()

  const dispose = () => {
    disposed = true
    requestGeneration += 1
    cancelPolling()
  }

  return {
    state: shallowReadonly(state),
    reload,
    refresh,
    pollNow,
    pollDashboard,
    applyAcknowledgment,
    dispose,
  }
}
