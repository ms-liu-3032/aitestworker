import assert from 'node:assert/strict'
import { test } from 'node:test'
import { apply } from '../dist/index.js'
import { TOOL_BOUNDARIES } from '../dist/tool-boundaries.js'

test('every registered AITestWork tool has an explicit trust boundary and new names fail closed', async () => {
  const names = new Set()
  let guard
  let preExecute
  apply({
    tools: {
      register(tool) { names.add(tool.name) },
      guard(fn) { guard = fn },
    },
    commands: { register() {} }, skills: { register() {} },
    systemPrompt: { section() {} },
    on(event, fn) { if (event === 'tools/pre-execute') preExecute = fn },
    credentials: { async resolve() { return undefined } },
  })
  assert.deepEqual([...names].sort(), Object.keys(TOOL_BOUNDARIES).sort())
  assert.equal(guard({ name: 'aitestwork_run_auto_asset' }), undefined)
  assert.match(guard({ name: 'aitestwork_new_write_tool' }), /未纳入规则清单/)
  assert.equal((await preExecute({ name: 'aitestwork_new_write_tool' },
    async () => ({ kind: 'allow' }))).kind, 'deny')
  assert.equal(guard({ name: 'other_plugin_tool' }), undefined)
})
