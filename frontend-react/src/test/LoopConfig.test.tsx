import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'

const mockShowToast = vi.fn()

vi.mock('../context/AppContext', () => ({
  useApp: () => ({ showToast: mockShowToast, setModal: vi.fn() }),
}))

vi.mock('../services/api', () => ({
  getLoopStatus: vi.fn(),
  setLoopStatus: vi.fn(),
  listLoopEvents: vi.fn(),
  listLoopClusters: vi.fn(),
  approveLoopCluster: vi.fn(),
  rejectLoopCluster: vi.fn(),
  consumeLoopCandidates: vi.fn(),
}))

import LoopConfig from '../pages/admin/LoopConfig'
import {
  getLoopStatus, setLoopStatus,
  listLoopEvents, listLoopClusters,
  approveLoopCluster, rejectLoopCluster, consumeLoopCandidates,
} from '../services/api'

const mockGetLoopStatus = vi.mocked(getLoopStatus)
const mockSetLoopStatus = vi.mocked(setLoopStatus)
const mockListLoopEvents = vi.mocked(listLoopEvents)
const mockListLoopClusters = vi.mocked(listLoopClusters)
const mockApproveLoopCluster = vi.mocked(approveLoopCluster)
const mockRejectLoopCluster = vi.mocked(rejectLoopCluster)
const mockConsumeLoopCandidates = vi.mocked(consumeLoopCandidates)

function setupMocks(overrides: {
  enabled?: boolean
  events?: any[]
  clusters?: any[]
} = {}) {
  mockGetLoopStatus.mockResolvedValue(overrides.enabled ?? false)
  mockListLoopEvents.mockResolvedValue(overrides.events ?? [])
  mockListLoopClusters.mockResolvedValue(overrides.clusters ?? [])
  mockSetLoopStatus.mockResolvedValue(undefined as any)
  mockApproveLoopCluster.mockResolvedValue({} as any)
  mockRejectLoopCluster.mockResolvedValue({} as any)
  mockConsumeLoopCandidates.mockResolvedValue({ candidatesGenerated: 2 })
}

