import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'

const apiMocks = vi.hoisted(() => ({
  listGenerationSessions: vi.fn(),
  createGenerationSession: vi.fn(),
  updateGenerationSession: vi.fn(),
  archiveGenerationSession: vi.fn(),
  listGenerationMessages: vi.fn(),
  sendGenerationMessage: vi.fn(),
  generateIncremental: vi.fn(),
  startSessionIncrementalGenerationTask: vi.fn(),
  listGenerationDrafts: vi.fn(),
  deprecateLocalCase: vi.fn(),
  getLatestGenerationAnalysis: vi.fn(),
  listGenerationAnalyses: vi.fn(),
  listEnabledModelConfigs: vi.fn(),
  listEnabledPromptTemplates: vi.fn(),
  startSessionCaseGenerationTask: vi.fn(),
  startSessionRequirementAnalysisTask: vi.fn(),
  getAsyncGenerationTask: vi.fn(),
  retryAsyncGenerationTask: vi.fn(),
  cancelAsyncGenerationTask: vi.fn(),
  confirmGenerationRequirementScope: vi.fn(),
  confirmGenerationTestPointScope: vi.fn(),
}))

const generationApiMocks = vi.hoisted(() => ({
  listAttachments: vi.fn(),
  uploadAttachment: vi.fn(),
}))

const appMocks = vi.hoisted(() => ({
  showToast: vi.fn(),
  setModal: vi.fn(),
}))

vi.mock('../context/AppContext', () => ({
  useApp: () => appMocks,
}))

vi.mock('react-router-dom', () => ({
  useParams: () => ({ projectId: '1' }),
  useNavigate: () => vi.fn(),
  Link: ({ children, to }: any) => <a href={to}>{children}</a>,
}))

vi.mock('../services/api', () => apiMocks)

vi.mock('../services/generationApi', () => generationApiMocks)

import TestCaseGeneration, { AnalysisMessageContent, AnalysisPanel } from '../pages/project/TestCaseGeneration'
import type { AsyncGenerationTask, CaseDraft, GenerationSession, RequirementAnalysis } from '../services/api'

// ── Helper ──────────────────────────────────────
function makeAnalysis(overrides: Record<string, any> = {}): RequirementAnalysis {
  const analysisResult = overrides.analysisResult ?? JSON.stringify({
    analysis: {
      requirement_understanding: '请假审批流程需求理解',
      affected_modules: ['请假模块'],
    },
    test_points: overrides.testPoints ?? [
      { title: '验证提交请假', description: '员工提交请假', point_type: 'MAIN_FLOW', priority_hint: 'CORE' },
      { title: '验证审批驳回', description: '主管驳回', point_type: 'BRANCH', priority_hint: 'EXTENDED' },
      { title: '验证并发审批', description: '多人同时审批', point_type: 'CONCURRENCY', priority_hint: 'RISK' },
    ],
    clarification_questions: [
      { question: '入口在哪里？', reason: '步骤', impact: '页面覆盖' },
    ],
    review_risk_questions: [
      { question: '审批节点数量是否有限制？', reason: '影响拆分', impact: '需确认上限' },
    ],
    uncertain_items: ['不确定项A'],
    input_sources: ['PRD_TEXT', 'BLUEPRINT'],
    requirement_type: 'RULE',
    risk_scenarios: ['并发审批冲突'],
    boundary_conditions: ['最大审批节点数为10'],
    coverage_matrix: [
      {
        module: '请假审批',
        main_flow: { count: 1, items: ['员工成功提交请假申请并进入审批'] },
        branch: { count: 1, items: ['主管同意或驳回请假申请'] },
        boundary: { count: 2, items: ['请假天数为最小值', '请假天数超过上限'] },
        exception: { count: 1, items: ['请假余额不足时提交失败'] },
        state: { count: 1, items: ['审批中状态不可重复提交'] },
        data: { count: 1, items: ['请假记录与审批列表数据一致'] },
        auth: { count: 0, items: [] },
        concurrency: { count: 1, items: ['多人同时审批同一申请只允许一个结果生效'] },
        idempotent: { count: 0, items: [] },
        total: 8,
      },
    ],
    affected_modules: ['请假模块'],
    affected_pages: ['请假申请页'],
  })
  return {
    id: overrides.id ?? 1,
    sessionId: 10,
    version: overrides.version ?? 1,
    subVersion: overrides.subVersion ?? 0,
    requirementText: '请假审批',
    analysisResult,
    testPoints: overrides.testPoints ? JSON.stringify(overrides.testPoints) : null,
    clarificationQuestions: null,
    clarificationAnswers: null,
    assumptions: null,
    tomScopeSnapshot: null,
    affectedCases: overrides.affectedCases ?? null,
    changeScope: overrides.changeScope ?? null,
    newCasesNeeded: overrides.newCasesNeeded ?? null,
    status: overrides.status ?? 'NEED_CONFIRMATION',
    createdAt: '2026-06-24T10:00:00',
    updatedAt: '2026-06-24T10:00:00',
  }
}

function makeSession(overrides: Partial<GenerationSession> = {}): GenerationSession {
  return {
    id: 10,
    projectId: 1,
    sessionTitle: '异步会话',
    status: 'ACTIVE',
    currentStage: 'CASE_READY',
    modelConfigId: null,
    promptTemplateId: null,
    useMiniTom: true,
    tomMode: 'PROJECT_AND_SYSTEM_TOM',
    latestAnalysisVersion: 1,
    executionTaskId: null,
    createdBy: 1,
    createdAt: '2026-06-30T10:00:00',
    updatedAt: '2026-06-30T10:00:00',
    ...overrides,
  }
}

function makeTask(overrides: Partial<AsyncGenerationTask> = {}): AsyncGenerationTask {
  return {
    taskId: 501,
    taskType: 'TEST_CASE_GENERATION',
    status: 'PENDING',
    errorCode: null,
    errorMessage: null,
    draftCount: 0,
    createdAt: '2026-06-30T10:00:00',
    updatedAt: '2026-06-30T10:00:00',
    ...overrides,
  }
}

