import { flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { DashboardSourceResult } from './dashboardSource'
import { makeDashboard } from './testing/dashboardTestData'
import { createDashboardSourceStub, deferred } from './testing/dashboardTestSource'
import { useDashboard } from './useDashboard'

const dashboard = makeDashboard()

describe('useDashboard', () => {
  it('publishes a dashboard only after the source resolves', async () => {
    const pending = deferred<DashboardSourceResult>()
    const source = createDashboardSourceStub({
      loadDashboard: () => pending.promise,
    })

    const { state } = useDashboard(source)

    expect(state.value.type).toBe('loading')

    pending.resolve({ type: 'snapshotChanged', dashboard })
    await flushPromises()

    expect(state.value.type).toBe('ready')
    if (state.value.type === 'ready') {
      // eslint-disable-next-line vitest/no-conditional-expect
      expect(state.value.dashboard.workspaceDisplayName).toBe('Acme Engineering')
    }
  })

  it('keeps workspace-not-configured as an expected business state', async () => {
    const source = createDashboardSourceStub({
      loadDashboard: () =>
        Promise.resolve({
          type: 'workspaceNotConfigured',
          setupCommand: 'bitbucket-helper workspace configure',
        }),
    })

    const { state } = useDashboard(source)
    await flushPromises()

    expect(state.value).toEqual({
      type: 'workspaceNotConfigured',
      setupCommand: 'bitbucket-helper workspace configure',
    })
  })

  it('does not expose rejection details in the failed state', async () => {
    const source = createDashboardSourceStub({
      loadDashboard: () => Promise.reject(new Error('credential=do-not-display')),
    })

    const { state } = useDashboard(source)
    await flushPromises()

    expect(state.value).toEqual({ type: 'failed' })
    expect(JSON.stringify(state.value)).not.toContain('do-not-display')
  })

  it('reloads after a failure and can recover', async () => {
    let firstAttempt = true
    const source = createDashboardSourceStub({
      loadDashboard: () => {
        if (firstAttempt) {
          firstAttempt = false
          return Promise.reject(new Error('temporary failure'))
        }
        return Promise.resolve({ type: 'snapshotChanged', dashboard })
      },
    })

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
