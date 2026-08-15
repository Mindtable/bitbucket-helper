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
import type {
  AcknowledgmentSourceResult,
  ActionContentSourceResult,
  DashboardSource,
  PullRequestDetailSourceResult,
  RefreshSourceResult,
} from './dashboardSource'
import type { AcknowledgedActionRef } from './dashboardReconciliation'

export type ActivityContentState =
  | { type: 'contentLoading'; actionItemId: string; activityVersion: string }
  | {
      type: 'contentAvailable'
      actionItemId: string
      activityVersion: string
      markdownSource: string
    }
  | { type: 'contentUnavailable'; message: string; retryable: boolean }
  | {
      type: 'newerActivity'
      actionItemId: string
      requestedActivityVersion: string
      currentActivityVersion: string
    }
  | {
      type: 'ackPending'
      actionItemId: string
      activityVersion: string
      markdownSource: string
    }
  | { type: 'acknowledged'; message: string }
  | {
      type: 'acknowledgmentRejected'
      message: string
      retryable: boolean
      actionItemId: string
      activityVersion: string
    }
  | { type: 'refreshing'; currentActivityVersion: string }

export interface DrawerContext {
  repositoryDisplayName: string
  pullRequest: PullRequestSummary
  selectedActionItem: ActionItemSummary | null
  detail: PullRequestDetailModel | null
  activityContent: ActivityContentState | null
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
  retrySelectedContent(): void
  acknowledgeSelected(): Promise<void>
  refreshSelectedRepository(): Promise<void>
}

export interface PullRequestDrawerDependencies {
  applyAcknowledgment(acknowledged: AcknowledgedActionRef): void
  pollDashboard(): Promise<void>
}

function firstActionable(actionItems: readonly ActionItemSummary[]) {
  return actionItems.find((item) => item.acknowledgmentState === 'actionable') ?? null
}

function toActivityContentState(
  result: ActionContentSourceResult,
  actionItemId: string,
): ActivityContentState {
  switch (result.type) {
    case 'contentAvailable':
      return {
        type: 'contentAvailable',
        actionItemId: result.actionItemId,
        activityVersion: result.activityVersion,
        markdownSource: result.markdownSource,
      }
    case 'contentUnavailable':
      return {
        type: 'contentUnavailable',
        message: result.reason,
        retryable: result.retryable,
      }
    case 'newerActivityObserved':
    case 'staleActivityVersion':
      return {
        type: 'newerActivity',
        actionItemId,
        requestedActivityVersion: result.requestedActivityVersion,
        currentActivityVersion: result.currentActivityVersion,
      }
    case 'actionItemNotFound':
      return {
        type: 'contentUnavailable',
        message: 'This activity is no longer available.',
        retryable: false,
      }
  }
}