function makeDraft(overrides: Partial<CaseDraft> = {}): CaseDraft {
  return {
    id: 9001,
    sessionId: 10,
    analysisId: 1,
    analysisVersion: 1,
    caseTitle: '审批通过生成草稿',
    moduleName: '审批模块',
    precondition: '存在待审批单据',
    steps: '1. 打开审批页面\n2. 点击通过',
    expectedResult: '审批状态更新为通过',
    priority: 'P0',
    caseType: 'FUNCTIONAL',
    status: 'DRAFT',
    sourceRefsJson: null,
    qualityStatus: null,
    createdAt: '2026-06-30T10:00:00',
    updatedAt: '2026-06-30T10:00:00',
    ...overrides,
  }
}

function mockPage(session: GenerationSession = makeSession()) {
  apiMocks.listGenerationSessions.mockResolvedValue({ items: [session], total: 1, page: 1, pageSize: 20 })
  apiMocks.listEnabledModelConfigs.mockResolvedValue([])
  apiMocks.listEnabledPromptTemplates.mockResolvedValue([])
  apiMocks.listGenerationMessages.mockResolvedValue([])
  apiMocks.listGenerationDrafts.mockResolvedValue([])
  apiMocks.getLatestGenerationAnalysis.mockResolvedValue(makeAnalysis({ status: 'CONFIRMED' }))
  apiMocks.listGenerationAnalyses.mockResolvedValue([makeAnalysis({ status: 'CONFIRMED' })])
  generationApiMocks.listAttachments.mockResolvedValue([])
}

beforeEach(() => {
  vi.clearAllMocks()
  mockPage()
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

async function waitForSessionLoaded() {
  await waitFor(() => {
    expect(screen.getAllByText('异步会话').length).toBeGreaterThan(0)
  })
}

function mockImmediatePolling() {
  const nativeSetTimeout = window.setTimeout.bind(window)
  vi.spyOn(window, 'setTimeout').mockImplementation((handler: TimerHandler, timeout?: number, ...args: any[]) => {
    if (timeout !== 2000 && timeout !== 3000) return nativeSetTimeout(handler, timeout, ...args)
    const callback = typeof handler === 'function' ? handler : () => {}
    void Promise.resolve().then(() => callback())
    return 1 as any
  })
}

// ── 1. 顺序断言 (AnalysisMessageContent) ──────────────
describe('AnalysisMessageContent - 顺序断言', () => {
  it('评审前需确认问题 渲染在 需要澄清 之前', () => {
    const { container } = render(<AnalysisMessageContent analysis={makeAnalysis()} />)
    const html = container.innerHTML
    const reviewIdx = html.indexOf('评审前需确认问题')
    const clarifyIdx = html.indexOf('需要澄清')
    expect(reviewIdx).toBeGreaterThanOrEqual(0)
    expect(clarifyIdx).toBeGreaterThan(reviewIdx)
  })

  it('影响范围 渲染在 评审前需确认问题 之前', () => {
    const { container } = render(<AnalysisMessageContent analysis={makeAnalysis()} />)
    const html = container.innerHTML
    const affectIdx = html.indexOf('影响范围')
    const reviewIdx = html.indexOf('评审前需确认问题')
    expect(affectIdx).toBeGreaterThanOrEqual(0)
    expect(reviewIdx).toBeGreaterThan(affectIdx)
  })

  it('覆盖矩阵 默认以可展开详情呈现', () => {
    const { container } = render(<AnalysisMessageContent analysis={makeAnalysis()} />)
    const details = container.querySelector('details')
    expect(details).not.toBeNull()
    expect(container.innerHTML).toContain('【覆盖矩阵】')
    expect(container.innerHTML).toContain('1 模块 / 合计 8')
    expect(container.innerHTML).toContain('请假审批')
    expect(container.innerHTML).toContain('边界明细')
    expect(container.innerHTML).toContain('请假天数超过上限')
    expect(container.innerHTML).toContain('并发明细')
    expect(container.innerHTML).toContain('多人同时审批同一申请只允许一个结果生效')
    expect(container.innerHTML).toContain('未覆盖或待确认：权限、幂等')
  })
})

// ── 2. 输入来源标签 (AnalysisPanel) ──────────────
describe('AnalysisPanel - 输入来源标签', () => {
  it('渲染 input_sources 中的 PRD_TEXT 和 BLUEPRINT', () => {
    const analysis = makeAnalysis({ analysisResult: JSON.stringify({
      requirement_understanding: '测试', input_sources: ['PRD_TEXT', 'BLUEPRINT'],
    }) })
    const { container } = render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} />)
    const html = container.innerHTML
    expect(html).toContain('PRD_TEXT')
    expect(html).toContain('BLUEPRINT')
  })

  it('渲染 teal 样式标签', () => {
    const analysis = makeAnalysis({ analysisResult: JSON.stringify({
      requirement_understanding: '测试', input_sources: ['TOM'],
    }) })
    const { container } = render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} />)
    expect(container.innerHTML).toContain('bg-teal-50')
  })
})

describe('AnalysisPanel - 用例编排计划', () => {
  it('展示节点聚焦、完整流程及其具体用例设计项', () => {
    const analysis = makeAnalysis({ analysisResult: JSON.stringify({
      requirement_understanding: '提交后审批并通知',
      case_plan: [
        { id: 'CP1', title: '审批节点', case_strategy: 'NODE_FOCUSED', source_test_point_refs: ['TP2'], precondition_test_point_refs: ['TP1'], case_designs: [{ id: 'CD1', title: '审批通过', design_method: '状态迁移' }] },
        { id: 'CP2', title: '完整闭环', case_strategy: 'FLOW_COMPOSED', source_test_point_refs: ['TP1', 'TP2'], case_designs: [{ id: 'CD2', title: '提交到通知', design_method: '场景法' }] },
      ],
    }) })

    const { container } = render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} />)

    expect(container.innerHTML).toContain('用例编排计划 (2)')
    expect(container.innerHTML).toContain('NODE_FOCUSED')
    expect(container.innerHTML).toContain('FLOW_COMPOSED')
    expect(container.innerHTML).toContain('CD1')
    expect(container.innerHTML).toContain('提交到通知')
  })
})

