import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { AdminLogRuntime } from '../dist/admin-logs.js'

async function fixture(t, initial = 'old request-123 SUCCESS\n') {
  const root = await mkdtemp(join(tmpdir(), 'admin-logs-'))
  const state = { body: initial, status: 206, truncated: false, calls: [], closed: false }
  const transport = { async get(path, requestedRange) {
    state.calls.push({ path, requestedRange })
    if (path === '/instances') return { status: state.status === 401 ? 401 : 200,
      contentType: 'application/json', range: '', truncated: false,
      text: JSON.stringify([{ id: 'abc123', registration: { name: 'auth', metadata: { password: 'DO_NOT_EXPOSE' } },
        endpoints: [{ id: 'logfile', url: 'http://private/' }], statusInfo: { status: 'UP' } }]) }
    const all = Buffer.from(state.body)
    const suffix = /^bytes=-(\d+)$/.exec(requestedRange)
    const explicit = /^bytes=(\d+)-(\d+)$/.exec(requestedRange)
    if (!all.length) return { status: 416, range: 'bytes */0', contentType: 'text/plain', text: '', truncated: false }
    const start = suffix ? Math.max(0, all.length - Number(suffix[1])) : Number(explicit[1])
    const end = suffix ? all.length - 1 : Math.min(all.length - 1, Number(explicit[2]))
    const bytes = all.subarray(start, end + 1)
    return { status: state.status, contentType: 'text/plain;charset=UTF-8', range: `bytes ${start}-${end}/${all.length}`,
      text: bytes.toString('utf8'), bytesBase64: bytes.toString('base64'), truncated: state.truncated }
  }, async close() { state.closed = true } }
  const runtime = new AdminLogRuntime(transport, root)
  t.after(async () => { await runtime.close(); await rm(root, { recursive: true, force: true }) })
  return { state, runtime }
}

test('instance list exposes safe fields only; authentication stays manual', async t => {
  const { state, runtime } = await fixture(t)
  assert.equal((await runtime.connect()).status, 'CONNECTED')
  const rows = await runtime.instances()
  assert.deepEqual(rows, [{ instanceId: 'abc123', serviceName: 'auth', status: 'UP', logfileAvailable: true }])
  assert.doesNotMatch(JSON.stringify(rows), /password|DO_NOT_EXPOSE|private/)
  state.status = 401
  assert.equal((await runtime.connect()).status, 'LOGIN_REQUIRED')
})

test('historical success cannot pass; fresh correlated line passes and writes protected local evidence', async t => {
  const { state, runtime } = await fixture(t)
  const first = await runtime.checkpoint('abc123')
  assert.equal((await runtime.assertContains(first.checkpointId, 'request-123', 'SUCCESS', 0)).result, 'FAIL')
  const second = await runtime.checkpoint('abc123')
  state.body += 'request-999 SUCCESS\nrequest-123 SUCCESS password=secret-value "token":"json-secret-value"\n'
  const result = await runtime.assertContains(second.checkpointId, 'request-123', 'SUCCESS', 0)
  assert.equal(result.result, 'PASS')
  assert.equal(result.storage, 'LOCAL_ONLY')
  const saved = await readFile(result.evidencePath, 'utf8')
  assert.doesNotMatch(saved, /secret-value|json-secret-value/)
  assert.match(saved, /REDACTED/)
  assert.equal((await stat(result.evidencePath)).mode & 0o777, 0o600)
  await assert.rejects(runtime.assertContains(second.checkpointId, 'request-123', 'SUCCESS', 0), /CHECKPOINT_NOT_FOUND/)
})

test('values must occur on the same complete new line', async t => {
  const { state, runtime } = await fixture(t)
  const { checkpointId } = await runtime.checkpoint('abc123')
  state.body += 'request-123 accepted\nrequest-999 SUCCESS\nrequest-123 SUCCESS'
  const result = await runtime.assertContains(checkpointId, 'request-123', 'SUCCESS', 0)
  assert.equal(result.result, 'FAIL')
})

test('UTF8 byte ranges preserve anchors even when suffix begins mid-character', async t => {
  const { state, runtime } = await fixture(t, '中'.repeat(800) + '\n')
  const { checkpointId } = await runtime.checkpoint('abc123')
  state.body += '业务ID-123 处理成功\n'
  assert.equal((await runtime.assertContains(checkpointId, '业务ID-123', '处理成功', 0)).result, 'PASS')
})

test('rotation, unauthorized, partial reads and oversized windows are inconclusive', async t => {
  for (const mode of ['rotation', 'unauthorized', 'partial', 'large']) {
    const { state, runtime } = await fixture(t)
    const { checkpointId } = await runtime.checkpoint('abc123')
    if (mode === 'rotation') state.body = 'request-123 SUCCESS\n'
    if (mode === 'unauthorized') state.status = 401
    if (mode === 'partial') state.truncated = true
    if (mode === 'large') state.body += 'x'.repeat(270000) + '\nrequest-123 SUCCESS\n'
    const result = await runtime.assertContains(checkpointId, 'request-123', 'SUCCESS', 0)
    assert.equal(result.result, 'INCONCLUSIVE', mode)
  }
})

test('unsupported Range and unsafe instance inputs are rejected before an assertion', async t => {
  const { state, runtime } = await fixture(t)
  await assert.rejects(runtime.checkpoint('../actuator/env'), /INVALID_INSTANCE/)
  assert.equal(state.calls.length, 0)
  state.status = 200
  await assert.rejects(runtime.checkpoint('abc123'), /RANGE_UNSUPPORTED/)
})

test('bounded async wait observes delayed events without rebuilding a checkpoint', async t => {
  const { state, runtime } = await fixture(t)
  const { checkpointId } = await runtime.checkpoint('abc123')
  const timer = setTimeout(() => { state.body += 'request-123 SUCCESS\n' }, 30)
  t.after(() => clearTimeout(timer))
  assert.equal((await runtime.assertContains(checkpointId, 'request-123', 'SUCCESS', 1000)).result, 'PASS')
})


test('trace ID prefixes cannot match another request and blank expectations are rejected', async t => {
  const { state, runtime } = await fixture(t)
  const checkpoint = await runtime.checkpoint('abc123')
  await assert.rejects(runtime.assertContains(checkpoint.checkpointId, 'request-123', '   ', 0), /INVALID_ASSERTION/)
  state.body += 'request-1234 SUCCESS\n'
  assert.equal((await runtime.assertContains(checkpoint.checkpointId, 'request-123', 'SUCCESS', 0)).result, 'FAIL')
})
