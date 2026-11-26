const TOKEN_KEY = 'aitest_token'
const DEFAULT_TIMEOUT_MS = 300_000

type RequestOptions = RequestInit & { timeoutMs?: number }
type ApiEnvelope<T> = { success: boolean; data: T; message?: string }

export class HttpError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly method: string,
    readonly url: string,
    readonly responseBody?: unknown,
    options?: { cause?: unknown },
  ) {
    super(message)
    this.name = 'HttpError'
    this.cause = options?.cause
  }

  readonly cause?: unknown
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

function parseJson(text: string): unknown {
  if (!text.trim()) return null
  try { return JSON.parse(text) }
  catch { return null }
}

function errorMessage(body: unknown): string | undefined {
  if (!body || typeof body !== 'object' || !('message' in body)) return undefined
  return typeof body.message === 'string' ? body.message : undefined
}

function failResponse(response: Response, method: string, url: string, body: unknown): never {
  const fallback = response.status === 401 ? '登录已失效，请重新登录'
    : response.status === 403 ? '无权限访问该资源'
      : `请求失败（HTTP ${response.status}）`
  if (response.status === 401) {
    clearToken()
    if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
      window.setTimeout(() => { window.location.href = '/login' }, 0)
    }
  }
  throw new HttpError(`${errorMessage(body) || fallback}（${method} ${url}）`,
    response.status, method, url, body)
}

async function withResponse<T>(
  url: string,
  options: RequestOptions,
  read: (response: Response, method: string) => Promise<T>,
): Promise<T> {
  const { timeoutMs = DEFAULT_TIMEOUT_MS, signal, ...requestOptions } = options
  const method = (requestOptions.method || 'GET').toUpperCase()
  const headers = new Headers(requestOptions.headers)
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (requestOptions.body && !(requestOptions.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const controller = new AbortController()
  const onAbort = () => controller.abort()
  if (signal?.aborted) controller.abort()
  else signal?.addEventListener('abort', onAbort, { once: true })
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(url, { ...requestOptions, headers, signal: controller.signal })
    return await read(response, method)
  } catch (error) {
    if (controller.signal.aborted) {
      const message = signal?.aborted ? '请求已取消' : '请求超时，请稍后重试'
      throw new HttpError(`${message}（${method} ${url}）`, 0, method, url, undefined, { cause: error })
    }
    if (error instanceof HttpError) throw error
    throw new HttpError(`网络请求失败（${method} ${url}）`, 0, method, url, undefined, { cause: error })
  } finally {
    window.clearTimeout(timer)
    signal?.removeEventListener('abort', onAbort)
  }
}

export function requestJson<T>(url: string, options: RequestOptions = {}): Promise<T> {
  return withResponse(url, options, async (response, method) => {
    const raw = await response.text()
    const body = parseJson(raw)
    if (!response.ok) failResponse(response, method, url, body)
    if (body === null && raw.trim()) {
      throw new HttpError(`服务返回非 JSON 响应（HTTP ${response.status}）`,
        response.status, method, url, raw)
    }
    const envelope = body as ApiEnvelope<T> | null
    if (!envelope || envelope.success !== true) {
      throw new HttpError(errorMessage(body) || '请求失败', response.status, method, url, body)
    }
    return envelope.data
  })
}

export function requestText(url: string, options: RequestOptions = {}): Promise<string> {
  return withResponse(url, options, async (response, method) => {
    const text = await response.text()
    if (!response.ok) failResponse(response, method, url, parseJson(text))
    return text
  })
}

export function requestBlob(url: string, options: RequestOptions = {}): Promise<Blob> {
  return withResponse(url, options, async (response, method) => {
    if (!response.ok) failResponse(response, method, url, parseJson(await response.text()))
    const blob = await response.blob()
    if (blob.size === 0) throw new HttpError('导出文件为空', response.status, method, url)
    return blob
  })
}