describe('AnalysisPanel - 结构化模型输出兜底', () => {
  it('对象型 impact/source_basis 不会触发 React 对象渲染错误', () => {
    const analysis = makeAnalysis({
      analysisResult: JSON.stringify({
        requirement_understanding: {
          content: '签到签出流程需求理解',
          source_basis: ['需求描述'],
        },
        requirement_type: { value: 'RULE', source_basis: ['PRD'] },
        review_risk_questions: [
          {
            question: '签到签出异常状态如何处理？',
            reason: '状态流转需要确认',
            impact: {
              impact: '影响测试覆盖和预期结果',
              condition: '签到失败、重复签到、签出失败',
              source_basis: ['TOM 状态：已签到 -> 已签出'],
            },
          },
        ],
        test_points: [
          {
            title: { title: '签到签出主流程', source_basis: ['签到管理模块'] },
            description: {
              condition: '用户完成签到后再签出',
              impact: '验证状态更新和记录一致性',
              source_basis: ['签到状态：已签到 -> 已签出'],
            },
            point_type: { value: 'STATE', source_basis: ['状态流转'] },
            priority_hint: { value: 'RISK', reason: '状态异常影响较大' },
            source_basis: [
              {
                impact: '来源为 TOM 节点',
                condition: '命中签到管理模块',
                source_basis: ['TOM:签到管理模块'],
              },
            ],
          },
        ],
        evidence_summary: {
          evidence_count: 1,
          confidence_label: { value: 'HIGH', source_basis: ['TOM'] },
          tom_node_refs: [
            { impact: '签到管理模块', condition: '签到状态', source_basis: ['TOM'] },
          ],
        },
      }),
    })

    const { container } = render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} />)
    const html = container.innerHTML
    expect(html).toContain('内容：签到签出流程需求理解')
    expect(html).toContain('影响：影响测试覆盖和预期结果')
    expect(html).toContain('条件：签到失败、重复签到、签出失败')
    expect(html).toContain('值：RULE')
    expect(html).toContain('值：HIGH')
    expect(html).toContain('来源为 TOM 节点')
  })
})

// ── 3. 需求类型标签 (AnalysisPanel) ──────────────
describe('AnalysisPanel - 需求类型标签', () => {
  it('渲染 requirement_type 为 RULE 时显示 indigo 标签', () => {
    const analysis = makeAnalysis({ analysisResult: JSON.stringify({
      requirement_understanding: '测试', requirement_type: 'RULE',
    }) })
    const { container } = render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} />)
    const html = container.innerHTML
    expect(html).toContain('RULE')
    expect(html).toContain('bg-indigo-50')
  })

  it('渲染 requirement_type 为 UI 时也正确显示', () => {
    const analysis = makeAnalysis({ analysisResult: JSON.stringify({
      requirement_understanding: '测试', requirement_type: 'UI',
    }) })
    const { container } = render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} />)
    expect(container.innerHTML).toContain('UI')
  })
})

