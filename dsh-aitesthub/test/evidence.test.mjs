import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { downloadCaptureEvidence } from '../dist/evidence.js'

test('downloads evidence with user authority and checks its content before writing', async () => {
  const root = await mkdtemp(join(tmpdir(), 'aitesthub-evidence-test-'))
  const originalFetch = globalThis.fetch
  const bytes = Buffer.from('frame bytes')
  const hash = createHash('sha256').update(bytes).digest('hex')
  const binding = { serverUrl: 'https://hub.example', userToken: 'user-secret', agentToken: 'agent-secret', userId: 1, connectionId: 2 }
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url: String(url), authorization: options.headers.Authorization })
    if (String(url).endsWith('/artifacts')) return {
      ok: true, json: async () => ({ success: true, data: [{ id: 3, kind: 'FRAME', fileName: '000000.jpg', sizeBytes: bytes.length, sha256: hash }] }),
    }
    return { ok: true, arrayBuffer: async () => bytes }
  }
  try {
    const result = await downloadCaptureEvidence(binding, 4, 5, root)
    assert.equal(result.frames, 1)
    assert.equal((await readFile(join(result.directory, 'frames', '000000.jpg'))).toString(), 'frame bytes')
    assert.ok((await readFile(result.viewer, 'utf8')).includes('frames/000000.jpg'))
    assert.ok(calls.every(call => call.authorization === 'Bearer user-secret'))
    globalThis.fetch = async (url, options) => {
      if (String(url).endsWith('/artifacts')) return {
        ok: true, json: async () => ({ success: true, data: [{ id: 3, kind: 'FRAME', fileName: '000000.jpg', sizeBytes: bytes.length, sha256: '0'.repeat(64) }] }),
      }
      return { ok: true, arrayBuffer: async () => bytes }
    }
    await assert.rejects(downloadCaptureEvidence(binding, 4, 6, root), /校验失败/)
  } finally {
    globalThis.fetch = originalFetch
    await rm(root, { recursive: true, force: true })
  }
})
