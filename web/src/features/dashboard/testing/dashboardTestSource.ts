import type { DashboardSource } from '../dashboardSource'

export function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })

  return { promise, resolve, reject }
}

export function createDashboardSourceStub(
  overrides: Partial<DashboardSource> = {},
): DashboardSource {
  const unexpected = (method: string): Promise<never> =>
    Promise.reject(new Error(`Unexpected DashboardSource call: ${method}`))

  return {
    loadDashboard: overrides.loadDashboard ?? (() => unexpected('loadDashboard')),
    startRefresh: overrides.startRefresh ?? (() => unexpected('startRefresh')),
    loadPullRequest: overrides.loadPullRequest ?? (() => unexpected('loadPullRequest')),
    loadActionContent: overrides.loadActionContent ?? (() => unexpected('loadActionContent')),
    acknowledgeActionItem:
      overrides.acknowledgeActionItem ?? (() => unexpected('acknowledgeActionItem')),
    startRepositoryRefresh:
      overrides.startRepositoryRefresh ?? (() => unexpected('startRepositoryRefresh')),
  }
}

export function button(): HTMLButtonElement {
  return document.createElement('button')
}
