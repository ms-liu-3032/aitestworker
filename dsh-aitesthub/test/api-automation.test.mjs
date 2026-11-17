import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { test } from 'node:test'
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { executeApiAutomationAsset } from '../dist/api-automation.js'

const binding = { serverUrl: 'https://hub.example', agentToken: 'agent', userToken: 'user', userId: 9, connectionId: 3 }
const mapping = { caseNo: 'TC-API-1', steps: [
  { method: 'POST', path: '/login', body: { password: '${secret:LOGIN_PASS}' }, extract: { access: '$.token', id: '$.id' } },
  { method: 'GET', path: '/items/${id}', headers: { Authorization: 'Bearer ${access}' } },
], assertions: [
  { stepIndex: 0, target: 'STATUS', operator: 'EQ', expected: 200 },
  { stepIndex: 1, target: 'STATUS', operator: 'EQ', expected: 200 },
  { stepIndex: 1, target: 'JSON', path: '$.ok', operator: 'EQ', expected: true },
] }

async function fixture() {
  const calls = []
  const server = createServer(async (request, response) => {
    let body = ''
    for await (const chunk of request) body += chunk
    calls.push({ path: request.url, method: request.method, body, authorization: request.headers.authorization })
    response.setHeader('Content-Type', 'application/json')
    if (request.url === '/login') {
      response.end(JSON.stringify({ token: 'local-session-secret', id: 42 }))
    } else if (request.url === '/items/42' && request.headers.authorization === 'Bearer local-session-secret') {
      response.end(JSON.stringify({ ok: true, token: 'response-secret' }))
    } else {
      response.statusCode = 404
      response.end(JSON.stringify({ ok: false }))
    }
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  return { server, calls, targetUrl: `http://127.0.0.1:${server.address().port}` }
}

test('local API runner passes chained requests and stores redacted evidence without a browser', async () => {
  const { server, calls, targetUrl } = await fixture()
  const reportRoot = await mkdtemp(join(tmpdir(), 'aitest-api-report-'))
  const originalFetch = globalThis.fetch
  const originalSecret = process.env.AITESTHUB_API_SECRET_LOGIN_PASS
  process.env.AITESTHUB_API_SECRET_LOGIN_PASS = 'local-password-secret'
  const saved = []
  globalThis.fetch = async (url, options) => {
    if (!String(url).startsWith(binding.serverUrl)) return originalFetch(url, options)
    const path = new URL(url).pathname
    if (options.body) saved.push({ path, body: JSON.parse(options.body) })
    const data = path.endsWith('/automation-assets/5')
      ? { id: 5, assetType: 'API', status: 'DRAFT', reviewStatus: 'PENDING', currentVersion: 1 }
      : path.endsWith('/versions/1') ? { versionNo: 1, status: 'DRAFT', mapping }
      : path.endsWith('/api-evidence') ? { id: 99 } : { id: 100 }
    return Response.json({ success: true, data })
  }
  try {
    const result = await executeApiAutomationAsset(binding, 18, 5, targetUrl, {}, 1, reportRoot)
    assert.equal(result.status, 'RECORDED')
    assert.equal(result.result, 'PASS')
    assert.match(result.reportPath, /\/reports\/api\/18\/5\/[^/]+\.html$/)
    const report = await readFile(result.reportPath, 'utf8')
    assert.match(report, /接口自动化测试报告/)
    assert.match(report, /浏览器截图 不适用/)
    assert.equal((await stat(result.reportPath)).mode & 0o777, 0o600)
    assert.equal(report.includes('local-password-secret'), false)
    assert.equal(report.includes('local-session-secret'), false)
    assert.equal(report.includes('response-secret'), false)
    assert.equal(calls.length, 2)
    assert.equal(calls[1].authorization, 'Bearer local-session-secret')
    assert.equal(saved[0].body.evidence.steps.length, 2)
    assert.equal(saved[0].body.evidence.assertions.length, 3)
    assert.equal(saved[1].body.apiEvidenceId, 99)
    assert.equal(JSON.stringify(saved).includes('local-password-secret'), false)
    assert.equal(JSON.stringify(saved).includes('local-session-secret'), false)
    assert.equal(JSON.stringify(saved).includes('response-secret'), false)
  } finally {
    globalThis.fetch = originalFetch
    if (originalSecret === undefined) delete process.env.AITESTHUB_API_SECRET_LOGIN_PASS
    else process.env.AITESTHUB_API_SECRET_LOGIN_PASS = originalSecret
    await new Promise(resolve => server.close(resolve))
    await rm(reportRoot, { recursive: true, force: true })
  }
})

test('failed API assertion is recorded as failure rather than PASS', async () => {
  const { server, targetUrl } = await fixture()
  const reportRoot = await mkdtemp(join(tmpdir(), 'aitest-api-report-'))
  const originalFetch = globalThis.fetch
  const originalSecret = process.env.AITESTHUB_API_SECRET_LOGIN_PASS
  process.env.AITESTHUB_API_SECRET_LOGIN_PASS = 'local-password-secret'
  const saved = []
  globalThis.fetch = async (url, options) => {
    if (!String(url).startsWith(binding.serverUrl)) return originalFetch(url, options)
    const path = new URL(url).pathname
    if (options.body) saved.push(JSON.parse(options.body))
    const data = path.endsWith('/automation-assets/5')
      ? { id: 5, assetType: 'API', status: 'DRAFT', reviewStatus: 'PENDING', currentVersion: 1 }
      : path.endsWith('/versions/1') ? { versionNo: 1, status: 'DRAFT', mapping: {
        ...mapping, assertions: [{ stepIndex: 0, target: 'STATUS', operator: 'EQ', expected: 201 }],
      } } : path.endsWith('/api-evidence') ? { id: 99 } : { id: 100 }
    return Response.json({ success: true, data })
  }
  try {
    const result = await executeApiAutomationAsset(binding, 18, 5, targetUrl, {}, 1, reportRoot)
    assert.equal(result.result, 'ASSERTION_FAILURE')
    assert.equal(saved[0].result, 'ASSERTION_FAILURE')
    assert.equal(saved[0].evidence.assertions[0].passed, false)
    assert.equal(saved[1].result, 'ASSERTION_FAILURE')
    assert.match(await readFile(result.reportPath, 'utf8'), /断言不符/)
  } finally {
    globalThis.fetch = originalFetch
    if (originalSecret === undefined) delete process.env.AITESTHUB_API_SECRET_LOGIN_PASS
    else process.env.AITESTHUB_API_SECRET_LOGIN_PASS = originalSecret
    await new Promise(resolve => server.close(resolve))
    await rm(reportRoot, { recursive: true, force: true })
  }
})

test('API execution still writes an honest local report when Hub writeback is pending', async () => {
  const { server, targetUrl } = await fixture()
  const reportRoot = await mkdtemp(join(tmpdir(), 'aitest-api-report-'))
  const originalFetch = globalThis.fetch
  const simple = { caseNo: 'TC-API-1', steps: [{ method: 'GET', path: '/missing' }],
    assertions: [{ stepIndex: 0, target: 'STATUS', operator: 'EQ', expected: 404 }] }
  try {
    for (const failedPath of ['/api-evidence', '/runs']) {
      globalThis.fetch = async (url, options) => {
        if (!String(url).startsWith(binding.serverUrl)) return originalFetch(url, options)
        const path = new URL(url).pathname
        if (path.endsWith(failedPath)) return Response.json({ success: false, message: '暂不可用' }, { status: 503 })
        const data = path.endsWith('/automation-assets/5')
          ? { id: 5, assetType: 'API', status: 'DRAFT', reviewStatus: 'PENDING', currentVersion: 1 }
          : path.endsWith('/versions/1') ? { versionNo: 1, status: 'DRAFT', mapping: simple }
            : { id: 99 }
        return Response.json({ success: true, data })
      }
      const result = await executeApiAutomationAsset(binding, 18, 5, targetUrl, {}, 1, reportRoot)
      assert.equal(result.status, failedPath === '/api-evidence' ? 'EVIDENCE_PENDING' : 'RUN_WRITE_PENDING')
      assert.equal(result.result, 'PASS')
      assert.match(await readFile(result.reportPath, 'utf8'), new RegExp(`回写状态 ${result.status}`))
    }
  } finally {
    globalThis.fetch = originalFetch
    await new Promise(resolve => server.close(resolve))
    await rm(reportRoot, { recursive: true, force: true })
  }
})
