import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { uploadEvidence } from '../dist/client.js'

test('HTML 413 from web proxy reports the upload limit instead of a JSON parser error', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'aitest-upload-'))
  const file = join(dir, 'screen.png')
  await writeFile(file, 'image bytes')
  const previous = globalThis.fetch
  globalThis.fetch = async () => ({ status: 413, ok: false,
    json: async () => { throw new SyntaxError('Unexpected token <') } })
  try {
    await assert.rejects(uploadEvidence({ serverUrl: 'https://hub.example', agentToken: 'agent' },
      '/api/dsh/tools/projects/18/captures/19/artifacts/SCREENSHOT', file), /HTTP 413.*client_max_body_size/)
  } finally {
    globalThis.fetch = previous
    await rm(dir, { recursive: true, force: true })
  }
})
