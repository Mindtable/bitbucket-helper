import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type {
  AcknowledgmentSourceResult,
  ActionContentSourceResult,
  DashboardSource,
  PullRequestDetailSourceResult,
  RefreshSourceResult,
} from './dashboardSource'
import {
  makeActionItem,
  makeDashboard,
  makePullRequest,
  makePullRequestDetail,
  makeRepository,
} from './testing/dashboardTestData'
import { button, createDashboardSourceStub, deferred } from './testing/dashboardTestSource'
import {
  usePullRequestDrawer as createPullRequestDrawer,
  type PullRequestDrawerDependencies,
} from './usePullRequestDrawer'

function usePullRequestDrawer(
  source: DashboardSource,
  dependencies: PullRequestDrawerDependencies = {
    applyAcknowledgment: vi.fn(),
    pollDashboard: vi.fn(() => Promise.resolve()),
  },
) {
  return createPullRequestDrawer(source, dependencies)
}

function selectedActionFixture() {
  const actionItem = makeActionItem({
    actionItemId: 'action_501',
    activityVersion: 'av_42',
    repositoryId: 'repo_payments',
    pullRequestId: 'pr_184',
  })
  const pullRequest = makePullRequest({
    pullRequestId: 'pr_184',
    repositoryId: 'repo_payments',
    actionItems: [actionItem],
    actionableItemCount: 1,
  })
  const repository = makeRepository({
    repositoryId: 'repo_payments',
    pullRequests: [pullRequest],
  })
  const detail = {
    ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
    pullRequest,
    actionItems: [actionItem],
  }
  const dashboard = makeDashboard({ repositoryGroups: [repository], inbox: [actionItem] })
  return { actionItem, dashboard, detail, pullRequest, repository }
}

function availableContent() {
  return {
    type: 'contentAvailable' as const,
    actionItemId: 'action_501',
    activityVersion: 'av_42',
    markdownSource: 'Exact activity body',
  }
}

async function openSelectedAction(
  source: DashboardSource,
  dependencies: PullRequestDrawerDependencies,
) {
  const fixture = selectedActionFixture()
  const drawer = usePullRequestDrawer(source, dependencies)
  await drawer.openActionItem(fixture.dashboard, fixture.actionItem, button())
  await flushPromises()
  return { drawer, ...fixture }
}

afterEach(() => {
  document.body.replaceChildren()
})

