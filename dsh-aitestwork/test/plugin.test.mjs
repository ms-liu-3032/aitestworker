import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { apply } from '../dist/index.js'
import { parseServerUrl } from '../dist/client.js'
import { applyMappingEdits } from '../dist/asset-reuse.js'

const previousFetch = globalThis.fetch
const validBindCode = 'ABCDEFGHJKLM'
afterEach(() => { globalThis.fetch = previousFetch })

function harness() {
  const tools = new Map()
  const commands = new Map()
  const skills = new Map()
  const promptSections = new Map()
  const events = new Map()
  let secret
  const ctx = {
    tools: { register(tool) { tools.set(tool.name, tool) } },
    commands: { register(command) { commands.set(command.name, command) } },
    skills: { register(skill) { skills.set(skill.name, skill) } },
    systemPrompt: { section(section) { promptSections.set(section.name, section) } },
    on(event, listener) { events.set(event, listener) },
    credentials: {
      async resolve() { return secret ? { value: secret, source: 'test' } : undefined },
      async set(_ref, value) { secret = value },
      async unset() { secret = undefined },
    },
  }
  apply(ctx)
  return { tools, commands, skills, events, promptSections }
}

test('full batch automation guidance requires orchestration and complete ID reconciliation', () => {
  const { promptSections, skills } = harness()
  assert.match(promptSections.get('aitestwork:complete-batch-automation').text, /workflow/)
  assert.match(promptSections.get('aitestwork:complete-batch-automation').text, /核对完成项、失败项及证据/)
  assert.ok(['local-test-mode', 'case-design-preflight', 'complete-batch-automation',
    'reuse-before-ui-automation'].reduce((sum, name) =>
    sum + promptSections.get(`aitestwork:${name}`).text.length, 0) < 600)
  const content = skills.get('aitestwork').content
  assert.match(content, /expectedIds/)
  assert.match(content, /got < expected/)
  assert.match(content, /同一采集会话.*串行/)
  assert.match(content, /自动续轮组件已启用/)
  assert.match(content, /failureStepIndex.*failureReason.*reportPath/)
  assert.match(content, /0 个成员启动/)
  assert.match(content, /只续做未确认的动作/)
})

test('rule coverage uses a server verdict and host-reviewed user confirmation', async () => {
  const { tools, commands, events } = harness()
  assert.equal(tools.has('aitestwork_start_ui_coverage'), true)
  assert.equal(tools.has('aitestwork_decide_ui_rule'), true)
  assert.equal(tools.has('aitestwork_add_ui_rule_check'), true)
  assert.equal(tools.has('aitestwork_attach_ui_rule_runs'), true)
  assert.equal(tools.has('aitestwork_get_ui_coverage'), true)
  assert.equal(tools.has('aitestwork_confirm_ui_coverage'), true)
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ path: new URL(url).pathname, method: options.method, body: options.body })
    const data = url.endsWith('/api/aitestwork/dsh/bind')
      ? { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 }
      : { taskId: 10, taskKey: 'regression-1', targetOrigin: 'https://target.example',
        result: 'INVENTORY_UNCONFIRMED', rules: [{ ruleCode: 'UI_REQUIRED_FIELD',
          applicability: 'APPLICABLE', reason: '页面必填' }],
        obligations: [{ ruleCode: 'UI_REQUIRED_FIELD', fieldLabel: '手机号', sourceBasis: '需求' }] }
    return { ok: true, status: 200, json: async () => ({ success: true, data }) }
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  await tools.get('aitestwork_start_ui_coverage').execute({ projectId: 18, taskKey: 'regression-1',
    targetOrigin: 'https://target.example' })
  await tools.get('aitestwork_get_ui_coverage').execute({ projectId: 18, taskId: 10 })
  const approval = await events.get('tools/pre-execute')({ name: 'aitestwork_confirm_ui_coverage',
    arguments: { projectId: 18, taskId: 10 } }, async () => ({ kind: 'continue' }))
  assert.equal(approval.kind, 'ask')
  assert.match(approval.reason, /手机号/)
  assert.deepEqual(calls.slice(-3).map(call => [call.method, call.path]), [
    ['POST', '/api/aitestwork/dsh/tools/projects/18/ui-coverage'],
    ['GET', '/api/aitestwork/dsh/tools/projects/18/ui-coverage/10'],
    ['GET', '/api/aitestwork/dsh/tools/projects/18/ui-coverage/10'],
  ])
})