describe('LoopConfig', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('loads data on mount via getLoopStatus, listLoopEvents, listLoopClusters', async () => {
    setupMocks({ enabled: true, events: [{ id: 1, eventType: 'TOM_STRATEGY' }], clusters: [{ id: 10, theme: 'T' }] })
    render(<LoopConfig />)
    await waitFor(() => expect(mockGetLoopStatus).toHaveBeenCalled())
    expect(mockListLoopEvents).toHaveBeenCalledWith(1)
    expect(mockListLoopClusters).toHaveBeenCalledWith(1)
  })

  it('renders event type and cluster theme after loading', async () => {
    setupMocks({
      events: [{ id: 1, eventType: 'GENERATION_QUALITY', status: 'PENDING', normalizedIssue: 'issue A' }],
      clusters: [{ id: 10, theme: 'THEME_X', eventCount: 5, status: 'APPROVED' }],
    })
    render(<LoopConfig />)
    await waitFor(() => {
      expect(screen.getByText('GENERATION_QUALITY')).toBeDefined()
      expect(screen.getByText('THEME_X')).toBeDefined()
      expect(screen.getByText('issue A')).toBeDefined()
    })
  })

  it('reloads data when project ID changes', async () => {
    setupMocks()
    render(<LoopConfig />)
    await waitFor(() => expect(mockListLoopEvents).toHaveBeenCalledWith(1))
    vi.clearAllMocks()
    setupMocks()
    const input = screen.getByRole('spinbutton')
    fireEvent.change(input, { target: { value: '42' } })
    await waitFor(() => {
      expect(mockListLoopEvents).toHaveBeenCalledWith(42)
      expect(mockListLoopClusters).toHaveBeenCalledWith(42)
    })
  })

  it('calls setLoopStatus when toggle button clicked', async () => {
    setupMocks({ enabled: false })
    render(<LoopConfig />)
    await waitFor(() => expect(screen.getByText('开启回灌')).toBeDefined())
    vi.clearAllMocks()
    setupMocks({ enabled: true })
    fireEvent.click(screen.getByText('开启回灌'))
    await waitFor(() => expect(mockSetLoopStatus).toHaveBeenCalledWith(true))
  })

  it('calls approveLoopCluster when approve button clicked', async () => {
    setupMocks({
      clusters: [{ id: 7, theme: 'T', eventCount: 2, status: 'PENDING', suggestedAction: 'fix' }],
    })
    render(<LoopConfig />)
    await waitFor(() => expect(screen.getByText('批准')).toBeDefined())
    vi.clearAllMocks()
    setupMocks()
    fireEvent.click(screen.getByText('批准'))
    await waitFor(() => expect(mockApproveLoopCluster).toHaveBeenCalledWith(7))
  })

  it('calls rejectLoopCluster when reject button clicked', async () => {
    setupMocks({
      clusters: [{ id: 8, theme: 'R', eventCount: 1, status: 'PENDING' }],
    })
    render(<LoopConfig />)
    await waitFor(() => expect(screen.getByText('驳回')).toBeDefined())
    vi.clearAllMocks()
    setupMocks()
    fireEvent.click(screen.getByText('驳回'))
    await waitFor(() => expect(mockRejectLoopCluster).toHaveBeenCalledWith(8))
  })

  it('calls consumeLoopCandidates when consume button clicked', async () => {
    setupMocks({ enabled: true })
    render(<LoopConfig />)
    await waitFor(() => expect(screen.getByText('消费候选')).toBeDefined())
    vi.clearAllMocks()
    setupMocks()
    fireEvent.click(screen.getByText('消费候选'))
    await waitFor(() => expect(mockConsumeLoopCandidates).toHaveBeenCalledWith(1))
  })

  it('shows showToast error on toggle failure', async () => {
    setupMocks({ enabled: false })
    mockSetLoopStatus.mockRejectedValue(new Error('toggle failed'))
    render(<LoopConfig />)
    await waitFor(() => expect(screen.getByText('开启回灌')).toBeDefined())
    fireEvent.click(screen.getByText('开启回灌'))
    await waitFor(() => expect(mockShowToast).toHaveBeenCalledWith('toggle failed', 'error'))
  })

  it('shows showToast on consume success with count', async () => {
    setupMocks({ enabled: true })
    mockConsumeLoopCandidates.mockResolvedValue({ candidatesGenerated: 5 })
    render(<LoopConfig />)
    await waitFor(() => expect(screen.getByText('消费候选')).toBeDefined())
    fireEvent.click(screen.getByText('消费候选'))
    await waitFor(() => expect(mockShowToast).toHaveBeenCalledWith('生成 5 个候选资产'))
  })

  it('shows showToast error on approve failure', async () => {
    setupMocks({
      clusters: [{ id: 7, theme: 'T', eventCount: 2, status: 'PENDING', suggestedAction: 'fix' }],
    })
    mockApproveLoopCluster.mockRejectedValue(new Error('approve failed'))
    render(<LoopConfig />)
    await waitFor(() => expect(screen.getByText('批准')).toBeDefined())
    fireEvent.click(screen.getByText('批准'))
    await waitFor(() => expect(mockShowToast).toHaveBeenCalledWith('approve failed', 'error'))
  })

  it('shows showToast error on reject failure', async () => {
    setupMocks({
      clusters: [{ id: 8, theme: 'R', eventCount: 1, status: 'PENDING' }],
    })
    mockRejectLoopCluster.mockRejectedValue(new Error('reject failed'))
    render(<LoopConfig />)
    await waitFor(() => expect(screen.getByText('驳回')).toBeDefined())
    fireEvent.click(screen.getByText('驳回'))
    await waitFor(() => expect(mockShowToast).toHaveBeenCalledWith('reject failed', 'error'))
  })

  it('shows showToast error on consume failure', async () => {
    setupMocks({ enabled: true })
    mockConsumeLoopCandidates.mockRejectedValue(new Error('consume failed'))
    render(<LoopConfig />)
    await waitFor(() => expect(screen.getByText('消费候选')).toBeDefined())
    fireEvent.click(screen.getByText('消费候选'))
    await waitFor(() => expect(mockShowToast).toHaveBeenCalledWith('consume failed', 'error'))
  })

  it('reloads data after successful approve', async () => {
    setupMocks({
      clusters: [{ id: 9, theme: 'X', eventCount: 1, status: 'PENDING' }],
    })
    render(<LoopConfig />)
    await waitFor(() => expect(screen.getByText('批准')).toBeDefined())
    vi.clearAllMocks()
    setupMocks({ clusters: [{ id: 9, theme: 'X', eventCount: 1, status: 'APPROVED' }] })
    fireEvent.click(screen.getByText('批准'))
    await waitFor(() => expect(mockListLoopClusters).toHaveBeenCalled())
  })

  it('does not show approve/reject buttons for non-PENDING clusters', async () => {
    setupMocks({
      clusters: [{ id: 11, theme: 'A', eventCount: 3, status: 'APPROVED' }],
    })
    render(<LoopConfig />)
    await waitFor(() => expect(screen.getByText('A')).toBeDefined())
    expect(screen.queryByText('批准')).toBeNull()
    expect(screen.queryByText('驳回')).toBeNull()
  })

  it('shows empty state messages when no data', async () => {
    setupMocks({ events: [], clusters: [] })
    render(<LoopConfig />)
    await waitFor(() => {
      expect(screen.getByText('暂无学习事件')).toBeDefined()
      expect(screen.getByText('暂无聚类')).toBeDefined()
    })
  })
})
