import assert from 'node:assert/strict'
import http from 'node:http'
import { createRequire } from 'node:module'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { CaptureRuntime } from '../dist/capture.js'
import { downloadCaptureEvidence } from '../dist/evidence.js'

if (process.env.AITESTHUB_SMOKE_DISPOSABLE !== '1') {
  throw new Error('只允许一次性测试库；设置 AITESTHUB_SMOKE_DISPOSABLE=1')
}
const base = process.env.AITESTHUB_SMOKE_URL || 'http://127.0.0.1:18080'
const backend = new URL(base)
if (backend.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(backend.hostname) || backend.pathname !== '/') {
  throw new Error('此脚本只允许连接本机 HTTP 测试后端')
}
const username = process.env.AITESTHUB_SMOKE_USER
const password = process.env.AITESTHUB_SMOKE_PASSWORD
if (!username || !password) throw new Error('需设置 AITESTHUB_SMOKE_USER 和 AITESTHUB_SMOKE_PASSWORD')

async function api(path, { method = 'GET', token, body } = {}) {
  const response = await fetch(base + path, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  const result = await response.json()
  if (!response.ok || !result.success) throw new Error(`${method} ${path}: ${result.message || response.status}`)
  return result.data
}

const require = createRequire(import.meta.url)
const { CaptureManager, resolveBrowser, DEFAULT_CONFIG } = require('../../local-browser-worker/dist/index.js')
const webServer = http.createServer((request, response) => {
  if (request.url === '/api/ping') {
    response.setHeader('Content-Type', 'application/json')
    response.end('{"ok":true}')
    return
  }
  response.setHeader('Content-Type', 'text/html')
  response.end('<!doctype html><title>AITestHub Capture Smoke</title><input id="name">'
    + '<select id="role"><option value="visitor">Visitor</option><option value="staff">Staff</option></select>'
    + '<input id="notify" type="checkbox">'
    + '<button id="agent" onclick="fetch(\'/api/ping\').then(()=>document.body.dataset.agent=\'yes\')">Agent</button>'
    + '<button id="extra" onclick="document.body.dataset.extra=\'yes\'">Extra</button>')
})
await new Promise(resolve => webServer.listen(0, '127.0.0.1', resolve))
const target = `http://127.0.0.1:${webServer.address().port}/`
const root = await mkdtemp(join(tmpdir(), 'aitesthub-real-capture-'))
const manager = new CaptureManager(resolveBrowser(DEFAULT_CONFIG, process.platform), process.platform, root)
const runtime = new CaptureRuntime(manager, join(root, 'recovery'))
let connectionId, webToken
try {
  const status = await api('/api/auth/init-status')
  const login = await api(status.initialized ? '/api/auth/login' : '/api/auth/init-admin', {
    method: 'POST', body: { username, password, displayName: 'DSH Smoke Admin' },
  })
  webToken = login.token
  const project = await api('/api/projects', {
    method: 'POST', token: webToken,
    body: { projectName: `DSH capture smoke ${Date.now()}`, description: 'disposable test' },
  })
  const code = await api('/api/dsh/bind-codes', { method: 'POST', token: webToken })
  const bound = await api('/api/dsh/bind', {
    method: 'POST', body: { code: code.code, deviceName: 'capture smoke' },
  })
  connectionId = bound.connectionId
  const binding = { serverUrl: base, ...bound }
  const work = await api(`/api/dsh/tools/projects/${project.id}/work-sessions`, {
    method: 'POST', token: binding.agentToken,
    body: { requirementText: '主动动作与额外页面操作采集', tomMode: 'DIRECT' },
  })
  const started = await runtime.start(binding, project.id, target, 'real Chrome', work.id)
  await runtime.action(binding, project.id, started.sessionId, 'fill', '#name', 'visitor')
  await runtime.action(binding, project.id, started.sessionId, 'press', '#name', 'Tab')
  await runtime.action(binding, project.id, started.sessionId, 'select', '#role', 'staff')
  await runtime.action(binding, project.id, started.sessionId, 'check', '#notify')
  await runtime.action(binding, project.id, started.sessionId, 'uncheck', '#notify')
  await runtime.action(binding, project.id, started.sessionId, 'assertVisible', '#agent')
  await runtime.action(binding, project.id, started.sessionId, 'assertText', 'body', 'Agent')
  await runtime.action(binding, project.id, started.sessionId, 'click', '#agent')
  const page = manager.pageForSession(started.sessionId)
  await page.locator('body[data-agent="yes"]').waitFor()
  await page.locator('#extra').click()
  await page.locator('body[data-extra="yes"]').waitFor()
  const finished = await runtime.stop(binding, project.id, started.sessionId)
  assert.ok(finished.eventCount >= 3)
  assert.ok(finished.networkCount >= 1)
  const downloaded = await downloadCaptureEvidence(binding, project.id, started.sessionId, join(root, 'download'))
  assert.ok(downloaded.count >= 4)
  assert.ok(downloaded.frames >= 1)
  const run = await api(`/api/dsh/user/projects/${project.id}/captures/${started.sessionId}`, {
    token: binding.userToken,
  })
  assert.equal(run.workSessionId, work.id)
  console.log(JSON.stringify({ ok: true, events: finished.eventCount, networks: finished.networkCount,
    artifacts: downloaded.count, frames: downloaded.frames, workLinked: true }))
} finally {
  await runtime.shutdown().catch(() => undefined)
  if (connectionId && webToken) {
    await api(`/api/dsh/connections/${connectionId}/revoke`, { method: 'POST', token: webToken }).catch(() => undefined)
  }
  await rm(root, { recursive: true, force: true })
  webServer.close()
}