test('changed UI requirement finds the existing mapping before a new script is designed', async () => {
  const { tools, commands, promptSections, skills } = harness()
  assert.match(promptSections.get('aitestwork:reuse-before-ui-automation').text, /aitestwork_find_auto_asset_candidates/)
  assert.match(skills.get('aitestwork').content, /业务语义修订，不是定位失效/)
  const requested = []
  globalThis.fetch = async (url) => {
    const path = new URL(url).pathname
    requested.push(path)
    let data
    if (path === '/api/aitestwork/dsh/bind') data = { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 }
    else if (path === '/api/aitestwork/dsh/tools/projects/2/automation-assets') data = [
      { id: 21, caseKind: 'DRAFT', caseId: 12, caseNo: 'DSH-12', assetType: 'UI',
        status: 'VERIFIED', currentVersion: 3, latestVersion: 3, reviewStatus: 'APPROVED' },
      { id: 22, caseKind: 'DRAFT', caseId: 13, caseNo: 'DSH-13', assetType: 'API',
        status: 'VERIFIED', currentVersion: 1, latestVersion: 1 },
    ]
    else if (path === '/api/aitestwork/dsh/user/projects/2/drafts') data = [
      { id: 12, caseNo: 'DSH-12', caseTitle: '手机号必填时阻止提交', moduleName: '/邀请/新增' },
      { id: 13, caseNo: 'DSH-13', caseTitle: '其他接口', moduleName: '/接口' },
    ]
    else throw new Error(`unexpected request ${path}`)
    return { ok: true, status: 200, json: async () => ({ success: true, data }) }
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  const found = JSON.parse(await tools.get('aitestwork_find_auto_asset_candidates').execute({
    projectId: 2, change: '原来手机号必填，改成手机号或邮箱至少填写一个',
  }))
  assert.equal(found.candidates[0].assetId, 21)
  assert.equal(found.candidates[0].currentVersion, 3)
  assert.equal(found.candidates[0].caseTitle, '手机号必填时阻止提交')
  assert.equal(found.candidates.length, 1)
  assert.deepEqual(requested, ['/api/aitestwork/dsh/bind', '/api/aitestwork/dsh/tools/projects/2/automation-assets',
    '/api/aitestwork/dsh/user/projects/2/drafts'])
})

test('UI reuse search also finds titled formal cases without exposing full case text', async () => {
  const { tools, commands } = harness()
  globalThis.fetch = async (url) => {
    const path = new URL(url).pathname
    let data
    if (path === '/api/aitestwork/dsh/bind') data = { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 }
    else if (path === '/api/aitestwork/dsh/tools/projects/2/automation-assets') data = [
      { id: 30, caseKind: 'FORMAL', caseId: 40, caseNo: 'TC-40', assetType: 'UI',
        status: 'VERIFIED', currentVersion: 2, latestVersion: 2, reviewStatus: 'APPROVED' },
    ]
    else if (path === '/api/aitestwork/dsh/user/projects/2/drafts') data = []
    else if (path === '/api/aitestwork/dsh/tools/projects/2/cases') data = [
      { id: 40, caseNo: 'TC-40', caseTitle: '邮箱输入后提交申请', moduleName: '/申请/新增',
        steps: '敏感的完整历史步骤不应出现在候选列表' },
    ]
    else throw new Error(`unexpected request ${path}`)
    return { ok: true, status: 200, json: async () => ({ success: true, data }) }
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  const found = JSON.parse(await tools.get('aitestwork_find_auto_asset_candidates').execute({
    projectId: 2, change: '邮箱提交条件改变',
  }))
  assert.equal(found.candidates[0].assetId, 30)
  assert.equal(found.candidates[0].caseKind, 'FORMAL')
  assert.equal(JSON.stringify(found).includes('敏感的完整历史步骤'), false)
})

test('UI asset copy preserves unaffected steps and creates a revision from small edits', async () => {
  const { tools, commands } = harness()
  const original = { caseNo: 'DSH-12', page: '/request/new', steps: [
    { type: 'click', locator: { by: 'text', value: '新增' } },
    { type: 'fill', locator: { by: 'label', value: '手机号' }, data: '${phone}' },
    { type: 'click', locator: { by: 'text', value: '提交' } },
  ], assertions: [{ by: 'text', value: '手机号不能为空' }] }
  let saved
  let savedNew
  globalThis.fetch = async (url, options = {}) => {
    const path = new URL(url).pathname
    let data
    if (path === '/api/aitestwork/dsh/bind') data = { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 }
    else if (path === '/api/aitestwork/dsh/tools/projects/2/automation-assets/21') data = {
      id: 21, caseKind: 'DRAFT', caseId: 12, caseNo: 'DSH-12', assetType: 'UI', currentVersion: 3,
    }
    else if (path === '/api/aitestwork/dsh/tools/projects/2/automation-assets/21/versions/3') data = {
      versionNo: 3, status: 'VERIFIED', mapping: original,
    }
    else if (path === '/api/aitestwork/dsh/tools/projects/2/automation-assets/21/versions') {
      saved = JSON.parse(options.body)
      data = { assetId: 21, versionNo: 4, status: 'DRAFT' }
    } else if (path === '/api/aitestwork/dsh/tools/projects/2/automation-assets') {
      savedNew = JSON.parse(options.body)
      data = { id: 31, currentVersion: 1, status: 'DRAFT' }
    } else throw new Error(`unexpected request ${path}`)
    return { ok: true, status: 200, json: async () => ({ success: true, data }) }
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  const result = JSON.parse(await tools.get('aitestwork_copy_ui_asset_version').execute({
    projectId: 2, sourceAssetId: 21, sourceVersionNo: 3, mode: 'REVISE', expectedVersion: 3,
    requestKey: 'change-1', changeReason: '需求改为手机或邮箱至少填写一项', edits: [
      { operation: 'insert', section: 'steps', index: 2,
        value: { type: 'fill', locator: { by: 'label', value: '邮箱' }, data: '${email}' } },
      { operation: 'replace', section: 'assertions', index: 0,
        value: { by: 'text', value: '手机号或邮箱至少填写一项' } },
    ],
  }))
  assert.equal(result.saved.versionNo, 4)
  assert.equal(saved.mapping.steps.length, 4)
  assert.deepEqual(saved.mapping.steps[0], original.steps[0])
  assert.deepEqual(saved.mapping.steps[3], original.steps[2])
  assert.equal(saved.mapping.assertions[0].value, '手机号或邮箱至少填写一项')
  assert.equal(original.steps.length, 3)
  const newCase = JSON.parse(await tools.get('aitestwork_copy_ui_asset_version').execute({
    projectId: 2, sourceAssetId: 21, sourceVersionNo: 3, mode: 'NEW_CASE',
    targetCaseKind: 'DRAFT', targetCaseId: 30, targetCaseNo: 'DSH-30', workSessionId: 8,
    requestKey: 'change-2', changeReason: '新增邮箱单独满足条件的独立用例', edits: [
      { operation: 'replace', section: 'steps', index: 1,
        value: { type: 'fill', locator: { by: 'label', value: '邮箱' }, data: '${email}' } },
    ],
  }))
  assert.equal(newCase.saved.id, 31)
  assert.equal(savedNew.mapping.caseNo, 'DSH-30')
  assert.deepEqual(savedNew.mapping.steps[0], original.steps[0])
  assert.throws(() => applyMappingEdits(original, [
    { operation: 'remove', section: 'steps', index: 9 },
  ], 'DSH-12'), /序号无效/)
})

test('AITestWork case design requires a live rule and context preflight before work starts', async () => {
  const { tools, commands, promptSections } = harness()
  assert.match(promptSections.get('aitestwork:case-design-preflight').text, /aitestwork_prepare_case_design/)
  let workStarted = false
  globalThis.fetch = async (url) => {
    if (url.endsWith('/api/aitestwork/dsh/bind')) return { ok: true, status: 200, json: async () => ({ success: true,
      data: { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/work-sessions')) { workStarted = true; throw new Error('unexpected work start') }
    throw new Error('backend unavailable')
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  await assert.rejects(tools.get('aitestwork_start_work').execute({ projectId: 2,
    requirementText: '访客审批', tomMode: 'PROJECT_TOM' }), /aitestwork_prepare_case_design/)
  await assert.rejects(tools.get('aitestwork_prepare_case_design').execute({ projectId: 2,
    requirementText: '访客审批', tomMode: 'PROJECT_TOM' }), /backend unavailable/)
  await assert.rejects(tools.get('aitestwork_start_work').execute({ projectId: 2,
    requirementText: '访客审批', tomMode: 'PROJECT_TOM' }), /aitestwork_prepare_case_design/)
  assert.equal(workStarted, false)
})

test('supplemental context always uses the initial mode saved in its work session', async () => {
  const { tools, commands } = harness()
  const modes = new Map([[11, 'DIRECT'], [12, 'PROJECT_TOM'], [13, 'PROJECT_AND_SYSTEM_TOM']])
  const designModes = []
  const tomModes = []
  globalThis.fetch = async (url, options = {}) => {
    const parsed = new URL(url)
    const path = parsed.pathname
    let data = {}
    if (path === '/api/aitestwork/dsh/bind') data = { userToken: 'user', agentToken: 'agent', userId: 9, connectionId: 3 }
    else if (/\/work-sessions\/\d+$/.test(path)) data = { tomMode: modes.get(Number(path.split('/').at(-1))) }
    else if (path.endsWith('/design-context')) {
      designModes.push(JSON.parse(options.body).tomMode)
      data = { tomMode: designModes.at(-1) }
    } else if (path.endsWith('/tom')) {
      tomModes.push(parsed.searchParams.get('mode'))
      data = []
    }
    return { ok: true, status: 200, json: async () => ({ success: true, data }) }
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  for (const [workSessionId, expectedMode] of modes) {
    await tools.get('aitestwork_get_design_context').execute({ projectId: 2, workSessionId,
      requirementText: '访客补查', tomMode: 'DIRECT' })
    await tools.get('aitestwork_get_tom_context').execute({ projectId: 2, workSessionId,
      tomMode: 'DIRECT' })
    assert.equal(designModes.at(-1), expectedMode)
    assert.equal(tomModes.at(-1), expectedMode)
  }
  await assert.rejects(tools.get('aitestwork_get_design_context').execute({ projectId: 2,
    requirementText: '访客补查' }), /workSessionId|请先创建本轮测试任务/)
  await assert.rejects(tools.get('aitestwork_get_tom_context').execute({ projectId: 2 }),
    /workSessionId|请先创建本轮测试任务/)
})

test('UI run tool visibly presents its local screenshot report path', () => {
  const { tools } = harness()
  const render = tools.get('aitestwork_run_auto_asset').output.render
  const [visible] = render({}, JSON.stringify({ status: 'RECORDED', result: 'PASS',
    reportPath: '/tmp/traces/58/report.html' }))
  assert.match(visible.text, /^执行结果：单条用例通过；整轮规则覆盖须查看任务判定\n回写状态：已回写 AITestWork\n本地测试报告：\/tmp\/traces\/58\/report\.html/)
  const [failed] = render({}, JSON.stringify({ status: 'RECORDED', reportError: '磁盘写入失败' }))
  assert.match(failed.text, /本地测试报告生成失败：磁盘写入失败/)
  const apiRender = tools.get('aitestwork_run_api_asset').output.render
  const [pending] = apiRender({}, JSON.stringify({ status: 'EVIDENCE_PENDING', result: 'PASS',
    reportPath: '/tmp/reports/api/18/5/run.html' }))
  assert.match(pending.text, /执行结果：单条用例通过；整轮规则覆盖须查看任务判定\n回写状态：证据待上传，运行尚未入库/)
  assert.match(pending.text, /本地测试报告：\/tmp\/reports\/api\/18\/5\/run\.html/)
})

test('case writing guidance stays reusable across businesses', () => {
  const { skills } = harness()
  const content = skills.get('aitestwork').content
  assert.match(content, /跨业务用例写作与交付检查/)
  assert.match(content, /一步一项可执行动作/)
  assert.match(content, /每一步配同序号的一项预期/)
  assert.match(content, /依目标模板导出/)
  assert.doesNotMatch(content, /寒武纪|访客|邀请|一体机|1914533709912|Underway/)
})

test('case design refuses to proceed when the Wiki inventory is unavailable', async () => {
  const { tools, commands } = harness()
  globalThis.fetch = async (url) => {
    const respond = data => ({ ok: true, status: 200, json: async () => ({ success: true, data }) })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return respond({ userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 })
    if (url.endsWith('/design-context')) return respond({ project: { id: 2 }, tomNodes: [], tomRelations: [], wikiLinked: [], formalCases: [], wikiSearchFallback: {} })
    if (url.includes('/context?')) return respond({ project: { id: 2 }, signals: [] })
    if (url.endsWith('/design-rules')) return respond([{ pointType: 'MAIN_FLOW' }])
    if (url.endsWith('/case-writing-standard')) return respond({ columns: ['用例名称'] })
    if (url.includes('/tom?')) return respond([])
    if (url.includes('/cases?')) return respond([])
    if (url.includes('/historical-style-examples?')) return respond([])
    if (url.endsWith('/preference')) return respond({})
    if (url.endsWith('/assets/search')) return respond([])
    if (url.includes('/wiki/context?')) return respond({})
    throw new Error(`unexpected URL ${url}`)
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  await assert.rejects(tools.get('aitestwork_prepare_case_design').execute({ projectId: 2,
    requirementText: '访客审批', tomMode: 'PROJECT_TOM' }), /Wiki 正文\/概况/)
  await assert.rejects(tools.get('aitestwork_start_work').execute({ projectId: 2,
    requirementText: '访客审批', tomMode: 'PROJECT_TOM' }), /aitestwork_prepare_case_design/)
})

test('Wiki and formal case detail tools return full text through bound project routes', async () => {
  const { tools, commands } = harness()
  const requested = []
  globalThis.fetch = async url => {
    requested.push(url)
    const respond = data => ({ ok: true, status: 200, json: async () => ({ success: true, data }) })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return respond({ userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 })
    if (url.endsWith('/projects/2/wiki/entries/7')) return respond({ entryId: 7, content: '完整规则'.repeat(300) })
    if (url.endsWith('/projects/2/cases/11')) return respond({ id: 11, steps: '1. 打开邀请记录', expectedResult: '1. 显示邀请记录列表' })
    throw new Error(`unexpected URL ${url}`)
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  const wiki = JSON.parse(await tools.get('aitestwork_get_wiki_entry').execute({ projectId: 2, entryId: 7 }))
  const formal = JSON.parse(await tools.get('aitestwork_get_test_case_detail').execute({ projectId: 2, caseId: 11 }))
  assert.equal(wiki.content.length, '完整规则'.repeat(300).length)
  assert.equal(formal.steps, '1. 打开邀请记录')
  assert.equal(requested.some(url => url.endsWith('/projects/2/wiki/entries/7')), true)
  assert.equal(requested.some(url => url.endsWith('/projects/2/cases/11')), true)
})

test('asset proposal tools use bound agent routes and do not call Web admin APIs', async () => {
  const { tools, commands } = harness()
  const requests = []
  globalThis.fetch = async (url, options) => {
    const respond = data => ({ ok: true, status: 200, json: async () => ({ success: true, data }) })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return respond({ userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 })
    requests.push({ url, options })
    return respond({ status: 'PENDING_REVIEW' })
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  const wiki = JSON.parse(await tools.get('aitestwork_propose_wiki').execute({ projectId: 18, workSessionId: 6,
    packName: '访客实现', entries: [{ type: 'IMPLEMENTATION', title: '邀请入口', content: '邀请记录页面点击新增按钮', source: '浏览器实测' }] }))
  await tools.get('aitestwork_propose_tom').execute({ projectId: 18, workSessionId: 6,
    nodes: [{ type: 'ROLE', name: '安保', description: '本楼层访问数据权限', evidence: '需求 2.2 角色表' }] })
  await tools.get('aitestwork_get_tom_relation_types').execute({ projectId: 18 })
  await tools.get('aitestwork_propose_tom_relations').execute({ projectId: 18, workSessionId: 6,
    relations: [{ fromModelId: 1, type: 'ROLE_CAN_ACTION', toModelId: 2, evidence: '需求 2.2 角色表' }] })
  await tools.get('aitestwork_propose_locator_map').execute({ projectId: 18, workSessionId: 6, name: '邀请页定位',
    locators: [{ page: '邀请页', control: '新增', strategy: 'TEXT', selector: '新建邀请', evidence: '浏览器实测' }] })
  await tools.get('aitestwork_get_locator_map').execute({ projectId: 18, query: '邀请' })
  assert.equal(wiki.status, 'PENDING_REVIEW')
  assert.deepEqual(requests.map(request => new URL(request.url).pathname), [
    '/api/aitestwork/dsh/tools/projects/18/asset-proposals/wiki',
    '/api/aitestwork/dsh/tools/projects/18/asset-proposals/tom',
    '/api/aitestwork/dsh/tools/projects/18/asset-proposals/tom-relation-types',
    '/api/aitestwork/dsh/tools/projects/18/asset-proposals/tom-relations',
    '/api/aitestwork/dsh/tools/projects/18/asset-proposals/locators',
    '/api/aitestwork/dsh/tools/projects/18/asset-proposals/locators',
  ])
  assert.equal(requests.every(request => request.options.headers.Authorization === 'Bearer agent_secret'), true)
  assert.equal(JSON.parse(requests[0].options.body).entries[0].source, '浏览器实测')
})

test('automation edits and deletions use bound asset ID and keep review in Web', async () => {
  const { tools, commands } = harness()
  const requests = []
  globalThis.fetch = async (url, options) => {
    const respond = data => ({ ok: true, status: 200, json: async () => ({ success: true, data }) })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return respond({ userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 })
    requests.push({ path: new URL(url).pathname, options })
    return respond({ id: 5 })
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  await tools.get('aitestwork_revise_auto_asset').execute({ projectId: 18, assetId: 5,
    requestKey: 'revision-1', expectedVersion: 2, changeReason: '页面按钮名称已更改', mapping: { caseNo: 'TC-1' } })
  await tools.get('aitestwork_discard_auto_asset_candidate').execute({ projectId: 18, assetId: 5,
    versionNo: 3, reason: '候选定位错误' })
  await tools.get('aitestwork_delete_auto_asset').execute({ projectId: 18, assetId: 5, reason: '用例已经废弃' })
  assert.deepEqual(requests.map(item => item.path), [
    '/api/aitestwork/dsh/tools/projects/18/automation-assets/5/versions',
    '/api/aitestwork/dsh/tools/projects/18/automation-assets/5/versions/3/discard',
    '/api/aitestwork/dsh/tools/projects/18/automation-assets/5/archive',
  ])
  assert.equal(requests.every(item => item.options.headers.Authorization === 'Bearer agent_secret'), true)
  assert.equal(JSON.parse(requests[0].options.body).expectedVersion, 2)
})

test('API automation tools remain under the bound agent routes', async () => {
  const { tools, commands } = harness()
  const requests = []
  globalThis.fetch = async (url, options) => {
    const respond = data => ({ ok: true, status: 200, json: async () => ({ success: true, data }) })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return respond({ userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 })
    requests.push({ path: new URL(url).pathname, options })
    return respond({ id: 17 })
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  await tools.get('aitestwork_create_auto_asset').execute({ projectId: 18, workSessionId: 6,
    requestKey: 'api-create', caseKind: 'DRAFT', caseId: 20, assetType: 'API',
    mapping: { caseNo: 'TC-API-1', steps: [], assertions: [] } })
  await tools.get('aitestwork_record_api_evidence').execute({ projectId: 18, assetId: 17,
    evidencePayload: { requestKey: 'api-evidence', versionNo: 1, result: 'PASS', evidence: { steps: [], assertions: [] } } })
  await tools.get('aitestwork_get_api_evidence').execute({ projectId: 18, assetId: 17, evidenceId: 2 })
  assert.equal(tools.has('aitestwork_run_api_asset'), true)
  assert.deepEqual(requests.map(item => item.path), [
    '/api/aitestwork/dsh/tools/projects/18/automation-assets',
    '/api/aitestwork/dsh/tools/projects/18/automation-assets/17/api-evidence',
    '/api/aitestwork/dsh/tools/projects/18/automation-assets/17/api-evidence/2',
  ])
  assert.equal(JSON.parse(requests[0].options.body).assetType, 'API')
  assert.equal(requests.every(item => item.options.headers.Authorization === 'Bearer agent_secret'), true)
})

test('local case update uses the exact signed ID and expected version without creating a draft', async () => {
  const { tools, commands } = harness()
  const requests = []
  globalThis.fetch = async (url, options) => {
    const respond = data => ({ ok: true, status: 200, json: async () => ({ success: true, data }) })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return respond({ userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 })
    requests.push({ url, options })
    return respond({ localCaseId: -12, versionNo: 2 })
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  await tools.get('aitestwork_get_local_case').execute({ projectId: 18, localCaseId: -12 })
  await tools.get('aitestwork_update_local_case').execute({ projectId: 18, localCaseId: -12,
    expectedVersion: 1, requestKey: 'edit-one', steps: '1. 点击新增' })
  assert.deepEqual(requests.map(item => `${item.options.method} ${new URL(item.url).pathname}`), [
    'GET /api/aitestwork/dsh/tools/projects/18/local-cases/-12',
    'PATCH /api/aitestwork/dsh/tools/projects/18/local-cases/-12',
  ])
  assert.equal(JSON.parse(requests[1].options.body).expectedVersion, 1)
  assert.equal(requests.every(item => item.options.headers.Authorization === 'Bearer agent_secret'), true)
})

test('candidate case batches are checked by AITestWork before the final design is saved', async () => {
  const { tools, commands } = harness()
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return { ok: true, status: 200, json: async () => ({ success: true,
      data: { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 } }) }
    if (url.endsWith('/case-design/validate')) return { ok: true, status: 200, json: async () => ({ success: true,
      data: { validatedPointIds: ['TP1'], caseCount: 2 } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/work-sessions/8/stages')) return {
      ok: true, status: 200, json: async () => ({ success: true, data: { stageKey: 'CASE_DESIGN' } }) }
    throw new Error(`unexpected URL ${url}`)
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  await assert.rejects(tools.get('aitestwork_save_stage').execute({ projectId: 2, workSessionId: 8,
    stageKey: 'CASE_DESIGN', payloadJson: '{"cases":[{},{}]}' }), /aitestwork_validate_case_batch/)
  const value = await tools.get('aitestwork_validate_case_batch').execute({ projectId: 2, workSessionId: 8,
    pointIdsJson: '["TP1"]', payloadJson: '{"cases":[{},{}]}' })
  assert.equal(JSON.parse(value).caseCount, 2)
  assert.deepEqual(JSON.parse(calls.at(-1).options.body), {
    pointIds: ['TP1'], payloadJson: '{"cases":[{},{}]}' })
  const saved = await tools.get('aitestwork_save_stage').execute({ projectId: 2, workSessionId: 8,
    stageKey: 'CASE_DESIGN', payloadJson: '{"cases":[{},{}]}' })
  assert.equal(JSON.parse(saved).stageKey, 'CASE_DESIGN')
  await assert.rejects(tools.get('aitestwork_save_stage').execute({ projectId: 2, workSessionId: 8,
    stageKey: 'CASE_DESIGN', payloadJson: '{"cases":[{}, {"caseTitle":"untested"}]}' }), /aitestwork_validate_case_batch/)
  await assert.rejects(tools.get('aitestwork_validate_case_batch').execute({ projectId: 2, workSessionId: 8,
    pointIdsJson: '["BAD"]', payloadJson: '{"cases":[]}' }), /测试点编号/)
})

test('binding stays outside model tools and case generation only writes a draft', async () => {
  const { tools, commands, skills } = harness()
  assert.equal(skills.has('aitestwork'), true)
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return { ok: true, status: 200, json: async () => ({ success: true, data: { userToken: 'dsh_user_secret', agentToken: 'dsh_agent_secret', userId: 9, connectionId: 3 } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/work-sessions/8')) return { ok: true, status: 200, json: async () => ({ success: true, data: { requirementText: '需求' } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/drafts')) return { ok: true, status: 200, json: async () => ({ success: true, data: { draftId: 42 } }) }
    throw new Error(`unexpected URL ${url}`)
  }

  assert.equal(tools.has('aitestwork_submit_case'), false)
  assert.equal(commands.get('aitestwork-bind').recordInput, false)
  const bound = await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  assert.equal(bound.kind, 'success')
  assert.equal(bound.text.includes('dsh_user_secret'), false)
  const saved = await tools.get('aitestwork_save_case_draft').execute({ projectId: 2, workSessionId: 8, idempotencyKey: 'one', caseTitle: '标题', moduleName: '/邀请记录/新增', steps: '操作', expectedResult: '结果' })
  assert.equal(saved, '{"draftId":42}')
  assert.equal(calls[2].options.headers.Authorization, 'Bearer dsh_agent_secret')
  assert.equal(calls[2].options.method, 'POST')
  assert.equal(JSON.parse(calls[2].options.body).requirementText, '需求')
})

test('binding rejects incomplete code before sending it to the server', async () => {
  const { commands } = harness()
  let sent = false
  globalThis.fetch = async () => { sent = true; throw new Error('unexpected request') }
  const result = await commands.get('aitestwork-bind').handler({ rawInput: 'https://hub.example SHORT' })
  assert.equal(result.kind, 'error')
  assert.match(result.text, /^失败：/)
  assert.match(result.text, /12 位/)
  assert.equal(sent, false)
})

test('project creation uses the bound human credential and returns its ID', async () => {
  const { commands } = harness()
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return { ok: true, status: 200, json: async () => ({ success: true,
      data: { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 } }) }
    if (url.endsWith('/api/aitestwork/dsh/user/projects')) return { ok: true, status: 200, json: async () => ({ success: true,
      data: { id: 16, projectName: '访客模块' } }) }
    throw new Error(`unexpected URL ${url}`)
  }
  const unbound = await commands.get('aitestwork-project-create').handler({ rawInput: '访客模块' })
  assert.equal(unbound.kind, 'error')
  assert.match(unbound.text, /^失败：/)
  const bound = await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  assert.match(bound.text, /^成功：/)
  const created = await commands.get('aitestwork-project-create').handler({ rawInput: '访客模块 | 新版回归测试' })
  assert.equal(created.kind, 'success')
  assert.match(created.text, /^成功：/)
  assert.match(created.text, /项目 ID：16/)
  assert.equal(calls.at(-1).options.headers.Authorization, 'Bearer user_secret')
  assert.deepEqual(JSON.parse(calls.at(-1).options.body), { projectName: '访客模块', description: '新版回归测试' })
})

test('natural-language project tool uses the bound user and telemetry records outcome without arguments', async () => {
  const { commands, tools, events } = harness()
  const sent = []
  globalThis.fetch = async (url, options) => {
    sent.push({ url, options })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return { ok: true, status: 200, json: async () => ({ success: true,
      data: { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 } }) }
    if (url.endsWith('/api/aitestwork/dsh/user/projects')) return { ok: true, status: 200, json: async () => ({ success: true,
      data: { id: 16, projectName: '访客模块' } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/telemetry')) return { ok: true, status: 200, json: async () => ({ success: true }) }
    throw new Error(`unexpected URL ${url}`)
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  const result = await tools.get('aitestwork_create_project').execute({ projectName: '访客模块', description: '' })
  assert.match(result, /"id":16/)
  const projectCall = sent.find(call => call.url.endsWith('/api/aitestwork/dsh/user/projects'))
  assert.equal(projectCall.options.headers.Authorization, 'Bearer user_secret')
  const wrapper = events.get('tools/execute')
  await wrapper({ name: 'aitestwork_create_project', callId: 'call-1', signal: new AbortController().signal },
    async () => ({ isError: false, value: 'ok', content: [] }))
  const telemetry = sent.filter(call => call.url.endsWith('/api/aitestwork/dsh/tools/telemetry'))
  assert.deepEqual(telemetry.map(call => JSON.parse(call.options.body).state), ['STARTED', 'SUCCEEDED'])
  assert.equal(JSON.stringify(telemetry).includes('访客模块'), false)
  assert.equal(telemetry[0].options.headers.Authorization, 'Bearer agent_secret')
})

test('telemetry distinguishes tool failure and cancellation', async () => {
  const { commands, events } = harness()
  const states = []
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/api/aitestwork/dsh/bind')) return { ok: true, status: 200, json: async () => ({ success: true,
      data: { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/telemetry')) {
      states.push(JSON.parse(options.body).state)
      return { ok: true, status: 200 }
    }
    throw new Error(`unexpected URL ${url}`)
  }
  await commands.get('aitestwork-bind').handler({ rawInput: `https://hub.example ${validBindCode}` })
  const wrapper = events.get('tools/execute')
  const failed = await wrapper({ name: 'aitestwork_get_project_context', callId: 'failed-1', signal: new AbortController().signal },
    async () => ({ isError: true, error: { message: 'request failed', info: { code: 'HTTP_ERROR' } }, content: [] }))
  assert.equal(failed.isError, true)
  const abort = new AbortController()
  await wrapper({ name: 'aitestwork_capture_stop', callId: 'aborted-1', signal: abort.signal }, async () => {
    abort.abort()
    return { isError: true, error: { message: 'aborted' }, content: [] }
  })
  assert.deepEqual(states, ['STARTED', 'FAILED', 'STARTED', 'ABORTED'])
})

test('binding accepts a pasted code with separators', async () => {
  const { commands } = harness()
  globalThis.fetch = async (_url, options) => {
    assert.equal(JSON.parse(options.body).code, validBindCode)
    return { ok: true, status: 200, json: async () => ({ success: true,
      data: { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 } }) }
  }
  const result = await commands.get('aitestwork-bind').handler({ rawInput: 'https://hub.example abcd-efgh-jklm' })
  assert.equal(result.kind, 'success')
})

test('binding accepts explicit LAN HTTP and explains missing scheme and old backend failure', async () => {
  assert.throws(() => parseServerUrl('192.0.2.10:5174'), /http:\/\//)
  assert.equal(parseServerUrl('http://192.0.2.10:5174/'), 'http://192.0.2.10:5174')
  assert.throws(() => parseServerUrl('ftp://192.0.2.10'), /HTTP 或 HTTPS/)
  const { commands } = harness()
  globalThis.fetch = async () => ({ ok: false, status: 401,
    json: async () => ({ success: false, message: '登录已失效，请重新登录' }) })
  const result = await commands.get('aitestwork-bind').handler({ rawInput: 'https://hub.example ABCDEFGHJKLM' })
  assert.equal(result.kind, 'error')
  assert.match(result.text, /HTTP 401/)
  assert.match(result.text, /DSH 接口/)
})

test('HTTP binding succeeds with a clear credential transport notice', async () => {
  const { commands } = harness()
  globalThis.fetch = async (url) => {
    assert.equal(url, 'http://192.0.2.10:5174/api/aitestwork/dsh/bind')
    return { ok: true, status: 200, json: async () => ({ success: true,
      data: { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 } }) }
  }
  const result = await commands.get('aitestwork-bind').handler({ rawInput: 'http://192.0.2.10:5174 ABCDEFGHJKLM' })
  assert.equal(result.kind, 'success')
  assert.match(result.text, /HTTP/)
  assert.equal(result.text.includes('user_secret'), false)
})

test('historical case tool passes encoded search and cursor with agent authority', async () => {
  const { commands, tools } = harness()
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return { ok: true, status: 200, json: async () => ({ success: true,
      data: { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 } }) }
    return { ok: true, status: 200, json: async () => ({ success: true, data: [{ id: 17 }] }) }
  }
  await commands.get('aitestwork-bind').handler({ rawInput: 'https://hub.example ABCDEFGHJKLM' })
  const result = await tools.get('aitestwork_get_test_cases').execute({ projectId: 2,
    query: '访客 审批', beforeId: 50, limit: 10 })
  assert.equal(result, '[{"id":17}]')
  const url = new URL(calls.at(-1).url)
  assert.equal(url.pathname, '/api/aitestwork/dsh/tools/projects/2/cases')
  assert.equal(url.searchParams.get('query'), '访客 审批')
  assert.equal(url.searchParams.get('beforeId'), '50')
  assert.equal(url.searchParams.get('limit'), '10')
  assert.equal(calls.at(-1).options.headers.Authorization, 'Bearer agent_secret')
})

test('status reports the configured service address without exposing credentials', async () => {
  const { commands } = harness()
  globalThis.fetch = async url => {
    if (url.endsWith('/api/aitestwork/dsh/bind')) return { ok: true, status: 200, json: async () => ({ success: true, data: {
      userToken: 'dsh_user_secret', agentToken: 'dsh_agent_secret', userId: 9, connectionId: 3 } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects')) return { ok: true, status: 200,
      json: async () => ({ success: true, data: [{ id: 1 }, { id: 2 }] }) }
    throw new Error(`unexpected URL ${url}`)
  }
  const unbound = await commands.get('aitestwork-status').handler()
  assert.equal(unbound.kind, 'error')
  await commands.get('aitestwork-bind').handler({ rawInput: 'https://hub.example ABCDEFGHJKLM' })
  const status = await commands.get('aitestwork-status').handler()
  assert.equal(status.kind, 'success')
  assert.match(status.text, /https:\/\/hub\.example/)
  assert.match(status.text, /2 个/)
  assert.equal(status.text.includes('dsh_user_secret'), false)
  assert.equal(status.text.includes('dsh_agent_secret'), false)
})

test('agent can only propose a style while acceptance requires human preview', async () => {
  const { tools, commands } = harness()
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return { ok: true, status: 200, json: async () => ({ success: true,
      data: { userToken: 'user_secret', agentToken: 'agent_secret', userId: 9, connectionId: 3 } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/preference-candidates')) return { ok: true, status: 200,
      json: async () => ({ success: true, data: { id: 12, status: 'PENDING' } }) }
    if (url.endsWith('/api/aitestwork/dsh/user/projects/2/preference-candidates')) return { ok: true, status: 200,
      json: async () => ({ success: true, data: [{ id: 12, styleText: '步骤简洁', reason: '多次修订',
        contentHash: 'reviewed-hash', status: 'PENDING' }] }) }
    if (url.endsWith('/api/aitestwork/dsh/user/projects/2/preference-candidates/12/accept')) return { ok: true, status: 200,
      json: async () => ({ success: true, data: { styleText: '步骤简洁' } }) }
    throw new Error(`unexpected URL ${url}`)
  }
  await commands.get('aitestwork-bind').handler({ rawInput: 'https://hub.example ABCDEFGHJKLM' })
  assert.equal(tools.has('aitestwork_accept_style'), false)
  const proposed = await tools.get('aitestwork_propose_style').execute({ projectId: 2,
    styleText: '步骤简洁', reason: '多次修订', sourceDraftId: 42 })
  assert.match(proposed, /PENDING/)
  assert.equal(calls.at(-1).options.headers.Authorization, 'Bearer agent_secret')
  assert.equal((await commands.get('aitest-style-accept').handler({ rawInput: '2 12' })).kind, 'error')
  assert.equal((await commands.get('aitestwork-style-candidates').handler({ rawInput: '2' })).kind, 'success')
  assert.equal((await commands.get('aitest-style-accept').handler({ rawInput: '2 12' })).kind, 'success')
  assert.equal(calls.at(-1).options.headers.Authorization, 'Bearer user_secret')
  assert.deepEqual(JSON.parse(calls.at(-1).options.body), { reviewedHash: 'reviewed-hash' })
})

test('human submission needs a preview and sends the reviewed content fingerprint', async () => {
  const { commands } = harness()
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return { ok: true, status: 200, json: async () => ({ success: true, data: { userToken: 'dsh_user_secret', agentToken: 'dsh_agent_secret', userId: 9, connectionId: 3 } }) }
    if (url.endsWith('/drafts/42')) return { ok: true, status: 200, json: async () => ({ success: true, data: { draft: { caseTitle: '标题', steps: '操作' }, fingerprint: 'abc123' } }) }
    if (url.endsWith('/drafts/42/submit')) return { ok: true, status: 200, json: async () => ({ success: true, data: null }) }
    throw new Error(`unexpected URL ${url}`)
  }

  await commands.get('aitestwork-bind').handler({ rawInput: 'https://hub.example ABCDEFGHJKLM' })
  const premature = await commands.get('aitestwork-submit').handler({ rawInput: '2 42 标题' })
  assert.equal(premature.kind, 'error')
  await commands.get('aitestwork-draft').handler({ rawInput: '2 42' })
  const result = await commands.get('aitestwork-submit').handler({ rawInput: '2 42 标题' })
  assert.equal(result.kind, 'success')
  assert.deepEqual(JSON.parse(calls[2].options.body), { reviewedTitle: '标题', reviewedFingerprint: 'abc123' })
  assert.equal(calls[2].options.headers.Authorization, 'Bearer dsh_user_secret')
})

test('stage confirmation uses the backend user connection credential after preview', async () => {
  const { tools, commands } = harness()
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    if (url.endsWith('/api/aitestwork/dsh/bind')) return { ok: true, status: 200, json: async () => ({ success: true, data: { userToken: 'dsh_user_secret', agentToken: 'dsh_agent_secret', userId: 9, connectionId: 3 } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/work-sessions')) return { ok: true, status: 200, json: async () => ({ success: true, data: { id: 8 } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/design-context')) return { ok: true, status: 200, json: async () => ({ success: true, data: { project: { id: 2 }, tomAvailableCount: 0, tomNodes: [], tomRelations: [], wikiLinked: [], formalCases: [], wikiSearchFallback: { availableByScope: { PROJECT: 0, REUSABLE: 0, SYSTEM: 2 }, matchedCount: 2, entries: [{ entryId: 14, title: '访客规则' }] } } }) }
    if (url.includes('/api/aitestwork/dsh/tools/projects/2/context?')) return { ok: true, status: 200, json: async () => ({ success: true, data: { project: { id: 2 }, signals: [] } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/design-rules')) return { ok: true, status: 200, json: async () => ({ success: true, data: [{ pointType: 'MAIN_FLOW' }] }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/case-writing-standard')) return { ok: true, status: 200, json: async () => ({ success: true, data: { columns: ['用例名称', '所属模块', '步骤描述', '预期结果'] } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/tom?mode=DIRECT')) return { ok: true, status: 200, json: async () => ({ success: true, data: [] }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/cases?limit=8')) return { ok: true, status: 200, json: async () => ({ success: true, data: [] }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/historical-style-examples?limit=8')) return { ok: true, status: 200, json: async () => ({ success: true, data: [{ id: 4, sourceProjectId: 2, caseTitle: '预约审批', styleOnly: true }] }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/historical-style-examples?query=%E9%82%80%E8%AF%B7%E8%AE%B0%E5%BD%95&limit=5')) return { ok: true, status: 200, json: async () => ({ success: true, data: [{ id: 5, caseTitle: '邀请记录新增', steps: '1. 点击新增' }] }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/preference')) return { ok: true, status: 200, json: async () => ({ success: true, data: { styleText: '' } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/assets/search')) return { ok: true, status: 200, json: async () => ({ success: true, data: [] }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/wiki/context?limit=8')) return { ok: true, status: 200, json: async () => ({ success: true, data: { availableByScope: { PROJECT: 0, REUSABLE: 0, SYSTEM: 2 }, matchedCount: 2, entries: [{ entryId: 14, packId: 6, scope: 'SYSTEM', title: '访客规则' }], truncated: true } }) }
    if (url.endsWith('/api/aitestwork/dsh/tools/projects/2/wiki/context?query=%E8%AE%BF%E5%AE%A2&limit=8')) return { ok: true, status: 200, json: async () => ({ success: true, data: { availableByScope: { PROJECT: 0, REUSABLE: 0, SYSTEM: 2 }, matchedCount: 2, entries: [{ entryId: 14, title: '访客规则' }] } }) }
    if (url.endsWith('/api/aitestwork/dsh/user/projects/2/work-sessions/8/stages')) return { ok: true, status: 200, json: async () => ({ success: true, data: [{ stageKey: 'TEST_POINTS', payloadJson: '{"points":[]}', contentHash: 'stage_hash', status: 'DRAFT' }] }) }
    if (url.endsWith('/api/aitestwork/dsh/user/projects/2/work-sessions/8/stages/TEST_POINTS/confirm')) return { ok: true, status: 200, json: async () => ({ success: true, data: { status: 'CONFIRMED' } }) }
    throw new Error(`unexpected URL ${url}`)
  }

  await commands.get('aitestwork-bind').handler({ rawInput: 'https://hub.example ABCDEFGHJKLM' })
  const requirementText = '访客审批'.repeat(110)
  const prepared = await tools.get('aitestwork_prepare_case_design').execute({ projectId: 2, requirementText, tomMode: 'DIRECT' })
  assert.equal(JSON.parse(prepared).ready, true)
  const designCall = calls.find(call => call.url.endsWith('/projects/2/design-context'))
  assert.deepEqual(JSON.parse(designCall.options.body), { requirementText, tomMode: 'DIRECT' })
  assert.equal(JSON.parse(prepared).designContext.tomAvailableCount, 0)
  assert.equal(JSON.parse(prepared).designContext.tomNodes, undefined)
  assert.match(JSON.parse(prepared).styleExampleNotice, /未按当前模块过滤/)
  assert.equal(JSON.parse(prepared).importedHistoricalExamples[0].styleOnly, true)
  assert.equal(JSON.parse(prepared).wiki.availableByScope.SYSTEM, 2)
  assert.equal(JSON.parse(prepared).writingStandard.columns[1], '所属模块')
  const style = await tools.get('aitestwork_search_historical_style_examples').execute({ projectId: 2, query: '邀请记录' })
  assert.equal(JSON.parse(style)[0].id, 5)
  const wiki = await tools.get('aitestwork_search_wiki').execute({ projectId: 2, query: '访客', limit: 8 })
  assert.equal(JSON.parse(wiki).matchedCount, 2)
  const started = await tools.get('aitestwork_start_work').execute({ projectId: 2, requirementText, tomMode: 'DIRECT' })
  assert.equal(started, '{"id":8}')
  const premature = await commands.get('aitestwork-confirm-stage').handler({ rawInput: '2 8 TEST_POINTS' })
  assert.equal(premature.kind, 'error')
  const preview = await commands.get('aitestwork-stage').handler({ rawInput: '2 8 TEST_POINTS' })
  assert.equal(preview.kind, 'success')
  const confirmed = await commands.get('aitestwork-confirm-stage').handler({ rawInput: '2 8 TEST_POINTS' })
  assert.equal(confirmed.kind, 'success')
  assert.equal(calls.at(-1).options.headers.Authorization, 'Bearer dsh_user_secret')
  assert.deepEqual(JSON.parse(calls.at(-1).options.body), { reviewedHash: 'stage_hash' })
})

test('local log tools register without binding and refuse reads before dedicated connection', async () => {
  const { tools, promptSections } = harness()
  for (const suffix of ['connect', 'instances', 'checkpoint', 'assert_contains', 'disconnect']) {
    assert.ok(tools.has(`aitestwork_local_log_${suffix}`))
  }
  assert.ok(tools.get('aitestwork_local_log_connect').parameters.required.includes('platformUrl'))
  assert.equal(tools.get('aitestwork_local_log_connect').parameters.properties.targetUrl, undefined)
  await assert.rejects(tools.get('aitestwork_local_log_instances').execute({}), /CONNECT_FIRST/)
  assert.match(promptSections.get('aitestwork:admin-log-assertions').text, /单独aitestwork_local_log_\*仍仅本地保存/)
})
