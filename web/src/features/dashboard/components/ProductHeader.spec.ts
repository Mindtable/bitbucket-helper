import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ProductHeader from './ProductHeader.vue'

describe('ProductHeader', () => {
  it('publishes workspace context, opaque revision, and active sync status', () => {
    const wrapper = mount(ProductHeader, {
      props: {
        workspaceDisplayName: 'Acme Engineering',
        dashboardRevision: 'revision_opaque_17',
        overallStatus: 'active',
        refreshState: { type: 'active' },
      },
    })

    expect(wrapper.get('h1').text()).toBe('Bitbucket Helper')
    expect(wrapper.text()).toContain('Acme Engineering')
    expect(wrapper.get('code').text()).toBe('revision_opaque_17')
    expect(wrapper.get('[role="status"]').text()).toContain('Sync active')
  })

  it('emits refresh and only disables refresh while registering', async () => {
    const idle = mount(ProductHeader, {
      props: {
        workspaceDisplayName: 'Acme Engineering',
        dashboardRevision: 'revision_opaque_17',
        overallStatus: 'idle',
        refreshState: { type: 'idle' },
      },
    })
    await idle.get('button').trigger('click')
    expect(idle.emitted('refresh')).toHaveLength(1)

    const registering = mount(ProductHeader, {
      props: {
        workspaceDisplayName: 'Acme Engineering',
        dashboardRevision: 'revision_opaque_17',
        overallStatus: 'active',
        refreshState: { type: 'registering' },
      },
    })
    expect(registering.get('button').attributes('disabled')).toBeDefined()
  })
})
