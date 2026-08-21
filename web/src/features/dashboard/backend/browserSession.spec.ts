import { describe, expect, it, vi } from 'vitest'
import { ApiVersion, BrowserSessionResultTypeEnum } from '@/generated/api-v1/src'
import { ResponseError } from '@/generated/api-v1/src/runtime'
import { BrowserSessionManager } from './browserSession'

const INVALID_SESSION_MESSAGE = 'Browser session response was invalid'

const session = (serviceInstanceId: string, csrfToken: string) => ({
  apiVersion: ApiVersion._1,
  requestId: `req_${serviceInstanceId}`,
  result: {
    type: BrowserSessionResultTypeEnum.browserSession,
    serviceInstanceId,
    csrfToken,
  },
})

const forbidden = () => new ResponseError(new Response(null, { status: 403 }))

describe('BrowserSessionManager', () => {
  it('shares one bootstrap across concurrent mutations', async () => {
    const getBrowserSession = vi.fn().mockResolvedValue(session('svc_one', 'csrf_one'))
    const manager = new BrowserSessionManager({ getBrowserSession })

    await Promise.all([
      manager.runMutation(async (token) => token),
      manager.runMutation(async (token) => token),
    ])

    expect(getBrowserSession).toHaveBeenCalledTimes(1)
  })

  it('retries once only after the service instance changes', async () => {
    const getBrowserSession = vi
      .fn()
      .mockResolvedValueOnce(session('svc_old', 'csrf_old'))
      .mockResolvedValueOnce(session('svc_new', 'csrf_new'))
    const operation = vi.fn().mockRejectedValueOnce(forbidden()).mockResolvedValueOnce('accepted')
    const manager = new BrowserSessionManager({ getBrowserSession })

    await expect(manager.runMutation(operation)).resolves.toBe('accepted')

    expect(operation.mock.calls.map(([token]) => token)).toEqual(['csrf_old', 'csrf_new'])
  })

  it('resets failed bootstrap state before a later mutation', async () => {
    const failure = new Error('session request failed')
    const getBrowserSession = vi
      .fn()
      .mockRejectedValueOnce(failure)
      .mockResolvedValueOnce(session('svc_one', 'csrf_one'))
    const manager = new BrowserSessionManager({ getBrowserSession })

    await expect(manager.runMutation(async (token) => token)).rejects.toBe(failure)
    await expect(manager.runMutation(async (token) => token)).resolves.toBe('csrf_one')

    expect(getBrowserSession).toHaveBeenCalledTimes(2)
  })

  it('rejects malformed or empty session fields with fixed copy', async () => {
    const malformed = [
      { ...session('svc_one', 'csrf_one'), apiVersion: '2' },
      {
        ...session('svc_one', 'csrf_one'),
        result: { ...session('svc_one', 'csrf_one').result, type: 'unexpected' },
      },
      session('svc_one', ''),
      session('', 'csrf_one'),
      session('service_one', 'csrf_one'),
    ]

    for (const response of malformed) {
      const operation = vi.fn()
      const manager = new BrowserSessionManager({
        getBrowserSession: vi.fn().mockResolvedValue(response),
      })

      await expect(manager.runMutation(operation)).rejects.toThrow(INVALID_SESSION_MESSAGE)
      expect(operation).not.toHaveBeenCalled()
    }
  })

  it('rethrows a 403 when the refreshed session belongs to the same service instance', async () => {
    const failure = forbidden()
    const getBrowserSession = vi
      .fn()
      .mockResolvedValueOnce(session('svc_one', 'csrf_old'))
      .mockResolvedValueOnce(session('svc_one', 'csrf_new'))
    const operation = vi.fn().mockRejectedValue(failure)
    const manager = new BrowserSessionManager({ getBrowserSession })

    await expect(manager.runMutation(operation)).rejects.toBe(failure)

    expect(operation).toHaveBeenCalledTimes(1)
    expect(getBrowserSession).toHaveBeenCalledTimes(2)
  })

  it('rethrows the second 403 after retrying against a restarted service', async () => {
    const firstFailure = forbidden()
    const secondFailure = forbidden()
    const getBrowserSession = vi
      .fn()
      .mockResolvedValueOnce(session('svc_old', 'csrf_old'))
      .mockResolvedValueOnce(session('svc_new', 'csrf_new'))
    const operation = vi
      .fn()
      .mockRejectedValueOnce(firstFailure)
      .mockRejectedValueOnce(secondFailure)
    const manager = new BrowserSessionManager({ getBrowserSession })

    await expect(manager.runMutation(operation)).rejects.toBe(secondFailure)

    expect(operation.mock.calls.map(([token]) => token)).toEqual(['csrf_old', 'csrf_new'])
    expect(getBrowserSession).toHaveBeenCalledTimes(2)
  })

  it('does not retry non-403 failures', async () => {
    const failure = new ResponseError(new Response(null, { status: 500 }))
    const getBrowserSession = vi.fn().mockResolvedValue(session('svc_one', 'csrf_one'))
    const operation = vi.fn().mockRejectedValue(failure)
    const manager = new BrowserSessionManager({ getBrowserSession })

    await expect(manager.runMutation(operation)).rejects.toBe(failure)

    expect(operation).toHaveBeenCalledTimes(1)
    expect(getBrowserSession).toHaveBeenCalledTimes(1)
  })

  it('shares one refreshed session across concurrent stale callers', async () => {
    const getBrowserSession = vi
      .fn()
      .mockResolvedValueOnce(session('svc_old', 'csrf_old'))
      .mockResolvedValueOnce(session('svc_new', 'csrf_new'))
    const operation = vi.fn((token: string) =>
      token === 'csrf_old' ? Promise.reject(forbidden()) : Promise.resolve(token),
    )
    const manager = new BrowserSessionManager({ getBrowserSession })

    await expect(
      Promise.all([manager.runMutation(operation), manager.runMutation(operation)]),
    ).resolves.toEqual(['csrf_new', 'csrf_new'])

    expect(getBrowserSession).toHaveBeenCalledTimes(2)
    expect(operation.mock.calls.map(([token]) => token)).toEqual([
      'csrf_old',
      'csrf_old',
      'csrf_new',
      'csrf_new',
    ])
  })

  it('prefetches one in-memory session shared by a later mutation', async () => {
    const getBrowserSession = vi.fn().mockResolvedValue(session('svc_one', 'csrf_one'))
    const manager = new BrowserSessionManager({ getBrowserSession })

    manager.prefetch()
    await expect(manager.runMutation(async (token) => token)).resolves.toBe('csrf_one')

    expect(getBrowserSession).toHaveBeenCalledTimes(1)
  })

  it('does not touch browser storage or cookies', async () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem')
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const removeItem = vi.spyOn(Storage.prototype, 'removeItem')
    const clear = vi.spyOn(Storage.prototype, 'clear')
    const readCookie = vi.spyOn(Document.prototype, 'cookie', 'get')
    const writeCookie = vi.spyOn(Document.prototype, 'cookie', 'set')
    const manager = new BrowserSessionManager({
      getBrowserSession: vi.fn().mockResolvedValue(session('svc_one', 'csrf_one')),
    })

    await expect(manager.runMutation(async (token) => token)).resolves.toBe('csrf_one')

    expect(getItem).not.toHaveBeenCalled()
    expect(setItem).not.toHaveBeenCalled()
    expect(removeItem).not.toHaveBeenCalled()
    expect(clear).not.toHaveBeenCalled()
    expect(readCookie).not.toHaveBeenCalled()
    expect(writeCookie).not.toHaveBeenCalled()
  })
})
