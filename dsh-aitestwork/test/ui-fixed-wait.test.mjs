import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { chromium } from 'playwright-core'
import { runUiMapping } from '../dist/automation.js'
import { validateLocalMapping } from '../dist/local-design.js'

const require = createRequire(import.meta.url)
const { resolveBrowser, DEFAULT_CONFIG } = require('local-browser-worker')
const browserInfo = resolveBrowser(DEFAULT_CONFIG, process.platform)

const mapping = { caseNo: 'TC-WAIT', page: '/', steps: [
  { type: 'click', locator: { by: 'text', value: '重置' } },
  { type: 'wait', timeoutMs: 500 },
  { type: 'click', locator: { by: 'text', value: '检查' } },
], assertions: [{ by: 'text', value: '完成' }] }

test('fixed wait pauses between actions without requiring a locator', async () => {
  const calls = []
  const target = { waitFor: async () => {}, click: async () => calls.push('click'), count: async () => 1 }
  const page = { goto: async () => ({ status: () => 200 }), getByText: () => target,
    waitForTimeout: async ms => calls.push(`wait:${ms}`), url: () => 'https://example.test/' }
  validateLocalMapping(mapping)
  const result = await runUiMapping(page, mapping, new URL('https://example.test/'))
  assert.equal(result.result, 'PASS', result.failureReason)
  assert.deepEqual(calls, ['click', 'wait:500', 'click'])
})

test('fixed wait rejects invalid duration, locator and excessive cumulative delay', () => {
  const wait = mapping.steps[1]
  for (const invalid of [0, 99, 30_001, '500', 500.5]) {
    assert.throws(() => validateLocalMapping({ ...mapping, steps: [{ ...wait, timeoutMs: invalid }] }), /wait/)
  }
  assert.throws(() => validateLocalMapping({ ...mapping,
    steps: [{ ...wait, locator: { by: 'css', value: 'body' } }] }), /wait/)
  assert.throws(() => validateLocalMapping({ ...mapping,
    steps: [{ ...wait, timeoutMs: 30_000 }, { ...wait, timeoutMs: 30_000 }, wait] }), /60000/)
})

test('real Playwright wait allows a reset with no DOM status to settle', { skip: !browserInfo }, async () => {
  const server = createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><button id="reset" onclick="window.busy=true;setTimeout(()=>window.busy=false,180)">重置</button>
      <button id="check" onclick="if(!window.busy)output.textContent='完成'">检查</button><div id="output"></div>`)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const browser = await chromium.launch({ executablePath: browserInfo.executablePath, headless: true })
  try {
    const actual = { caseNo: 'TC-WAIT', page: '/', steps: [
      { type: 'click', locator: { by: 'css', value: '#reset' } },
      { type: 'wait', timeoutMs: 250 },
      { type: 'click', locator: { by: 'css', value: '#check' } },
    ], assertions: [{ by: 'css', value: '#output', expected: '完成' }] }
    validateLocalMapping(actual)
    const started = Date.now()
    const result = await runUiMapping(await browser.newPage(), actual,
      new URL(`http://127.0.0.1:${server.address().port}/`))
    assert.equal(result.result, 'PASS', result.failureReason)
    assert.ok(Date.now() - started >= 230)
  } finally {
    await browser.close()
    await new Promise(resolve => server.close(resolve))
  }
})
