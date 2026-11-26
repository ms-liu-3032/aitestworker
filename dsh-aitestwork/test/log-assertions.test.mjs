import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { AdminLogRuntime } from '../dist/admin-logs.js'
import { LogAssertionRun, selectLogSources, responseCorrelation } from '../dist/log-assertions.js'
import { executeApiAutomationAsset } from '../dist/api-automation.js'
import { applyMappingEdits } from '../dist/asset-reuse.js'
import { runUiMapping } from '../dist/automation.js'

const rule = { id: 'inviteRequest', stepIndex: 0, purpose: 'REQUEST_ASSOCIATION', expected: 'POST /api/invitations', timeoutMs: 0 }
const mapping = { caseNo: 'TC-LOG', steps: [{ method: 'POST', path: '/vsk/vst-visitor/api/invitations' }],
  assertions: [{ stepIndex: 0, target: 'JSON', path: '$.code', operator: 'EQ', expected: 0 }], logAssertions: [rule] }
const source = id => ({ instanceId: id, serviceName: 'VST-VISITOR', status: 'UP', logfileAvailable: true })
async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'log-rules-'))
  const state = { rows: [source('one'), source('two')], logs: { one: 'history\n', two: 'history\n' }, denied: false, reads: [] }
  const transport = { async close() {}, async get(path, range) {
    state.reads.push({ path, range })
    if (path === '/instances') return { status: state.denied ? 401 : 200, contentType: 'application/json', range: '', truncated: false,
      text: JSON.stringify(state.rows.map(row => ({ id: row.instanceId, registration: { name: row.serviceName }, statusInfo: { status: row.status }, endpoints: [{ id: 'logfile' }] }))) }
    const data = Buffer.from(state.logs[path.split('/')[2]])
    const start = range.startsWith('bytes=-') ? Math.max(0, data.length - 1024) : Number(range.match(/bytes=(\d+)-/)[1])
    const bytes = data.subarray(start)
    return { status: 206, contentType: 'text/plain', range: `bytes ${start}-${data.length - 1}/${data.length}`,
      text: bytes.toString(), bytesBase64: bytes.toString('base64'), truncated: false }
  } }
  const runtime = new AdminLogRuntime(transport, root)
  t.after(async () => { await runtime.close(); await rm(root, { recursive: true, force: true }) })
  return { state, runtime, root }
}

test('source discovery handles replicas and registration changes; ambiguous or unhealthy services stop preflight', () => {
  assert.deepEqual(selectLogSources([source('new-id')], mapping.steps[0].path).map(row => row.instanceId), ['new-id'])
  assert.equal(selectLogSources([source('a'), source('b')], mapping.steps[0].path).length, 2)
  assert.throws(() => selectLogSources([source('a'), { ...source('b'), serviceName: 'api' }], mapping.steps[0].path), /AMBIGUOUS/)
  assert.throws(() => selectLogSources([{ ...source('a'), status: 'DOWN' }], mapping.steps[0].path), /FULLY_READABLE/)
  assert.throws(() => selectLogSources([source('a')], '/unrelated'), /NOT_FOUND/)
})

test('runtime correlation defaults to real response IDs; conflicting or missing IDs require calibration', () => {
  assert.equal(responseCorrelation({ headers: {}, body: { traceId: 'trace-123' } }, rule), 'trace-123')
  assert.throws(() => responseCorrelation({ headers: { 'x-request-id': 'other-456' }, body: { traceId: 'trace-123' } }, rule), /AMBIGUOUS/)
  assert.throws(() => responseCorrelation({ headers: {}, body: { code: 0 } }, rule), /MISSING/)
  assert.throws(() => responseCorrelation({ headers: {}, body: { traceId: true } }, rule), /MISSING/)
  assert.equal(responseCorrelation({ headers: {}, body: { data: { id: 12345 } } }, { ...rule, correlation: { target: 'JSON', path: '$.data.id' } }), '12345')
})

