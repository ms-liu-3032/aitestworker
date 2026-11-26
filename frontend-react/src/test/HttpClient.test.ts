import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearToken, HttpError, requestBlob, requestJson, setToken } from '../services/httpClient'

function response(status: number, body: string) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => body,
    blob: async () => new Blob([body]),
  } as Response
}

beforeEach(() => {
  const values = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value) },
    removeItem: (key: string) => { values.delete(key) },
  })
  clearToken()
})

afterEach(() => {
  clearToken()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('HTTP client', () => {
  it('keeps valid false and zero values in successful responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(200, '{"success":true,"data":{"count":0,"enabled":false}}')))
    await expect(requestJson('/api/status')).resolves.toEqual({ count: 0, enabled: false })
    const [, options] = vi.mocked(fetch).mock.calls[0]
    expect(new Headers(options?.headers).has('Authorization')).toBe(false)
  })

  it('preserves status and body for forbidden responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(403, '{"message":"无权限访问"}')))
    await expect(requestJson('/api/private')).rejects.toMatchObject({
      name: 'HttpError', status: 403, method: 'GET', url: '/api/private',
      responseBody: { message: '无权限访问' },
    } satisfies Partial<HttpError>)
  })

  it('removes expired credentials after a 401', async () => {
    vi.spyOn(window, 'setTimeout').mockImplementation(() => 0)
    setToken('expired-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(401, '{"message":"登录已失效"}')))
    await expect(requestJson('/api/private')).rejects.toMatchObject({ status: 401 })
    expect(localStorage.getItem('aitest_token')).toBeNull()
  })

  it('uploads a form without forcing JSON content type', async () => {
    setToken('local-test-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(200, '{"success":true,"data":1}')))
    await expect(requestJson('/api/upload', { method: 'POST', body: new FormData() })).resolves.toBe(1)
    const [, options] = vi.mocked(fetch).mock.calls[0]
    const headers = new Headers(options?.headers)
    expect(headers.get('Authorization')).toBe('Bearer local-test-token')
    expect(headers.has('Content-Type')).toBe(false)
  })

  it('aborts a stalled request after the configured timeout', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_url, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
    })))
    const pending = requestJson('/api/slow', { timeoutMs: 20 })
    const rejection = expect(pending).rejects.toMatchObject({ status: 0, message: expect.stringContaining('请求超时') })
    await vi.advanceTimersByTimeAsync(21)
    await rejection
  })

  it('reports an empty exported file as a failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true, status: 200, blob: async () => new Blob([]),
    } as Response))
    await expect(requestBlob('/api/export')).rejects.toMatchObject({ message: '导出文件为空' })
  })
})
