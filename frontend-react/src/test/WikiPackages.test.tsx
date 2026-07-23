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
})
