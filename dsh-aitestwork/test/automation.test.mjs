import test from 'node:test'
import assert from 'node:assert/strict'
import { executeAutomationAsset } from '../dist/automation.js'

const binding = { serverUrl: 'https://hub.example', agentToken: 'agent', userToken: 'user', userId: 9, connectionId: 3 }
const mapping = { caseNo: 'TC-001', page: '/visit/newInvitation', steps: [
  { type: 'click', locator: { by: 'text', value: '新增' } },
], assertions: [{ by: 'text', value: '邀请成功' }] }

function environment({ failClick = false, failUpload = false, reviewStatus = 'APPROVED',
  assetStatus = 'VERIFIED', currentVersion = 1, latestVersion = 1, versionStatus = 'VERIFIED', caseMissing = false } = {}) {
  const calls = []
  const target = { async waitFor() {}, async count() { return 1 }, async click() {
    calls.push('click')
    if (failClick) throw new Error(typeof failClick === 'string' ? failClick : 'Timeout waiting for locator')
  } }
  const page = {
    async goto(url) { calls.push(url); return { status: () => 200 } },
    getByText(value) { calls.push(`text:${value}`); return target },
    url() { return 'https://target.example/visit/newInvitation' },
    locator() { return { innerText: async () => '新增 邀请成功' } },
  }
  const capture = {
    async start() { calls.push('start'); return { sessionId: 71 } },
    pageForAutomation() { return page },
    async stop() { calls.push('stop'); if (failUpload) throw new Error('HTTP 502') },
    async writeLocalReport(input) { calls.push(`report:${input.storageStatus}`); return '/tmp/report-71.html' },
  }
  const fetchMock = async (url, options) => {
    calls.push(`${options.method}:${new URL(url).pathname}`)
    const data = url.endsWith('/automation-assets/5') ? { id: 5, status: assetStatus, reviewStatus, currentVersion, latestVersion, caseMissing }
      : /\/versions\/\d+$/.test(url) ? { versionNo: Number(url.split('/').at(-1)), status: versionStatus, mapping }
      : { id: 91, result: failClick ? 'LOCATOR_FAILURE' : 'PASS' }
    return { ok: true, status: 200, json: async () => ({ success: true, data }) }
  }
  return { calls, capture, fetchMock }
}

test('unreviewed mapping cannot run routinely or start evidence capture', async () => {
  const previous = globalThis.fetch
  const { calls, capture, fetchMock } = environment({ reviewStatus: 'PENDING' })
  globalThis.fetch = fetchMock
  try {
    await assert.rejects(executeAutomationAsset(binding, capture, 18, 5, 'https://target.example'), /审核通过/)
    assert.equal(calls.includes('start'), false)
  } finally { globalThis.fetch = previous }
})

test('orphaned asset cannot run even when its old version was verified', async () => {
  const previous = globalThis.fetch
  const { calls, capture, fetchMock } = environment({ caseMissing: true })
  globalThis.fetch = fetchMock
  try {
    await assert.rejects(executeAutomationAsset(binding, capture, 18, 5, 'https://target.example'), /关联用例已不存在/)
    assert.equal(calls.includes('start'), false)
  } finally { globalThis.fetch = previous }
})

test('degraded asset may validate its newer draft candidate explicitly', async () => {
  const previous = globalThis.fetch
  const { capture, fetchMock } = environment({ assetStatus: 'DEGRADED', currentVersion: 1,
    latestVersion: 2, versionStatus: 'DRAFT' })
  globalThis.fetch = fetchMock
  try {
    const result = await executeAutomationAsset(binding, capture, 18, 5, 'https://target.example', {}, 2)
    assert.equal(result.result, 'PASS')
  } finally { globalThis.fetch = previous }
})

test('verified mapping runs from stored steps and records evidence-backed result', async () => {
  const previous = globalThis.fetch
  const { calls, capture, fetchMock } = environment()
  globalThis.fetch = fetchMock
  try {
    const result = await executeAutomationAsset(binding, capture, 18, 5, 'https://target.example')
    assert.equal(result.status, 'RECORDED')
    assert.equal(result.result, 'PASS')
    assert.equal(result.reportPath, '/tmp/report-71.html')
    assert.equal(calls.includes('text:新增'), true)
    assert.equal(calls.includes('stop'), true)
    assert.equal(calls.includes('POST:/api/aitestwork/dsh/tools/projects/18/automation-assets/5/runs'), true)
  } finally { globalThis.fetch = previous }
})

test('locator failure is eligible for heal; failed evidence upload leaves run pending', async () => {
  const previous = globalThis.fetch
  const { calls, capture, fetchMock } = environment({ failClick: true, failUpload: true })
  globalThis.fetch = fetchMock
  try {
    const result = await executeAutomationAsset(binding, capture, 18, 5, 'https://target.example')
    assert.equal(result.status, 'EVIDENCE_PENDING')
    assert.equal(result.result, 'LOCATOR_FAILURE')
    assert.equal(result.runPayload.evidenceSessionId, 71)
    assert.equal(calls.some(call => call.endsWith('/runs')), false)
  } finally { globalThis.fetch = previous }
})

test('closed browser is an environment failure and cannot trigger locator self-heal', async () => {
  const previous = globalThis.fetch
  const { capture, fetchMock } = environment({ failClick: 'Target page, context or browser has been closed' })
  globalThis.fetch = fetchMock
  try {
    const result = await executeAutomationAsset(binding, capture, 18, 5, 'https://target.example')
    assert.equal(result.result, 'ENVIRONMENT_FAILURE')
    assert.equal(result.selfHealAllowed, false)
  } finally { globalThis.fetch = previous }
})

test('failed run write returns a retryable payload after evidence upload', async () => {
  const previous = globalThis.fetch
  const { capture, fetchMock } = environment()
  globalThis.fetch = async (url, options) => {
    if (new URL(url).pathname.endsWith('/runs')) return {
      ok: false, status: 503, json: async () => ({ success: false, message: '服务暂不可用' }),
    }
    return fetchMock(url, options)
  }
  try {
    const result = await executeAutomationAsset(binding, capture, 18, 5, 'https://target.example')
    assert.equal(result.status, 'RUN_WRITE_PENDING')
    assert.equal(result.runPayload.result, 'PASS')
    assert.equal(result.runPayload.evidenceSessionId, 71)
  } finally { globalThis.fetch = previous }
})