export function usePullRequestDrawer(
  source: DashboardSource,
  dependencies: PullRequestDrawerDependencies,
): PullRequestDrawerController {
  const state = shallowRef<DrawerUiState>({ type: 'closed' })
  const statusMessage = ref<string | null>(null)
  let generation = 0
  let focusReturnElement: HTMLButtonElement | null = null

  const isCurrent = (requestGeneration: number, pullRequestId: string) =>
    requestGeneration === generation &&
    state.value.type !== 'closed' &&
    state.value.context.pullRequest.pullRequestId === pullRequestId

  const isContentCurrent = (
    requestGeneration: number,
    pullRequestId: string,
    actionItemId: string,
    activityVersion: string,
  ) => {
    if (!isCurrent(requestGeneration, pullRequestId) || state.value.type === 'closed') return false
    const selected = state.value.context.selectedActionItem
    return selected?.actionItemId === actionItemId && selected.activityVersion === activityVersion
  }

  const loadContent = async (
    actionItem: ActionItemSummary,
    requestGeneration: number,
    pullRequestId: string,
  ) => {
    const { actionItemId, activityVersion } = actionItem
    if (!isContentCurrent(requestGeneration, pullRequestId, actionItemId, activityVersion)) return
    const loadingState = state.value
    if (loadingState.type === 'closed') return
    state.value = {
      ...loadingState,
      context: {
        ...loadingState.context,
        activityContent: { type: 'contentLoading', actionItemId, activityVersion },
      },
    }
    try {
      const result = await source.loadActionContent(actionItemId, activityVersion)
      if (!isContentCurrent(requestGeneration, pullRequestId, actionItemId, activityVersion)) return
      const loadedState = state.value
      state.value = {
        ...loadedState,
        context: {
          ...loadedState.context,
          activityContent: toActivityContentState(result, actionItemId),
        },
      }
    } catch {
      if (!isContentCurrent(requestGeneration, pullRequestId, actionItemId, activityVersion)) return
      const unavailableState = state.value
      state.value = {
        ...unavailableState,
        context: {
          ...unavailableState.context,
          activityContent: {
            type: 'contentUnavailable',
            message: 'Activity content is unavailable.',
            retryable: true,
          },
        },
      }
    }
  }

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
    const selectedActionItem =
      selectionOverride === undefined ? firstActionable(detail.actionItems) : selectionOverride
    state.value = {
      type: 'metadata',
      context: {
        repositoryDisplayName:
          contextOverride?.repositoryDisplayName ?? detail.repositoryDisplayName,
        pullRequest: contextOverride?.pullRequest ?? detail.pullRequest,
        selectedActionItem,
        detail,
        activityContent: selectionOverride === undefined ? null : immediate.activityContent,
      },
    }
    if (selectedActionItem && state.value.context.activityContent === null) {
      void loadContent(selectedActionItem, requestGeneration, pullRequestId)
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
        activityContent: null,
      },
    }

    if (selectionOverride) {
      void loadContent(selectionOverride, requestGeneration, pullRequest.pullRequestId)
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
    const selectionWasAcknowledged = current.context.activityContent?.type === 'acknowledged'
    const selectedActionItem = currentSelection
      ? (dashboard.inbox.find((item) => item.actionItemId === currentSelection.actionItemId) ??
        pullRequest.actionItems.find(
          (item) => item.actionItemId === currentSelection.actionItemId,
        ) ??
        (selectionWasAcknowledged ? currentSelection : null))
      : firstActionable(pullRequest.actionItems)
    const exactSelectionUnchanged =
      currentSelection !== null &&
      selectedActionItem !== null &&
      currentSelection.actionItemId === selectedActionItem.actionItemId &&
      currentSelection.activityVersion === selectedActionItem.activityVersion
    const exactSelectionAdvanced =
      currentSelection !== null &&
      selectedActionItem !== null &&
      currentSelection.actionItemId === selectedActionItem.actionItemId &&
      currentSelection.activityVersion !== selectedActionItem.activityVersion
    const contentNeedsReload =
      current.context.activityContent?.type === 'contentLoading' ||
      current.context.activityContent?.type === 'refreshing' ||
      (current.context.activityContent?.type === 'ackPending' && exactSelectionAdvanced)
    const requestGeneration = ++generation
    state.value = {
      ...current,
      context: {
        ...current.context,
        repositoryDisplayName: repository.displayName,
        pullRequest,
        selectedActionItem,
        activityContent:
          exactSelectionUnchanged && !contentNeedsReload ? current.context.activityContent : null,
      },
    }
    if (current.type === 'detailLoading') {
      void loadDetail(pullRequestId, selectedActionItem, requestGeneration, state.value.context)
    }
    if (contentNeedsReload && selectedActionItem) {
      void loadContent(selectedActionItem, requestGeneration, pullRequestId)
    }
  }

  const retrySelectedContent = () => {
    if (state.value.type === 'closed') return
    const current = state.value
    if (
      current.context.activityContent?.type !== 'contentUnavailable' ||
      !current.context.activityContent.retryable ||
      !current.context.selectedActionItem
    ) {
      return
    }
    void loadContent(
      current.context.selectedActionItem,
      generation,
      current.context.pullRequest.pullRequestId,
    )
  }

  const setActivityContent = (activityContent: ActivityContentState) => {
    if (state.value.type === 'closed') return
    state.value = {
      ...state.value,
      context: { ...state.value.context, activityContent },
    }
  }

  const refreshRepository = async (
    repositoryId: string,
    observedActivityVersion: string,
    requestGeneration: number,
    pullRequestId: string,
    actionItemId: string,
    requestedActivityVersion: string,
  ) => {
    if (
      !isContentCurrent(requestGeneration, pullRequestId, actionItemId, requestedActivityVersion)
    ) {
      return
    }
    setActivityContent({ type: 'refreshing', currentActivityVersion: observedActivityVersion })

    let result: RefreshSourceResult
    try {
      result = await source.startRepositoryRefresh(repositoryId, observedActivityVersion)
    } catch {
      if (
        isContentCurrent(requestGeneration, pullRequestId, actionItemId, requestedActivityVersion)
      ) {
        setActivityContent({
          type: 'contentUnavailable',
          message: 'Refresh unavailable',
          retryable: false,
        })
      }
      return
    }

    switch (result.type) {
      case 'refreshRunRegistered':
        await dependencies.pollDashboard()
        return
      case 'noRepositoriesConfigured':
      case 'workspaceNotConfigured':
        if (
          isContentCurrent(requestGeneration, pullRequestId, actionItemId, requestedActivityVersion)
        ) {
          setActivityContent({
            type: 'contentUnavailable',
            message: `Refresh unavailable. ${result.setupCommand}`,
            retryable: false,
          })
        }
        return
    }
  }

  const acknowledgeSelected = async () => {
    if (state.value.type === 'closed') return
    const current = state.value
    const content = current.context.activityContent
    const selected = current.context.selectedActionItem
    if (
      content?.type !== 'contentAvailable' ||
      !selected ||
      selected.actionItemId !== content.actionItemId ||
      selected.activityVersion !== content.activityVersion
    ) {
      return
    }

    const acknowledged: AcknowledgedActionRef = {
      actionItemId: content.actionItemId,
      activityVersion: content.activityVersion,
      repositoryId: selected.repositoryId,
      pullRequestId: selected.pullRequestId,
    }
    const requestGeneration = generation
    const { pullRequestId } = acknowledged
    setActivityContent({
      type: 'ackPending',
      actionItemId: content.actionItemId,
      activityVersion: content.activityVersion,
      markdownSource: content.markdownSource,
    })

    let result: AcknowledgmentSourceResult
    try {
      result = await source.acknowledgeActionItem(content.actionItemId, content.activityVersion)
    } catch {
      if (
        isContentCurrent(
          requestGeneration,
          pullRequestId,
          content.actionItemId,
          content.activityVersion,
        )
      ) {
        setActivityContent({
          type: 'acknowledgmentRejected',
          message: 'Acknowledgment unavailable.',
          retryable: true,
          actionItemId: content.actionItemId,
          activityVersion: content.activityVersion,
        })
      }
      return
    }

    switch (result.type) {
      case 'acknowledged':
      case 'alreadyAcknowledged':
        dependencies.applyAcknowledgment(acknowledged)
        if (
          isContentCurrent(
            requestGeneration,
            pullRequestId,
            content.actionItemId,
            content.activityVersion,
          )
        ) {
          setActivityContent({ type: 'acknowledged', message: 'Activity acknowledged.' })
        }
        return
      case 'staleActivityVersion':
        if (
          isContentCurrent(
            requestGeneration,
            pullRequestId,
            content.actionItemId,
            content.activityVersion,
          )
        ) {
          await refreshRepository(
            acknowledged.repositoryId,
            result.currentActivityVersion,
            requestGeneration,
            pullRequestId,
            content.actionItemId,
            content.activityVersion,
          )
        }
        return
      case 'acknowledgmentRejected':
        if (
          isContentCurrent(
            requestGeneration,
            pullRequestId,
            content.actionItemId,
            content.activityVersion,
          )
        ) {
          setActivityContent({
            type: 'acknowledgmentRejected',
            message: result.reason,
            retryable: false,
            actionItemId: content.actionItemId,
            activityVersion: content.activityVersion,
          })
        }
        return
      case 'actionItemNotFound':
        if (
          isContentCurrent(
            requestGeneration,
            pullRequestId,
            content.actionItemId,
            content.activityVersion,
          )
        ) {
          setActivityContent({
            type: 'acknowledgmentRejected',
            message: 'This activity is no longer available.',
            retryable: false,
            actionItemId: content.actionItemId,
            activityVersion: content.activityVersion,
          })
        }
        return
    }
  }

  const refreshSelectedRepository = async () => {
    if (state.value.type === 'closed') return
    const content = state.value.context.activityContent
    const selected = state.value.context.selectedActionItem
    if (
      content?.type !== 'newerActivity' ||
      !selected ||
      selected.actionItemId !== content.actionItemId ||
      selected.activityVersion !== content.requestedActivityVersion
    ) {
      return
    }
    await refreshRepository(
      selected.repositoryId,
      content.currentActivityVersion,
      generation,
      selected.pullRequestId,
      content.actionItemId,
      content.requestedActivityVersion,
    )
  }

  return {
    state: shallowReadonly(state),
    statusMessage: readonly(statusMessage),
    openPullRequest,
    openActionItem,
    close,
    reconcileDashboard,
    retrySelectedContent,
    acknowledgeSelected,
    refreshSelectedRepository,
  }
}
