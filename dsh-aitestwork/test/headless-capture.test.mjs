import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { mkdtemp, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const require = createRequire(import.meta.url)
const { CaptureManager, resolveBrowser, DEFAULT_CONFIG } = require('local-browser-worker')
const browserInfo = resolveBrowser(DEFAULT_CONFIG, process.platform)

test('headless capture retains page, trace and screenshot evidence', { skip: !browserInfo }, async () => {
  const root = await mkdtemp(join(tmpdir(), 'aitest-headless-'))
  const server = createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><title>headless fixture</title><button id="save">保存</button>')
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const manager = new CaptureManager(browserInfo, process.platform, root)
  try {
    const targetHost = `http://127.0.0.1:${server.address().port}`
    await manager.start({ traceGroupId: 1, sessionId: 901, profileId: 901, targetHost, headless: true })
    const page = manager.pageForSession(901)
    await page.locator('#save').click()
    const result = await manager.stop(901)
    assert.equal(result.status, 'STOPPED')
    assert.ok((await stat(result.traceFilePath)).size > 0)
    assert.ok((await stat(result.videoPath)).size > 0)
    assert.ok(result.events.length >= 2)
  } finally {
    await manager.shutdown()
    server.close()
    await rm(root, { recursive: true, force: true })
  }
})
