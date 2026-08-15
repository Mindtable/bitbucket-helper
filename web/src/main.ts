import { createApp } from 'vue'

import App from './app/App.vue'
import './assets/main.css'
import { fixtureDashboardSource } from './features/dashboard/fixtures/fixtureDashboardSource'

createApp(App, {
  dashboardSource: fixtureDashboardSource,
}).mount('#app')
