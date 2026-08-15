import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { makePullRequest } from '../testing/dashboardTestData'
import ReadinessSummary from './ReadinessSummary.vue'

describe('ReadinessSummary', () => {
  it('renders summary, build, checks, and action counts from the shared view models', () => {
    const wrapper = mount(ReadinessSummary, {
      props: {
        pullRequest: makePullRequest({
          pullRequestId: 'pr_184',
          readiness: { type: 'available', passed: 6, total: 7 },
          buildState: { type: 'failed', failedCheckCount: 1 },
          actionableItemCount: 1,
          acknowledgedItemCount: 2,
        }),
        readinessChecks: [
          { checkId: 'contract', label: 'Contract', state: 'passed' },
          { checkId: 'tests', label: 'Unit tests', state: 'pending' },
        ],
      },
    })

    expect(wrapper.text()).toContain('6 of 7 checks passed')
    expect(wrapper.get('[data-build-status="failed"]').text()).toContain('Build failed')
    expect(wrapper.text()).toContain('Contract')
    expect(wrapper.text()).toContain('Passed')
    expect(wrapper.text()).toContain('Unit tests')
    expect(wrapper.text()).toContain('Pending')
    expect(wrapper.text()).toContain('1 actionable item')
    expect(wrapper.text()).toContain('2 acknowledged items')
  })

  it('renders readiness unavailability and an empty check state explicitly', () => {
    const wrapper = mount(ReadinessSummary, {
      props: {
        pullRequest: makePullRequest({
          readiness: { type: 'unavailable', reason: 'Observation incomplete' },
        }),
        readinessChecks: [],
      },
    })

    expect(wrapper.text()).toContain('Readiness unavailable: Observation incomplete')
    expect(wrapper.text()).toContain('Detailed readiness checks are unavailable.')
  })
})
