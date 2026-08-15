import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it } from 'vitest'

import {
  makeActionItem,
  makePullRequest,
  makePullRequestDetail,
} from '../testing/dashboardTestData'
import type { DrawerUiState } from '../usePullRequestDrawer'
import PullRequestDrawer from './PullRequestDrawer.vue'

function loadingState(): Extract<DrawerUiState, { type: 'detailLoading' }> {
  return {
    type: 'detailLoading',
    context: {
      repositoryDisplayName: 'Payments API',
      pullRequest: makePullRequest({
        pullRequestId: 'pr_184',
        displayNumber: 184,
        title: 'Add retry budget',
        authorDisplayName: 'Mira',
      }),
      selectedActionItem: makeActionItem({
        actionItemId: 'action_501',
        activityVersion: 'av_42',
        actorDisplayName: 'Alex Chen',
      }),
      detail: null,
      activityContent: null,
    },
  }
}

describe('PullRequestDrawer', () => {
  it('renders nothing while closed', () => {
    const wrapper = mount(PullRequestDrawer, { props: { state: { type: 'closed' } } })

    expect(wrapper.html()).toBe('<!--v-if-->')
  })

  it('renders immediate summary in a complementary aside and focuses Close on open', async () => {
    const wrapper = mount(PullRequestDrawer, {
      attachTo: document.body,
      props: { state: { type: 'closed' } },
    })

    await wrapper.setProps({ state: loadingState() })
    await nextTick()

    const aside = wrapper.get('aside')
    expect(aside.attributes('role')).toBeUndefined()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(aside.attributes('aria-labelledby')).toBe('pull-request-drawer-heading')
    expect(wrapper.get('#pull-request-drawer-heading').text()).toBe('Add retry budget')
    expect(wrapper.text()).toContain('Payments API')
    expect(wrapper.text()).toContain('Pull request #184')
    expect(wrapper.text()).toContain('By Mira')
    expect(wrapper.text()).toContain('Loading pull request details…')
    expect(document.activeElement).toBe(wrapper.get('[data-close-drawer]').element)
    wrapper.unmount()
  })

  it('renders readiness and the selected activity version from detail', () => {
    const actionItem = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      actorDisplayName: 'Alex Chen',
    })
    const detail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
      readinessChecks: [{ checkId: 'contract', label: 'Contract', state: 'passed' as const }],
    }
    const wrapper = mount(PullRequestDrawer, {
      props: {
        state: {
          type: 'metadata',
          context: {
            repositoryDisplayName: 'Payments API',
            pullRequest: detail.pullRequest,
            selectedActionItem: actionItem,
            detail,
            activityContent: {
              type: 'contentAvailable',
              actionItemId: 'action_501',
              activityVersion: 'av_42',
              markdownSource: 'Please keep the retry budget exact.',
            },
          },
        },
      },
    })

    expect(wrapper.text()).toContain('Readiness')
    expect(wrapper.text()).toContain('Contract')
    expect(wrapper.text()).toContain('Selected activity')
    expect(wrapper.text()).toContain('Activity version av_42')
    expect(wrapper.text()).toContain('Alex Chen')
    expect(wrapper.text()).toContain('Please keep the retry budget exact.')
    expect(wrapper.text()).not.toContain('Loading pull request details')
  })

  it('renders no-action and unavailable-detail copy explicitly', async () => {
    const context = { ...loadingState().context, selectedActionItem: null }
    const wrapper = mount(PullRequestDrawer, {
      props: { state: { type: 'detailLoading', context } },
    })

    expect(wrapper.text()).toContain('No actionable activity is selected.')
    await wrapper.setProps({
      state: {
        type: 'detailUnavailable',
        context,
        message: 'Pull request details are unavailable.',
      },
    })
    expect(wrapper.text()).toContain('Pull request details are unavailable.')
  })

  it('emits close from both the Close control and Escape', async () => {
    const wrapper = mount(PullRequestDrawer, { props: { state: loadingState() } })

    await wrapper.get('[data-close-drawer]').trigger('click')
    await wrapper.get('aside').trigger('keydown', { key: 'Escape' })

    expect(wrapper.emitted('close')).toHaveLength(2)
  })

  it('forwards retry from retryable activity content', async () => {
    const state = loadingState()
    state.context.activityContent = {
      type: 'contentUnavailable',
      message: 'Temporary upstream failure.',
      retryable: true,
    }
    const wrapper = mount(PullRequestDrawer, { props: { state } })

    await wrapper.get('.drawer-activity button').trigger('click')

    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('forwards acknowledgment and repository refresh commands', async () => {
    const state = loadingState()
    state.context.activityContent = {
      type: 'contentAvailable',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      markdownSource: 'Exact activity body',
    }
    const wrapper = mount(PullRequestDrawer, { props: { state } })

    await wrapper.get('.drawer-activity button').trigger('click')
    expect(wrapper.emitted('acknowledge')).toHaveLength(1)

    state.context.activityContent = {
      type: 'newerActivity',
      actionItemId: 'action_501',
      requestedActivityVersion: 'av_42',
      currentActivityVersion: 'av_43',
    }
    await wrapper.setProps({ state: { ...state, context: { ...state.context } } })
    await wrapper.get('.drawer-activity button').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })
})
