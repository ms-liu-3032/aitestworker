import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtemp, writeFile, rm, mkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { CaptureRuntime } from '../dist/capture.js'

test('active UI and passive recorder share a page; remote evidence upload can resume', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'aitest-capture-'))
  const screenshot = join(dir, 'screen.png')
  const trace = join(dir, 'trace.zip')
  const cast = join(dir, 'screencast')
  await mkdir(join(cast, 'frames'), { recursive: true })
  await writeFile(screenshot, 'png')
  await writeFile(trace, 'zip')
  const frameNames = Array.from({ length: 10 }, (_, index) => `${String(index).padStart(6, '0')}.jpg`)
  for (const filename of frameNames) await writeFile(join(cast, 'frames', filename), 'frame')
  await writeFile(join(cast, 'manifest.json'), JSON.stringify({ frames: frameNames.map(filename => ({ filename })) }))
  const calls = []
  let uploadNumber = 0
  let failOnce = true
  const previousFetch = globalThis.fetch
  globalThis.fetch = async (url, options) => {
    calls.push({ url: String(url), options })
    const respond = (data) => ({ ok: true, status: 200, json: async () => ({ success: true, data }) })
    if (String(url).endsWith('/captures') && options.method === 'POST') return respond({ sessionId: 31, groupId: 32, profileId: 33, targetUrl: 'https://example.test' })
    if (String(url).includes('/artifacts/')) {
      uploadNumber++
      if (failOnce && uploadNumber === 2) {
        failOnce = false
        throw new Error('network interrupted')
      }
      return respond({ id: uploadNumber, kind: String(url).split('/').at(-1) })
    }
    if (String(url).includes('/bundle/chunks/')) return respond(null)
    if (String(url).endsWith('/bundle/commit')) return respond({ id: 99, sha256: 'test' })
    if (String(url).endsWith('/finish')) return respond({ sessionId: 31, eventCount: 1, networkCount: 1, alreadyFinished: false })
    if (String(url).endsWith('/captures/31')) return respond({ status: 'RECORDING' })
    throw new Error(`unexpected URL: ${url}`)
  }
  let stopped = 0
  const closedProfiles = []
  const page = {
    url: () => 'https://example.test/page',
    title: async () => 'Example',
    locator: (selector) => ({
      first: () => ({
        click: async () => { calls.push({ action: `click:${selector}` }) },
        fill: async () => {},
        press: async key => { calls.push({ action: `press:${selector}:${key}` }) },
        selectOption: async value => { calls.push({ action: `select:${selector}:${value}` }) },
        check: async () => { calls.push({ action: `check:${selector}` }) },
        uncheck: async () => { calls.push({ action: `uncheck:${selector}` }) },
        innerText: async () => '页面内容',
        waitFor: async () => { calls.push({ action: `visible:${selector}` }) },
      }),
      innerText: async () => '页面内容',
    }),
  }
  const manager = {
    start: async () => ({ sessionId: 31, status: 'RECORDING', url: 'https://example.test' }),
    pageForSession: () => page,
    stop: async () => {
      stopped++
      return {
        events: [{ eventType: 'CLICK', pageUrl: page.url(), pageTitle: 'Example', elementText: '提交',
          elementRole: 'button', selector: 'button', normalizedLocator: 'button', sectionTitle: '', dialogTitle: '',
          objectLabel: '', valueSummary: '', screenshotPath: screenshot, happenedAtUtc: '2026-11-03T01:02:03.000Z',
          happenedAtLocal: '2026-11-03T09:02:03.000+08:00', timezone: 'Asia/Shanghai', relativeMs: 500 }],
        networks: [{ url: 'https://example.test/api', method: 'GET', statusCode: 200, durationMs: 12, failed: false,
          errorMessage: null, requestSummary: null, responseSummary: null,
          requestStartedAtUtc: '2026-11-03T01:02:03.000Z', requestStartedAtLocal: '2026-11-03T09:02:03.000+08:00',
          responseEndedAtUtc: null, responseEndedAtLocal: null, timezone: 'Asia/Shanghai', relativeMs: 500 }],
        videoPath: screenshot, traceFilePath: trace, screencastPath: join(cast, 'manifest.json'),
        screencastStartedAtUtc: '2026-11-03T01:02:02.000Z', screencastStoppedAtUtc: '2026-11-03T01:02:04.000Z',
        screencastDurationMs: 2000,
      }
    },
    shutdown: async () => {},
    closeProfileContext: async profileId => { closedProfiles.push(profileId) },
  }
  const binding = { serverUrl: 'https://hub.example', userToken: 'user', agentToken: 'agent', userId: 7, connectionId: 8 }
  const runtime = new CaptureRuntime(manager, join(dir, 'recovery'))
  try {
    const start = await runtime.start(binding, 2, 'https://example.test')
    assert.equal(start.sessionId, 31)
    assert.equal((await runtime.action(binding, 2, 31, 'click', 'button')).body, '页面内容')
    await runtime.action(binding, 2, 31, 'press', 'input', 'Tab')
    await runtime.action(binding, 2, 31, 'select', 'select', 'visitor')
    await runtime.action(binding, 2, 31, 'check', 'input[type=checkbox]')
    await runtime.action(binding, 2, 31, 'uncheck', 'input[type=checkbox]')
    await runtime.action(binding, 2, 31, 'assertText', 'body', '页面')
    await runtime.action(binding, 2, 31, 'assertVisible', 'button')
    await assert.rejects(runtime.action(binding, 2, 31, 'assertText', 'body', '不存在'), /文本断言失败/)
    assert.ok(calls.some(call => call.action === 'select:select:visitor'))
    await assert.rejects(runtime.stop(binding, 2, 31), /network interrupted/)
    assert.deepEqual(closedProfiles, [33], 'window closes before a failed evidence upload')
    assert.equal(runtime.windowClosed(31), true)
    const restarted = new CaptureRuntime(manager, join(dir, 'recovery'))
    const finished = await restarted.stop(binding, 2, 31)
    assert.equal(finished.eventCount, 1)
    assert.equal(finished.browserWindowClosed, undefined, 'a resumed upload does not claim a second window cleanup')
    assert.equal(finished.bundle.summary.artifactCount, 13)
    assert.equal(stopped, 1)
    assert.deepEqual(closedProfiles, [33], 'upload retry must not reopen or close another window')
    assert.equal(calls.filter(call => call.url?.endsWith('/artifacts/SCREENSHOT')).length, 1)
    const finish = calls.find(call => call.url?.endsWith('/finish'))
    assert.equal(JSON.parse(finish.options.body).events[0].event.happenedAtUtc, '2026-11-03T01:02:03')
    assert.equal(JSON.parse(finish.options.body).manifestArtifactId > 0, true)
    assert.equal(calls.filter(call => call.url?.endsWith('/artifacts/FRAME')).length, 10)
    const commit = calls.find(call => call.url?.endsWith('/bundle/commit'))
    assert.ok(commit)
    assert.equal(JSON.parse(commit.options.body).summary.eventCount, 1)
    assert.equal(JSON.parse(commit.options.body).summary.byKind.FRAME, 10)
    assert.ok(calls.some(call => call.url?.includes('/bundle/chunks/')))
    assert.equal(restarted.hasActive(), false)
  } finally {
    globalThis.fetch = previousFetch
    await rm(dir, { recursive: true, force: true })
  }
})
