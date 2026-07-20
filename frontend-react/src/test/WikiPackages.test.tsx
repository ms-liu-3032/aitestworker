import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

const apiMocks = vi.hoisted(() => ({
  listWikiPacks: vi.fn(),
  createWikiPack: vi.fn(),
  listWikiEntries: vi.fn(),
  createWikiEntry: vi.fn(),
  reviewWikiEntry: vi.fn(),
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
})
