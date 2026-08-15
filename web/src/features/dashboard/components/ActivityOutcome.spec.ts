import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ActivityOutcome from './ActivityOutcome.vue'

describe('ActivityOutcome', () => {
  it('renders exact activity markdown as literal whitespace-preserving text', () => {
    const markdownSource = '<img src=x onerror=alert(1)> **review**\nnext line'
    const wrapper = mount(ActivityOutcome, {
      props: {
        activityContent: {
          type: 'contentAvailable',
          actionItemId: 'action_501',
          activityVersion: 'av_42',
          markdownSource,
        },
      },
    })

    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)> **review**')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('[data-activity-markdown]').classes()).toContain(
      'activity-outcome__markdown',
    )
  })

  it('renders loading separately from pull-request detail loading', () => {
    const wrapper = mount(ActivityOutcome, {
      props: {
        activityContent: {
          type: 'contentLoading',
          actionItemId: 'action_501',
          activityVersion: 'av_42',
        },
      },
    })

    expect(wrapper.get('[role="status"]').text()).toContain('Loading activity')
    expect(wrapper.text()).not.toContain('pull request details')
  })

  it('offers retry only for retryable unavailable content', async () => {
    const wrapper = mount(ActivityOutcome, {
      props: {
        activityContent: {
          type: 'contentUnavailable',
          message: 'Temporary upstream failure.',
          retryable: true,
        },
      },
    })

    expect(wrapper.text()).toContain('Temporary upstream failure.')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)

    await wrapper.setProps({
      activityContent: {
        type: 'contentUnavailable',
        message: 'This activity is no longer available.',
        retryable: false,
      },
    })
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('shows the current version for newer activity without a requested body', () => {
    const wrapper = mount(ActivityOutcome, {
      props: {
        activityContent: {
          type: 'newerActivity',
          actionItemId: 'action_501',
          requestedActivityVersion: 'av_42',
          currentActivityVersion: 'av_43',
        },
      },
    })

    expect(wrapper.text()).toContain('Newer activity is available')
    expect(wrapper.text()).toContain('av_43')
    expect(wrapper.find('[data-activity-markdown]').exists()).toBe(false)
  })
})
