import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { ActionContentSourceResult, PullRequestDetailSourceResult } from './dashboardSource'
import {
  makeActionItem,
  makeDashboard,
  makePullRequest,
  makePullRequestDetail,
  makeRepository,
} from './testing/dashboardTestData'
import { button, createDashboardSourceStub, deferred } from './testing/dashboardTestSource'
import { usePullRequestDrawer } from './usePullRequestDrawer'

afterEach(() => {
  document.body.replaceChildren()
})

describe('usePullRequestDrawer', () => {
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
      context: { detail },
    })
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
        detail,
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

  it('discards loaded content when reconciliation changes the opaque activity version', async () => {
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
    const drawer = usePullRequestDrawer(
      createDashboardSourceStub({
        loadPullRequest: () => Promise.resolve({ type: 'pullRequestAvailable', detail }),
        loadActionContent: () =>
          Promise.resolve({
            type: 'contentAvailable',
            actionItemId: 'action_501',
            activityVersion: 'av_42',
            markdownSource: 'Do not relabel this older body',
          }),
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

    if (drawer.state.value.type === 'closed') throw new Error('expected open drawer')
    expect(drawer.state.value.context.selectedActionItem).toMatchObject({
      actionItemId: 'action_501',
      activityVersion: 'av_43',
    })
    expect(drawer.state.value.context.activityContent).toBeNull()
    expect(JSON.stringify(drawer.state.value)).not.toContain('Do not relabel this older body')
  })

  it('re-requests exact content when reconciliation invalidates a pending content load', async () => {
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
    const acceptedSelection = { ...selected, actorDisplayName: 'Accepted actor metadata' }
    const acceptedPullRequest = { ...pullRequest, actionItems: [acceptedSelection] }

    drawer.reconcileDashboard(
      makeDashboard({
        repositoryGroups: [{ ...repository, pullRequests: [acceptedPullRequest] }],
        inbox: [acceptedSelection],
      }),
    )

    expect(loadActionContent).toHaveBeenNthCalledWith(1, 'action_501', 'av_42')
    expect(loadActionContent).toHaveBeenNthCalledWith(2, 'action_501', 'av_42')
    supersededContent.resolve({
      type: 'contentAvailable',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
      markdownSource: 'Superseded body',
    })
    replacementContent.resolve({
      type: 'contentAvailable',
      actionItemId: 'action_501',
      activityVersion: 'av_42',
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
          activityVersion: 'av_42',
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
        repositoryDisplayName: 'Stale detail repository',
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
