import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'

const traceMocks = vi.hoisted(() => ({
  checkWorkerHealth: vi.fn(),
  listProfiles: vi.fn(),
  listGroups: vi.fn(),
  listGeneratedCasesPage: vi.fn(),
  listFormalCasesPage: vi.fn(),
}))

const apiMocks = vi.hoisted(() => ({
  api: vi.fn(),
  listLocalCasesPage: vi.fn(),
}))

vi.mock('../context/AppContext', () => ({
  useApp: () => ({ showToast: vi.fn() }),
}))

vi.mock('react-router-dom', () => ({
  Link: ({ children, to }: any) => <a href={to}>{children}</a>,
}))

vi.mock('../services/api', async importOriginal => ({
  ...(await importOriginal<typeof import('../services/api')>()),
  api: apiMocks.api,
  listLocalCasesPage: apiMocks.listLocalCasesPage,
}))

vi.mock('../services/traceApi', async importOriginal => ({
  ...(await importOriginal<typeof import('../services/traceApi')>()),
  checkWorkerHealth: traceMocks.checkWorkerHealth,
  listProfiles: traceMocks.listProfiles,
  listGroups: traceMocks.listGroups,
  listGeneratedCasesPage: traceMocks.listGeneratedCasesPage,
  listFormalCasesPage: traceMocks.listFormalCasesPage,
}))

import TracePanel from '../components/trace/TracePanel'

beforeEach(() => {
  vi.clearAllMocks()
  traceMocks.listProfiles.mockResolvedValue([])
  traceMocks.listGroups.mockResolvedValue([])
  traceMocks.checkWorkerHealth.mockResolvedValue(null)
  apiMocks.api.mockImplementation(async (path: string) => {
    if (path === '/api/model-configs/enabled') {
      return [{ id: 7, configName: '测试模型', modelName: 'model-1' }]
    }
    return []
  })
})

describe('TracePanel initial loading', () => {
  it('renders core trace controls without waiting for case-library previews', async () => {
    const never = new Promise(() => undefined)
    apiMocks.listLocalCasesPage.mockReturnValue(never)
    traceMocks.listFormalCasesPage.mockReturnValue(never)

    render(<TracePanel projectId={9} />)

    expect(await screen.findByText('测试执行轨迹')).toBeInTheDocument()
    expect(screen.getByText('身份空间')).toBeInTheDocument()

    await waitFor(() => {
      expect(traceMocks.listProfiles).toHaveBeenCalledTimes(1)
      expect(traceMocks.listGroups).toHaveBeenCalledTimes(1)
      expect(apiMocks.api).toHaveBeenCalledTimes(1)
    })

    expect(apiMocks.listLocalCasesPage).toHaveBeenCalledWith(9, 0, 10)
    expect(traceMocks.listFormalCasesPage).toHaveBeenCalledWith(9, 0, 10)
    expect(traceMocks.listGeneratedCasesPage).not.toHaveBeenCalled()
  })

  it('loads only one page of trace drafts after a group is expanded', async () => {
    traceMocks.listGroups.mockResolvedValue([{
      id: 31,
      projectId: 9,
      userId: 2,
      profileId: 4,
      groupName: '登录流程',
      status: 'STOPPED',
      createdAt: '2026-07-17T09:00:00',
      updatedAt: '2026-07-17T09:00:00',
    }])
    traceMocks.listGeneratedCasesPage.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
    apiMocks.listLocalCasesPage.mockResolvedValue({ items: [], total: 0, page: 0, size: 10 })
    traceMocks.listFormalCasesPage.mockResolvedValue({ items: [], total: 0, page: 0, size: 10 })
    apiMocks.api.mockImplementation(async (path: string) => {
      if (path === '/api/model-configs/enabled') return []
      if (path === '/api/trace/groups/31/detail') {
        return { group: {}, sessions: [], events: [], networks: [], issueClips: [] }
      }
      return []
    })

    render(<TracePanel projectId={9} />)
    fireEvent.click(await screen.findByText('登录流程'))

    await waitFor(() => {
      expect(traceMocks.listGeneratedCasesPage).toHaveBeenCalledWith(9, 31, 0, 20)
    })
  })
})
