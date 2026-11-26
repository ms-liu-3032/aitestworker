import type { Context } from '@deepseek-ai/cordis'
import { credentialRef } from '@deepseek-ai/dsh-credentials'
import { readFile } from 'node:fs/promises'
import { basename } from 'node:path'

export const BINDING_KEY = credentialRef('AITESTWORK_CONNECTION')

export interface Binding {
  serverUrl: string
  // AITestWork backend credentials, stored in local DSH; neither logs the user into DSH.
  userToken: string
  agentToken: string
  userId: number
  connectionId: number
}

export function parseServerUrl(raw: string): string {
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    throw new Error('请输入带 http:// 或 https:// 的 AITestWork 服务根地址')
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    throw new Error('AITestWork 服务地址仅支持 HTTP 或 HTTPS')
  }
  if (url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error('请输入 AITestWork 服务根地址')
  }
  return url.origin
}

export async function currentBinding(ctx: Context): Promise<Binding> {
  const record = await ctx.credentials.resolve(BINDING_KEY)
  if (!record) throw new Error('请先运行 /aitestwork-bind <服务地址> <绑定码>')
  let value: Partial<Binding>
  try {
    value = JSON.parse(record.value) as Partial<Binding>
  } catch {
    throw new Error('AITestWork 连接凭据格式错误，请重新绑定')
  }
  if (!value.serverUrl || !value.userToken || !value.agentToken || !value.userId || !value.connectionId) {
    throw new Error('AITestWork 连接凭据不完整，请重新绑定')
  }
  return value as Binding
}

export async function callApi<T>(binding: Binding, path: string, method = 'GET', body?: unknown): Promise<T> {
  if (!path.startsWith('/api/aitestwork/dsh/tools/')) throw new Error('Agent 工具仅可调用草稿与只读接口')
  return sendApi(binding.serverUrl, binding.agentToken, path, method, body)
}

export async function callUserApi<T>(binding: Binding, path: string, method = 'GET', body?: unknown): Promise<T> {
  if (!path.startsWith('/api/aitestwork/dsh/user/')) throw new Error('用户操作接口路径错误')
  return sendApi(binding.serverUrl, binding.userToken, path, method, body)
}

export async function uploadEvidence<T>(binding: Binding, path: string, localPath: string): Promise<T> {
  if (!path.startsWith('/api/aitestwork/dsh/tools/')) throw new Error('证据上传路径错误')
  const form = new FormData()
  form.set('file', new Blob([await readFile(localPath)]), basename(localPath))
  const response = await fetch(`${binding.serverUrl}${path}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${binding.agentToken}` },
    body: form,
    signal: AbortSignal.timeout(120_000),
  })
  const parsed = await readApiResponse<T>(response, '证据上传')
  if (!response.ok || !parsed.success) throw new Error(parsed.message || `证据上传失败（HTTP ${response.status}）`)
  return parsed.data as T
}

async function sendApi<T>(serverUrl: string, token: string, path: string, method: string, body?: unknown): Promise<T> {
  const response = await fetch(`${serverUrl}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(20_000),
  })
  const parsed = await readApiResponse<T>(response, 'AITestWork 请求')
  if (!response.ok || !parsed.success) {
    throw new Error(parsed.message || `AITestWork 请求失败（HTTP ${response.status}）`)
  }
  return parsed.data as T
}

async function readApiResponse<T>(response: Response, operation: string): Promise<{ success?: boolean; data?: T; message?: string }> {
  try {
    return await response.json() as { success?: boolean; data?: T; message?: string }
  } catch {
    if (response.status === 413) {
      throw new Error(`${operation}失败（HTTP 413）：Web 代理拒绝了上传体，请检查 nginx 的 client_max_body_size`)
    }
    throw new Error(`${operation}失败（HTTP ${response.status}）：服务端返回非 JSON 内容，请检查代理与后端日志`)
  }
}

export function projectPath(projectId: number): string {
  if (!Number.isSafeInteger(projectId) || projectId < 1) throw new Error('projectId 必须是正整数')
  return `/api/aitestwork/dsh/tools/projects/${projectId}`
}