// ── 4. 测试点标签 (AnalysisPanel) ──────────────
describe('AnalysisPanel - 测试点标签', () => {
  it('渲染 point_type 和 priority_hint 标签', () => {
    const tps = [
      { title: '测试点A', point_type: 'BOUNDARY', priority_hint: 'RISK' },
      { title: '测试点B', point_type: 'STATE', priority_hint: 'CORE' },
    ]
    const analysis = makeAnalysis({ testPoints: tps })
    const { container } = render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} />)
    const html = container.innerHTML
    expect(html).toContain('边界条件')
    expect(html).toContain('风险')
    expect(html).toContain('状态流转')
    expect(html).toContain('核心')
  })

  it('中文化测试维度，并将 0-1 规则值正确显示为百分比', () => {
    const analysis = makeAnalysis({
      analysisResult: JSON.stringify({
        requirement_understanding: '测试',
        evidence_summary: { tom_node_refs: ['请假审批流程'] },
        test_points: [{
          title: '验证预约主流程',
          point_type: 'MAIN_FLOW',
          skill_layer: 'FUNCTIONAL',
          priority_hint: 'CORE',
          test_dimension: 'main_flow',
          confidence: 0.85,
        }],
      }),
    })

    const { container } = render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} />)
    const html = container.innerHTML
    expect(html).toContain('测试维度：主流程')
    expect(html).toContain('规则置信：85%')
    expect(html).not.toContain('0.85%')
    expect(html).toContain('本轮已向覆盖矩阵提供 1 个 TOM 节点作为上下文')
  })

  it('不同 priority_hint 显示不同颜色标签', () => {
    const tps = [
      { title: '核心流程', point_type: 'MAIN_FLOW', priority_hint: 'CORE' },
      { title: '风险点', point_type: 'CONCURRENCY', priority_hint: 'RISK' },
      { title: '扩展点', point_type: 'EXCEPTION', priority_hint: 'EXTENDED' },
    ]
    const analysis = makeAnalysis({ testPoints: tps })
    const { container } = render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} />)
    const html = container.innerHTML
    expect(html).toContain('bg-green-50')
    expect(html).toContain('bg-red-50')
    expect(html).toContain('bg-blue-50')
  })

  it('支持人工确认生成、仅参考和排除范围', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined)
    const points = [
      { id: 'TP1', title: '本期新增审批', scope_recommendation: 'IN_SCOPE', generation_scope: 'GENERATE' },
      { id: 'TP2', title: '历史背景说明', scope_recommendation: 'REFERENCE_ONLY', generation_scope: 'REFERENCE_ONLY' },
    ]
    const analysis = makeAnalysis({
      testPoints: points,
      analysisResult: JSON.stringify({
        requirement_understanding: '测试',
        test_point_scope_review: { status: 'PENDING' },
      }),
    })

    render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} onConfirmTestPointScope={onConfirm} />)
    expect(screen.getByText('待人工确认')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('测试点范围-历史背景说明'), { target: { value: 'EXCLUDED' } })
    fireEvent.click(screen.getByRole('button', { name: '确认测试点范围' }))

    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith(1, [
      expect.objectContaining({ testPointId: 'TP1', disposition: 'GENERATE' }),
      expect.objectContaining({ testPointId: 'TP2', disposition: 'EXCLUDED' }),
    ]))
  })

  it('需求范围确认后才启动覆盖矩阵和测试点任务', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined)
    const analysis = makeAnalysis({
      status: 'NEED_SCOPE_CONFIRMATION',
      testPoints: [],
      analysisResult: JSON.stringify({
        requirement_understanding: '本期新增审批，历史报表仅作背景',
        requirement_atoms: [
          { id: 'R1', title: '新增审批', requirement: '本期新增审批流程', scope_recommendation: 'IN_SCOPE', generation_scope: 'GENERATE' },
          { id: 'R2', title: '历史报表', requirement: '历史报表结构说明', scope_recommendation: 'REFERENCE_ONLY', generation_scope: 'REFERENCE_ONLY' },
        ],
        requirement_scope_review: { status: 'PENDING' },
      }),
    })

    render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} onConfirmRequirementScope={onConfirm} />)
    expect(screen.getByText('等待人工确认')).toBeTruthy()
    expect(screen.queryByText('测试点生成范围')).toBeNull()
    fireEvent.change(screen.getByLabelText('需求范围-R2'), { target: { value: 'EXCLUDED' } })
    fireEvent.click(screen.getByRole('button', { name: '确认范围并生成测试点' }))

    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith(1, [
      expect.objectContaining({ requirementAtomId: 'R1', disposition: 'GENERATE' }),
      expect.objectContaining({ requirementAtomId: 'R2', disposition: 'EXCLUDED' }),
    ]))
  })

  it('AI 建议仅参考或待确认的需求默认仍选本期生成', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined)
    const analysis = makeAnalysis({
      status: 'NEED_SCOPE_CONFIRMATION',
      testPoints: [],
      analysisResult: JSON.stringify({
        requirement_understanding: '需求包含背景说明和待澄清入口',
        requirement_atoms: [
          { id: 'R1', title: '背景规则', scope_recommendation: 'REFERENCE_ONLY', generation_scope: 'REFERENCE_ONLY', scope_decision_source: 'AI_RECOMMENDATION' },
          { id: 'R2', title: '待澄清入口', scope_recommendation: 'NEEDS_CONFIRMATION', generation_scope: 'REFERENCE_ONLY', scope_decision_source: 'AI_RECOMMENDATION' },
        ],
        requirement_scope_review: { status: 'PENDING' },
      }),
    })

    render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} onConfirmRequirementScope={onConfirm} />)

    expect((screen.getByLabelText('需求范围-R1') as HTMLSelectElement).value).toBe('GENERATE')
    expect((screen.getByLabelText('需求范围-R2') as HTMLSelectElement).value).toBe('GENERATE')
    const button = screen.getByRole('button', { name: '确认范围并生成测试点' })
    expect(button).not.toBeDisabled()
    fireEvent.click(button)
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith(1, [
      expect.objectContaining({ requirementAtomId: 'R1', disposition: 'GENERATE' }),
      expect.objectContaining({ requirementAtomId: 'R2', disposition: 'GENERATE' }),
    ]))
  })

  it('需求范围未确认时不提前展示模型残留的测试点审核区', () => {
    const analysis = makeAnalysis({
      status: 'NEED_SCOPE_CONFIRMATION',
      testPoints: [{ id: 'TP1', title: '不应提前出现的测试点', generation_scope: 'GENERATE' }],
      analysisResult: JSON.stringify({
        requirement_understanding: '先审核需求范围',
        requirement_atoms: [{ id: 'R1', title: '新增审批', scope_recommendation: 'IN_SCOPE', generation_scope: 'GENERATE' }],
        requirement_scope_review: { status: 'PENDING' },
        test_points: [{ id: 'TP1', title: '不应提前出现的测试点', generation_scope: 'GENERATE' }],
      }),
    })

    render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} onConfirmRequirementScope={vi.fn()} />)

    expect(screen.queryByText('测试点生成范围')).toBeNull()
    expect(screen.queryByText('不应提前出现的测试点')).toBeNull()
  })

  it('明确不在本期的需求默认进入排除且可填写人工调整原因', () => {
    const analysis = makeAnalysis({
      status: 'NEED_SCOPE_CONFIRMATION',
      testPoints: [],
      analysisResult: JSON.stringify({
        requirement_understanding: '本期新增审批，下期再做导出',
        requirement_atoms: [
          { id: 'R1', title: '新增审批', scope_recommendation: 'IN_SCOPE', generation_scope: 'GENERATE' },
          { id: 'R2', title: '报表导出', scope_recommendation: 'OUT_OF_SCOPE', generation_scope: 'EXCLUDED' },
        ],
        requirement_scope_review: { status: 'PENDING' },
      }),
    })

    render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} onConfirmRequirementScope={vi.fn()} />)

    expect((screen.getByLabelText('需求范围-R2') as HTMLSelectElement).value).toBe('EXCLUDED')
    fireEvent.change(screen.getByLabelText('需求范围-R2'), { target: { value: 'GENERATE' } })
    expect(screen.getByPlaceholderText('人工调整原因（可选）')).toBeTruthy()
  })

  it('大量测试点分页渲染并支持按筛选结果批量排除', () => {
    const points = Array.from({ length: 65 }, (_, index) => ({
      id: `TP${index + 1}`,
      title: index === 64 ? '仅供背景的历史报表' : `本期审批测试点 ${index + 1}`,
      related_module: index === 64 ? '历史报表' : '审批',
      generation_scope: 'GENERATE',
    }))
    const analysis = makeAnalysis({
      testPoints: points,
      analysisResult: JSON.stringify({
        requirement_understanding: '测试',
        test_point_scope_review: { status: 'PENDING' },
      }),
    })

    render(<AnalysisPanel analyses={[analysis]} latestAnalysis={analysis} onConfirmTestPointScope={vi.fn()} />)

    expect(screen.getByText('第 1 / 3 页 · 筛选后 65 条')).toBeTruthy()
    expect(screen.queryByText('仅供背景的历史报表')).toBeNull()
    fireEvent.change(screen.getByPlaceholderText('搜索测试点、模块、维度'), { target: { value: '历史报表' } })
    expect(screen.getByText('仅供背景的历史报表')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '筛选项排除' }))
    expect((screen.getByLabelText('测试点范围-仅供背景的历史报表') as HTMLSelectElement).value).toBe('EXCLUDED')
  })
})

