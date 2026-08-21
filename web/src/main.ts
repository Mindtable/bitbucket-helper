import { createApp } from 'vue'

import App from './app/App.vue'
import './assets/main.css'
import { createDashboardSource } from './features/dashboard/createDashboardSource'

async function bootstrap() {
  const source = await createDashboardSource()
  createApp(App, { dashboardSource: source }).mount('#app')
}

void bootstrap()
