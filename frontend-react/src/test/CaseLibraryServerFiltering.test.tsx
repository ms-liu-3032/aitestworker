import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({
  listLocalCasesPage: vi.fn(),
  listFormalCasesPage: vi.fn(),
  exportLocalCasesToXmind: vi.fn(),
}))

const appMocks = vi.hoisted(() => ({
  showToast: vi.fn(),
}))

vi.mock('../context/AppContext', () => ({
  useApp: () => appMocks,
}))

vi.mock('react-router-dom', () => ({
  useParams: () => ({ projectId: '9' }),
  Link: ({ children, to }: any) => <a href={to}>{children}</a>,
}))

vi.mock('../services/api', () => apiMocks)

import FormalCaseLibrary from '../pages/project/FormalCaseLibrary'
import LocalCaseLibrary from '../pages/project/LocalCaseLibrary'

const emptyPage = {
  items: [],
  total: 0,
  page: 0,
  size: 50,
  moduleOptions: ['审批模块'],
}

describe('case libraries use server-side filtering', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.listLocalCasesPage.mockResolvedValue(emptyPage)
    apiMocks.listFormalCasesPage.mockResolvedValue(emptyPage)
    apiMocks.exportLocalCasesToXmind.mockResolvedValue(new Blob(['xmind']))
  })

  afterEach(() => cleanup())

  it('reloads local cases from page zero with the current filters', async () => {
    apiMocks.listLocalCasesPage
      .mockReset()
      .mockResolvedValueOnce({ ...emptyPage, page: 4, total: 300 })
      .mockResolvedValue({ ...emptyPage, page: 0, total: 8 })
    render(<LocalCaseLibrary />)

    await waitFor(() => expect(apiMocks.listLocalCasesPage).toHaveBeenCalledWith(9, 0, 50, {
      keyword: '',
      modules: [],
      priorities: [],
      statuses: [],
      sources: [],
      scenarioTypes: [],
    }))
    expect(await screen.findByText('第 5 / 6 页，共 300 条')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('搜索用例名称、模块、类型...'), {
      target: { value: '门禁审批' },
    })
    expect(screen.getByText('正在统计筛选结果...')).toBeInTheDocument()

    await waitFor(() => expect(apiMocks.listLocalCasesPage).toHaveBeenLastCalledWith(9, 0, 50, {
      keyword: '门禁审批',
      modules: [],
      priorities: [],
      statuses: [],
      sources: [],
      scenarioTypes: [],
    }))
    expect(await screen.findByText('第 1 / 1 页，共 8 条')).toBeInTheDocument()
  })

  it('reloads formal cases from page zero with the current filters', async () => {
    apiMocks.listFormalCasesPage
      .mockReset()
      .mockResolvedValueOnce({ ...emptyPage, page: 4, total: 300 })
      .mockResolvedValue({ ...emptyPage, page: 0, total: 6 })
    render(<FormalCaseLibrary />)

    await waitFor(() => expect(apiMocks.listFormalCasesPage).toHaveBeenCalledWith(9, 0, 50, {
      keyword: '',
      modules: [],
      priorities: [],
      statuses: [],
      sources: [],
      scenarioTypes: [],
    }))
    expect(await screen.findByText('第 5 / 6 页，共 300 条')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('搜索编号、名称、模块...'), {
      target: { value: '动态二维码' },
    })
    expect(screen.getByText('正在统计筛选结果...')).toBeInTheDocument()

    await waitFor(() => expect(apiMocks.listFormalCasesPage).toHaveBeenLastCalledWith(9, 0, 50, {
      keyword: '动态二维码',
      modules: [],
      priorities: [],
      statuses: [],
      sources: [],
      scenarioTypes: [],
    }))
    expect(await screen.findByText('第 1 / 1 页，共 6 条')).toBeInTheDocument()
  })

  it('exports all confirmed or submitted local cases through the protected export endpoint', async () => {
    const createObjectURL = vi.fn(() => 'blob:local-cases')
    const revokeObjectURL = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectURL })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    apiMocks.listLocalCasesPage.mockResolvedValue({ ...emptyPage, total: 1, items: [{
      id: 8,
      projectId: 9,
      caseNo: 'TC-8',
      caseTitle: '已确认审批用例',
      caseStatus: 'CONFIRMED',
      sourceType: 'GENERATION',
      createdAt: '2026-07-21T10:00:00',
      updatedAt: '2026-07-21T10:00:00',
    }] })

    render(<LocalCaseLibrary />)
    await screen.findByText('已确认审批用例')
    fireEvent.click(screen.getByRole('button', { name: '导出全部已确认/已提交' }))

    await waitFor(() => expect(apiMocks.exportLocalCasesToXmind).toHaveBeenCalledWith(9, undefined))
    expect(createObjectURL).toHaveBeenCalled()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:local-cases')
    expect(appMocks.showToast).toHaveBeenCalledWith('本地用例导出完成')
  })

  it('exports only eligible cases from a mixed local selection', async () => {
    const createObjectURL = vi.fn(() => 'blob:selected-local-cases')
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    apiMocks.listLocalCasesPage.mockResolvedValue({
      ...emptyPage,
      total: 2,
      items: [
        {
          id: 7, projectId: 9, caseNo: 'TC-7', caseTitle: '仍是草稿', caseStatus: 'DRAFT',
          sourceType: 'GENERATION', createdAt: '2026-07-21T10:00:00', updatedAt: '2026-07-21T10:00:00',
        },
        {
          id: -8, projectId: 9, caseNo: 'TRACE-8', caseTitle: '已提交轨迹用例', caseStatus: 'SUBMITTED',
          sourceType: 'TRACE', createdAt: '2026-07-21T10:00:00', updatedAt: '2026-07-21T10:00:00',
        },
      ],
    })

    render(<LocalCaseLibrary />)
    await screen.findByText('已提交轨迹用例')
    const checkboxes = screen.getAllByRole('checkbox')
    fireEvent.click(checkboxes[1])
    fireEvent.click(checkboxes[2])
    fireEvent.click(screen.getByRole('button', { name: '导出已确认/已提交 (1)' }))

    await waitFor(() => expect(apiMocks.exportLocalCasesToXmind).toHaveBeenCalledWith(9, [-8]))
    expect(appMocks.showToast).toHaveBeenCalledWith('导出完成，已忽略 1 条未确认用例')
  })
})
