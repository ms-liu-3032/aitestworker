import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { writeLocalAutomationReport, writeLocalApiAutomationReport } from '../dist/report.js'

test('local report embeds capture screenshot and escapes untrusted text', async () => {
  const root = await mkdtemp(join(tmpdir(), 'aitest-report-'))
  try {
    const imageDir = join(root, 'traces', '7', 'screenshots')
    await mkdir(imageDir, { recursive: true })
    await writeFile(join(imageDir, 'stop.png'), Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9l4xAAAAAASUVORK5CYII=', 'base64'))
    const path = await writeLocalAutomationReport(root, { projectId: 1, assetId: 2,
      caseNo: 'TC-<script>', versionNo: 3, sessionId: 7, result: 'ASSERTION_FAILURE', durationMs: 1250,
      stepCount: 4, assertionCount: 1, failureReason: '<img src=x>', storageStatus: 'RECORDED', startedAt: Date.now() })
    const html = await readFile(path, 'utf8')
    assert.match(html, /data:image\/png;base64,/)
    assert.match(html, /class="gallery"/)
    assert.match(html, /点击展开大图/)
    assert.match(html, /@media print/)
    assert.doesNotMatch(html, /<script|<link/i)
    assert.match(html, /&lt;script&gt;/)
    assert.match(html, /&lt;img src=x&gt;/)
    assert.doesNotMatch(html, /<script>/)
    assert.equal((await stat(path)).mode & 0o777, 0o600)
  } finally { await rm(root, { recursive: true, force: true }) }
})

test('API report uses the same offline layout and redacts unsafe text', async () => {
  const root = await mkdtemp(join(tmpdir(), 'aitest-api-report-'))
  try {
    const path = await writeLocalApiAutomationReport(root, {
      projectId: 1, assetId: 2, caseNo: 'API-1', versionNo: 1,
      requestKey: '9cf2d14e-3a7b-4c0d-a8f9-91cafe52e118', result: 'PASS', durationMs: 800,
      storageStatus: 'RECORDED', startedAt: Date.now(),
      steps: [{ method: 'GET', path: '/items?name=<script>', statusCode: 200, durationMs: 40 }],
      assertions: [{ stepIndex: 0, target: 'STATUS', operator: 'EQUALS', passed: true }],
    })
    const html = await readFile(path, 'utf8')
    assert.match(html, /AITestWork 接口自动化测试报告/)
    assert.match(html, /linear-gradient/)
    assert.match(html, /&lt;script&gt;/)
    assert.doesNotMatch(html, /<script|<link/i)
    assert.equal((await stat(path)).mode & 0o777, 0o600)
  } finally { await rm(root, { recursive: true, force: true }) }
})
