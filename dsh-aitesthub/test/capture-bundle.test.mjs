import assert from 'node:assert/strict'
import { test } from 'node:test'
import { randomBytes } from 'node:crypto'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { uploadCaptureBundle } from '../dist/capture-bundle.js'

test('capture archive resumes at the next 4 MiB chunk and reports verified summary', async () => {
  const root = await mkdtemp(join(tmpdir(), 'aitest-bundle-'))
  const screenshot = join(root, 'screen.png')
  await writeFile(screenshot, randomBytes(5 * 1024 * 1024))
  const result = { events: [{ screenshotPath: screenshot }], networks: [{ method: 'GET' }],
    videoPath: null, traceFilePath: null, screencastPath: null }
  const binding = { serverUrl: 'https://hub.example', agentToken: 'agent' }
  const previous = globalThis.fetch
  const chunks = []
  let fail = true
  globalThis.fetch = async (url, options) => {
    const path = String(url)
    if (path.includes('/chunks/')) {
      const index = Number(path.split('/').at(-1))
      chunks.push(index)
      if (index === 1 && fail) { fail = false; throw new Error('temporary disconnect') }
      return { ok: true, status: 200, json: async () => ({ success: true, data: null }) }
    }
    if (path.endsWith('/bundle/commit')) {
      const request = JSON.parse(options.body)
      assert.equal(request.summary.artifactCount, 1)
      assert.equal(request.summary.eventCount, 1)
      assert.equal(request.summary.networkCount, 1)
      return { ok: true, status: 200, json: async () => ({ success: true,
        data: { id: 12, sha256: request.sha256, sizeBytes: request.sizeBytes } }) }
    }
    throw new Error(path)
  }
  try {
    await assert.rejects(uploadCaptureBundle(binding, 2, 3, result, root), /temporary disconnect/)
    const saved = await uploadCaptureBundle(binding, 2, 3, result, root)
    assert.equal(saved.id, 12)
    assert.deepEqual(chunks, [0, 1, 1])
    assert.match(saved.sha256, /^[a-f0-9]{64}$/)
  } finally { globalThis.fetch = previous; await rm(root, { recursive: true, force: true }) }
})
