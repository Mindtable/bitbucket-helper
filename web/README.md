# Bitbucket Helper Web

Vue 3, Vite, and TypeScript frontend for Bitbucket Helper.

The current slice renders a deterministic in-process dashboard fixture. It does
not call the Kotlin service. A later slice will generate the TypeScript API client
from openapi/api-v1.yaml and replace the fixture through DashboardSource.

## Requirements

- Node.js ^22.22.2 || ^24.15.0 || >=26.0.0
- npm 11.17.0

## Setup

    npm ci
    npx playwright install chromium

## Commands

    npm run dev
    npm run format:check
    npm run lint
    npm run type-check
    npm run test:unit
    npm run test:e2e
    npm run build
    npm run check

npm run build writes untracked production assets to dist/.
