import {
  nextTick,
  readonly,
  shallowReadonly,
  shallowRef,
  ref,
  type Ref,
  type ShallowRef,
} from 'vue'

import type {
  ActionItemSummary,
  DashboardViewModel,
  PullRequestDetailModel,
  PullRequestSummary,
  RepositoryGroupModel,
} from './dashboard.models'
import type { DashboardSource, PullRequestDetailSourceResult } from './dashboardSource'

export interface DrawerContext {
  repositoryDisplayName: string
  pullRequest: PullRequestSummary
  selectedActionItem: ActionItemSummary | null
  detail: PullRequestDetailModel | null
}

export type DrawerUiState =
  | { type: 'closed' }
  | { type: 'detailLoading'; context: DrawerContext }
  | { type: 'metadata'; context: DrawerContext }
  | { type: 'detailUnavailable'; context: DrawerContext; message: string }

export interface PullRequestDrawerController {
  state: Readonly<ShallowRef<DrawerUiState>>
  statusMessage: Readonly<Ref<string | null>>
  openPullRequest(
    repository: RepositoryGroupModel,
    pullRequest: PullRequestSummary,
    invoker: HTMLButtonElement,
  ): Promise<void>
  openActionItem(
    dashboard: DashboardViewModel,
    actionItem: ActionItemSummary,
    invoker: HTMLButtonElement,
  ): Promise<void>
  close(): void
  reconcileDashboard(dashboard: DashboardViewModel): void
}

function firstActionable(actionItems: readonly ActionItemSummary[]) {
  return actionItems.find((item) => item.acknowledgmentState === 'actionable') ?? null
}

export function usePullRequestDrawer(source: DashboardSource): PullRequestDrawerController {
  const state = shallowRef<DrawerUiState>({ type: 'closed' })
  const statusMessage = ref<string | null>(null)
  let generation = 0
  let focusReturnElement: HTMLButtonElement | null = null

  const isCurrent = (requestGeneration: number, pullRequestId: string) =>
    requestGeneration === generation &&
    state.value.type !== 'closed' &&
    state.value.context.pullRequest.pullRequestId === pullRequestId

  const applyDetail = (
    result: PullRequestDetailSourceResult,
    requestGeneration: number,
    pullRequestId: string,
    selectionOverride: ActionItemSummary | null | undefined,
    contextOverride?: DrawerContext,
  ) => {
    if (!isCurrent(requestGeneration, pullRequestId) || state.value.type === 'closed') return
    const immediate = state.value.context
    if (result.type === 'pullRequestNotFound') {
      state.value = {
        type: 'detailUnavailable',
        context: immediate,
        message: 'Pull request details are unavailable.',
      }
      return
    }

    const detail = result.detail
    state.value = {
      type: 'metadata',
      context: {
        repositoryDisplayName:
          contextOverride?.repositoryDisplayName ?? detail.repositoryDisplayName,
        pullRequest: contextOverride?.pullRequest ?? detail.pullRequest,
        selectedActionItem:
          selectionOverride === undefined ? firstActionable(detail.actionItems) : selectionOverride,
        detail,
      },
    }
  }

  const loadDetail = async (
    pullRequestId: string,
    selectionOverride: ActionItemSummary | null | undefined,
    requestGeneration: number,
    contextOverride?: DrawerContext,
  ) => {
    try {
      const result = await source.loadPullRequest(pullRequestId)
      applyDetail(result, requestGeneration, pullRequestId, selectionOverride, contextOverride)
    } catch {
      if (state.value.type === 'closed' || !isCurrent(requestGeneration, pullRequestId)) return
      state.value = {
        type: 'detailUnavailable',
        context: state.value.context,
        message: 'Pull request details are unavailable.',
      }
    }
  }

  const open = (
    repository: RepositoryGroupModel,
    pullRequest: PullRequestSummary,
    invoker: HTMLButtonElement,
    selectionOverride?: ActionItemSummary | null,
  ) => {
    const requestGeneration = ++generation
    focusReturnElement = invoker
    statusMessage.value = null
    state.value = {
      type: 'detailLoading',
      context: {
        repositoryDisplayName: repository.displayName,
        pullRequest,
        selectedActionItem:
          selectionOverride === undefined
            ? firstActionable(pullRequest.actionItems)
            : selectionOverride,
        detail: null,
      },
    }

    return loadDetail(pullRequest.pullRequestId, selectionOverride, requestGeneration)
  }

  const close = () => {
    generation += 1
    state.value = { type: 'closed' }
    statusMessage.value = null
    const invoker = focusReturnElement
    focusReturnElement = null
    if (invoker) void nextTick(() => invoker.focus())
  }

  const openPullRequest = (
    repository: RepositoryGroupModel,
    pullRequest: PullRequestSummary,
    invoker: HTMLButtonElement,
  ) => open(repository, pullRequest, invoker)

  const openActionItem = (
    dashboard: DashboardViewModel,
    actionItem: ActionItemSummary,
    invoker: HTMLButtonElement,
  ) => {
    const repository = dashboard.repositoryGroups.find(
      (candidate) => candidate.repositoryId === actionItem.repositoryId,
    )
    const pullRequest = repository?.pullRequests.find(
      (candidate) => candidate.pullRequestId === actionItem.pullRequestId,
    )
    if (!repository || !pullRequest) {
      generation += 1
      state.value = { type: 'closed' }
      statusMessage.value = 'That pull request is no longer in this dashboard.'
      return Promise.resolve()
    }
    return open(repository, pullRequest, invoker, actionItem)
  }

  const reconcileDashboard = (dashboard: DashboardViewModel) => {
    if (state.value.type === 'closed') return
    const current = state.value
    const pullRequestId = current.context.pullRequest.pullRequestId
    const repository = dashboard.repositoryGroups.find((candidate) =>
      candidate.pullRequests.some((pullRequest) => pullRequest.pullRequestId === pullRequestId),
    )
    const pullRequest = repository?.pullRequests.find(
      (candidate) => candidate.pullRequestId === pullRequestId,
    )
    if (!repository || !pullRequest) {
      generation += 1
      state.value = { type: 'closed' }
      statusMessage.value = 'That pull request is no longer in this dashboard.'
      focusReturnElement = null
      return
    }

    const currentSelection = current.context.selectedActionItem
    const selectedActionItem = currentSelection
      ? (dashboard.inbox.find((item) => item.actionItemId === currentSelection.actionItemId) ??
        pullRequest.actionItems.find(
          (item) => item.actionItemId === currentSelection.actionItemId,
        ) ??
        null)
      : firstActionable(pullRequest.actionItems)
    const requestGeneration = ++generation
    state.value = {
      ...current,
      context: {
        ...current.context,
        repositoryDisplayName: repository.displayName,
        pullRequest,
        selectedActionItem,
      },
    }
    if (current.type === 'detailLoading') {
      void loadDetail(pullRequestId, selectedActionItem, requestGeneration, state.value.context)
    }
  }

  return {
    state: shallowReadonly(state),
    statusMessage: readonly(statusMessage),
    openPullRequest,
    openActionItem,
    close,
    reconcileDashboard,
  }
}
