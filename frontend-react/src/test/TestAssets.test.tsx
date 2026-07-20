import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

const apiMocks = vi.hoisted(() => ({
  listFormalCases: vi.fn(),
  listLocalCases: vi.fn(),
  submitLocalCase: vi.fn(),
  listAdminSummaries: vi.fn(),
  listAdminSkills: vi.fn(),
  listAdminTools: vi.fn(),
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
  apiMocks.listAdminSummaries.mockResolvedValue({ items: [] })
  apiMocks.listAdminSkills.mockResolvedValue({ items: [] })
  apiMocks.listAdminTools.mockResolvedValue({ items: [] })
})

describe('TestAssets', () => {
  it('does not crash when asset APIs return non-array payloads', async () => {
    render(<TestAssets />)

    await waitFor(() => {
      expect(screen.getByText('暂无轨迹摘要，先去测试执行轨迹里生成或确认摘要。')).toBeInTheDocument()
    })
  })
})
