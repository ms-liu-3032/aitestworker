import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'

if (process.env.AITESTWORK_SMOKE_DISPOSABLE !== '1') {
  throw new Error('只允许一次性测试库；设置 AITESTWORK_SMOKE_DISPOSABLE=1')
}
const base = process.env.AITESTWORK_SMOKE_URL || 'http://127.0.0.1:18080'
const backend = new URL(base)
if (backend.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(backend.hostname) || backend.pathname !== '/') {
  throw new Error('此脚本只允许连接本机 HTTP 测试后端')
}
const username = process.env.AITESTWORK_SMOKE_USER
const password = process.env.AITESTWORK_SMOKE_PASSWORD
if (!username || !password) throw new Error('需设置 AITESTWORK_SMOKE_USER 和 AITESTWORK_SMOKE_PASSWORD')
const dbContainer = process.env.AITESTWORK_SMOKE_DB_CONTAINER
const dbPassword = process.env.AITESTWORK_SMOKE_DB_PASSWORD
if (Boolean(dbContainer) !== Boolean(dbPassword)) {
  throw new Error('跨用户用例权限测试须同时设置一次性数据库容器名和密码')
}
if (dbContainer && !/^aitest-dsh-[a-z0-9-]+$/.test(dbContainer)) {
  throw new Error('跨用户用例权限测试只允许 aitest-dsh-* 一次性容器')
}

function insertForeignPersonalCase(projectId) {
  const sql = `INSERT INTO test_case_asset
    (project_id, case_no, case_title, steps, expected_result, case_scope, case_status,
     submitted_by, created_at, updated_at)
    VALUES (${Number(projectId)}, 'FOREIGN-PERSONAL-SMOKE', '其他用户的个人用例',
      '私有操作', '私有结果', 'PERSONAL', 'SUBMITTED', 999999999,
      NOW(), NOW()); SELECT LAST_INSERT_ID();`
  const output = execFileSync('docker', ['exec', '-e', `MYSQL_PWD=${dbPassword}`, dbContainer,
    'mysql', '-uroot', '-N', 'aitest', '-e', sql], { encoding: 'utf8' }).trim()
  const id = Number(output)
  assert.ok(Number.isSafeInteger(id) && id > 0, '须在一次性数据库中插入个人用例')
  return id
}

