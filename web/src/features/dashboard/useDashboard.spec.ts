import { flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { DashboardViewModel } from './dashboard.models'
import type { DashboardSource, DashboardSourceResult } from './dashboardSource'
import { useDashboard } from './useDashboard'

const dashboard: DashboardViewModel = {
  workspaceDisplayName: 'Acme Engineering',
  generatedAt: '2026-08-15T10:00:00Z',
  repositoryGroups: [],
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })

  return { promise, resolve }
}

describe('useDashboard', () => {
  it('publishes a dashboard only after the source resolves', async () => {
    const pending = deferred<DashboardSourceResult>()
    const source: DashboardSource = {
      load: () => pending.promise,
    }

    const { state } = useDashboard(source)

    expect(state.value.type).toBe('loading')

    pending.resolve({ type: 'dashboardAvailable', dashboard })
    await flushPromises()

    expect(state.value.type).toBe('ready')
    if (state.value.type === 'ready') {
      // eslint-disable-next-line vitest/no-conditional-expect
      expect(state.value.dashboard.workspaceDisplayName).toBe('Acme Engineering')
    }
  })

  it('keeps workspace-not-configured as an expected business state', async () => {
    const source: DashboardSource = {
      load: () =>
        Promise.resolve({
          type: 'workspaceNotConfigured',
          setupCommand: 'bitbucket-helper workspace configure',
        }),
    }

    const { state } = useDashboard(source)
    await flushPromises()

    expect(state.value).toEqual({
      type: 'workspaceNotConfigured',
      setupCommand: 'bitbucket-helper workspace configure',
    })
  })

  it('does not expose rejection details in the failed state', async () => {
    const source: DashboardSource = {
      load: () => Promise.reject(new Error('credential=do-not-display')),
    }

    const { state } = useDashboard(source)
    await flushPromises()

    expect(state.value).toEqual({ type: 'failed' })
    expect(JSON.stringify(state.value)).not.toContain('do-not-display')
  })

  it('reloads after a failure and can recover', async () => {
    let firstAttempt = true
    const source: DashboardSource = {
      load: () => {
        if (firstAttempt) {
          firstAttempt = false
          return Promise.reject(new Error('temporary failure'))
        }
        return Promise.resolve({ type: 'dashboardAvailable', dashboard })
      },
    }

    const { reload, state } = useDashboard(source)
    await flushPromises()
    expect(state.value.type).toBe('failed')

    const reloading = reload()
    expect(state.value.type).toBe('loading')
    await reloading

    expect(state.value.type).toBe('ready')
    if (state.value.type === 'ready') {
      // eslint-disable-next-line vitest/no-conditional-expect
      expect(state.value.dashboard.workspaceDisplayName).toBe('Acme Engineering')
    }
  })
})
