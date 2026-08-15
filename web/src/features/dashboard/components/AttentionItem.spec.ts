import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { makeActionItem } from '../testing/dashboardTestData'
import AttentionItem from './AttentionItem.vue'

describe('AttentionItem', () => {
  it('renders action context and emits the exact item with its invoking button', async () => {
    const actionItem = makeActionItem({
      actionItemId: 'action_501',
      repositoryId: 'repo_payments',
      pullRequestId: 'pr_184',
      kind: 'changesRequested',
      actorDisplayName: 'Alex Chen',
      occurredAt: '2026-08-15T09:57:00Z',
      acknowledgmentState: 'acknowledged',
    })
    const wrapper = mount(AttentionItem, { props: { item: actionItem } })

    expect(wrapper.text()).toContain('repo_payments')
    expect(wrapper.text()).toContain('pr_184')
    expect(wrapper.text()).toContain('Changes requested')
    expect(wrapper.text()).toContain('Alex Chen')
    expect(wrapper.get('time').attributes('datetime')).toBe('2026-08-15T09:57:00Z')
    expect(wrapper.text()).toContain('Acknowledged')

    await wrapper.get('[data-action-item-id="action_501"]').trigger('click')
    const [item, invoker] = wrapper.emitted('review')?.[0] ?? []
    expect(item).toEqual(actionItem)
    expect(invoker).toBeInstanceOf(HTMLButtonElement)
  })
})
