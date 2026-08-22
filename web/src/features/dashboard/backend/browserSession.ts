import {
  ApiVersion,
  BrowserSessionResultTypeEnum,
  type BrowserSessionResponse,
} from '@/generated/api-v1/src'
import { ResponseError } from '@/generated/api-v1/src/runtime'

export interface BrowserSessionClient {
  getBrowserSession(): Promise<BrowserSessionResponse>
}

export interface BrowserSessionState {
  csrfToken: string
  serviceInstanceId: string
}

export class BrowserSessionManager {
  private resolved: BrowserSessionState | undefined
  private inFlight: Promise<BrowserSessionState> | undefined

  constructor(private readonly client: BrowserSessionClient) {}

  prefetch(): void {
    void this.session().catch(() => undefined)
  }

  async runMutation<T>(operation: (csrfToken: string) => Promise<T>): Promise<T> {
    const used = await this.session()
    try {
      return await operation(used.csrfToken)
    } catch (failure) {
      if (!(failure instanceof ResponseError) || failure.response.status !== 403) throw failure
      const refreshed = await this.refreshAfter(used.serviceInstanceId)
      if (refreshed.serviceInstanceId === used.serviceInstanceId) throw failure
      return operation(refreshed.csrfToken)
    }
  }

  private session(): Promise<BrowserSessionState> {
    if (this.resolved) return Promise.resolve(this.resolved)
    if (this.inFlight) return this.inFlight
    const request = Promise.resolve()
      .then(() => this.client.getBrowserSession())
      .then((response) => this.toState(response))
    const tracked = request.then(
      (state) => {
        this.resolved = state
        this.inFlight = undefined
        return state
      },
      (failure) => {
        this.inFlight = undefined
        throw failure
      },
    )
    this.inFlight = tracked
    return tracked
  }

  private async refreshAfter(serviceInstanceId: string): Promise<BrowserSessionState> {
    const current = this.resolved
    if (current && current.serviceInstanceId !== serviceInstanceId) return current
    if (current?.serviceInstanceId === serviceInstanceId) this.resolved = undefined
    return this.session()
  }

  private toState(response: BrowserSessionResponse): BrowserSessionState {
    if (!response || typeof response !== 'object') {
      throw new Error('Browser session response was invalid')
    }
    const { result } = response
    if (!result || typeof result !== 'object') {
      throw new Error('Browser session response was invalid')
    }
    if (
      response.apiVersion !== ApiVersion._1 ||
      result.type !== BrowserSessionResultTypeEnum.browserSession ||
      typeof result.csrfToken !== 'string' ||
      result.csrfToken.length === 0 ||
      typeof result.serviceInstanceId !== 'string' ||
      !/^svc_[A-Za-z0-9_-]+$/.test(result.serviceInstanceId)
    ) {
      throw new Error('Browser session response was invalid')
    }
    return {
      csrfToken: result.csrfToken,
      serviceInstanceId: result.serviceInstanceId,
    }
  }
}
