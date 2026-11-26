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

test('drag moves a picker column with pointer events and verifies selection', { skip: !browserInfo }, async () => {
  const server = createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><style>.picker-column{height:180px;width:240px;touch-action:none;user-select:none}</style>
      <div class="picker-column">日期滚轮</div><div id="selected">选中 0</div><script>
      const picker=document.querySelector('.picker-column');let startY=null;
      picker.addEventListener('pointerdown',event=>{startY=event.clientY;picker.setPointerCapture(event.pointerId)});
      picker.addEventListener('pointerup',event=>{if(startY!==null && startY-event.clientY>50)
        document.querySelector('#selected').textContent='选中 1';startY=null});
      </script>`)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const browser = await chromium.launch({ executablePath: browserInfo.executablePath, headless: true })
  try {
    const origin = `http://127.0.0.1:${server.address().port}`
    const step = { type: 'drag', locator: { by: 'css', value: '.picker-column' }, deltaY: -120, dragSteps: 12 }
    const mapping = { caseNo: 'UI-DRAG', page: '/', steps: [step],
      assertions: [{ by: 'css', value: '#selected', expected: '选中 1' }] }
    validateLocalMapping(mapping)
    const result = await runUiMapping(await browser.newPage(), mapping, new URL(origin))
    assert.equal(result.result, 'PASS', result.failureReason)
    assert.throws(() => validateLocalMapping({ ...mapping, steps: [{ ...step, deltaY: 0 }] }), /拖拽参数无效/)
    assert.throws(() => validateLocalMapping({ ...mapping, steps: [{ ...step, dragSteps: 51 }] }), /拖拽参数无效/)
  } finally {
    await browser.close()
    await new Promise(resolve => server.close(resolve))
  }
})
