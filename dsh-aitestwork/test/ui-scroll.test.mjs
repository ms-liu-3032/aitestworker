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

test('scroll sends wheel to the picker column and waits for observable selection', { skip: !browserInfo }, async () => {
  const server = createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><div class="am-picker-col" style="height:180px;width:240px;overflow:auto">日期滚轮</div>
      <div id="selected">选中 0</div><script>
      let count=0;document.querySelector('.am-picker-col').addEventListener('wheel',event=>{
        count+=event.deltaY>0?1:-1;document.getElementById('selected').textContent='选中 '+count
      })</script>`)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const browser = await chromium.launch({ executablePath: browserInfo.executablePath, headless: true })
  try {
    const origin = `http://127.0.0.1:${server.address().port}`
    const mapping = { caseNo: 'UI-SCROLL', page: '/', steps: [{ type: 'scroll',
      locator: { by: 'css', value: '.am-picker-col' }, deltaY: 120, repeat: 2 }],
      assertions: [{ by: 'css', value: '#selected', expected: '选中 2' }] }
    validateLocalMapping(mapping)
    const result = await runUiMapping(await browser.newPage(), mapping, new URL(origin))
    assert.equal(result.result, 'PASS', result.failureReason)
    assert.throws(() => validateLocalMapping({ ...mapping,
      steps: [{ ...mapping.steps[0], deltaY: 0 }] }), /滚动参数无效/)
  } finally {
    await browser.close()
    await new Promise(resolve => server.close(resolve))
  }
})
