import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { PullRequestDetailSourceResult } from './dashboardSource'
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
