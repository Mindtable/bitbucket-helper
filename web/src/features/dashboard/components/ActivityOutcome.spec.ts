import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ActivityOutcome from './ActivityOutcome.vue'

describe('ActivityOutcome', () => {
  it('offers acknowledgment only for exact available content', async () => {
    const wrapper = mount(ActivityOutcome, {
      props: {
        activityContent: {
          type: 'contentAvailable',
          actionItemId: 'action_501',
          activityVersion: 'av_42',
          markdownSource: 'Exact activity body',
        },
      },
    })

    const acknowledge = wrapper.get('button')
    expect(acknowledge.text()).toBe('Acknowledge av_42')
    await acknowledge.trigger('click')
    expect(wrapper.emitted('acknowledge')).toHaveLength(1)

    await wrapper.setProps({
      activityContent: {
        type: 'contentUnavailable',
        message: 'This activity is no longer available.',
        retryable: false,
      },
    })
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('keeps exact content visible with a disabled pending acknowledgment label', () => {
    const wrapper = mount(ActivityOutcome, {
      props: {
        activityContent: {
          type: 'ackPending',
          actionItemId: 'action_501',
          activityVersion: 'av_42',
          markdownSource: 'Exact activity body',
        },
      },
    })

    expect(wrapper.get('[data-activity-markdown]').text()).toBe('Exact activity body')
    const pending = wrapper.get('button')
    expect(pending.text()).toBe('Acknowledging av_42…')
    expect(pending.attributes()).toHaveProperty('disabled')
  })

  it('renders acknowledgment outcomes without exposing another command', async () => {
    const wrapper = mount(ActivityOutcome, {
      props: {
        activityContent: { type: 'acknowledged', message: 'Activity acknowledged.' },
      },
    })

    expect(wrapper.text()).toContain('Activity acknowledged.')
    expect(wrapper.find('button').exists()).toBe(false)

    await wrapper.setProps({
      activityContent: {
        type: 'acknowledgmentRejected',
        message: 'Only reviewers can acknowledge this activity.',
        retryable: false,
        actionItemId: 'action_501',
        activityVersion: 'av_42',
      },
    })
    expect(wrapper.text()).toContain('Only reviewers can acknowledge this activity.')
    expect(wrapper.find('button').exists()).toBe(false)
  })

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

  it('shows the current version and emits refresh for newer activity without a requested body', async () => {
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
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it('announces repository refresh without synthesizing content', () => {
    const wrapper = mount(ActivityOutcome, {
      props: {
        activityContent: { type: 'refreshing', currentActivityVersion: 'av_43' },
      },
    })

    expect(wrapper.get('[role="status"]').text()).toContain('Refreshing activity at av_43')
    expect(wrapper.find('[data-activity-markdown]').exists()).toBe(false)
  })
})