// ── Smoke test ──────────────────────────────
describe('TestCaseGeneration', () => {
  it('折叠大量已完成节点，只保留当前节点和完成摘要在聊天区上方', async () => {
    const completedStages = Array.from({ length: 63 }, (_, index) => ({
      code: `COVERAGE_${index + 1}`,
      label: `生成覆盖矩阵节点 ${index + 1}`,
      status: 'SUCCEEDED' as const,
    }))
    mockPage(makeSession({ executionTaskId: 778, currentStage: 'ASK_TOM_MODE' }))
    apiMocks.getAsyncGenerationTask.mockResolvedValue(makeTask({
      taskId: 778,
      taskType: 'REQUIREMENT_ANALYSIS',
      status: 'RUNNING',
      stages: [...completedStages, {
        code: 'TEST_POINTS', label: '拆解测试点', status: 'RUNNING',
      }],
    }))

    render(<TestCaseGeneration />)

    await waitForSessionLoaded()
    await waitFor(() => {
      expect(apiMocks.getAsyncGenerationTask).toHaveBeenCalledWith(1, 778)
    })
    await screen.findByText(/已完成 63 个节点/)
    expect(screen.getByText('拆解测试点')).toBeTruthy()
    const details = screen.getByText(/已完成 63 个节点/).closest('details') as HTMLDetailsElement
    expect(details.open).toBe(false)
    expect(details.querySelector('.max-h-64.overflow-y-auto')).toBeTruthy()
  })

  it('重新进入会话时恢复已有异步任务，而不是允许重复发送', async () => {
    mockPage(makeSession({ executionTaskId: 777, currentStage: 'ASK_TOM_MODE' }))
    apiMocks.getAsyncGenerationTask.mockResolvedValue(makeTask({ taskId: 777, taskType: 'REQUIREMENT_ANALYSIS', status: 'RUNNING' }))

    render(<TestCaseGeneration />)

    await waitForSessionLoaded()
    await screen.findByText('异步需求分析任务 #777')
    expect(apiMocks.getAsyncGenerationTask).toHaveBeenCalledWith(1, 777)
    expect(screen.getByRole('button', { name: '分析中...' })).toBeDisabled()
  })

  it('会话刷新恢复到已完成分析任务时释放思考状态并允许继续澄清', async () => {
    const session = makeSession({ currentStage: 'WAITING_REQUIREMENT_SCOPE', latestAnalysisVersion: 1 })
    const completedSession = makeSession({
      executionTaskId: 54,
      currentStage: 'WAITING_REQUIREMENT_SCOPE',
      latestAnalysisVersion: 1,
    })
    mockPage(session)
    const pendingAnalysis = makeAnalysis({ status: 'NEED_SCOPE_CONFIRMATION' })
    apiMocks.getLatestGenerationAnalysis.mockResolvedValue(pendingAnalysis)
    apiMocks.listGenerationAnalyses.mockResolvedValue([pendingAnalysis])
    apiMocks.startSessionRequirementAnalysisTask.mockResolvedValue(makeTask({
      taskId: 54,
      taskType: 'REQUIREMENT_ANALYSIS',
      status: 'PENDING',
    }))
    apiMocks.getAsyncGenerationTask
      .mockImplementationOnce(() => new Promise(() => {}))
      .mockResolvedValueOnce(makeTask({
        taskId: 54,
        taskType: 'REQUIREMENT_ANALYSIS',
        status: 'SUCCEEDED',
      }))

    render(<TestCaseGeneration />)
    await waitForSessionLoaded()

    const composer = screen.getByPlaceholderText(/输入需求描述/)
    fireEvent.change(composer, { target: { value: '补充澄清内容' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(apiMocks.getAsyncGenerationTask).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('button', { name: '分析中...' })).toBeDisabled()

    apiMocks.listGenerationSessions.mockResolvedValue({
      items: [completedSession], total: 1, page: 1, pageSize: 20,
    })
    fireEvent.click(screen.getByRole('button', { name: '刷新' }))

    await waitFor(() => expect(apiMocks.getAsyncGenerationTask).toHaveBeenCalledTimes(2))
    await screen.findByText(/状态：已成功/)
    expect(composer).not.toBeDisabled()
    fireEvent.change(composer, { target: { value: '继续补充另一条澄清' } })
    expect(screen.getByRole('button', { name: '发送' })).not.toBeDisabled()
    expect(screen.queryByText('正在思考')).not.toBeInTheDocument()
  })

  it('renders without crashing', () => {
    const { container } = render(<TestCaseGeneration />)
    expect(container).toBeTruthy()
    expect(container.innerHTML.length).toBeGreaterThan(0)
  })

  it('等待确认阶段补充内容启动异步需求分析，不走同步消息接口', async () => {
    mockPage(makeSession({ currentStage: 'WAITING_USER_CONFIRMATION', latestAnalysisVersion: 1 }))
    apiMocks.startSessionRequirementAnalysisTask.mockResolvedValue(makeTask({ taskType: 'REQUIREMENT_ANALYSIS', status: 'PENDING' }))

    render(<TestCaseGeneration />)
    await waitForSessionLoaded()

    fireEvent.change(screen.getByPlaceholderText(/输入需求描述/), { target: { value: '规则入口在用户管理页面' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => {
      expect(apiMocks.startSessionRequirementAnalysisTask).toHaveBeenCalledWith(1, 10, '规则入口在用户管理页面')
    })
    expect(apiMocks.sendGenerationMessage).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '分析中...' })).toBeDisabled()
  })

  it('等待需求范围确认阶段输入澄清内容会重新分析', async () => {
    mockPage(makeSession({ currentStage: 'WAITING_REQUIREMENT_SCOPE', latestAnalysisVersion: 1 }))
    const pendingAnalysis = makeAnalysis({ status: 'NEED_SCOPE_CONFIRMATION' })
    apiMocks.getLatestGenerationAnalysis.mockResolvedValue(pendingAnalysis)
    apiMocks.listGenerationAnalyses.mockResolvedValue([pendingAnalysis])
    apiMocks.startSessionRequirementAnalysisTask.mockResolvedValue(makeTask({ taskType: 'REQUIREMENT_ANALYSIS', status: 'PENDING' }))

    render(<TestCaseGeneration />)
    await waitForSessionLoaded()

    fireEvent.change(screen.getByPlaceholderText(/输入需求描述/), { target: { value: '规则入口在用户管理页面' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => {
      expect(apiMocks.startSessionRequirementAnalysisTask).toHaveBeenCalledWith(1, 10, '规则入口在用户管理页面')
    })
    expect(apiMocks.sendGenerationMessage).not.toHaveBeenCalled()
  })

  it('范围未确认时输入生成用例先返回审核提示，不创建生成任务', async () => {
    mockPage(makeSession({ currentStage: 'WAITING_REQUIREMENT_SCOPE', latestAnalysisVersion: 1 }))
    const pendingAnalysis = makeAnalysis({ status: 'NEED_SCOPE_CONFIRMATION' })
    apiMocks.getLatestGenerationAnalysis.mockResolvedValue(pendingAnalysis)
    apiMocks.listGenerationAnalyses.mockResolvedValue([pendingAnalysis])
    apiMocks.sendGenerationMessage.mockResolvedValue({ newMessages: [], analysis: pendingAnalysis })

    render(<TestCaseGeneration />)
    await waitForSessionLoaded()

    fireEvent.change(screen.getByPlaceholderText(/输入需求描述/), { target: { value: '生成用例' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => expect(apiMocks.sendGenerationMessage).toHaveBeenCalledWith(1, 10, '生成用例'))
    expect(apiMocks.startSessionCaseGenerationTask).not.toHaveBeenCalled()
  })

  it('等待确认阶段确认命令仍走同步消息，不启动异步分析', async () => {
    mockPage(makeSession({ currentStage: 'WAITING_USER_CONFIRMATION', latestAnalysisVersion: 1 }))
    apiMocks.sendGenerationMessage.mockResolvedValue({ newMessages: [], analysis: makeAnalysis({ status: 'CONFIRMED' }) })

    render(<TestCaseGeneration />)
    await waitForSessionLoaded()

    fireEvent.change(screen.getByPlaceholderText(/输入需求描述/), { target: { value: '确认' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => {
      expect(apiMocks.sendGenerationMessage).toHaveBeenCalledWith(1, 10, '确认')
    })
    expect(apiMocks.startSessionRequirementAnalysisTask).not.toHaveBeenCalled()
  })

  it('选择 TOM 时启动异步需求分析任务，不走同步消息接口', async () => {
    mockPage(makeSession({ currentStage: 'ASK_TOM_MODE', latestAnalysisVersion: 0 }))
    apiMocks.getLatestGenerationAnalysis.mockResolvedValue(null)
    apiMocks.listGenerationAnalyses.mockResolvedValue([])
    apiMocks.startSessionRequirementAnalysisTask.mockResolvedValue(makeTask({ taskType: 'REQUIREMENT_ANALYSIS', status: 'PENDING' }))
    apiMocks.getAsyncGenerationTask.mockResolvedValue(makeTask({ taskType: 'REQUIREMENT_ANALYSIS', status: 'RUNNING' }))

    render(<TestCaseGeneration />)
    await waitForSessionLoaded()

    const input = screen.getByPlaceholderText(/输入需求描述/)
    fireEvent.change(input, { target: { value: '使用 TOM' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => {
      expect(apiMocks.startSessionRequirementAnalysisTask).toHaveBeenCalledWith(1, 10, '使用 TOM')
    })
    expect(apiMocks.sendGenerationMessage).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '分析中...' })).toBeDisabled()
    expect(screen.getAllByText('需求分析中').length).toBeGreaterThan(0)
    expect(screen.getByText(/异步需求分析任务 #501/)).toBeInTheDocument()
  })

  it('已有分析但阶段残留 ASK_TOM_MODE 时生成命令不重复启动需求分析', async () => {
    mockPage(makeSession({ currentStage: 'ASK_TOM_MODE', latestAnalysisVersion: 1 }))
    apiMocks.getLatestGenerationAnalysis.mockResolvedValue(makeAnalysis({ version: 1, status: 'CONFIRMED' }))
    apiMocks.listGenerationAnalyses.mockResolvedValue([makeAnalysis({ version: 1, status: 'CONFIRMED' })])
    apiMocks.startSessionCaseGenerationTask.mockResolvedValue(makeTask({ status: 'PENDING' }))

    render(<TestCaseGeneration />)
    await waitForSessionLoaded()

    fireEvent.change(screen.getByPlaceholderText(/输入需求描述/), { target: { value: '生成用例' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => {
      expect(apiMocks.startSessionCaseGenerationTask).toHaveBeenCalledWith(1, 10, '生成用例')
    })
    expect(apiMocks.startSessionRequirementAnalysisTask).not.toHaveBeenCalled()
  })

  it('跳过确认直接生成用例启动异步生成任务，不走同步消息接口', async () => {
    mockPage(makeSession({ currentStage: 'WAITING_USER_CONFIRMATION', latestAnalysisVersion: 1 }))
    apiMocks.startSessionCaseGenerationTask.mockResolvedValue(makeTask({ status: 'PENDING' }))

    render(<TestCaseGeneration />)
    await waitForSessionLoaded()

    fireEvent.change(screen.getByPlaceholderText(/输入需求描述/), { target: { value: '跳过确认直接生成用例' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => {
      expect(apiMocks.startSessionCaseGenerationTask).toHaveBeenCalledWith(1, 10, '跳过确认直接生成用例')
    })
    expect(apiMocks.sendGenerationMessage).not.toHaveBeenCalled()
  })

  it('输入生成用例时启动会话异步任务，不调用旧同步消息接口', async () => {
    apiMocks.startSessionCaseGenerationTask.mockResolvedValue(makeTask({ status: 'PENDING' }))
    apiMocks.getAsyncGenerationTask.mockResolvedValue(makeTask({ status: 'RUNNING' }))

    render(<TestCaseGeneration />)

    await waitForSessionLoaded()
    fireEvent.change(screen.getByPlaceholderText('输入需求描述... (Ctrl+Enter 发送，Shift+Enter 换行)'), {
      target: { value: '生成用例' },
    })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await screen.findByText('异步生成任务 #501')
    expect(apiMocks.startSessionCaseGenerationTask).toHaveBeenCalledWith(1, 10, '生成用例')
    expect(apiMocks.sendGenerationMessage).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '生成中...' })).toBeDisabled()
    expect(screen.getAllByText('用例生成中').length).toBeGreaterThan(0)
  })

  it('异步需求分析完成后刷新分析结果并提示完成', async () => {
    mockImmediatePolling()
    mockPage(makeSession({ currentStage: 'ASK_TOM_MODE', latestAnalysisVersion: 0 }))
    apiMocks.getLatestGenerationAnalysis
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(makeAnalysis({ status: 'NEED_CONFIRMATION', version: 2 }))
    apiMocks.listGenerationAnalyses
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([makeAnalysis({ status: 'NEED_CONFIRMATION', version: 2 })])
    apiMocks.startSessionRequirementAnalysisTask.mockResolvedValue(makeTask({ taskType: 'REQUIREMENT_ANALYSIS', status: 'RUNNING' }))
    apiMocks.getAsyncGenerationTask.mockResolvedValue(makeTask({ taskType: 'REQUIREMENT_ANALYSIS', status: 'SUCCEEDED' }))

    render(<TestCaseGeneration />)
    await waitForSessionLoaded()

    fireEvent.change(screen.getByPlaceholderText(/输入需求描述/), { target: { value: '使用 TOM' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => {
      expect(apiMocks.getAsyncGenerationTask).toHaveBeenCalledWith(1, 501)
      expect(appMocks.showToast).toHaveBeenCalledWith('需求范围识别完成')
    })
  })

  it('异步补充分析完成后打开受影响用例弹窗，确认后启动异步增量任务', async () => {
    mockImmediatePolling()
    const affectedTitle = '审批通过生成草稿'
    const affectedDraft = makeDraft({ id: 9001, caseTitle: affectedTitle })
    const unaffectedDrafts = [
      makeDraft({ id: 9002, caseTitle: '审批驳回生成草稿' }),
      makeDraft({ id: 9003, caseTitle: '审批撤回生成草稿' }),
    ]
    const affectedAnalysis = makeAnalysis({
      status: 'NEED_CONFIRMATION',
      changeScope: 'MINOR',
      affectedCases: JSON.stringify([{ title: affectedTitle, reason: '规则变更影响', confidence: 0.9 }]),
    })
    mockPage(makeSession({ currentStage: 'WAITING_USER_CONFIRMATION', latestAnalysisVersion: 1 }))
    apiMocks.listGenerationDrafts
      .mockResolvedValueOnce([affectedDraft, ...unaffectedDrafts])
      .mockResolvedValue([affectedDraft, ...unaffectedDrafts])
    apiMocks.getLatestGenerationAnalysis.mockResolvedValue(affectedAnalysis)
    apiMocks.listGenerationAnalyses.mockResolvedValue([affectedAnalysis])
    apiMocks.startSessionRequirementAnalysisTask.mockResolvedValue(makeTask({ taskType: 'REQUIREMENT_ANALYSIS', status: 'RUNNING' }))
    apiMocks.getAsyncGenerationTask.mockResolvedValue(makeTask({ taskType: 'REQUIREMENT_ANALYSIS', status: 'SUCCEEDED' }))
    apiMocks.startSessionIncrementalGenerationTask.mockResolvedValue(makeTask({ taskType: 'INCREMENTAL_CASE_GENERATION', status: 'PENDING' }))

    render(<TestCaseGeneration />)
    await waitForSessionLoaded()

    fireEvent.change(screen.getByPlaceholderText(/输入需求描述/), { target: { value: '规则入口在用户管理页面' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await screen.findByText(/确认更新 1 个用例/)
    fireEvent.click(screen.getByRole('button', { name: /确认更新 1 个用例/ }))

    await waitFor(() => {
      expect(apiMocks.startSessionIncrementalGenerationTask).toHaveBeenCalledWith(1, 10, [9001])
    })
    expect(apiMocks.startSessionRequirementAnalysisTask).toHaveBeenCalledWith(1, 10, '规则入口在用户管理页面')
    expect(apiMocks.sendGenerationMessage).not.toHaveBeenCalled()
    expect(apiMocks.generateIncremental).not.toHaveBeenCalled()
    expect(appMocks.showToast).toHaveBeenCalledWith('已启动增量更新任务')
  })

  it('轮询到任务成功后刷新草稿箱并展示完成提示', async () => {
    mockImmediatePolling()
    apiMocks.startSessionCaseGenerationTask.mockResolvedValue(makeTask({ status: 'PENDING' }))
    apiMocks.getAsyncGenerationTask.mockResolvedValue(makeTask({ status: 'SUCCEEDED', draftCount: 1 }))
    apiMocks.listGenerationDrafts
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValue([makeDraft()])

    render(<TestCaseGeneration />)

    await waitForSessionLoaded()
    fireEvent.change(screen.getByPlaceholderText('输入需求描述... (Ctrl+Enter 发送，Shift+Enter 换行)'), {
      target: { value: '生成测试用例' },
    })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await screen.findByText('异步生成任务 #501')

    await screen.findByText('用例生成完成')
    await screen.findByText('审批通过生成草稿')
    expect(apiMocks.getAsyncGenerationTask).toHaveBeenCalledWith(1, 501)
    expect(appMocks.showToast).toHaveBeenCalledWith('用例生成完成')
  })

  it('恢复到遗留 PENDING 任务后立即串行查询并更新完成状态', async () => {
    mockImmediatePolling()
    mockPage(makeSession({ executionTaskId: 501, status: 'COMPLETED', currentStage: 'CASE_READY' }))
    apiMocks.getAsyncGenerationTask
      .mockResolvedValueOnce(makeTask({ status: 'PENDING', draftCount: 18 }))
      .mockResolvedValueOnce(makeTask({ status: 'SUCCEEDED', draftCount: 18 }))
    apiMocks.listGenerationDrafts.mockResolvedValue(Array.from({ length: 18 }, (_, index) => makeDraft({ id: 9100 + index })))

    render(<TestCaseGeneration />)

    await waitForSessionLoaded()
    await screen.findByText('用例生成完成')
    expect(screen.getByText(/状态：已成功/)).toBeInTheDocument()
    expect(screen.queryByText('生成任务正在后台执行，完成后会自动刷新右侧草稿箱。')).not.toBeInTheDocument()
    expect(apiMocks.getAsyncGenerationTask).toHaveBeenCalledTimes(2)
  })

  it('任务查询短暂失败后自动重试并恢复为完成状态', async () => {
    mockImmediatePolling()
    apiMocks.startSessionCaseGenerationTask.mockResolvedValue(makeTask({ status: 'PENDING' }))
    apiMocks.getAsyncGenerationTask
      .mockRejectedValueOnce(new Error('任务状态查询暂时不可用'))
      .mockResolvedValueOnce(makeTask({ status: 'SUCCEEDED', draftCount: 1 }))
    apiMocks.listGenerationDrafts.mockResolvedValue([makeDraft()])

    render(<TestCaseGeneration />)
    await waitForSessionLoaded()
    fireEvent.change(screen.getByPlaceholderText(/输入需求描述/), { target: { value: '生成用例' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await screen.findByText('用例生成完成')
    expect(apiMocks.getAsyncGenerationTask).toHaveBeenCalledTimes(2)
    expect(appMocks.showToast).toHaveBeenCalledWith('任务状态查询暂时不可用', 'error')
    expect(screen.queryByText('生成任务正在后台执行，完成后会自动刷新右侧草稿箱。')).not.toBeInTheDocument()
  })

  it('轮询到任务失败后展示错误并允许重试', async () => {
    mockImmediatePolling()
    apiMocks.startSessionCaseGenerationTask.mockResolvedValue(makeTask({ status: 'RUNNING' }))
    apiMocks.getAsyncGenerationTask.mockResolvedValue(makeTask({
      status: 'TIMEOUT',
      errorCode: 'TIMEOUT',
      errorMessage: '模型响应超时',
    }))
    apiMocks.retryAsyncGenerationTask.mockResolvedValue(makeTask({ status: 'RUNNING', taskId: 501 }))

    render(<TestCaseGeneration />)

    await waitForSessionLoaded()
    fireEvent.change(screen.getByPlaceholderText('输入需求描述... (Ctrl+Enter 发送，Shift+Enter 换行)'), {
      target: { value: '重新生成' },
    })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await screen.findByText('异步生成任务 #501')

    await screen.findByText('模型响应超时')
    fireEvent.click(screen.getByRole('button', { name: '从失败节点继续' }))

    await waitFor(() => {
      expect(apiMocks.retryAsyncGenerationTask).toHaveBeenCalledWith(1, 501)
    })
    expect(appMocks.showToast).toHaveBeenCalledWith('模型响应超时', 'error')
  })

  it('超长异步错误可展开查看且不会撑死聊天滚动区', async () => {
    const longError = `已完成的用例编排仍遗漏测试点：${'门禁权限数据、'.repeat(80)}`
    mockPage(makeSession({ executionTaskId: 501 }))
    apiMocks.getAsyncGenerationTask.mockResolvedValue(makeTask({
      status: 'FAILED',
      errorCode: 'OUTPUT_PARSE_ERROR',
      errorMessage: longError,
      stages: [{ code: 'CASE_GENERATION', label: '生成测试用例', status: 'FAILED', errorMessage: longError }],
    }))

    const { container } = render(<TestCaseGeneration />)
    await waitForSessionLoaded()

    expect((await screen.findAllByText('展开完整错误')).length).toBeGreaterThan(0)
    expect(container.querySelector('.max-h-\\[42vh\\].overflow-y-auto')).not.toBeNull()
    expect(container.querySelector('.flex-1.overflow-y-auto')).not.toBeNull()
  })

  it('普通同步消息失败时刷新会话，避免必须手动刷新才看到失败消息', async () => {
    mockPage(makeSession({ currentStage: 'ACTIVE', latestAnalysisVersion: 1 }))
    apiMocks.sendGenerationMessage.mockRejectedValue(new Error('HTTP 524'))

    render(<TestCaseGeneration />)

    await waitForSessionLoaded()
    const callsBeforeSend = apiMocks.listGenerationMessages.mock.calls.length
    fireEvent.change(screen.getByPlaceholderText('输入需求描述... (Ctrl+Enter 发送，Shift+Enter 换行)'), {
      target: { value: '普通备注消息' },
    })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => {
      expect(appMocks.showToast).toHaveBeenCalledWith('HTTP 524', 'error')
    })
    expect(apiMocks.listGenerationMessages.mock.calls.length).toBeGreaterThan(callsBeforeSend)
    expect(screen.getByText('分析请求异常')).toBeInTheDocument()
  })
})
