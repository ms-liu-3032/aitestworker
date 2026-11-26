import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { displayReport } from '../dist/report-display.js'

test('UI and API reports update one offline viewer without launching another browser', async () => {
  const root = await mkdtemp(join(tmpdir(), 'aitest-report-viewer-'))
  try {
    const ui = join(root, 'ui.html')
    const api = join(root, 'api.html')
    await writeFile(ui, '<h1>UI</h1>')
    await writeFile(api, '<h1>API</h1>')
    const first = displayReport({ reportPath: ui, result: 'PASS' }, 'Hub UI', root, false)
    const second = displayReport({ reportPath: api, result: 'ASSERTION_FAILURE' }, 'Hub API', root, false)
    assert.equal(first.reportViewerPath, second.reportViewerPath)
    const html = await readFile(second.reportViewerPath, 'utf8')
    assert.match(html, /Hub UI/)
    assert.match(html, /Hub API/)
    assert.match(html, /content="5"/)
    assert.match(html, /ui\.html/)
    assert.match(html, /api\.html/)
    assert.equal(displayReport({ reportPath: join(root, 'missing.html') }, 'Hub API', root, false).reportDisplayError,
      '本地报告文件不存在')
  } finally { await rm(root, { recursive: true, force: true }) }
})
