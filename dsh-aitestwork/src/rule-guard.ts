/** A host approval is required before a model can select a design mode or start an execution scope. */
export const EXECUTION_TOOLS = new Set([
  'aitestwork_capture_start', 'aitestwork_run_auto_asset', 'aitestwork_run_api_asset', 'aitestwork_run_local_ui',
])

export const SCOPE_TTL_MS = 8 * 60 * 60_000

export function executionScope(name: string, args: unknown, principal: string): { key: string; reason: string } | null {
  if (!EXECUTION_TOOLS.has(name)) return null
  if (!args || typeof args !== 'object') throw new Error('执行参数无效')
  const values = args as Record<string, unknown>
  if (typeof values.targetUrl !== 'string') throw new Error('执行目标地址不能为空')
  let url: URL
  try { url = new URL(values.targetUrl) } catch { throw new Error('执行目标地址无效') }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash)
    throw new Error('执行目标必须是无账号密码和锚点的 HTTP/HTTPS 地址')
  const project = name === 'aitestwork_run_local_ui' ? 'LOCAL' : values.projectId
  if (project !== 'LOCAL' && (!Number.isSafeInteger(project) || Number(project) < 1))
    throw new Error('执行项目 ID 无效')
  const asset = name === 'aitestwork_capture_start' || name === 'aitestwork_run_local_ui' ? '' : `，资产 #${values.assetId}`
  return {
    key: JSON.stringify([principal, project, url.origin]),
    reason: `AITestWork 将在项目 ${project}${asset} 对 ${url.origin} 执行${name === 'aitestwork_run_api_asset' ? '接口' : '浏览器'}测试，并可能触发业务写入。确认后本机 DSH 可在本次连接的该项目和地址继续执行 8 小时；更换地址须重新确认。`,
  }
}

export function designModeApproval(name: string, args: unknown): string | null {
  if (name !== 'aitestwork_prepare_case_design') return null
  if (!args || typeof args !== 'object') throw new Error('需求准备参数无效')
  const { projectId, tomMode } = args as Record<string, unknown>
  if (!Number.isSafeInteger(projectId) || Number(projectId) < 1
      || !['DIRECT', 'PROJECT_TOM', 'PROJECT_AND_SYSTEM_TOM'].includes(String(tomMode)))
    throw new Error('项目或 TOM 模式无效')
  return `AITestWork 将为项目 #${projectId} 按 ${tomMode} 模式读取规则并准备本轮用例设计。请确认这是你选择的 TOM 范围。`
}