describe('usePullRequestDrawer', () => {
  it.each([
    {
      name: 'acknowledged',
      result: {
        type: 'acknowledged',
        actionItemId: 'action_501',
        activityVersion: 'av_42',
      } satisfies AcknowledgmentSourceResult,
    },
    {
      name: 'already acknowledged',
      result: {
        type: 'alreadyAcknowledged',
        actionItemId: 'action_501',
        activityVersion: 'av_42',
      } satisfies AcknowledgmentSourceResult,
    },
  ])('reconciles an $name result with the captured exact identity', async ({ result }) => {
    const fixture = selectedActionFixture()
    const applyAcknowledgment = vi.fn()
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent: () => Promise.resolve(availableContent()),
      acknowledgeActionItem: vi.fn(() => Promise.resolve(result)),
    })
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment,
      pollDashboard: vi.fn(() => Promise.resolve()),
    })

    await drawer.acknowledgeSelected()

    expect(source.acknowledgeActionItem).toHaveBeenCalledWith('action_501', 'av_42')
    expect(applyAcknowledgment).toHaveBeenCalledTimes(1)
    expect(applyAcknowledgment).toHaveBeenCalledWith({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      repositoryId: 'repo_payments',
      pullRequestId: 'pr_184',
    })
    expect(drawer.state.value).toMatchObject({
      context: {
        activityContent: { type: 'acknowledged', message: 'Activity acknowledged.' },
      },
    })
  })

  it.each([
    {
      name: 'acknowledged action identity',
      result: {
        type: 'acknowledged',
        actionItemId: 'action_response_leak',
        activityVersion: 'av_42',
      } satisfies AcknowledgmentSourceResult,
    },
    {
      name: 'acknowledged activity version',
      result: {
        type: 'acknowledged',
        actionItemId: 'action_501',
        activityVersion: 'version_response_leak',
      } satisfies AcknowledgmentSourceResult,
    },
    {
      name: 'already-acknowledged action identity',
      result: {
        type: 'alreadyAcknowledged',
        actionItemId: 'action_response_leak',
        activityVersion: 'av_42',
      } satisfies AcknowledgmentSourceResult,
    },
    {
      name: 'already-acknowledged activity version',
      result: {
        type: 'alreadyAcknowledged',
        actionItemId: 'action_501',
        activityVersion: 'version_response_leak',
      } satisfies AcknowledgmentSourceResult,
    },
    {
      name: 'stale-acknowledgment action identity',
      result: {
        type: 'staleActivityVersion',
        actionItemId: 'action_response_leak',
        requestedActivityVersion: 'av_42',
        currentActivityVersion: 'current_response_leak',
        hasNewerActivity: true,
      } satisfies AcknowledgmentSourceResult,
    },
    {
      name: 'stale-acknowledgment requested version',
      result: {
        type: 'staleActivityVersion',
        actionItemId: 'action_501',
        requestedActivityVersion: 'version_response_leak',
        currentActivityVersion: 'current_response_leak',
        hasNewerActivity: true,
      } satisfies AcknowledgmentSourceResult,
    },
  ])(
    'rejects a mismatched $name echo without reconciliation or repository refresh',
    async ({ result }) => {
      const fixture = selectedActionFixture()
      const applyAcknowledgment = vi.fn()
      const pollDashboard = vi.fn(() => Promise.resolve())
      const startRepositoryRefresh = vi.fn(() =>
        Promise.resolve({ type: 'refreshRunRegistered' as const, refreshRunId: 'refresh_repo_1' }),
      )
      const source = createDashboardSourceStub({
        loadPullRequest: () =>
          Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
        loadActionContent: () => Promise.resolve(availableContent()),
        acknowledgeActionItem: () => Promise.resolve(result),
        startRepositoryRefresh,
      })
      const { drawer } = await openSelectedAction(source, {
        applyAcknowledgment,
        pollDashboard,
      })

      await drawer.acknowledgeSelected()

      expect(applyAcknowledgment).not.toHaveBeenCalled()
      expect(startRepositoryRefresh).not.toHaveBeenCalled()
      expect(pollDashboard).not.toHaveBeenCalled()
      expect(drawer.state.value).toMatchObject({
        context: {
          activityContent: {
            type: 'contentUnavailable',
            message: 'Acknowledgment unavailable.',
            retryable: false,
          },
        },
      })
      expect(JSON.stringify(drawer.state.value)).not.toContain('response_leak')
      expect(JSON.stringify(drawer.state.value)).not.toContain('Exact activity body')
    },
  )

  it('registers a repository refresh for a stale acknowledgment without reconciling locally', async () => {
    const fixture = selectedActionFixture()
    const applyAcknowledgment = vi.fn()
    const pollDashboard = vi.fn(() => Promise.resolve())
    const startRepositoryRefresh = vi.fn(() =>
      Promise.resolve({ type: 'refreshRunRegistered' as const, refreshRunId: 'refresh_repo_1' }),
    )
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent: () => Promise.resolve(availableContent()),
      acknowledgeActionItem: () =>
        Promise.resolve({
          type: 'staleActivityVersion',
          actionItemId: 'action_501',
          requestedActivityVersion: 'av_42',
          currentActivityVersion: 'av_43',
          hasNewerActivity: true,
        }),
      startRepositoryRefresh,
    })
    const { drawer } = await openSelectedAction(source, { applyAcknowledgment, pollDashboard })

    await drawer.acknowledgeSelected()

    expect(applyAcknowledgment).not.toHaveBeenCalled()
    expect(startRepositoryRefresh).toHaveBeenCalledWith('repo_payments')
    expect(pollDashboard).toHaveBeenCalledTimes(1)
    expect(drawer.state.value).toMatchObject({
      context: { activityContent: { type: 'refreshing', currentActivityVersion: 'av_43' } },
    })
  })

  it.each([
    {
      name: 'domain rejection',
      result: {
        type: 'acknowledgmentRejected',
        reason: 'Only reviewers can acknowledge this activity.',
      } satisfies AcknowledgmentSourceResult,
      message: 'Only reviewers can acknowledge this activity.',
    },
    {
      name: 'missing action',
      result: { type: 'actionItemNotFound' } satisfies AcknowledgmentSourceResult,
      message: 'This activity is no longer available.',
    },
  ])('preserves the actionable item for $name', async ({ message, result }) => {
    const fixture = selectedActionFixture()
    const applyAcknowledgment = vi.fn()
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent: () => Promise.resolve(availableContent()),
      acknowledgeActionItem: () => Promise.resolve(result),
    })
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment,
      pollDashboard: vi.fn(() => Promise.resolve()),
    })

    await drawer.acknowledgeSelected()

    expect(applyAcknowledgment).not.toHaveBeenCalled()
    expect(drawer.state.value).toMatchObject({
      context: {
        selectedActionItem: { actionItemId: 'action_501', activityVersion: 'av_42' },
        activityContent: {
          type: 'acknowledgmentRejected',
          message,
          retryable: false,
          actionItemId: 'action_501',
          activityVersion: 'av_42',
        },
      },
    })
  })

  it('suppresses duplicate acknowledgment dispatch while the exact request is pending', async () => {
    const fixture = selectedActionFixture()
    const pending = deferred<AcknowledgmentSourceResult>()
    const acknowledgeActionItem = vi.fn(() => pending.promise)
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent: () => Promise.resolve(availableContent()),
      acknowledgeActionItem,
    })
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment: vi.fn(),
      pollDashboard: vi.fn(() => Promise.resolve()),
    })

    const first = drawer.acknowledgeSelected()
    const second = drawer.acknowledgeSelected()

    expect(acknowledgeActionItem).toHaveBeenCalledTimes(1)
    expect(drawer.state.value).toMatchObject({
      context: {
        activityContent: {
          type: 'ackPending',
          actionItemId: 'action_501',
          activityVersion: 'av_42',
          markdownSource: 'Exact activity body',
        },
      },
    })
    pending.resolve({ type: 'acknowledged', actionItemId: 'action_501', activityVersion: 'av_42' })
    await Promise.all([first, second])
  })

  it('reconciles one late successful acknowledgment without reopening a closed drawer', async () => {
    const fixture = selectedActionFixture()
    const pending = deferred<AcknowledgmentSourceResult>()
    const applyAcknowledgment = vi.fn()
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent: () => Promise.resolve(availableContent()),
      acknowledgeActionItem: () => pending.promise,
    })
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment,
      pollDashboard: vi.fn(() => Promise.resolve()),
    })
    const acknowledgment = drawer.acknowledgeSelected()

    drawer.close()
    pending.resolve({ type: 'acknowledged', actionItemId: 'action_501', activityVersion: 'av_42' })
    await acknowledgment

    expect(applyAcknowledgment).toHaveBeenCalledTimes(1)
    expect(drawer.state.value).toEqual({ type: 'closed' })
  })

  it('reconciles one late idempotent result without overwriting a newer selection', async () => {
    const fixture = selectedActionFixture()
    const pending = deferred<AcknowledgmentSourceResult>()
    const applyAcknowledgment = vi.fn()
    const otherAction = makeActionItem({
      actionItemId: 'action_777',
      activityVersion: 'av_other',
      repositoryId: 'repo_other',
      pullRequestId: 'pr_other',
    })
    const otherPullRequest = makePullRequest({
      pullRequestId: 'pr_other',
      repositoryId: 'repo_other',
      actionItems: [otherAction],
    })
    const otherRepository = makeRepository({
      repositoryId: 'repo_other',
      displayName: 'Other repository',
      pullRequests: [otherPullRequest],
    })
    const otherDetail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_other' }),
      repositoryDisplayName: 'Other repository',
      pullRequest: otherPullRequest,
      actionItems: [otherAction],
    }
    const source = createDashboardSourceStub({
      loadPullRequest: vi
        .fn()
        .mockResolvedValueOnce({ type: 'pullRequestAvailable', detail: fixture.detail })
        .mockResolvedValueOnce({ type: 'pullRequestAvailable', detail: otherDetail }),
      loadActionContent: vi.fn().mockResolvedValueOnce(availableContent()).mockResolvedValueOnce({
        type: 'contentAvailable',
        actionItemId: 'action_777',
        activityVersion: 'av_other',
        markdownSource: 'Other exact body',
      }),
      acknowledgeActionItem: () => pending.promise,
    })
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment,
      pollDashboard: vi.fn(() => Promise.resolve()),
    })
    const acknowledgment = drawer.acknowledgeSelected()
    await drawer.openActionItem(
      makeDashboard({ repositoryGroups: [otherRepository], inbox: [otherAction] }),
      otherAction,
      button(),
    )
    await flushPromises()

    pending.resolve({
      type: 'alreadyAcknowledged',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
    })
    await acknowledgment

    expect(applyAcknowledgment).toHaveBeenCalledTimes(1)
    expect(drawer.state.value).toMatchObject({
      context: {
        pullRequest: { pullRequestId: 'pr_other' },
        selectedActionItem: { actionItemId: 'action_777', activityVersion: 'av_other' },
        activityContent: { type: 'contentAvailable', markdownSource: 'Other exact body' },
      },
    })
  })

  it.each([
    {
      name: 'newer activity observed',
      result: {
        type: 'newerActivityObserved',
        repositoryId: 'repo_payments',
        requestedActivityVersion: 'av_42',
        currentActivityVersion: 'av_43',
      } satisfies ActionContentSourceResult,
    },
    {
      name: 'stale activity version',
      result: {
        type: 'staleActivityVersion',
        requestedActivityVersion: 'av_42',
        currentActivityVersion: 'av_43',
      } satisfies ActionContentSourceResult,
    },
  ])('refreshes the repository for $name content', async ({ result }) => {
    const fixture = selectedActionFixture()
    const startRepositoryRefresh = vi.fn(() =>
      Promise.resolve({ type: 'refreshRunRegistered' as const, refreshRunId: 'refresh_repo_1' }),
    )
    const pollDashboard = vi.fn(() => Promise.resolve())
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent: () => Promise.resolve(result),
      startRepositoryRefresh,
    })
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment: vi.fn(),
      pollDashboard,
    })

    await drawer.refreshSelectedRepository()

    expect(startRepositoryRefresh).toHaveBeenCalledWith('repo_payments')
    expect(pollDashboard).toHaveBeenCalledTimes(1)
    expect(drawer.state.value).toMatchObject({
      context: { activityContent: { type: 'refreshing', currentActivityVersion: 'av_43' } },
    })
  })

  it.each([
    {
      name: 'no repositories configured',
      result: {
        type: 'noRepositoriesConfigured',
        setupCommand: 'bitbucket-helper repository add',
      } satisfies RefreshSourceResult,
    },
    {
      name: 'workspace not configured',
      result: {
        type: 'workspaceNotConfigured',
        setupCommand: 'bitbucket-helper workspace configure',
      } satisfies RefreshSourceResult,
    },
  ])('surfaces setup guidance when repository refresh reports $name', async ({ result }) => {
    const fixture = selectedActionFixture()
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent: () =>
        Promise.resolve({
          type: 'newerActivityObserved',
          repositoryId: 'repo_payments',
          requestedActivityVersion: 'av_42',
          currentActivityVersion: 'av_43',
        }),
      startRepositoryRefresh: () => Promise.resolve(result),
    })
    const pollDashboard = vi.fn(() => Promise.resolve())
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment: vi.fn(),
      pollDashboard,
    })

    await drawer.refreshSelectedRepository()

    expect(pollDashboard).not.toHaveBeenCalled()
    expect(drawer.state.value).toMatchObject({
      context: {
        selectedActionItem: { actionItemId: 'action_501' },
        activityContent: {
          type: 'contentUnavailable',
          message: `Refresh unavailable. ${result.setupCommand}`,
          retryable: false,
        },
      },
    })
  })

  it('uses technical-safe copy when acknowledgment or repository-refresh requests throw', async () => {
    const fixture = selectedActionFixture()
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent: () => Promise.resolve(availableContent()),
      acknowledgeActionItem: () => Promise.reject(new Error('credential=do-not-display')),
    })
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment: vi.fn(),
      pollDashboard: vi.fn(() => Promise.resolve()),
    })

    await drawer.acknowledgeSelected()

    expect(drawer.state.value).toMatchObject({
      context: {
        activityContent: {
          type: 'acknowledgmentRejected',
          message: 'Acknowledgment unavailable.',
          retryable: true,
        },
      },
    })
    expect(JSON.stringify(drawer.state.value)).not.toContain('do-not-display')

    const refreshSource = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent: () =>
        Promise.resolve({
          type: 'staleActivityVersion',
          requestedActivityVersion: 'av_42',
          currentActivityVersion: 'av_43',
        }),
      startRepositoryRefresh: () => Promise.reject(new Error('token=do-not-display')),
    })
    const refreshDrawer = await openSelectedAction(refreshSource, {
      applyAcknowledgment: vi.fn(),
      pollDashboard: vi.fn(() => Promise.resolve()),
    })

    await refreshDrawer.drawer.refreshSelectedRepository()

    expect(refreshDrawer.drawer.state.value).toMatchObject({
      context: {
        activityContent: {
          type: 'contentUnavailable',
          message: 'Refresh unavailable',
          retryable: false,
        },
      },
    })
    expect(JSON.stringify(refreshDrawer.drawer.state.value)).not.toContain('do-not-display')
  })
  it('loads the selected action content with its exact opaque activity version', async () => {
    const actionItem = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      pullRequestId: 'pr_184',
    })
    const pullRequest = makePullRequest({
      pullRequestId: 'pr_184',
      actionItems: [actionItem],
    })
    const detail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
      pullRequest,
      actionItems: [actionItem],
    }
    const source = createDashboardSourceStub({
      loadPullRequest: () => Promise.resolve({ type: 'pullRequestAvailable', detail }),
      loadActionContent: vi.fn(() =>
        Promise.resolve({
          type: 'contentAvailable' as const,
          actionItemId: 'action_501',
          activityVersion: 'av_42',
          markdownSource: 'Exact activity body',
        }),
      ),
    })
    const drawer = usePullRequestDrawer(source)

    await drawer.openPullRequest(
      makeRepository({ pullRequests: [pullRequest] }),
      pullRequest,
      button(),
    )
    await flushPromises()

    expect(source.loadActionContent).toHaveBeenCalledWith('action_501', 'av_42')
    expect(drawer.state.value).toMatchObject({
      type: 'metadata',
      context: {
        activityContent: {
          type: 'contentAvailable',
          actionItemId: 'action_501',
          activityVersion: 'av_42',
        },
      },
    })
  })

  it.each([
    {
      name: 'content unavailable',
      result: {
        type: 'contentUnavailable',
        reason: 'Bitbucket did not return the activity.',
        retryable: false,
      } satisfies ActionContentSourceResult,
      expected: {
        type: 'contentUnavailable',
        message: 'Bitbucket did not return the activity.',
        retryable: false,
      },
    },
    {
      name: 'newer activity observed',
      result: {
        type: 'newerActivityObserved',
        repositoryId: 'repo_payments',
        requestedActivityVersion: 'av_42',
        currentActivityVersion: 'av_43',
      } satisfies ActionContentSourceResult,
      expected: {
        type: 'newerActivity',
        actionItemId: 'action_501',
        requestedActivityVersion: 'av_42',
        currentActivityVersion: 'av_43',
      },
    },
    {
      name: 'stale activity version',
      result: {
        type: 'staleActivityVersion',
        requestedActivityVersion: 'av_42',
        currentActivityVersion: 'av_43',
      } satisfies ActionContentSourceResult,
      expected: {
        type: 'newerActivity',
        actionItemId: 'action_501',
        requestedActivityVersion: 'av_42',
        currentActivityVersion: 'av_43',
      },
    },
    {
      name: 'action item not found',
      result: { type: 'actionItemNotFound' } satisfies ActionContentSourceResult,
      expected: {
        type: 'contentUnavailable',
        message: 'This activity is no longer available.',
        retryable: false,
      },
    },
  ])('maps $name without displaying a requested body', async ({ result, expected }) => {
    const actionItem = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      repositoryId: 'repo_payments',
      pullRequestId: 'pr_184',
    })
    const pullRequest = makePullRequest({
      pullRequestId: 'pr_184',
      repositoryId: 'repo_payments',
      actionItems: [actionItem],
    })
    const detail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
      pullRequest,
      actionItems: [actionItem],
    }
    const source = createDashboardSourceStub({
      loadPullRequest: () => Promise.resolve({ type: 'pullRequestAvailable', detail }),
      loadActionContent: () => Promise.resolve(result),
    })
    const drawer = usePullRequestDrawer(source)

    await drawer.openPullRequest(
      makeRepository({ pullRequests: [pullRequest] }),
      pullRequest,
      button(),
    )
    await flushPromises()

    if (drawer.state.value.type === 'closed') throw new Error('expected open drawer')
    expect(drawer.state.value.context.activityContent).toEqual(expected)
  })

  it.each([
    {
      name: 'content-available action identity',
      result: {
        type: 'contentAvailable',
        actionItemId: 'action_response_leak',
        activityVersion: 'av_42',
        markdownSource: 'raw_body_response_leak',
      } satisfies ActionContentSourceResult,
    },
    {
      name: 'content-available activity version',
      result: {
        type: 'contentAvailable',
        actionItemId: 'action_501',
        activityVersion: 'version_response_leak',
        markdownSource: 'raw_body_response_leak',
      } satisfies ActionContentSourceResult,
    },
    {
      name: 'newer-activity repository identity',
      result: {
        type: 'newerActivityObserved',
        repositoryId: 'repository_response_leak',
        requestedActivityVersion: 'av_42',
        currentActivityVersion: 'current_response_leak',
      } satisfies ActionContentSourceResult,
    },
    {
      name: 'newer-activity requested version',
      result: {
        type: 'newerActivityObserved',
        repositoryId: 'repo_payments',
        requestedActivityVersion: 'version_response_leak',
        currentActivityVersion: 'current_response_leak',
      } satisfies ActionContentSourceResult,
    },
    {
      name: 'stale-content requested version',
      result: {
        type: 'staleActivityVersion',
        requestedActivityVersion: 'version_response_leak',
        currentActivityVersion: 'current_response_leak',
      } satisfies ActionContentSourceResult,
    },
  ])('rejects a mismatched $name echo without retaining response data', async ({ result }) => {
    const fixture = selectedActionFixture()
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent: () => Promise.resolve(result),
    })

    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment: vi.fn(),
      pollDashboard: vi.fn(() => Promise.resolve()),
    })

    expect(drawer.state.value).toMatchObject({
      context: {
        activityContent: {
          type: 'contentUnavailable',
          message: 'Activity content is unavailable.',
          retryable: false,
        },
      },
    })
    expect(JSON.stringify(drawer.state.value)).not.toContain('response_leak')
  })

  it('retries only a retryable failure with the still-selected exact version', async () => {
    const actionItem = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      pullRequestId: 'pr_184',
    })
    const pullRequest = makePullRequest({
      pullRequestId: 'pr_184',
      actionItems: [actionItem],
    })
    const detail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
      pullRequest,
      actionItems: [actionItem],
    }
    const loadActionContent = vi
      .fn()
      .mockResolvedValueOnce({
        type: 'contentUnavailable',
        reason: 'Temporary upstream failure.',
        retryable: true,
      })
      .mockResolvedValueOnce({
        type: 'contentAvailable',
        actionItemId: 'action_501',
        activityVersion: 'av_42',
        markdownSource: 'Loaded after retry',
      })
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () => Promise.resolve({ type: 'pullRequestAvailable', detail }),
        loadActionContent,
      }),
    )
    await drawer.openPullRequest(
      makeRepository({ pullRequests: [pullRequest] }),
      pullRequest,
      button(),
    )
    await flushPromises()

    drawer.retrySelectedContent()
    await flushPromises()

    expect(loadActionContent).toHaveBeenNthCalledWith(1, 'action_501', 'av_42')
    expect(loadActionContent).toHaveBeenNthCalledWith(2, 'action_501', 'av_42')
    expect(drawer.state.value).toMatchObject({
      context: {
        activityContent: { type: 'contentAvailable', markdownSource: 'Loaded after retry' },
      },
    })
    drawer.retrySelectedContent()
    expect(loadActionContent).toHaveBeenCalledTimes(2)
  })

  it('does not load content when detail has no selected action', async () => {
    const pullRequest = makePullRequest({ pullRequestId: 'pr_184', actionItems: [] })
    const detail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
      pullRequest,
      actionItems: [],
    }
    const loadActionContent = vi.fn()
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () => Promise.resolve({ type: 'pullRequestAvailable', detail }),
        loadActionContent,
      }),
    )

    await drawer.openPullRequest(
      makeRepository({ pullRequests: [pullRequest] }),
      pullRequest,
      button(),
    )
    await flushPromises()

    expect(loadActionContent).not.toHaveBeenCalled()
    if (drawer.state.value.type === 'closed') throw new Error('expected open drawer')
    expect(drawer.state.value.context.activityContent).toBeNull()
    drawer.retrySelectedContent()
    expect(loadActionContent).not.toHaveBeenCalled()
  })

  it('keeps content returned for an older action selection out of the drawer', async () => {
    const first = deferred<ActionContentSourceResult>()
    const second = deferred<ActionContentSourceResult>()
    const loadActionContent = vi
      .fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    const firstAction = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
    })
    const secondAction = makeActionItem({
      actionItemId: 'action_502',
      activityVersion: 'av_9',
    })
    const pullRequest = makePullRequest({ actionItems: [firstAction, secondAction] })
    const repository = makeRepository({ pullRequests: [pullRequest] })
    const dashboard = makeDashboard({
      repositoryGroups: [repository],
      inbox: [firstAction, secondAction],
    })
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () =>
          Promise.resolve({
            type: 'pullRequestAvailable',
            detail: {
              ...makePullRequestDetail(),
              pullRequest,
              actionItems: [firstAction, secondAction],
            },
          }),
        loadActionContent,
      }),
    )

    void drawer.openActionItem(dashboard, firstAction, button())
    void drawer.openActionItem(dashboard, secondAction, button())
    second.resolve({
      type: 'contentAvailable',
      actionItemId: 'action_502',
      activityVersion: 'av_9',
      markdownSource: 'Second selection body',
    })
    await flushPromises()
    first.resolve({
      type: 'contentAvailable',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      markdownSource: 'Stale first body',
    })
    await flushPromises()

    expect(drawer.state.value).toMatchObject({
      context: {
        selectedActionItem: { actionItemId: 'action_502', activityVersion: 'av_9' },
        activityContent: { type: 'contentAvailable', markdownSource: 'Second selection body' },
      },
    })
    expect(JSON.stringify(drawer.state.value)).not.toContain('Stale first body')
  })

  it('ignores content that resolves after the drawer closes', async () => {
    const pending = deferred<ActionContentSourceResult>()
    const actionItem = makeActionItem()
    const pullRequest = makePullRequest({ actionItems: [actionItem] })
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () =>
          Promise.resolve({
            type: 'pullRequestAvailable',
            detail: { ...makePullRequestDetail(), pullRequest, actionItems: [actionItem] },
          }),
        loadActionContent: () => pending.promise,
      }),
    )
    await drawer.openPullRequest(
      makeRepository({ pullRequests: [pullRequest] }),
      pullRequest,
      button(),
    )
    await flushPromises()

    drawer.close()
    pending.resolve({
      type: 'contentAvailable',
      actionItemId: actionItem.actionItemId,
      activityVersion: actionItem.activityVersion,
      markdownSource: 'Late body',
    })
    await flushPromises()

    expect(drawer.state.value).toEqual({ type: 'closed' })
  })

  it('ignores content that resolves after selecting another pull request', async () => {
    const firstContent = deferred<ActionContentSourceResult>()
    const firstAction = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      repositoryId: 'repo_1',
      pullRequestId: 'pr_1',
    })
    const secondAction = makeActionItem({
      actionItemId: 'action_601',
      activityVersion: 'av_7',
      repositoryId: 'repo_2',
      pullRequestId: 'pr_2',
    })
    const firstPullRequest = makePullRequest({
      pullRequestId: 'pr_1',
      repositoryId: 'repo_1',
      actionItems: [firstAction],
    })
    const secondPullRequest = makePullRequest({
      pullRequestId: 'pr_2',
      repositoryId: 'repo_2',
      actionItems: [secondAction],
    })
    const source = createDashboardSourceStub({
      loadPullRequest: (pullRequestId) =>
        Promise.resolve({
          type: 'pullRequestAvailable',
          detail:
            pullRequestId === 'pr_1'
              ? {
                  ...makePullRequestDetail({ pullRequestId }),
                  pullRequest: firstPullRequest,
                  actionItems: [firstAction],
                }
              : {
                  ...makePullRequestDetail({ pullRequestId }),
                  pullRequest: secondPullRequest,
                  actionItems: [secondAction],
                },
        }),
      loadActionContent: vi.fn().mockReturnValueOnce(firstContent.promise).mockResolvedValueOnce({
        type: 'contentAvailable',
        actionItemId: 'action_601',
        activityVersion: 'av_7',
        markdownSource: 'Current PR body',
      }),
    })
    const drawer = usePullRequestDrawer(source)
    await drawer.openPullRequest(
      makeRepository({ repositoryId: 'repo_1', pullRequests: [firstPullRequest] }),
      firstPullRequest,
      button(),
    )
    await flushPromises()
    await drawer.openPullRequest(
      makeRepository({ repositoryId: 'repo_2', pullRequests: [secondPullRequest] }),
      secondPullRequest,
      button(),
    )
    await flushPromises()
    firstContent.resolve({
      type: 'contentAvailable',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      markdownSource: 'Other PR stale body',
    })
    await flushPromises()

    expect(drawer.state.value).toMatchObject({
      context: {
        pullRequest: { pullRequestId: 'pr_2' },
        activityContent: { type: 'contentAvailable', markdownSource: 'Current PR body' },
      },
    })
    expect(JSON.stringify(drawer.state.value)).not.toContain('Other PR stale body')
  })

  it('opens from a PR summary immediately and enriches it with detail', async () => {
    const detail = makePullRequestDetail()
    const pending = deferred<PullRequestDetailSourceResult>()
    const source = createDashboardSourceStub({
      loadPullRequest: () => pending.promise,
    })
    const drawer = usePullRequestDrawer(source)
    const repository = makeRepository({
      displayName: 'Payments API',
      pullRequests: [detail.pullRequest],
    })

    const opening = drawer.openPullRequest(repository, detail.pullRequest, button())

    expect(drawer.state.value).toMatchObject({
      type: 'detailLoading',
      context: {
        repositoryDisplayName: 'Payments API',
        pullRequest: detail.pullRequest,
        detail: null,
      },
    })

    pending.resolve({ type: 'pullRequestAvailable', detail })
    await opening

    expect(drawer.state.value).toMatchObject({
      type: 'metadata',
      context: {
        repositoryDisplayName: 'Payments API',
        pullRequest: detail.pullRequest,
        detail: {
          repositoryDisplayName: 'Payments API',
          pullRequest: detail.pullRequest,
          readinessChecks: detail.readinessChecks,
          actionItems: detail.pullRequest.actionItems,
        },
      },
    })
  })

  it('treats initial detail as enrichment without replacing accepted repository or PR metadata', async () => {
    const acceptedPullRequest = makePullRequest({
      pullRequestId: 'pr_184',
      repositoryId: 'repo_payments',
      displayNumber: 184,
      title: 'Accepted dashboard title',
      webUrl: 'https://bitbucket.org/acme/payments/pull-requests/184',
      buildState: { type: 'failed', failedCheckCount: 2 },
      readiness: { type: 'available', passed: 5, total: 7 },
      actionableItemCount: 4,
      acknowledgedItemCount: 3,
      actionItems: [],
    })
    const acceptedRepository = makeRepository({
      repositoryId: 'repo_payments',
      displayName: 'Accepted Payments API',
      pullRequests: [acceptedPullRequest],
    })
    const divergentDetail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
      repositoryDisplayName: 'Divergent detail repository',
      pullRequest: makePullRequest({
        pullRequestId: 'pr_184',
        repositoryId: 'repo_response_leak',
        displayNumber: 999,
        title: 'Divergent detail title',
        webUrl: 'https://response-leak.invalid/pull-request',
        buildState: { type: 'successful' },
        readiness: { type: 'available', passed: 7, total: 7 },
        actionableItemCount: 99,
        acknowledgedItemCount: 98,
      }),
      readinessChecks: [{ checkId: 'contract', label: 'Contract', state: 'passed' as const }],
      actionItems: [
        makeActionItem({
          actionItemId: 'action_response_leak',
          repositoryId: 'repo_response_leak',
          pullRequestId: 'pr_184',
        }),
      ],
    }
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () =>
          Promise.resolve({ type: 'pullRequestAvailable', detail: divergentDetail }),
      }),
    )

    await drawer.openPullRequest(acceptedRepository, acceptedPullRequest, button())

    expect(drawer.state.value).toMatchObject({
      type: 'metadata',
      context: {
        repositoryDisplayName: 'Accepted Payments API',
        pullRequest: acceptedPullRequest,
        selectedActionItem: null,
        detail: {
          repositoryDisplayName: 'Accepted Payments API',
          pullRequest: acceptedPullRequest,
          readinessChecks: divergentDetail.readinessChecks,
          actionItems: [],
        },
      },
    })
    if (drawer.state.value.type === 'closed') throw new Error('expected open drawer')
    expect(drawer.state.value.context.pullRequest).toBe(acceptedPullRequest)
    expect(JSON.stringify(drawer.state.value)).not.toContain('response_leak')
    expect(JSON.stringify(drawer.state.value)).not.toContain('Divergent detail title')
  })

  it('rejects pull-request detail whose echoed identity does not match the request', async () => {
    const acceptedPullRequest = makePullRequest({ pullRequestId: 'pr_184', actionItems: [] })
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () =>
          Promise.resolve({
            type: 'pullRequestAvailable',
            detail: {
              ...makePullRequestDetail({ pullRequestId: 'pr_response_leak' }),
              repositoryDisplayName: 'repository_response_leak',
              pullRequest: makePullRequest({
                pullRequestId: 'pr_response_leak',
                title: 'title_response_leak',
              }),
            },
          }),
      }),
    )

    await drawer.openPullRequest(makeRepository(), acceptedPullRequest, button())

    expect(drawer.state.value).toMatchObject({
      type: 'detailUnavailable',
      context: { pullRequest: acceptedPullRequest, detail: null },
      message: 'Pull request details are unavailable.',
    })
    expect(JSON.stringify(drawer.state.value)).not.toContain('response_leak')
  })

  it('ignores detail returned for an older selection', async () => {
    const first = deferred<PullRequestDetailSourceResult>()
    const second = deferred<PullRequestDetailSourceResult>()
    const source = createDashboardSourceStub({
      loadPullRequest: vi
        .fn()
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise),
    })
    const drawer = usePullRequestDrawer(source)

    void drawer.openPullRequest(
      makeRepository(),
      makePullRequest({ pullRequestId: 'pr_184' }),
      button(),
    )
    void drawer.openPullRequest(
      makeRepository(),
      makePullRequest({ pullRequestId: 'pr_179' }),
      button(),
    )
    first.resolve({
      type: 'pullRequestAvailable',
      detail: makePullRequestDetail({ pullRequestId: 'pr_184' }),
    })
    await flushPromises()

    if (drawer.state.value.type !== 'detailLoading') throw new Error('expected loading')
    expect(drawer.state.value.context.pullRequest.pullRequestId).toBe('pr_179')
  })

  it('selects the first actionable item in server-provided order without sorting versions', async () => {
    const firstActionable = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      pullRequestId: 'pr_184',
    })
    const laterActionable = makeActionItem({
      actionItemId: 'action_499',
      activityVersion: 'av_99',
      pullRequestId: 'pr_184',
    })
    const acknowledged = makeActionItem({
      actionItemId: 'action_500',
      activityVersion: 'av_1000',
      pullRequestId: 'pr_184',
      acknowledgmentState: 'acknowledged',
    })
    const pullRequest = makePullRequest({
      pullRequestId: 'pr_184',
      actionItems: [acknowledged, firstActionable, laterActionable],
    })
    const pending = deferred<PullRequestDetailSourceResult>()
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({ loadPullRequest: () => pending.promise }),
    )

    void drawer.openPullRequest(makeRepository(), pullRequest, button())

    if (drawer.state.value.type !== 'detailLoading') throw new Error('expected loading')
    expect(drawer.state.value.context.selectedActionItem).toMatchObject({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
    })
  })

  it('opens an inbox action with the exact supplied item as selection', async () => {
    const inboxItem = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      repositoryId: 'repo_payments',
      pullRequestId: 'pr_184',
    })
    const pullRequestItem = { ...inboxItem, actorDisplayName: 'Older actor metadata' }
    const pullRequest = makePullRequest({
      pullRequestId: 'pr_184',
      repositoryId: 'repo_payments',
      actionItems: [pullRequestItem],
    })
    const repository = makeRepository({
      repositoryId: 'repo_payments',
      pullRequests: [pullRequest],
    })
    const pending = deferred<PullRequestDetailSourceResult>()
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({ loadPullRequest: () => pending.promise }),
    )

    void drawer.openActionItem(
      makeDashboard({ repositoryGroups: [repository], inbox: [inboxItem] }),
      inboxItem,
      button(),
    )

    if (drawer.state.value.type !== 'detailLoading') throw new Error('expected loading')
    expect(drawer.state.value.context.selectedActionItem).toBe(inboxItem)
  })

  it('keeps immediate context when pull-request detail is not found', async () => {
    const pullRequest = makePullRequest({ pullRequestId: 'pr_missing' })
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () => Promise.resolve({ type: 'pullRequestNotFound' }),
      }),
    )

    await drawer.openPullRequest(makeRepository(), pullRequest, button())

    expect(drawer.state.value).toMatchObject({
      type: 'detailUnavailable',
      context: { pullRequest, detail: null },
    })
  })

  it('closes, ignores late detail, and restores the exact invoker focus', async () => {
    const pending = deferred<PullRequestDetailSourceResult>()
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({ loadPullRequest: () => pending.promise }),
    )
    const invoker = button()
    document.body.append(invoker)
    void drawer.openPullRequest(makeRepository(), makePullRequest(), invoker)

    drawer.close()
    await flushPromises()
    expect(drawer.state.value).toEqual({ type: 'closed' })
    expect(document.activeElement).toBe(invoker)

    pending.resolve({ type: 'pullRequestAvailable', detail: makePullRequestDetail() })
    await flushPromises()
    expect(drawer.state.value).toEqual({ type: 'closed' })
  })

  it('reconciles matching metadata while preserving enriched detail and selection', async () => {
    const selected = makeActionItem({ actionItemId: 'action_501', activityVersion: 'av_42' })
    const initial = makePullRequest({ actionItems: [selected], title: 'Initial title' })
    const detail = makePullRequestDetail()
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () => Promise.resolve({ type: 'pullRequestAvailable', detail }),
      }),
    )
    await drawer.openPullRequest(
      makeRepository({ displayName: 'Initial repository', pullRequests: [initial] }),
      initial,
      button(),
    )
    const updatedSelected = { ...selected, actorDisplayName: 'Updated actor' }
    const updated = makePullRequest({
      pullRequestId: initial.pullRequestId,
      title: 'Updated title',
      actionItems: [updatedSelected],
    })

    drawer.reconcileDashboard(
      makeDashboard({
        repositoryGroups: [
          makeRepository({ displayName: 'Updated repository', pullRequests: [updated] }),
        ],
      }),
    )

    expect(drawer.state.value).toMatchObject({
      type: 'metadata',
      context: {
        repositoryDisplayName: 'Updated repository',
        pullRequest: { title: 'Updated title' },
        selectedActionItem: { actorDisplayName: 'Updated actor' },
        detail: {
          repositoryDisplayName: 'Updated repository',
          pullRequest: updated,
          readinessChecks: detail.readinessChecks,
          actionItems: updated.actionItems,
        },
      },
    })
  })

  it('preserves loaded content when reconciliation keeps the exact action and version', async () => {
    const selected = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      repositoryId: 'repo_payments',
      pullRequestId: 'pr_184',
    })
    const pullRequest = makePullRequest({
      pullRequestId: 'pr_184',
      repositoryId: 'repo_payments',
      actionItems: [selected],
    })
    const repository = makeRepository({
      repositoryId: 'repo_payments',
      pullRequests: [pullRequest],
    })
    const detail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
      pullRequest,
      actionItems: [selected],
    }
    const loadActionContent = vi.fn(() =>
      Promise.resolve({
        type: 'contentAvailable' as const,
        actionItemId: 'action_501',
        activityVersion: 'av_42',
        markdownSource: 'Keep this exact loaded body',
      }),
    )
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () => Promise.resolve({ type: 'pullRequestAvailable', detail }),
        loadActionContent,
      }),
    )
    await drawer.openActionItem(
      makeDashboard({ repositoryGroups: [repository], inbox: [selected] }),
      selected,
      button(),
    )
    await flushPromises()
    const acceptedSelection = { ...selected, actorDisplayName: 'Accepted actor metadata' }
    const acceptedPullRequest = { ...pullRequest, actionItems: [acceptedSelection] }

    drawer.reconcileDashboard(
      makeDashboard({
        repositoryGroups: [{ ...repository, pullRequests: [acceptedPullRequest] }],
        inbox: [acceptedSelection],
      }),
    )

    expect(drawer.state.value).toMatchObject({
      type: 'metadata',
      context: {
        selectedActionItem: {
          actionItemId: 'action_501',
          activityVersion: 'av_42',
          actorDisplayName: 'Accepted actor metadata',
        },
        activityContent: {
          type: 'contentAvailable',
          actionItemId: 'action_501',
          activityVersion: 'av_42',
          markdownSource: 'Keep this exact loaded body',
        },
      },
    })
    expect(loadActionContent).toHaveBeenCalledTimes(1)
  })

  it('reloads an advanced accepted action from a contentAvailable state', async () => {
    const selected = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      repositoryId: 'repo_payments',
      pullRequestId: 'pr_184',
    })
    const pullRequest = makePullRequest({
      pullRequestId: 'pr_184',
      repositoryId: 'repo_payments',
      actionItems: [selected],
    })
    const repository = makeRepository({
      repositoryId: 'repo_payments',
      pullRequests: [pullRequest],
    })
    const detail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
      pullRequest,
      actionItems: [selected],
    }
    const loadActionContent = vi
      .fn()
      .mockResolvedValueOnce({
        type: 'contentAvailable',
        actionItemId: 'action_501',
        activityVersion: 'av_42',
        markdownSource: 'Do not relabel this older body',
      })
      .mockResolvedValueOnce({
        type: 'contentAvailable',
        actionItemId: 'action_501',
        activityVersion: 'av_43',
        markdownSource: 'Accepted replacement body',
      })
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () => Promise.resolve({ type: 'pullRequestAvailable', detail }),
        loadActionContent,
      }),
    )
    await drawer.openActionItem(
      makeDashboard({ repositoryGroups: [repository], inbox: [selected] }),
      selected,
      button(),
    )
    await flushPromises()
    const changedSelection = { ...selected, activityVersion: 'av_43' }
    const changedPullRequest = { ...pullRequest, actionItems: [changedSelection] }

    drawer.reconcileDashboard(
      makeDashboard({
        repositoryGroups: [{ ...repository, pullRequests: [changedPullRequest] }],
        inbox: [changedSelection],
      }),
    )
    await flushPromises()

    expect(loadActionContent).toHaveBeenNthCalledWith(2, 'action_501', 'av_43')
    expect(drawer.state.value).toMatchObject({
      context: {
        selectedActionItem: { actionItemId: 'action_501', activityVersion: 'av_43' },
        activityContent: {
          type: 'contentAvailable',
          actionItemId: 'action_501',
          activityVersion: 'av_43',
          markdownSource: 'Accepted replacement body',
        },
      },
    })
    expect(JSON.stringify(drawer.state.value)).not.toContain('Do not relabel this older body')
  })

  it('reloads an advanced accepted action from a contentUnavailable state', async () => {
    const fixture = selectedActionFixture()
    const advancedAction = { ...fixture.actionItem, activityVersion: 'av_43' }
    const advancedPullRequest = { ...fixture.pullRequest, actionItems: [advancedAction] }
    const loadActionContent = vi
      .fn()
      .mockResolvedValueOnce({
        type: 'contentUnavailable',
        reason: 'Older version unavailable',
        retryable: true,
      })
      .mockResolvedValueOnce({
        type: 'contentAvailable',
        actionItemId: 'action_501',
        activityVersion: 'av_43',
        markdownSource: 'Accepted replacement body',
      })
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent,
    })
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment: vi.fn(),
      pollDashboard: vi.fn(() => Promise.resolve()),
    })

    drawer.reconcileDashboard(
      makeDashboard({
        repositoryGroups: [{ ...fixture.repository, pullRequests: [advancedPullRequest] }],
        inbox: [advancedAction],
      }),
    )
    await flushPromises()

    expect(loadActionContent).toHaveBeenNthCalledWith(2, 'action_501', 'av_43')
    expect(drawer.state.value).toMatchObject({
      context: {
        selectedActionItem: { activityVersion: 'av_43' },
        activityContent: {
          type: 'contentAvailable',
          activityVersion: 'av_43',
          markdownSource: 'Accepted replacement body',
        },
      },
    })
    expect(JSON.stringify(drawer.state.value)).not.toContain('Older version unavailable')
  })

  it('reloads an advanced accepted action from an acknowledged state', async () => {
    const fixture = selectedActionFixture()
    const advancedAction = { ...fixture.actionItem, activityVersion: 'av_43' }
    const advancedPullRequest = { ...fixture.pullRequest, actionItems: [advancedAction] }
    const loadActionContent = vi
      .fn()
      .mockResolvedValueOnce(availableContent())
      .mockResolvedValueOnce({
        type: 'contentAvailable',
        actionItemId: 'action_501',
        activityVersion: 'av_43',
        markdownSource: 'Accepted replacement body',
      })
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent,
      acknowledgeActionItem: () =>
        Promise.resolve({
          type: 'acknowledged',
          actionItemId: 'action_501',
          activityVersion: 'av_42',
        }),
    })
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment: vi.fn(),
      pollDashboard: vi.fn(() => Promise.resolve()),
    })
    await drawer.acknowledgeSelected()
    expect(drawer.state.value).toMatchObject({
      context: { activityContent: { type: 'acknowledged' } },
    })

    drawer.reconcileDashboard(
      makeDashboard({
        repositoryGroups: [{ ...fixture.repository, pullRequests: [advancedPullRequest] }],
        inbox: [advancedAction],
      }),
    )
    await flushPromises()

    expect(loadActionContent).toHaveBeenNthCalledWith(2, 'action_501', 'av_43')
    expect(drawer.state.value).toMatchObject({
      context: {
        selectedActionItem: { activityVersion: 'av_43' },
        activityContent: {
          type: 'contentAvailable',
          activityVersion: 'av_43',
          markdownSource: 'Accepted replacement body',
        },
      },
    })
  })

  it('reloads an advanced accepted action from a null no-content state', async () => {
    const fixture = selectedActionFixture()
    const advancedAction = { ...fixture.actionItem, activityVersion: 'av_43' }
    const advancedPullRequest = { ...fixture.pullRequest, actionItems: [advancedAction] }
    const loadActionContent = vi.fn(() =>
      Promise.resolve({
        type: 'contentAvailable' as const,
        actionItemId: 'action_501',
        activityVersion: 'av_43',
        markdownSource: 'Accepted replacement body',
      }),
    )
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () => Promise.resolve({ type: 'pullRequestNotFound' }),
        loadActionContent,
      }),
    )
    await drawer.openPullRequest(fixture.repository, fixture.pullRequest, button())
    expect(drawer.state.value).toMatchObject({
      type: 'detailUnavailable',
      context: { activityContent: null },
    })

    drawer.reconcileDashboard(
      makeDashboard({
        repositoryGroups: [{ ...fixture.repository, pullRequests: [advancedPullRequest] }],
        inbox: [advancedAction],
      }),
    )
    await flushPromises()

    expect(loadActionContent).toHaveBeenCalledTimes(1)
    expect(loadActionContent).toHaveBeenCalledWith('action_501', 'av_43')
    expect(drawer.state.value).toMatchObject({
      context: {
        selectedActionItem: { activityVersion: 'av_43' },
        activityContent: {
          type: 'contentAvailable',
          activityVersion: 'av_43',
          markdownSource: 'Accepted replacement body',
        },
      },
    })
  })

  it('invalidates a late old-version load and does not duplicate the advanced exact request', async () => {
    const selected = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      repositoryId: 'repo_payments',
      pullRequestId: 'pr_184',
    })
    const pullRequest = makePullRequest({
      pullRequestId: 'pr_184',
      repositoryId: 'repo_payments',
      actionItems: [selected],
    })
    const repository = makeRepository({
      repositoryId: 'repo_payments',
      pullRequests: [pullRequest],
    })
    const detail = {
      ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
      pullRequest,
      actionItems: [selected],
    }
    const supersededContent = deferred<ActionContentSourceResult>()
    const replacementContent = deferred<ActionContentSourceResult>()
    const loadActionContent = vi
      .fn()
      .mockReturnValueOnce(supersededContent.promise)
      .mockReturnValueOnce(replacementContent.promise)
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () => Promise.resolve({ type: 'pullRequestAvailable', detail }),
        loadActionContent,
      }),
    )
    await drawer.openActionItem(
      makeDashboard({ repositoryGroups: [repository], inbox: [selected] }),
      selected,
      button(),
    )
    const acceptedSelection = {
      ...selected,
      activityVersion: 'av_43',
      actorDisplayName: 'Accepted actor metadata',
    }
    const acceptedPullRequest = { ...pullRequest, actionItems: [acceptedSelection] }

    const acceptedDashboard = makeDashboard({
      repositoryGroups: [{ ...repository, pullRequests: [acceptedPullRequest] }],
      inbox: [acceptedSelection],
    })
    drawer.reconcileDashboard(acceptedDashboard)
    drawer.reconcileDashboard(acceptedDashboard)

    expect(loadActionContent).toHaveBeenNthCalledWith(1, 'action_501', 'av_42')
    expect(loadActionContent).toHaveBeenNthCalledWith(2, 'action_501', 'av_43')
    expect(loadActionContent).toHaveBeenCalledTimes(2)
    supersededContent.resolve({
      type: 'contentAvailable',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      markdownSource: 'Superseded body',
    })
    replacementContent.resolve({
      type: 'contentAvailable',
      actionItemId: 'action_501',
      activityVersion: 'av_43',
      markdownSource: 'Replacement body',
    })
    await flushPromises()

    expect(drawer.state.value).toMatchObject({
      type: 'metadata',
      context: {
        selectedActionItem: { actorDisplayName: 'Accepted actor metadata' },
        activityContent: {
          type: 'contentAvailable',
          actionItemId: 'action_501',
          activityVersion: 'av_43',
          markdownSource: 'Replacement body',
        },
      },
    })
    expect(JSON.stringify(drawer.state.value)).not.toContain('Superseded body')
  })

  it('keeps a newer accepted snapshot when older same-PR detail resolves later', async () => {
    const initialAction = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      repositoryId: 'repo_payments',
      pullRequestId: 'pr_184',
      actorDisplayName: 'Initial actor',
    })
    const initialPullRequest = makePullRequest({
      pullRequestId: 'pr_184',
      repositoryId: 'repo_payments',
      title: 'Initial title',
      actionItems: [initialAction],
    })
    const initialRepository = makeRepository({
      repositoryId: 'repo_payments',
      displayName: 'Initial repository',
      pullRequests: [initialPullRequest],
    })
    const pending = deferred<PullRequestDetailSourceResult>()
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({ loadPullRequest: () => pending.promise }),
    )
    void drawer.openActionItem(
      makeDashboard({
        repositoryGroups: [initialRepository],
        inbox: [initialAction],
      }),
      initialAction,
      button(),
    )
    const acceptedAction = makeActionItem({
      ...initialAction,
      activityVersion: 'av_43',
      actorDisplayName: 'Accepted actor',
    })
    const acceptedPullRequest = makePullRequest({
      ...initialPullRequest,
      title: 'Accepted title',
      actionItems: [acceptedAction],
    })

    drawer.reconcileDashboard(
      makeDashboard({
        repositoryGroups: [
          makeRepository({
            ...initialRepository,
            displayName: 'Accepted repository',
            pullRequests: [acceptedPullRequest],
          }),
        ],
        inbox: [acceptedAction],
      }),
    )
    pending.resolve({
      type: 'pullRequestAvailable',
      detail: {
        ...makePullRequestDetail({ pullRequestId: 'pr_184' }),
        pullRequest: { ...initialPullRequest, title: 'Stale detail title' },
        actionItems: [initialAction],
      },
    })
    await flushPromises()

    if (drawer.state.value.type === 'closed') throw new Error('expected open drawer')
    expect(drawer.state.value.context.repositoryDisplayName).toBe('Accepted repository')
    expect(drawer.state.value.context.pullRequest.title).toBe('Accepted title')
    expect(drawer.state.value.context.selectedActionItem).toBe(acceptedAction)
  })

  it('re-requests detail for reconciled same-PR context and reaches enriched metadata', async () => {
    const initialDetail = deferred<PullRequestDetailSourceResult>()
    const reconciledDetail = deferred<PullRequestDetailSourceResult>()
    const source = createDashboardSourceStub({
      loadPullRequest: vi
        .fn()
        .mockReturnValueOnce(initialDetail.promise)
        .mockReturnValueOnce(reconciledDetail.promise),
    })
    const initialAction = makeActionItem({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
    })
    const initialPullRequest = makePullRequest({ actionItems: [initialAction] })
    const drawer = usePullRequestDrawer(source)
    void drawer.openActionItem(
      makeDashboard({
        repositoryGroups: [makeRepository({ pullRequests: [initialPullRequest] })],
        inbox: [initialAction],
      }),
      initialAction,
      button(),
    )
    const acceptedAction = makeActionItem({
      ...initialAction,
      activityVersion: 'av_43',
      actorDisplayName: 'Accepted actor',
    })
    const acceptedPullRequest = makePullRequest({
      ...initialPullRequest,
      title: 'Accepted title',
      actionItems: [acceptedAction],
    })

    drawer.reconcileDashboard(
      makeDashboard({
        repositoryGroups: [makeRepository({ pullRequests: [acceptedPullRequest] })],
        inbox: [acceptedAction],
      }),
    )
    initialDetail.resolve({
      type: 'pullRequestAvailable',
      detail: {
        ...makePullRequestDetail(),
        pullRequest: initialPullRequest,
        actionItems: [initialAction],
      },
    })
    await flushPromises()
    if (drawer.state.value.type !== 'detailLoading') throw new Error('expected loading')
    expect(drawer.state.value.context.pullRequest.title).toBe('Accepted title')
    expect(drawer.state.value.context.selectedActionItem).toBe(acceptedAction)

    const detail = {
      ...makePullRequestDetail(),
      pullRequest: acceptedPullRequest,
      readinessChecks: [{ checkId: 'contract', label: 'Contract', state: 'passed' as const }],
      actionItems: [acceptedAction],
    }
    reconciledDetail.resolve({ type: 'pullRequestAvailable', detail })
    await flushPromises()

    expect(drawer.state.value).toMatchObject({
      type: 'metadata',
      context: {
        pullRequest: { title: 'Accepted title' },
        selectedActionItem: { activityVersion: 'av_43' },
        detail,
      },
    })
  })

  it('reloads exact content only after an accepted dashboard snapshot advances a refreshing version', async () => {
    const fixture = selectedActionFixture()
    const updatedAction = { ...fixture.actionItem, activityVersion: 'av_43' }
    const updatedPullRequest = { ...fixture.pullRequest, actionItems: [updatedAction] }
    const loadActionContent = vi
      .fn()
      .mockResolvedValueOnce({
        type: 'newerActivityObserved',
        repositoryId: 'repo_payments',
        requestedActivityVersion: 'av_42',
        currentActivityVersion: 'av_43',
      })
      .mockResolvedValueOnce({
        type: 'contentAvailable',
        actionItemId: 'action_501',
        activityVersion: 'av_43',
        markdownSource: 'Accepted newer body',
      })
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent,
      startRepositoryRefresh: () =>
        Promise.resolve({ type: 'refreshRunRegistered', refreshRunId: 'refresh_repo_1' }),
    })
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment: vi.fn(),
      pollDashboard: vi.fn(() => Promise.resolve()),
    })
    await drawer.refreshSelectedRepository()

    drawer.reconcileDashboard(
      makeDashboard({
        dashboardRevision: 'dash_18',
        repositoryGroups: [{ ...fixture.repository, pullRequests: [updatedPullRequest] }],
        inbox: [updatedAction],
      }),
    )
    await flushPromises()

    expect(loadActionContent).toHaveBeenNthCalledWith(1, 'action_501', 'av_42')
    expect(loadActionContent).toHaveBeenNthCalledWith(2, 'action_501', 'av_43')
    expect(drawer.state.value).toMatchObject({
      context: {
        selectedActionItem: { activityVersion: 'av_43' },
        activityContent: {
          type: 'contentAvailable',
          activityVersion: 'av_43',
          markdownSource: 'Accepted newer body',
        },
      },
    })
  })

  it('reloads an advanced exact version when a snapshot wins the pending-acknowledgment race', async () => {
    const fixture = selectedActionFixture()
    const acknowledgment = deferred<AcknowledgmentSourceResult>()
    const updatedAction = { ...fixture.actionItem, activityVersion: 'av_43' }
    const updatedPullRequest = { ...fixture.pullRequest, actionItems: [updatedAction] }
    const loadActionContent = vi
      .fn()
      .mockResolvedValueOnce(availableContent())
      .mockResolvedValueOnce({
        type: 'contentAvailable',
        actionItemId: 'action_501',
        activityVersion: 'av_43',
        markdownSource: 'Accepted newer body',
      })
    const applyAcknowledgment = vi.fn()
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent,
      acknowledgeActionItem: () => acknowledgment.promise,
    })
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment,
      pollDashboard: vi.fn(() => Promise.resolve()),
    })
    const pendingAcknowledgment = drawer.acknowledgeSelected()

    drawer.reconcileDashboard(
      makeDashboard({
        dashboardRevision: 'dash_18',
        repositoryGroups: [{ ...fixture.repository, pullRequests: [updatedPullRequest] }],
        inbox: [updatedAction],
      }),
    )
    await flushPromises()

    expect(loadActionContent).toHaveBeenNthCalledWith(2, 'action_501', 'av_43')
    expect(drawer.state.value).toMatchObject({
      context: {
        selectedActionItem: { activityVersion: 'av_43' },
        activityContent: {
          type: 'contentAvailable',
          activityVersion: 'av_43',
          markdownSource: 'Accepted newer body',
        },
      },
    })

    acknowledgment.resolve({
      type: 'acknowledged',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
    })
    await pendingAcknowledgment

    expect(applyAcknowledgment).toHaveBeenCalledWith({
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      repositoryId: 'repo_payments',
      pullRequestId: 'pr_184',
    })
    expect(drawer.state.value).toMatchObject({
      context: {
        selectedActionItem: { activityVersion: 'av_43' },
        activityContent: { type: 'contentAvailable', activityVersion: 'av_43' },
      },
    })
  })

  it('finishes polling a registered repository refresh after the drawer closes', async () => {
    const fixture = selectedActionFixture()
    const registration = deferred<RefreshSourceResult>()
    const pollDashboard = vi.fn(() => Promise.resolve())
    const source = createDashboardSourceStub({
      loadPullRequest: () =>
        Promise.resolve({ type: 'pullRequestAvailable', detail: fixture.detail }),
      loadActionContent: () =>
        Promise.resolve({
          type: 'newerActivityObserved',
          repositoryId: 'repo_payments',
          requestedActivityVersion: 'av_42',
          currentActivityVersion: 'av_43',
        }),
      startRepositoryRefresh: () => registration.promise,
    })
    const { drawer } = await openSelectedAction(source, {
      applyAcknowledgment: vi.fn(),
      pollDashboard,
    })
    const refresh = drawer.refreshSelectedRepository()

    drawer.close()
    registration.resolve({ type: 'refreshRunRegistered', refreshRunId: 'refresh_repo_1' })
    await refresh

    expect(drawer.state.value).toEqual({ type: 'closed' })
    expect(pollDashboard).toHaveBeenCalledTimes(1)
  })

  it('closes with a polite status when reconciliation removes the pull request', async () => {
    const pending = deferred<PullRequestDetailSourceResult>()
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({ loadPullRequest: () => pending.promise }),
    )
    void drawer.openPullRequest(makeRepository(), makePullRequest(), button())

    drawer.reconcileDashboard(makeDashboard())

    expect(drawer.state.value).toEqual({ type: 'closed' })
    expect(drawer.statusMessage.value).toBe('That pull request is no longer in this dashboard.')
    pending.resolve({ type: 'pullRequestAvailable', detail: makePullRequestDetail() })
    await flushPromises()
    expect(drawer.state.value).toEqual({ type: 'closed' })
  })
})
