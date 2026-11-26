import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { LocalCoverageStore } from '../dist/local-coverage.js'

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), 'aitest-coverage-'))
  const store = new LocalCoverageStore(root)
  const task = await store.start('https://target.example')
  return { root, store, task }
}

test('a happy-path run cannot close an unconfirmed or partly covered task', async () => {
  const { root, store, task } = await fixture()
  try {
    assert.equal((await store.get(task.id)).verdict.result, 'INVENTORY_UNCONFIRMED')
    for (const rule of task.rules) await store.decide(task.id, rule.code,
      rule.code === 'UI_EXPECTED_BEHAVIOR' ? 'APPLICABLE' : 'NOT_APPLICABLE', '本轮需求与页面范围已核对')
    const withItem = await store.addItem(task.id, 'UI_EXPECTED_BEHAVIOR', 'main', '/page', '主流程', '需求第 1 条')
    const itemId = withItem.items[0].id
    await store.confirm(task.id)
    assert.equal((await store.get(task.id)).verdict.result, 'INCOMPLETE')
    await assert.rejects(store.attach(task.id, itemId, 101), /run\.json|ENOENT/)
    const traceDir = join(root, 'traces', '101')
    await mkdir(traceDir, { recursive: true })
    await writeFile(join(traceDir, 'shot.png'), 'screenshot')
    await writeFile(join(traceDir, 'trace.zip'), 'trace')
    await writeFile(join(traceDir, 'run.json'), JSON.stringify({ sessionId: 101,
      targetOrigin: 'https://target.example', startedAt: Date.now(), status: 'LOCAL_ONLY',
      result: 'PASS', evidence: { screenshotPath: join(traceDir, 'shot.png'), tracePath: join(traceDir, 'trace.zip') },
      coverageProof: { page: '/page', ruleCode: 'UI_EXPECTED_BEHAVIOR', subjectKey: 'main',
        role: 'RULE', assertionState: 'visible' } }))
    assert.equal((await store.attach(task.id, itemId, 101)).verdict.result, 'PASS')
  } finally { await rm(root, { recursive: true, force: true }) }
})

test('missing applicable rule or negative-field evidence blocks completion', async () => {
  const { root, store, task } = await fixture()
  try {
    for (const rule of task.rules) await store.decide(task.id, rule.code,
      rule.code === 'UI_REQUIRED_FIELD' || rule.code === 'UI_EXPECTED_BEHAVIOR' ? 'APPLICABLE' : 'NOT_APPLICABLE',
      '页面和需求已核对')
    await store.addItem(task.id, 'UI_EXPECTED_BEHAVIOR', 'main', '/page', '主流程', '需求')
    await assert.rejects(store.confirm(task.id), /未核实完整/)
    const withField = await store.addItem(task.id, 'UI_REQUIRED_FIELD', 'phone', '/page', '手机号', '表单必填标记')
    await store.confirm(task.id)
    await assert.rejects(store.attach(task.id, withField.items[1].id, 101), /正向与单项留空/)
    assert.equal((await store.get(task.id)).verdict.result, 'INCOMPLETE')
  } finally { await rm(root, { recursive: true, force: true }) }
})
