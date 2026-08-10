import { describe, expect, it, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'

const apiMocks = vi.hoisted(() => ({
  listWikiPacks: vi.fn(),
  createWikiPack: vi.fn(),
  listWikiEntries: vi.fn(),
  createWikiEntry: vi.fn(),
  reviewWikiEntry: vi.fn(),
  reviewWikiPack: vi.fn(),
  updateWikiPackStatus: vi.fn(),
  updateWikiPack: vi.fn(),
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
}))

vi.mock('../services/api', () => apiMocks)

import WikiPackages from '../pages/project/WikiPackages'

beforeEach(() => {
  vi.clearAllMocks()
})

describe('WikiPackages', () => {
  it('does not crash when pack API returns a non-array payload', async () => {
    apiMocks.listWikiPacks.mockResolvedValue({ items: [] })

    render(<WikiPackages />)

    await waitFor(() => {
      expect(screen.getByText('暂无知识包')).toBeInTheDocument()
    })
  })

  it('supports reviewing entries, reviewing the pack and activating it', async () => {
    const pendingPack = {
      id: 7, projectId: 1, scope: 'PROJECT', name: '项目自动沉淀', status: 'DRAFT',
      reviewStatus: 'PENDING', trustLevel: null, sourceType: 'AUTO_DEPOSITION',
      description: null, createdBy: 1, createdAt: '', updatedAt: '',
    }
    const approvedPack = { ...pendingPack, reviewStatus: 'APPROVED' }
    apiMocks.listWikiPacks.mockResolvedValue([pendingPack])
    const pendingEntry = {
      id: 9, packId: 7, entryType: 'RULE', title: '审批规则', content: '必须审批',
      keywordsJson: null, sourceRefsJson: '{"sourceType":"REQUIREMENT_ANALYSIS"}',
      reviewStatus: 'PENDING', confidence: 0.7, effectiveStatus: 'INACTIVE',
      createdBy: 1, createdAt: '', updatedAt: '',
    }
    apiMocks.listWikiEntries.mockResolvedValue([pendingEntry])
    apiMocks.reviewWikiEntry.mockResolvedValue({ ...pendingEntry, reviewStatus: 'APPROVED', effectiveStatus: 'ACTIVE' })
    apiMocks.reviewWikiPack.mockResolvedValue(approvedPack)
    apiMocks.updateWikiPackStatus.mockResolvedValue({ ...approvedPack, status: 'ACTIVE' })

    render(<WikiPackages />)
    fireEvent.click(await screen.findByText('项目自动沉淀'))
    fireEvent.click(await screen.findByText('通过'))
    expect(apiMocks.reviewWikiEntry).toHaveBeenCalledWith(9, 'APPROVED')

    fireEvent.click(screen.getByText('通过知识包'))
    await waitFor(() => expect(apiMocks.reviewWikiPack).toHaveBeenCalledWith(7, 'APPROVED'))
    fireEvent.click(await screen.findByText('激活'))
    await waitFor(() => expect(apiMocks.updateWikiPackStatus).toHaveBeenCalledWith(7, 'ACTIVE'))
    expect(screen.getByText('查看来源')).toBeInTheDocument()
  })

  it('shows full entry details and returns an edited entry to review', async () => {
    const pack = { id: 7, projectId: 1, scope: 'PROJECT', name: '项目自动沉淀', status: 'ACTIVE', reviewStatus: 'APPROVED', description: '项目规则', createdAt: '', updatedAt: '' }
    const entry = { id: 9, packId: 7, entryType: 'RULE', title: '审批规则', content: '这里是需要完整查看和编辑的知识内容。', sourceRefsJson: '{"sourceType":"REQUIREMENT_ANALYSIS"}', reviewStatus: 'APPROVED', effectiveStatus: 'ACTIVE', createdAt: '', updatedAt: '' }
    apiMocks.listWikiPacks.mockResolvedValue([pack])
    apiMocks.listWikiEntries.mockResolvedValue([entry])
    apiMocks.updateWikiEntry.mockResolvedValue({ ...entry, title: '已修订规则', reviewStatus: 'PENDING', effectiveStatus: 'INACTIVE' })

    render(<WikiPackages />)
    fireEvent.click(await screen.findByText('项目自动沉淀'))
    fireEvent.click(await screen.findByText('查看详情'))
    expect((await screen.findAllByText('这里是需要完整查看和编辑的知识内容。')).length).toBeGreaterThan(1)
    fireEvent.click(screen.getAllByText('编辑')[0])
    fireEvent.change(screen.getByDisplayValue('审批规则'), { target: { value: '已修订规则' } })
    fireEvent.click(screen.getByText('保存并重新审核'))
    await waitFor(() => expect(apiMocks.updateWikiEntry).toHaveBeenCalledWith(9, 'RULE', '已修订规则', '这里是需要完整查看和编辑的知识内容。'))
    expect(appMocks.showToast).toHaveBeenCalledWith('知识条目已保存，已回到待审核状态')
  })
})
