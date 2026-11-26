import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'

const mocks = vi.hoisted(() => ({ listAssetInventory: vi.fn() }))
vi.mock('../services/api', () => mocks)

import AssetInventoryPanel from '../pages/admin/AssetInventoryPanel'

beforeEach(() => mocks.listAssetInventory.mockReset())

describe('AssetInventoryPanel', () => {
  it('shows project inventory with source and status', async () => {
    mocks.listAssetInventory.mockResolvedValue([
      { assetType: 'TOM_NODE', scope: 'PROJECT', status: 'ACTIVE', sourceType: 'MANUAL_IMPORT', count: 2 },
    ])
    render(<AssetInventoryPanel projectId={18} />)
    fireEvent.click(screen.getByRole('button', { name: '查看盘点' }))
    expect(await screen.findByText('MANUAL_IMPORT')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(mocks.listAssetInventory).toHaveBeenCalledWith(18, expect.any(AbortSignal))
  })
})
