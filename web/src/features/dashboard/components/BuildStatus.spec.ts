import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import BuildStatus from './BuildStatus.vue'

describe('BuildStatus', () => {
  it.each([
    [{ type: 'successful' } as const, 'Build successful'],
    [{ type: 'inProgress' } as const, 'Build in progress'],
    [
      { type: 'unavailable', reason: 'No build observed' } as const,
      'Build unavailable: No build observed',
    ],
  ])('renders %o build state', (buildState, expectedCopy) => {
    const wrapper = mount(BuildStatus, {
      props: { buildState, controlId: 'pr-17' },
    })

    expect(wrapper.text()).toContain(expectedCopy)
  })

  it('renders failed build details as unavailable controls', () => {
    const wrapper = mount(BuildStatus, {
      props: {
        buildState: { type: 'failed', failedCheckCount: 2 },
        controlId: 'pr-92',
      },
    })

    expect(wrapper.text()).toContain('Build failed')
    expect(wrapper.text()).toContain('2 failed checks')
    expect(wrapper.get('[data-view-build]').attributes('aria-disabled')).toBe('true')
    expect(wrapper.get('[data-view-build]').attributes('aria-describedby')).toBe(
      'build-details-pr-92',
    )
  })
})
