import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const api = vi.hoisted(() => ({ listAllProjects: vi.fn(), listAssetCatalog: vi.fn() }));
vi.mock('../services/api', () => api);

import UnifiedCatalogPanel from '../pages/admin/UnifiedCatalogPanel';

describe('UnifiedCatalogPanel', () => {
  it('shows source lineage and opens the owning editor for a system Wiki entry', async () => {
    api.listAllProjects.mockResolvedValue([{ id: 18, projectName: '访客项目' }]);
    api.listAssetCatalog.mockResolvedValue({ total: 1, items: [{
      assetType: 'WIKI_ENTRY', id: 77, projectId: 18, title: '签到规则', scope: 'SYSTEM',
      lifecycleStatus: 'ACTIVE', reviewStatus: 'PENDING', sourceType: 'MANUAL_IMPORT',
      sourceId: 42, parentId: 6, createdAt: null,
    }] });
    render(<MemoryRouter><UnifiedCatalogPanel /></MemoryRouter>);
    expect(await screen.findByText('签到规则')).toBeInTheDocument();
    expect(screen.getByText('MANUAL_IMPORT #42')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '查看处理' })).toHaveAttribute('href',
      '/admin/asset-center?tab=catalog&assetType=wiki&packId=6&entryId=77');
  });
});
