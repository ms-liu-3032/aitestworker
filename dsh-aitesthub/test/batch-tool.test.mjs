import test from 'node:test'
import assert from 'node:assert/strict'
import { apply } from '../dist/index.js'

test('batch tool fetches requirement once and posts independent draft and mapping items', async () => {
  const tools = new Map()
  apply({
    tools: { register(tool) { tools.set(tool.name, tool) } },
    commands: { register() {} }, skills: { register() {} }, systemPrompt: { section() {} },
    credentials: { async resolve() { return { value: JSON.stringify({ serverUrl: 'https://hub.example',
      userToken: 'user', agentToken: 'agent', userId: 7, connectionId: 9 }) } } },
  })
  const requests = []
  const originalFetch = globalThis.fetch
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options })
    return new Response(JSON.stringify({ success: true, data: options.method === 'GET'
      ? { requirementText: '已确认需求' } : [{ index: 1, draftId: 41, assetId: 91 }] }),
      { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const value = await tools.get('aitest_save_case_batch').execute({ projectId: 3, workSessionId: 5,
      items: [{ draft: { idempotencyKey: 'draft-1', caseTitle: '独立用例', sourceRefs: [] },
        assetRequestKey: 'asset-1', assetType: 'UI', mapping: { page: '/' } }] })
    assert.equal(JSON.parse(value)[0].assetId, 91)
    assert.equal(requests.length, 2)
    assert.match(requests[1].url, /\/case-batch$/)
    const body = JSON.parse(requests[1].options.body)
    assert.equal(body.items[0].draft.requirementText, '已确认需求')
    assert.equal(body.items[0].draft.workSessionId, 5)
    assert.equal(body.items[0].assetRequestKey, 'asset-1')
  } finally { globalThis.fetch = originalFetch }
})
