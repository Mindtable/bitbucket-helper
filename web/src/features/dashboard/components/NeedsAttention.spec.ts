import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { makeActionItem } from '../testing/dashboardTestData'
import NeedsAttention from './NeedsAttention.vue'

const action501 = makeActionItem({ actionItemId: 'action_501' })
const action502 = makeActionItem({ actionItemId: 'action_502' })

describe('NeedsAttention', () => {
  it('starts expanded, keeps the count visible while collapsed, and preserves collapse on prop update', async () => {
    const wrapper = mount(NeedsAttention, {
      props: { items: [action501, action502] },
    })
    const toggle = wrapper.get('button.needs-attention-toggle')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('#needs-attention-body').isVisible()).toBe(true)
    expect(toggle.text()).toContain('2 open')

    await toggle.trigger('click')
    await wrapper.setProps({ items: [action502] })

    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('#needs-attention-body').exists()).toBe(false)
    expect(toggle.text()).toContain('1 open')
  })

  it('emits the exact item and invoking button', async () => {
    const wrapper = mount(NeedsAttention, { props: { items: [action501] } })

    await wrapper.get('[data-action-item-id="action_501"]').trigger('click')

    const [item, invoker] = wrapper.emitted('review')?.[0] ?? []
    expect(item).toEqual(action501)
    expect(invoker).toBeInstanceOf(HTMLButtonElement)
  })

  it('renders the zero state only while expanded', async () => {
    const wrapper = mount(NeedsAttention, { props: { items: [] } })

    expect(wrapper.get('button.needs-attention-toggle').text()).toContain('0 open')
    expect(wrapper.get('#needs-attention-body').text()).toContain("You're all caught up.")

    await wrapper.get('button.needs-attention-toggle').trigger('click')

    expect(wrapper.find('#needs-attention-body').exists()).toBe(false)
    expect(wrapper.text()).not.toContain("You're all caught up.")
  })
})
