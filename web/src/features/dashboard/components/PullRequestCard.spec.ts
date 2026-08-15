import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { makePullRequest } from '../testing/dashboardTestData'
import PullRequestCard from './PullRequestCard.vue'

describe('PullRequestCard', () => {
  it('renders failed build details without changing readiness', () => {
    const wrapper = mount(PullRequestCard, {
      props: {
        pullRequest: makePullRequest({
          pullRequestId: 'pr_92',
          displayNumber: 92,
          buildState: { type: 'failed', failedCheckCount: 2 },
          readiness: { type: 'available', passed: 5, total: 7 },
        }),
      },
    })
    expect(wrapper.text()).toContain('Build failed')
    expect(wrapper.text()).toContain('2 failed checks')
    expect(wrapper.text()).toContain('5 of 7 checks')
    expect(wrapper.get('[data-view-build]').attributes('aria-disabled')).toBe('true')
  })

  it('shows counts, uses a safe external link, and emits the review invoker', async () => {
    const wrapper = mount(PullRequestCard, {
      props: {
        pullRequest: makePullRequest({
          pullRequestId: 'pr_184',
          title: 'Add retry budget',
          webUrl: 'https://bitbucket.org/acme/payments/pull-requests/184',
          actionableItemCount: 2,
          acknowledgedItemCount: 3,
        }),
      },
    })

    expect(wrapper.text()).toContain('2 actionable items')
    expect(wrapper.text()).toContain('3 acknowledged items')
    const link = wrapper.get('a[href="https://bitbucket.org/acme/payments/pull-requests/184"]')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
    await wrapper.get('[data-review-context]').trigger('click')
    expect(wrapper.emitted('review')?.[0]?.[0]).toBe('pr_184')
    expect(wrapper.emitted('review')?.[0]?.[1]).toBeInstanceOf(HTMLButtonElement)
  })
})