test('all replicas get pre-action checkpoints; only fresh complete correlated log may pass', async t => {
  const { runtime, state } = await fixture(t)
  const logs = new LogAssertionRun(runtime, mapping)
  await logs.beforeStep(0)
  assert.equal(state.reads.filter(row => row.range === 'bytes=-1024').length, 2)
  state.logs.two += 'trace-123 POST /api/invitations\n'
  await logs.afterStep(0, { headers: {}, body: { traceId: 'trace-123' } })
  assert.equal(logs.evidence[0].result, 'PASS')
  assert.equal(logs.evidence[0].instances.length, 2)
  assert.doesNotMatch(JSON.stringify(logs.evidence), /trace-123|matchedLine"|platform|https:/)
  const rerun = new LogAssertionRun(runtime, mapping)
  await rerun.beforeStep(0)
  await assert.rejects(rerun.afterStep(0, { headers: {}, body: { traceId: 'trace-123' } }), /LOG_FAIL:/)
  assert.equal(rerun.evidence[0].result, 'FAIL')
})

test('instance change during action and correlation failure yield inconclusive rather than replacement baseline', async t => {
  for (const mode of ['instance', 'correlation']) {
    const { runtime, state } = await fixture(t)
    const logs = new LogAssertionRun(runtime, mapping)
    await logs.beforeStep(0)
    if (mode === 'instance') state.rows = [source('replacement')]
    await assert.rejects(logs.afterStep(0, { headers: {}, body: mode === 'correlation' ? {} : { traceId: 'trace-123' } }), /LOG_INCONCLUSIVE/)
    assert.equal(logs.evidence[0].result, 'INCONCLUSIVE')
    assert.equal(state.reads.filter(row => row.range === 'bytes=-1024').length, 2)
  }
})

test('complete platform URL preserves arbitrary prefix, supports another host and never adds a login route', async t => {
  const { runtime } = await fixture(t)
  runtime.configure('https://logs.example/custom/monitor/', 'https://logs.example/custom/sign-in')
  runtime.useTarget('https://app.example/testing/')
  assert.equal(runtime.baseUrl, 'https://logs.example/custom/monitor')
  assert.throws(() => runtime.configure('https://logs.example/other/'), /PLATFORM_CHANGED/)
  assert.throws(() => runtime.useTarget('https://other.example/'), /ENVIRONMENT_CHANGED/)
  await runtime.close()
  runtime.configure('https://new.example/arbitrary/')
  assert.equal(runtime.baseUrl, 'https://new.example/arbitrary')
  assert.throws(() => runtime.configure('https://user:password@new.example/'), /INVALID_PLATFORM_URL/)
})

test('API run merges log outcome, preserves retry payload, and prevents mutation if preflight is unavailable', async t => {
  const originalFetch = globalThis.fetch
  t.after(() => { globalThis.fetch = originalFetch })
  for (const mode of ['pass', 'missing', 'denied', 'pending']) {
    const { runtime, state, root } = await fixture(t)
    const saved = []
    let actions = 0
    state.denied = mode === 'denied'
    globalThis.fetch = async (url, options) => {
      if (String(url).startsWith('https://target.example')) {
        actions++
        if (mode !== 'missing') state.logs.two += 'trace-123 POST /api/invitations\n'
        return Response.json({ code: 0, traceId: 'trace-123' })
      }
      const path = new URL(url).pathname
      if (options.body) saved.push(JSON.parse(options.body))
      if (path.endsWith('/runs') && mode === 'pending') throw new Error('offline')
      return Response.json({ success: true, data: path.endsWith('/automation-assets/5') ? { assetType: 'API', status: 'DRAFT', currentVersion: 1 }
        : path.endsWith('/versions/1') ? { status: 'DRAFT', mapping } : { id: 99 } })
    }
    const result = await executeApiAutomationAsset({ serverUrl: 'https://hub.example', agentToken: 'agent' }, 18, 5,
      'https://target.example/vsk/main/', {}, 1, root, runtime)
    assert.equal(result.result, mode === 'missing' ? 'ASSERTION_FAILURE' : mode === 'denied' ? 'ENVIRONMENT_FAILURE' : 'PASS')
    assert.equal(actions, mode === 'denied' ? 0 : 1)
    assert.equal(saved[1].logEvidence[0].result, mode === 'missing' ? 'FAIL' : mode === 'denied' ? 'INCONCLUSIVE' : 'PASS')
    assert.doesNotMatch(JSON.stringify(saved), /trace-123|matchedLine"|password/)
    assert.match(await readFile(result.reportPath, 'utf8'), /日志断言/)
    if (mode === 'pending') { assert.equal(result.status, 'RUN_WRITE_PENDING'); assert.equal(result.runPayload.logEvidence[0].result, 'PASS') }
  }
})

test('UI response observation feeds the same saved rule and fails overall if log evidence is missing', async t => {
  for (const emit of [true, false]) {
    const { runtime, state } = await fixture(t)
    let resolveResponse
    const response = { headerValue: async () => null, body: async () => Buffer.from('{"traceId":"trace-123"}') }
    const target = { async waitFor() {}, async count() { return 1 }, async click() {
      if (emit) state.logs.two += 'trace-123 POST /api/invitations\n'
      resolveResponse(response)
    } }
    const page = { goto: async () => ({ status: () => 200 }), url: () => 'https://target.example/vsk/main/',
      waitForResponse: () => new Promise(resolve => { resolveResponse = resolve }), getByText: () => target,
      locator: () => ({ innerText: async () => 'done' }) }
    const ui = { caseNo: 'TC-UI', page: '/vsk/main/', steps: [{ type: 'click', locator: { by: 'text', value: '邀请' },
      waitForResponse: { path: mapping.steps[0].path } }], assertions: [{ by: 'text', value: '完成' }], logAssertions: [rule] }
    const result = await runUiMapping(page, ui, new URL('https://target.example/vsk/main/'), {}, runtime)
    assert.equal(result.result, emit ? 'PASS' : 'ASSERTION_FAILURE')
    assert.equal(result.logEvidence[0].result, emit ? 'PASS' : 'FAIL')
  }
})


test('rule reuse preserves frozen expectations; explicit log edits produce a separate mapping', () => {
  const source = { ...mapping, page: '/vsk/main/' }
  const revised = applyMappingEdits(source, [{ operation: 'replace', section: 'logAssertions', index: 0,
    value: { ...rule, correlation: { target: 'JSON', path: '$.data.traceId' } } }], 'TC-NEW')
  assert.equal(revised.logAssertions[0].expected, rule.expected)
  assert.equal(source.logAssertions[0].correlation, undefined)
  assert.equal(revised.caseNo, 'TC-NEW')
  assert.deepEqual(revised.steps, source.steps)
  assert.throws(() => new LogAssertionRun(undefined, { ...mapping, logAssertions: [{ ...rule, domain: 'forbidden.example' }] }), /INVALID_LOG_RULE/)
})
