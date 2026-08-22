import { describe, expect, it } from 'vitest'
import { fixtureJourneyFromSearch, selectDashboardSourceMode } from './createDashboardSource'

describe('dashboard source selection', () => {
  it('uses the Kotlin source in production regardless of the fixture query state', () => {
    expect(
      selectDashboardSourceMode({
        production: true,
        backendDevelopment: false,
      }),
    ).toBe('kotlin')
    expect(fixtureJourneyFromSearch('?fixtureJourney=partial-refresh')).toBe('partial-refresh')
    expect(fixtureJourneyFromSearch('?fixtureJourney=unknown')).toBe('healthy-refresh')
  })

  it('uses fixture mode only during regular development and accepts only approved journeys', () => {
    expect(
      selectDashboardSourceMode({
        production: false,
        backendDevelopment: false,
      }),
    ).toBe('fixture')
    expect(
      selectDashboardSourceMode({
        production: false,
        backendDevelopment: true,
      }),
    ).toBe('kotlin')

    expect(fixtureJourneyFromSearch('?fixtureJourney=healthy-refresh')).toBe('healthy-refresh')
    expect(fixtureJourneyFromSearch('?fixtureJourney=partial-refresh')).toBe('partial-refresh')
    expect(fixtureJourneyFromSearch('?fixtureJourney=content-success')).toBe('content-success')
    expect(fixtureJourneyFromSearch('?fixtureJourney=content-unavailable')).toBe(
      'content-unavailable',
    )
    expect(fixtureJourneyFromSearch('?fixtureJourney=newer-activity')).toBe('newer-activity')
    expect(fixtureJourneyFromSearch('?fixtureJourney=stale-acknowledgment')).toBe(
      'stale-acknowledgment',
    )
    expect(fixtureJourneyFromSearch('?fixtureJourney=unknown')).toBe('healthy-refresh')
  })
})
