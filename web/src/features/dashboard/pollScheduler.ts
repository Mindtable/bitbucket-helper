export interface PollScheduler {
  schedule(afterMilliseconds: number, task: () => void): () => void
}

export const browserPollScheduler: PollScheduler = {
  schedule(afterMilliseconds, task) {
    const timeoutId = window.setTimeout(task, afterMilliseconds)
    return () => window.clearTimeout(timeoutId)
  },
}
