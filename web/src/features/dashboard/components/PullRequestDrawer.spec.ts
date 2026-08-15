import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'

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

function stubDrawerBreakpoint(narrow: boolean) {
  const matchMedia = vi.fn((query: string) => ({
    matches: query === '(max-width: 759px)' ? narrow : false,
  }))
  vi.stubGlobal('matchMedia', matchMedia)
  return matchMedia
}

function stubScrollIntoView() {
  const originalDescriptor = Object.getOwnPropertyDescriptor(
    HTMLElement.prototype,
    'scrollIntoView',
  )
  const scrollIntoView = vi.fn()
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    value: scrollIntoView,
  })
  return {
    restore: () => {
      if (originalDescriptor) {
        Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalDescriptor)
      } else {
        Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
      }
    },
    scrollIntoView,
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
    const activityAnnouncement = wrapper.get('[data-activity-announcement]')
    expect(activityAnnouncement.attributes('aria-live')).toBe('polite')
    expect(activityAnnouncement.text()).toBe('')
    const externalLinks = wrapper.findAll('a[target="_blank"]')
    expect(externalLinks).not.toHaveLength(0)
    for (const link of externalLinks) {
      expect(link.attributes('rel')).toBe('noopener noreferrer')
    }
    wrapper.unmount()
  })

  it('scrolls the drawer heading into view when it opens in the narrow layout', async () => {
    const { restore, scrollIntoView } = stubScrollIntoView()
    const matchMedia = stubDrawerBreakpoint(true)
    const wrapper = mount(PullRequestDrawer, {
      attachTo: document.body,
      props: { state: { type: 'closed' } },
    })

    try {
      await wrapper.setProps({ state: loadingState() })
      await nextTick()

      expect(matchMedia).toHaveBeenCalledWith('(max-width: 759px)')
      expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start' })
      expect(document.activeElement).toBe(wrapper.get('[data-close-drawer]').element)
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
      restore()
    }
  })

  it('does not scroll the drawer heading when it opens in the wide layout', async () => {
    const { restore, scrollIntoView } = stubScrollIntoView()
    const matchMedia = stubDrawerBreakpoint(false)
    const wrapper = mount(PullRequestDrawer, {
      attachTo: document.body,
      props: { state: { type: 'closed' } },
    })

    try {
      await wrapper.setProps({ state: loadingState() })
      await nextTick()

      expect(matchMedia).toHaveBeenCalledWith('(max-width: 759px)')
      expect(scrollIntoView).not.toHaveBeenCalled()
      expect(document.activeElement).toBe(wrapper.get('[data-close-drawer]').element)
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
      restore()
    }
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
    expect(wrapper.get('[data-drawer-detail-status]').attributes('aria-live')).toBe('polite')
  })

  it('uses native disabled while acknowledgment is pending and politely announces outcomes', async () => {
    const state = loadingState()
    state.context.activityContent = {
      type: 'ackPending',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      markdownSource: 'Exact activity body',
    }
    const wrapper = mount(PullRequestDrawer, { props: { state } })

    expect(wrapper.get('.drawer-activity button').attributes()).toHaveProperty('disabled')
    const activityScope = wrapper.get('.drawer-activity')
    expect(activityScope.findAll('[aria-live]')).toHaveLength(1)
    expect(activityScope.findAll('[role="status"]')).toHaveLength(1)
    const status = activityScope.get('[data-activity-announcement]')
    expect(status.attributes('aria-live')).toBe('polite')
    expect(status.text()).toBe('Acknowledging av_42…')
    expect(wrapper.get('.activity-outcome').find('[aria-live]').exists()).toBe(false)
    expect(wrapper.get('.activity-outcome').find('[role="status"]').exists()).toBe(false)
    const persistentStatus = status.element

    state.context.activityContent = {
      type: 'acknowledged',
      message: 'Activity acknowledged.',
    }
    await wrapper.setProps({ state: { ...state, context: { ...state.context } } })

    const acknowledgedStatus = activityScope.get('[data-activity-announcement]')
    expect(acknowledgedStatus.element).toBe(persistentStatus)
    expect(acknowledgedStatus.text()).toBe('Activity acknowledged.')
    expect(activityScope.findAll('[aria-live]')).toHaveLength(1)
  })

  it('uses one persistent concise region for loading and refresh announcements', async () => {
    const state = loadingState()
    state.context.activityContent = {
      type: 'contentLoading',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
    }
    const wrapper = mount(PullRequestDrawer, { props: { state } })
    const activityScope = wrapper.get('.drawer-activity')

    expect(activityScope.findAll('[aria-live]')).toHaveLength(1)
    expect(activityScope.findAll('[role="status"]')).toHaveLength(1)
    const loadingStatus = activityScope.get('[data-activity-announcement]')
    expect(loadingStatus.text()).toBe('Loading activity av_42…')
    expect(wrapper.get('.activity-outcome').find('[role="status"]').exists()).toBe(false)
    const persistentStatus = loadingStatus.element

    state.context.activityContent = { type: 'refreshing', currentActivityVersion: 'av_43' }
    await wrapper.setProps({ state: { ...state, context: { ...state.context } } })

    const refreshingStatus = activityScope.get('[data-activity-announcement]')
    expect(refreshingStatus.element).toBe(persistentStatus)
    expect(refreshingStatus.text()).toBe('Refreshing activity at av_43…')
    expect(activityScope.findAll('[aria-live]')).toHaveLength(1)
    expect(wrapper.get('.activity-outcome').find('[role="status"]').exists()).toBe(false)
  })

  it('emits close from the Close control', async () => {
    const wrapper = mount(PullRequestDrawer, { props: { state: loadingState() } })

    await wrapper.get('[data-close-drawer]').trigger('click')

    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('emits exactly one close per Escape from inside or outside the drawer', async () => {
    const wrapper = mount(PullRequestDrawer, {
      attachTo: document.body,
      props: { state: loadingState() },
    })
    const outsideDrawer = document.createElement('button')
    document.body.append(outsideDrawer)

    try {
      wrapper
        .get('aside')
        .element.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))
      await nextTick()
      expect(wrapper.emitted('close')).toHaveLength(1)

      outsideDrawer.focus()
      outsideDrawer.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))
      await nextTick()
      expect(wrapper.emitted('close')).toHaveLength(2)
    } finally {
      wrapper.unmount()
      outsideDrawer.remove()
    }
  })

  it('removes global Escape handling while closed and after unmount', async () => {
    const onClose = vi.fn()
    const wrapper = mount(PullRequestDrawer, {
      attachTo: document.body,
      props: { onClose, state: loadingState() },
    })
    const outsideDrawer = document.createElement('button')
    document.body.append(outsideDrawer)

    try {
      outsideDrawer.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))
      await nextTick()
      expect(onClose).toHaveBeenCalledTimes(1)

      await wrapper.setProps({ state: { type: 'closed' } })
      outsideDrawer.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))
      await nextTick()
      expect(onClose).toHaveBeenCalledTimes(1)

      await wrapper.setProps({ state: loadingState() })
      wrapper.unmount()
      outsideDrawer.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))
      await nextTick()
      expect(onClose).toHaveBeenCalledTimes(1)
    } finally {
      if (wrapper.exists()) wrapper.unmount()
      outsideDrawer.remove()
    }
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