async function request(path, { method = 'GET', token, body } = {}) {
  const response = await fetch(base + path, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const result = await response.json()
  return { status: response.status, ...result }
}

async function api(path, options) {
  const result = await request(path, options)
  if (result.status >= 400 || !result.success) {
    throw new Error(`${options?.method || 'GET'} ${path}: ${result.message || result.status}`)
  }
  return result.data
}

let connectionId, webToken
try {
  const status = await api('/api/auth/init-status')
  const login = await api(status.initialized ? '/api/auth/login' : '/api/auth/init-admin', {
    method: 'POST', body: { username, password, displayName: 'DSH Dual Entry Smoke' },
  })
  webToken = login.token
  const code = await api('/api/aitestwork/dsh/bind-codes', { method: 'POST', token: webToken })
  const bound = await api('/api/aitestwork/dsh/bind', {
    method: 'POST', body: { code: code.code, deviceName: 'dual entry smoke' },
  })
  connectionId = bound.connectionId
  const project = await api('/api/aitestwork/dsh/user/projects', {
    method: 'POST', token: bound.userToken,
    body: { projectName: `DSH dual entry smoke ${Date.now()}`, description: 'disposable test' },
  })
  const webProjects = await api('/api/projects', { token: webToken })
  assert.ok(webProjects.some(item => item.id === project.id), 'Web 必须能读取 DSH 创建的项目')
  await api('/api/aitestwork/dsh/tools/telemetry', {
    method: 'POST', token: bound.agentToken,
    body: { toolName: 'aitestwork_create_project', callId: `smoke-${project.id}`,
      state: 'SUCCEEDED', durationMs: 12 },
  })
  const baseProject = `/api/aitestwork/dsh/tools/projects/${project.id}`
  const humanProject = `/api/aitestwork/dsh/user/projects/${project.id}`
  const webCases = `/api/projects/${project.id}/generation/local-cases`
  const requirementText = '验证 Web 和 DSH 共用草稿、正式用例与来源快照'
  const missingTomMode = await request(`${baseProject}/work-sessions`, {
    method: 'POST', token: bound.agentToken, body: { requirementText },
  })
  assert.ok(missingTomMode.status >= 400 || !missingTomMode.success, 'DSH 任务不能静默默认不使用 TOM')
  const tom = await api(`${baseProject}/tom?mode=DIRECT`, { token: bound.agentToken })
  assert.deepEqual(tom, [])
  assert.deepEqual(await api(`${baseProject}/tom?mode=PROJECT_TOM`, { token: bound.agentToken }), [])
  assert.deepEqual(await api(`${baseProject}/tom?mode=PROJECT_AND_SYSTEM_TOM`, { token: bound.agentToken }), [])
  const designRules = await api('/api/aitestwork/dsh/tools/design-rules', { token: bound.agentToken })
  assert.ok(designRules.some(rule => rule.pointType === 'BOUNDARY' && rule.coverageRequirements.includes('AT_BOUNDARY')))
  const work = await api(`${baseProject}/work-sessions`, {
    method: 'POST', token: bound.agentToken, body: { requirementText, tomMode: 'DIRECT' },
  })
  const firstCase = { caseTitle: 'DSH 创建的草稿',
    steps: '1. 进入当前项目的用例库页面\n2. 查看刚保存的个人草稿列表',
    expectedResult: '1. 用例库展示当前项目草稿区域\n2. 列表包含本轮保存的草稿标题' }
  const secondCase = { caseTitle: '引用正式用例的草稿',
    steps: '1. 在用例库打开已提交的用例\n2. 查看新草稿记录的来源信息',
    expectedResult: '1. 页面展示已提交用例的完整内容\n2. 新草稿保留该用例的来源版本快照' }
  const asDesignedCase = value => ({ ...value, caseStrategy: 'NODE_FOCUSED', scenarioType: 'POSITIVE',
    sourceTestPointRefs: ['TP1'], designMethods: ['场景法'],
    designCoverage: ['VALID_FLOW', 'EXPECTED_BUSINESS_RESULT'],
    sourceBasis: ['需求原文'], unsupportedItems: [], confidence: 0.8 })
  for (const [stageKey, payload] of [
    ['REQUIREMENT_ANALYSIS', { summary: requirementText }],
    ['TEST_POINTS', { points: [{ id: 'TP1', title: '双入口一致性', pointType: 'MAIN_FLOW',
      scopeDecision: 'GENERATE', sourceBasis: ['需求原文'] }] }],
    ['COVERAGE_PLAN', { items: [{ pointId: 'TP1', designMethods: ['场景法'],
      coverageRequirements: ['VALID_FLOW', 'EXPECTED_BUSINESS_RESULT'] }] }],
    ['CASE_DESIGN', { cases: [asDesignedCase(firstCase), asDesignedCase(secondCase)] }],
    ['QUALITY_REVIEW', { findings: [] }],
  ]) {
    if (stageKey === 'CASE_DESIGN') {
      const validBatch = await api(`${baseProject}/work-sessions/${work.id}/case-design/validate`, {
        method: 'POST', token: bound.agentToken,
        body: { pointIds: ['TP1'], payloadJson: JSON.stringify(payload) },
      })
      assert.deepEqual(validBatch.validatedPointIds, ['TP1'])
      const incomplete = await request(`${baseProject}/work-sessions/${work.id}/stages`, {
        method: 'POST', token: bound.agentToken,
        body: { stageKey, payloadJson: JSON.stringify({ cases: [
          { ...asDesignedCase(firstCase), designCoverage: ['VALID_FLOW'] },
        ] }) },
      })
      assert.ok(incomplete.status >= 400 || !incomplete.success, '缺少设计覆盖义务必须拒绝')
      const vague = await request(`${baseProject}/work-sessions/${work.id}/stages`, {
        method: 'POST', token: bound.agentToken,
        body: { stageKey, payloadJson: JSON.stringify({ cases: [
          { ...asDesignedCase(firstCase), steps: '1. 点击保存', expectedResult: '1. 操作成功' },
        ] }) },
      })
      assert.ok(vague.status >= 400 || !vague.success, '空泛步骤和预期必须拒绝')
    }
    await api(`${baseProject}/work-sessions/${work.id}/stages`, {
      method: 'POST', token: bound.agentToken,
      body: { stageKey, payloadJson: JSON.stringify(payload) },
    })
  }

  const undesigned = await request(`${baseProject}/drafts`, {
    method: 'POST', token: bound.agentToken,
    body: { workSessionId: work.id, idempotencyKey: 'not-in-design', requirementText,
      caseTitle: '不在设计中的草稿', steps: firstCase.steps,
      expectedResult: firstCase.expectedResult, sourceRefs: [] },
  })
  assert.ok(undesigned.status >= 400 || !undesigned.success, '草稿必须与已校验设计一致')

  const first = await api(`${baseProject}/drafts`, {
    method: 'POST', token: bound.agentToken,
    body: { workSessionId: work.id, idempotencyKey: 'dual-entry-first', requirementText,
      ...firstCase, sourceRefs: [] },
  })
  const webList = await api(webCases, { token: webToken })
  assert.ok(webList.some(item => item.id === first.draftId), 'Web 必须能读取 DSH 草稿')

  const oldPreview = await api(`${humanProject}/drafts/${first.draftId}`, { token: bound.userToken })
  await api(`${webCases}/${first.draftId}`, {
    method: 'PATCH', token: webToken, body: { caseTitle: 'Web 审核后的草稿' },
  })
  const staleSubmit = await request(`${humanProject}/drafts/${first.draftId}/submit`, {
    method: 'POST', token: bound.userToken,
    body: { reviewedTitle: oldPreview.draft.caseTitle, reviewedFingerprint: oldPreview.fingerprint },
  })
  assert.ok(staleSubmit.status >= 400 || !staleSubmit.success, 'Web 编辑后旧 DSH 审核指纹必须失效')
  const currentPreview = await api(`${humanProject}/drafts/${first.draftId}`, { token: bound.userToken })
  assert.equal(currentPreview.draft.caseTitle, 'Web 审核后的草稿')
  await api(`${humanProject}/drafts/${first.draftId}/submit`, {
    method: 'POST', token: bound.userToken,
    body: { reviewedTitle: currentPreview.draft.caseTitle, reviewedFingerprint: currentPreview.fingerprint },
  })
  const formal = await api(`${baseProject}/cases?limit=20`, { token: bound.agentToken })
  const submitted = formal.find(item => item.caseTitle === currentPreview.draft.caseTitle)
  assert.ok(submitted?.id, '正式用例必须能被 DSH 读取')
  if (dbContainer) {
    const foreignId = insertForeignPersonalCase(project.id)
    const visible = await api(`${baseProject}/cases?limit=50`, { token: bound.agentToken })
    assert.ok(!visible.some(item => item.id === foreignId), '其他用户的个人正式用例不能被 DSH 检索')
    const guessedSource = await request(`${baseProject}/drafts`, {
      method: 'POST', token: bound.agentToken,
      body: { workSessionId: work.id, idempotencyKey: 'foreign-personal-source', requirementText,
        ...firstCase,
        sourceRefs: [`test_case_asset#${foreignId}`] },
    })
    assert.ok(guessedSource.status >= 400 || !guessedSource.success,
      '其他用户的个人正式用例不能通过 ID 猜测作为草稿来源')
  }
  const matchedCases = await api(`${baseProject}/cases?query=${encodeURIComponent('Web 审核')}&limit=1`, {
    token: bound.agentToken,
  })
  assert.equal(matchedCases.length, 1)
  assert.equal(matchedCases[0].id, submitted.id)
  const earlierCases = await api(`${baseProject}/cases?beforeId=${submitted.id}&limit=1`, {
    token: bound.agentToken,
  })
  assert.ok(earlierCases.every(item => item.id < submitted.id), '游标只能返回更早正式用例')

  const second = await api(`${baseProject}/drafts`, {
    method: 'POST', token: bound.agentToken,
    body: { workSessionId: work.id, idempotencyKey: 'dual-entry-source', requirementText,
      ...secondCase,
      sourceRefs: [`test_case_asset#${submitted.id}`] },
  })
  const webSecond = await api(`${webCases}/${second.draftId}`, { token: webToken })
  const sources = JSON.parse(webSecond.sourceRefsJson)
  assert.deepEqual(sources.assetRefs, [`test_case_asset#${submitted.id}`])
  assert.equal(sources.assetRefSnapshots[0].ref, `test_case_asset#${submitted.id}`)
  assert.equal(sources.assetRefSnapshots[0].version, submitted.version ?? 1)
  assert.match(sources.assetRefSnapshots[0].contentHash, /^[0-9a-f]{64}$/)
  const initialStyle = await api(`${baseProject}/preference`, { token: bound.agentToken })
  assert.equal(initialStyle.styleText, '')
  const candidate = await api(`${baseProject}/preference-candidates`, {
    method: 'POST', token: bound.agentToken,
    body: { styleText: '标题写业务动作；预期逐步对应操作', reason: '用户在 Web 中修订了草稿标题',
      sourceDraftId: first.draftId },
  })
  assert.equal(candidate.status, 'PENDING')
  assert.equal((await api(`${baseProject}/preference`, { token: bound.agentToken })).styleText, '')
  const candidateList = await api(`${humanProject}/preference-candidates`, { token: bound.userToken })
  assert.ok(candidateList.some(item => item.id === candidate.id))
  await api(`${humanProject}/preference-candidates/${candidate.id}/accept`, {
    method: 'POST', token: bound.userToken, body: { reviewedHash: candidate.contentHash },
  })
  assert.equal((await api(`${baseProject}/preference`, { token: bound.agentToken })).styleText,
    '标题写业务动作；预期逐步对应操作')
  const repeatedDecision = await request(`${humanProject}/preference-candidates/${candidate.id}/accept`, {
    method: 'POST', token: bound.userToken, body: { reviewedHash: candidate.contentHash },
  })
  assert.ok(repeatedDecision.status >= 400 || !repeatedDecision.success)
  console.log(JSON.stringify({ ok: true, webReadsDshDraft: true, staleReviewRejected: true,
    formalShared: true, casesFilteredAndPaged: true, sourceVersionFrozen: true,
    styleRequiresHumanAcceptance: true, foreignPersonalCaseIsolated: Boolean(dbContainer) }))
} finally {
  if (connectionId && webToken) {
    await api(`/api/aitestwork/dsh/connections/${connectionId}/revoke`, { method: 'POST', token: webToken }).catch(() => undefined)
  }
}
