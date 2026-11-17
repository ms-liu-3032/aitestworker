import assert from 'node:assert/strict'
import { apply } from '../dist/index.js'

if (process.env.AITESTHUB_SMOKE_DISPOSABLE !== '1') {
  throw new Error('只允许一次性测试库；设置 AITESTHUB_SMOKE_DISPOSABLE=1')
}
const base = process.env.AITESTHUB_SMOKE_URL || 'http://127.0.0.1:18080'
const url = new URL(base)
if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(url.hostname) || url.pathname !== '/') {
  throw new Error('此脚本只允许连接本机 HTTP 测试后端')
}
const username = process.env.AITESTHUB_SMOKE_USER
const password = process.env.AITESTHUB_SMOKE_PASSWORD
if (!username || !password) throw new Error('需设置一次性测试账号环境变量')

async function api(path, { method = 'GET', token, body } = {}) {
  const response = await fetch(base + path, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const result = await response.json()
  if (!response.ok || !result.success) throw new Error(`${method} ${path}: ${result.message || response.status}`)
  return result.data
}

const commands = new Map()
const tools = new Map()
let credential
apply({
  tools: { register(tool) { tools.set(tool.name, tool) } },
  commands: { register(command) { commands.set(command.name, command) } },
  skills: { register() {} },
  systemPrompt: { section() {} },
  credentials: {
    async resolve() { return credential ? { value: credential, source: 'smoke' } : undefined },
    async set(_ref, value) { credential = value },
    async unset() { credential = undefined },
  },
})

let connectionId
let webToken
try {
  const login = await api('/api/auth/login', {
    method: 'POST', body: { username, password },
  })
  webToken = login.token
  const code = await api('/api/dsh/bind-codes', { method: 'POST', token: webToken })
  const bound = await commands.get('aitest-bind').handler({ rawInput: `${base} ${code.code}` })
  assert.equal(bound.kind, 'success', bound.text)
  assert.match(bound.text, /HTTP.*明文传输/)
  const status = await commands.get('aitest-status').handler()
  assert.equal(status.kind, 'success', status.text)
  assert.ok(status.text.includes(`服务地址：${base}`))
  const project = JSON.parse(await tools.get('aitest_create_project').execute({
    projectName: `DSH preflight smoke ${Date.now()}`, description: 'disposable test',
  }))
  const prepared = JSON.parse(await tools.get('aitest_prepare_case_design').execute({
    projectId: project.id, requirementText: '验证访客审批的状态与权限', tomMode: 'DIRECT',
  }))
  assert.equal(prepared.ready, true)
  assert.ok(prepared.designRules.length > 0)
  const work = JSON.parse(await tools.get('aitest_start_work').execute({
    projectId: project.id, requirementText: '验证访客审批的状态与权限', tomMode: 'DIRECT',
  }))
  assert.ok(work.id > 0)
  connectionId = JSON.parse(credential).connectionId
  await api(`/api/dsh/connections/${connectionId}/revoke`, { method: 'POST', token: webToken })
  connectionId = undefined
  const revoked = await commands.get('aitest-status').handler()
  assert.equal(revoked.kind, 'error', '撤销后状态指令必须失败')
  console.log(JSON.stringify({ ok: true, httpBind: true, status: true, preflight: true,
    revokedCredentialRejected: true }))
} finally {
  if (connectionId && webToken) {
    await api(`/api/dsh/connections/${connectionId}/revoke`, { method: 'POST', token: webToken }).catch(() => undefined)
  }
}
