import { describe, it, expect, vi } from 'vitest'
import { render } from '@testing-library/react'

vi.mock('../context/AppContext', () => ({
  useApp: () => ({ showToast: vi.fn(), setModal: vi.fn() }),
}))

vi.mock('react-router-dom', () => ({
  useParams: () => ({ projectId: '1' }),
  useNavigate: () => vi.fn(),
  Link: ({ children, to }: any) => <a href={to}>{children}</a>,
}))

import TestCaseGeneration, { AnalysisMessageContent, AnalysisPanel } from '../pages/project/TestCaseGeneration'
import type { RequirementAnalysis } from '../services/api'

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
    affectedCases: null,
    changeScope: null,
    newCasesNeeded: null,
    status: 'NEED_CONFIRMATION',
    createdAt: '2026-06-24T10:00:00',
    updatedAt: '2026-06-24T10:00:00',
  }
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
    expect(html).toContain('BOUNDARY')
    expect(html).toContain('RISK')
    expect(html).toContain('STATE')
    expect(html).toContain('CORE')
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
})

// ── Smoke test ──────────────────────────────
describe('TestCaseGeneration', () => {
  it('renders without crashing', () => {
    const { container } = render(<TestCaseGeneration />)
    expect(container).toBeTruthy()
    expect(container.innerHTML.length).toBeGreaterThan(0)
  })
})
