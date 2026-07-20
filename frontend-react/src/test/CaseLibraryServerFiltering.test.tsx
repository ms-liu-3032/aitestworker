import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({
  listLocalCasesPage: vi.fn(),
  listFormalCasesPage: vi.fn(),
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
    }))
    expect(await screen.findByText('第 1 / 1 页，共 6 条')).toBeInTheDocument()
  })
})
