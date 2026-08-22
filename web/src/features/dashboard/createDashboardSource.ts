import type { DashboardSource } from './dashboardSource'
import type { FixtureJourney } from './fixtures/fixtureDashboardSource'
import { createKotlinApiDashboardSource } from './backend/kotlinApiDashboardSource'

export interface DashboardSourceModeEnvironment {
  production: boolean
  backendDevelopment: boolean
}

export function selectDashboardSourceMode(
  environment: DashboardSourceModeEnvironment,
): 'kotlin' | 'fixture' {
  return environment.production || environment.backendDevelopment ? 'kotlin' : 'fixture'
}

export function fixtureJourneyFromSearch(search: string): FixtureJourney {
  const requestedJourney = new URLSearchParams(search).get('fixtureJourney')
  switch (requestedJourney) {
    case 'healthy-refresh':
    case 'partial-refresh':
    case 'content-success':
    case 'content-unavailable':
    case 'newer-activity':
    case 'stale-acknowledgment':
      return requestedJourney
    default:
      return 'healthy-refresh'
  }
}

export async function createDashboardSource(): Promise<DashboardSource> {
  if (import.meta.env.PROD || import.meta.env.MODE === 'backend') {
    return createKotlinApiDashboardSource()
  }
  const { createFixtureDashboardSource } = await import('./fixtures/fixtureDashboardSource')
  return createFixtureDashboardSource(fixtureJourneyFromSearch(window.location.search))
}
