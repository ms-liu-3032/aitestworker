import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

vi.mock('../pages/admin/CandidateReview', () => ({ default: () => <div>待审核内容</div> }))
vi.mock('../pages/admin/AssetLibrary', () => ({ default: () => <div>资产目录内容</div> }))
vi.mock('../pages/admin/MiniTomModels', () => ({ default: () => <div>TOM 模型内容</div> }))

import AssetCenter from '../pages/admin/AssetCenter'
import { ADMIN_NAV_ITEMS } from '../types'

describe('AssetCenter', () => {
  it('keeps one admin asset entry and switches review, catalog and model views', () => {
    expect(ADMIN_NAV_ITEMS.filter(item => ['asset-center', 'assets', 'candidates', 'mini-tom'].includes(item.key)))
      .toEqual([{ key: 'asset-center', label: '资产中心', icon: '📦' }])

    render(<MemoryRouter initialEntries={['/admin/asset-center?tab=review']}><AssetCenter /></MemoryRouter>)
    expect(screen.getByText('待审核内容')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '资产目录' }))
    expect(screen.getByText('资产目录内容')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'TOM 模型' }))
    expect(screen.getByText('TOM 模型内容')).toBeInTheDocument()
  })
})
