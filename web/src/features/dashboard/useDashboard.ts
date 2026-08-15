import { readonly, shallowRef } from 'vue'

import type { DashboardViewModel } from './dashboard.models'
import type { DashboardSource } from './dashboardSource'

export type DashboardUiState =
  | { type: 'loading' }
  | { type: 'ready'; dashboard: DashboardViewModel }
  | { type: 'workspaceNotConfigured'; setupCommand: string }
  | { type: 'failed' }

export function useDashboard(source: DashboardSource) {
  const state = shallowRef<DashboardUiState>({ type: 'loading' })

  const reload = async (): Promise<void> => {
    state.value = { type: 'loading' }

    try {
      const result = await source.loadDashboard()
      state.value =
        result.type === 'snapshotChanged'
          ? { type: 'ready', dashboard: result.dashboard }
          : result.type === 'workspaceNotConfigured'
            ? {
                type: 'workspaceNotConfigured',
                setupCommand: result.setupCommand,
              }
            : { type: 'failed' }
    } catch {
      state.value = { type: 'failed' }
    }
  }

  void reload()

  return {
    state: readonly(state),
    reload,
  }
}
