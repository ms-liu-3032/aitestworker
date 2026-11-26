import assert from 'node:assert/strict'
import { test } from 'node:test'
import { apply } from '../dist/index.js'
import { designModeApproval, executionScope } from '../dist/rule-guard.js'

function harness() {
  const events = new Map()
  const commands = new Map()
  let credential
  apply({
    on(name, fn) { events.set(name, fn) },
    tools: { register() {} }, skills: { register() {} },
    systemPrompt: { section() {} },
    commands: { register(command) { commands.set(command.name, command) } },
    credentials: {
      async resolve() { return credential ? { value: credential } : undefined },
      async set(_key, value) { credential = value },
      async unset() { credential = undefined },
    },
  })
  return { events, commands }
}

test('TOM choice requires host approval on each preparation and rejects invalid modes', async () => {
  const { events } = harness()
  const gate = events.get('tools/pre-execute')
  const first = await gate({ name: 'aitestwork_prepare_case_design', arguments: { projectId: 2,
    tomMode: 'PROJECT_TOM' } }, async () => ({ kind: 'allow' }))
  assert.equal(first.kind, 'ask')
  assert.match(first.reason, /PROJECT_TOM/)
  assert.equal((await gate({ name: 'aitestwork_prepare_case_design', arguments: { projectId: 2,
    tomMode: 'BOGUS' } }, async () => ({ kind: 'allow' }))).kind, 'deny')
  assert.match(designModeApproval('aitestwork_prepare_case_design', { projectId: 2, tomMode: 'DIRECT' }), /DIRECT/)
})

test('execution approval is bound to project, target origin and current AITestWork connection', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async url => {
    if (String(url).endsWith('/api/aitestwork/dsh/bind')) return { ok: true, status: 200,
      json: async () => ({ success: true, data: { userToken: 'user', agentToken: 'agent', userId: 9, connectionId: 3 } }) }
    return { ok: true, status: 200, json: async () => ({ success: true, data: {} }) }
  }
  try {
    const { events, commands } = harness()
    const gate = events.get('tools/pre-execute')
    const execute = events.get('tools/execute')
    const args = { projectId: 2, assetId: 5, targetUrl: 'https://test.example/app' }
    assert.equal((await gate({ name: 'aitestwork_run_auto_asset', arguments: args }, async () => ({ kind: 'allow' }))).kind, 'deny')
    assert.equal((await commands.get('aitestwork-bind').handler({ rawInput: 'https://hub.example ABCDEFGHJKLM' })).kind, 'success')
    const requested = await gate({ name: 'aitestwork_run_auto_asset', arguments: args }, async () => ({ kind: 'allow' }))
    assert.equal(requested.kind, 'ask')
    assert.match(requested.reason, /test\.example/)
    await execute({ name: 'aitestwork_run_auto_asset', arguments: args, callId: '1', signal: { aborted: false } },
      async () => ({ isError: false }))
    assert.equal((await gate({ name: 'aitestwork_run_auto_asset', arguments: args }, async () => ({ kind: 'allow' }))).kind, 'allow')
    const originalNow = Date.now
    try {
      const future = originalNow() + 8 * 60 * 60_000 + 1
      Date.now = () => future
      assert.equal((await gate({ name: 'aitestwork_run_auto_asset', arguments: args },
        async () => ({ kind: 'allow' }))).kind, 'ask')
    } finally { Date.now = originalNow }
    assert.equal((await gate({ name: 'aitestwork_run_api_asset', arguments: { ...args, targetUrl: 'https://other.example' } },
      async () => ({ kind: 'allow' }))).kind, 'ask')
    assert.equal((await gate({ name: 'aitestwork_run_auto_asset', arguments: { ...args, projectId: 3 } },
      async () => ({ kind: 'allow' }))).kind, 'ask')
    await commands.get('aitestwork-unbind').handler()
    assert.equal((await gate({ name: 'aitestwork_run_auto_asset', arguments: args }, async () => ({ kind: 'allow' }))).kind, 'deny')
  } finally { globalThis.fetch = originalFetch }
})

test('scope parser rejects credentialed or malformed target before any approval', () => {
  for (const targetUrl of ['javascript:alert(1)', 'https://u:p@example.test/', 'not a url', 'https://example.test/#x']) {
    assert.throws(() => executionScope('aitestwork_capture_start', { projectId: 1, targetUrl }, 'user'))
  }
})
