import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { chromium } from 'playwright-core'
import { executeAutomationAsset } from '../dist/automation.js'

const require = createRequire(import.meta.url)
const { resolveBrowser, DEFAULT_CONFIG } = require('local-browser-worker')
const browserInfo = resolveBrowser(DEFAULT_CONFIG, process.platform)

test('real Playwright waits for visitor save before drawer submit', { skip: !browserInfo }, async () => {
  const server = createServer((request, response) => {
    if (request.url === '/visitor/saveOrUpdate' || request.url === '/whiteList/batchCheckBlacklist') {
      setTimeout(() => { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end('{"code":0}') }, 250)
      return
    }
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><meta charset="utf-8">
      <button id="open" onclick="drawer.style.display='block'">请添加访客</button>
      <button id="page-submit" onclick="result.textContent=window.saved?'操作成功':'请先保存当前添加的访客信息'">提 交</button>
      <div id="result"></div>
      <div id="drawer" style="display:none"><input placeholder="请输入访客姓名"><input id="phone" placeholder="请输入访客手机">
        <button onclick="fetch('/visitor/saveOrUpdate',{method:'POST'}).then(()=>window.confirmed=true)">确 定</button>
        <button onclick="if(window.confirmed&&document.getElementById('visitor').textContent)fetch('/whiteList/batchCheckBlacklist',{method:'POST'}).then(()=>{window.saved=true;drawer.style.display='none'})">提 交</button>
        <button onclick="drawer.style.display='none'">关闭</button>
        <div id="visitor"></div><div id="savedFlag" style="display:none">访客已保存</div>
      </div><script>phone.onkeydown=e=>{if(e.key==='Enter'&&window.confirmed) visitor.textContent='本次邀请访客'}</script>`)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const browser = await chromium.launch({ executablePath: browserInfo.executablePath, headless: true })
  const page = await browser.newPage()
  const calls = []
  const mapping = { caseNo: 'TC-001', page: '/', steps: [
    { type: 'click', locator: { by: 'role', value: 'button', name: '请添加访客' } },
    { type: 'fill', locator: { by: 'placeholder', value: '请输入访客姓名', within: { by: 'css', value: '#drawer' } }, data: '测试访客' },
    { type: 'fill', locator: { by: 'placeholder', value: '请输入访客手机', within: { by: 'css', value: '#drawer' } }, data: '${phone}' },
    { type: 'click', locator: { by: 'role', value: 'button', name: '确 定', within: { by: 'css', value: '#drawer' } },
      waitForResponse: { path: '/visitor/saveOrUpdate', method: 'POST', status: 200 } },
    { type: 'press', locator: { by: 'css', value: '#phone' }, data: 'Enter' },
    { type: 'waitFor', locator: { by: 'text', value: '本次邀请访客', within: { by: 'css', value: '#drawer' } }, state: 'visible' },
    { type: 'click', locator: { by: 'role', value: 'button', name: '提 交', within: { by: 'css', value: '#drawer' } },
      waitForResponse: { path: '/whiteList/batchCheckBlacklist', method: 'POST', status: 200 } },
    { type: 'waitFor', locator: { by: 'css', value: '#drawer' }, state: 'hidden' },
    { type: 'click', locator: { by: 'css', value: '#page-submit' } },
  ], assertions: [{ by: 'text', value: '操作成功' }] }
  const oldFetch = globalThis.fetch
  globalThis.fetch = async (url, options) => {
    calls.push(`${options.method}:${new URL(url).pathname}`)
    const data = url.endsWith('/automation-assets/6') ? { id: 6, assetType: 'UI', status: 'VERIFIED', reviewStatus: 'APPROVED', currentVersion: 1 }
      : url.endsWith('/versions/1') ? { versionNo: 1, status: 'VERIFIED', mapping } : { id: 1, result: 'PASS' }
    return { ok: true, status: 200, json: async () => ({ success: true, data }) }
  }
  try {
    const capture = { start: async () => ({ sessionId: 36 }), pageForAutomation: () => page,
      stop: async () => {}, writeLocalReport: async () => '/tmp/report-36.html' }
    const result = await executeAutomationAsset({ serverUrl: 'https://hub.example', agentToken: 'agent' }, capture,
      18, 6, `http://127.0.0.1:${server.address().port}`, { phone: 'test-phone' })
    assert.equal(result.result, 'PASS', result.failureReason)
    assert.equal(await page.locator('#result').textContent(), '操作成功')
    assert.ok(calls.some(call => call.endsWith('/runs')))
  } finally { globalThis.fetch = oldFetch; await browser.close(); server.close() }
})
