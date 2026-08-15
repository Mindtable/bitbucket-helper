import { createApp } from 'vue'

import App from './app/App.vue'
import './assets/main.css'
import {
  createFixtureDashboardSource,
  type FixtureJourney,
} from './features/dashboard/fixtures/fixtureDashboardSource'

function fixtureJourneyFromUrl(): FixtureJourney {
  const requestedJourney = new URLSearchParams(window.location.search).get('fixtureJourney')
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

const fixtureJourney = import.meta.env.DEV ? fixtureJourneyFromUrl() : 'healthy-refresh'

createApp(App, {
  dashboardSource: createFixtureDashboardSource(fixtureJourney),
}).mount('#app')
