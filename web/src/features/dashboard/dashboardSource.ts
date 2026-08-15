import type { DashboardViewModel } from './dashboard.models'

export interface DashboardSource {
  load(): Promise<DashboardSourceResult>
}

export type DashboardSourceResult =
  | { type: 'dashboardAvailable'; dashboard: DashboardViewModel }
  | { type: 'workspaceNotConfigured'; setupCommand: string }
