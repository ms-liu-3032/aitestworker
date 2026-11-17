import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { writeLocalAutomationReport } from '../dist/report.js'

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
    assert.match(html, /&lt;script&gt;/)
    assert.match(html, /&lt;img src=x&gt;/)
    assert.doesNotMatch(html, /<script>/)
    assert.equal((await stat(path)).mode & 0o777, 0o600)
  } finally { await rm(root, { recursive: true, force: true }) }
})
