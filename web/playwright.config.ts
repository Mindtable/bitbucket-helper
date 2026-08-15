import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  forbidOnly: true,
  fullyParallel: false,
  reporter: 'line',
  use: { baseURL: 'http://127.0.0.1:5173', headless: true, trace: 'on-first-retry' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev -- --port 5173 --strictPort',
    reuseExistingServer: false,
    url: 'http://127.0.0.1:5173',
  },
})
