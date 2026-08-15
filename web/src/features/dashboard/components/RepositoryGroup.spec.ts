import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { makePullRequest, makeRepository } from '../testing/dashboardTestData'
import RepositoryGroup from './RepositoryGroup.vue'

describe('RepositoryGroup', () => {
  it('renders native PR children under the owning repository', () => {
    const wrapper = mount(RepositoryGroup, {
      props: {
        repository: makeRepository({
          repositoryId: 'repo_payments',
          pullRequests: [
            makePullRequest({ pullRequestId: 'pr_184', title: 'Add retry budget' }),
            makePullRequest({ pullRequestId: 'pr_179', title: 'Remove legacy token' }),
          ],
        }),
      },
    })

    expect(wrapper.get('section').attributes('aria-labelledby')).toBe('repository-repo_payments')
    expect(wrapper.get('ul.pull-request-list').findAll('li.pull-request-branch')).toHaveLength(2)
    expect(wrapper.get('[data-pull-request-id="pr_184"]').text()).toContain('Add retry budget')
    expect(wrapper.get('[data-pull-request-id="pr_179"]').text()).toContain('Remove legacy token')
  })

  it('renders a repository problem and forwards review context', async () => {
    const wrapper = mount(RepositoryGroup, {
      props: {
        repository: makeRepository({
          problem: {
            type: 'present',
            message: 'Credentials need attention',
            retryable: true,
            retryAfterDescription: 'Try again in 1 minute',
          },
          pullRequests: [makePullRequest({ pullRequestId: 'pr_184' })],
        }),
      },
    })

    expect(wrapper.text()).toContain('Credentials need attention')
    await wrapper.get('[data-review-context]').trigger('click')
    expect(wrapper.emitted('review')?.[0]?.[0]).toBe('pr_184')
    expect(wrapper.emitted('review')?.[0]?.[1]).toBeInstanceOf(HTMLButtonElement)
  })
})
