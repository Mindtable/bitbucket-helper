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
      const result = await source.load()
      state.value =
        result.type === 'dashboardAvailable'
          ? { type: 'ready', dashboard: result.dashboard }
          : {
              type: 'workspaceNotConfigured',
              setupCommand: result.setupCommand,
            }
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
