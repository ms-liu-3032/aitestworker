import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { apply } from '../dist/index.js'
import { LocalDesignStore } from '../dist/local-design.js'
import { OfflineAutomation } from '../dist/offline-automation.js'

const require = createRequire(import.meta.url)
const { resolveBrowser, DEFAULT_CONFIG } = require('local-browser-worker')
const browserInfo = resolveBrowser(DEFAULT_CONFIG, process.platform)

const point = { id: 'TP1', title: '新增记录入口', pointType: 'MAIN_FLOW', sourceBasis: ['用户提供的需求'] }
const localCase = { title: '点击新增进入填写页面', moduleName: '/记录/新增',
  steps: '1. 在记录列表点击新增按钮', expectedResult: '1. 页面显示新增记录填写页面',
  sourceTestPointRefs: ['TP1'], sourceBasis: ['用户提供的需求'] }

test('local rules are available without a Hub binding', async () => {
  const tools = new Map()
  const ctx = { tools: { register(tool) { tools.set(tool.name, tool) } },
    commands: { register() {} }, skills: { register() {} }, systemPrompt: { section() {} },
    credentials: { async resolve() { return undefined } } }
  apply(ctx)
  const rules = JSON.parse(await tools.get('aitest_local_design_rules').execute({}))
  assert.equal(rules.mode, 'LOCAL_ONLY')
  assert.ok(tools.has('aitest_run_local_ui'))
  assert.ok(tools.has('aitest_local_save_design'))
})

test('local tools do not send Hub telemetry even when another mode is bound', async () => {
  const events = new Map()
  const ctx = { tools: { register() {} }, commands: { register() {} }, skills: { register() {} },
    systemPrompt: { section() {} }, on(name, handler) { events.set(name, handler) },
    credentials: { async resolve() { return { value: JSON.stringify({ serverUrl: 'https://hub.example',
      userToken: 'user', agentToken: 'agent', userId: 1, connectionId: 2 }) } } } }
  apply(ctx)
  const previousFetch = globalThis.fetch
  let calls = 0
  globalThis.fetch = async () => { calls++; throw new Error('local telemetry must not call Hub') }
  try {
    const result = await events.get('tools/execute')({ name: 'aitest_run_local_ui' }, async () => ({ isError: false }))
    assert.equal(result.isError, false)
    assert.equal(calls, 0)
  } finally { globalThis.fetch = previousFetch }
})

test('local designs keep unique case IDs, reject dependent cases and guard updates', async () => {
  const root = await mkdtemp(join(tmpdir(), 'aitest-local-design-'))
  const store = new LocalDesignStore(root)
  try {
    const first = await store.save({ requirementText: '列表可新增记录', testPoints: [point], cases: [] })
    assert.equal(first.versionNo, 1)
    assert.equal(first.cases.length, 0)
    await assert.rejects(store.save({ id: first.id, expectedVersion: 1,
      requirementText: first.requirementText, testPoints: [point],
      cases: [{ ...localCase, steps: '1. 执行上一条用例后点击新增' }] }), /独立执行/)
    const second = await store.save({ id: first.id, expectedVersion: 1,
      requirementText: first.requirementText, testPoints: [point], cases: [localCase] })
    assert.equal(second.versionNo, 2)
    assert.match(second.cases[0].id, /^[0-9a-f-]{36}$/)
    await assert.rejects(store.save({ id: first.id, expectedVersion: 1,
      requirementText: first.requirementText, testPoints: [point], cases: second.cases }), /版本已变化/)
    const third = await store.setMapping(first.id, second.cases[0].id, 2, {
      page: '/', steps: [{ type: 'click', locator: { by: 'css', value: '#add' } }],
      assertions: [{ by: 'css', value: '#form', expected: '新增记录' }],
    })
    assert.equal(third.cases[0].id, second.cases[0].id)
    assert.equal(third.cases[0].mapping.caseNo, `LOCAL-${second.cases[0].id.slice(0, 8)}`)
    assert.equal((await store.list())[0].caseCount, 1)
    const revised = await store.save({ id: first.id, expectedVersion: 3,
      requirementText: first.requirementText, testPoints: [point],
      cases: [{ ...third.cases[0], steps: '1. 在记录列表点击新增入口' }] })
    assert.equal(revised.cases[0].mapping, undefined, 'changing case steps must invalidate the old UI mapping')
  } finally { await rm(root, { recursive: true, force: true }) }
})

test('local Playwright run produces screenshot report and trace without Hub', { skip: !browserInfo }, async () => {
  const root = await mkdtemp(join(tmpdir(), 'aitest-local-ui-'))
  const server = createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><title>本地测试</title><button id="add" onclick="document.querySelector('#form').hidden=false">新增</button><div id="form" hidden>新增记录</div>`)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const previousFetch = globalThis.fetch
  globalThis.fetch = async () => { throw new Error('local run must not call Hub') }
  try {
    const runtime = new OfflineAutomation(root)
    const run = await runtime.run({ caseNo: 'LOCAL-DEMO', page: '/',
      steps: [{ type: 'click', locator: { by: 'css', value: '#add' } }],
      assertions: [{ by: 'css', value: '#form', expected: '新增记录' }],
    }, `http://127.0.0.1:${server.address().port}`, {}, true, '新增记录可填写')
    assert.equal(run.status, 'LOCAL_ONLY')
    assert.equal(run.result, 'PASS', run.failureReason)
    const html = await readFile(run.reportPath, 'utf8')
    assert.match(html, /本地 UI 自动化测试报告/)
    assert.match(html, /仅保存在本机，未写入 Hub/)
    assert.match(html, /data:image\/png;base64,/)
    assert.ok((await stat(run.evidence.tracePath)).size > 0)
    assert.equal(JSON.parse(await readFile(join(root, 'traces', String(run.sessionId), 'run.json'), 'utf8')).result, 'PASS')
  } finally {
    globalThis.fetch = previousFetch
    server.close()
    await rm(root, { recursive: true, force: true })
  }
})
