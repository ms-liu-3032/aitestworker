import { describe, expect, it, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'

const apiMocks = vi.hoisted(() => ({
  listFormalCases: vi.fn(),
  listLocalCases: vi.fn(),
  submitLocalCase: vi.fn(),
  listProjectSummaries: vi.fn(),
  listProjectSkills: vi.fn(),
  listProjectTools: vi.fn(),
  updateProjectSkill: vi.fn(),
  updateProjectTool: vi.fn(),
  listWikiPacks: vi.fn(),
  listWikiEntries: vi.fn(),
  updateWikiEntry: vi.fn(),
}))

const appMocks = vi.hoisted(() => ({
  showToast: vi.fn(),
}))

vi.mock('../context/AppContext', () => ({
  useApp: () => appMocks,
}))

vi.mock('react-router-dom', () => ({
  useParams: () => ({ projectId: '1' }),
  Link: ({ children, to }: any) => <a href={to}>{children}</a>,
}))

vi.mock('../services/api', () => apiMocks)

import TestAssets from '../pages/project/TestAssets'

beforeEach(() => {
  vi.clearAllMocks()
  apiMocks.listLocalCases.mockResolvedValue({ items: [] })
  apiMocks.listFormalCases.mockResolvedValue({ items: [] })
  apiMocks.listProjectSummaries.mockResolvedValue([])
  apiMocks.listProjectSkills.mockResolvedValue([])
  apiMocks.listProjectTools.mockResolvedValue([])
  apiMocks.listWikiPacks.mockResolvedValue([])
})

describe('TestAssets', () => {
  it('does not crash when asset APIs return non-array payloads', async () => {
    render(<TestAssets />)

    await waitFor(() => {
      expect(screen.getByText('暂无轨迹摘要，先去测试执行轨迹里生成或确认摘要。')).toBeInTheDocument()
    })
  })

  it('shows project Wiki entries and lets users inspect and edit a candidate', async () => {
    const pack = { id: 7, projectId: 1, scope: 'PROJECT', name: '项目自动沉淀', status: 'DRAFT', reviewStatus: 'PENDING', description: '自动候选', createdAt: '', updatedAt: '' }
    const entry = { id: 9, packId: 7, entryType: 'RULE', title: '预约规则', content: '预约需要审批后才允许签到。', reviewStatus: 'PENDING', effectiveStatus: 'INACTIVE', sourceRefsJson: '{"sourceType":"REQUIREMENT_ANALYSIS"}', createdAt: '', updatedAt: '' }
    apiMocks.listWikiPacks.mockResolvedValue([pack])
    apiMocks.listWikiEntries.mockResolvedValue([entry])
    apiMocks.updateWikiEntry.mockResolvedValue({ ...entry, title: '更新后的预约规则', reviewStatus: 'PENDING' })

    render(<TestAssets />)
    fireEvent.click(screen.getByRole('button', { name: /项目 Wiki/ }))
    expect(await screen.findByText('预约规则')).toBeInTheDocument()
    fireEvent.click(screen.getByText('查看详情'))
    expect(screen.getAllByText('预约需要审批后才允许签到。')).toHaveLength(2)
    fireEvent.click(screen.getByText('关闭'))
    fireEvent.click(screen.getByText('编辑'))
    fireEvent.change(screen.getByDisplayValue('预约规则'), { target: { value: '更新后的预约规则' } })
    fireEvent.click(screen.getByText('保存并重新审核'))
    await waitFor(() => expect(apiMocks.updateWikiEntry).toHaveBeenCalledWith(9, 'RULE', '更新后的预约规则', '预约需要审批后才允许签到。'))
  })

  it('uses project asset APIs and sends edited Skill content back for review', async () => {
    const skill = { id: 8, projectId: 1, skillName: '预约操作', applicableScene: '业务对象预约', flowSteps: '1. 填表\n2. 提交', status: 'ACTIVE', createdAt: '', updatedAt: '' }
    apiMocks.listProjectSkills.mockResolvedValue([skill])
    apiMocks.updateProjectSkill.mockResolvedValue({ ...skill, skillName: '预约操作修订', status: 'DRAFT' })

    render(<TestAssets />)
    fireEvent.click(screen.getByRole('button', { name: /Skill 模板/ }))
    fireEvent.click(await screen.findByText('编辑'))
    fireEvent.change(screen.getByDisplayValue('预约操作'), { target: { value: '预约操作修订' } })
    fireEvent.click(screen.getByText('保存并重新审核'))
    await waitFor(() => expect(apiMocks.updateProjectSkill).toHaveBeenCalledWith(1, 8, {
      skillName: '预约操作修订', applicableScene: '业务对象预约', flowSteps: '1. 填表\n2. 提交',
    }))
  })
})
