import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

const apiMocks = vi.hoisted(() => ({
  listAdminWikiPacks: vi.fn(), listProjects: vi.fn(), listWikiEntries: vi.fn(),
  createWikiPack: vi.fn(), createWikiEntry: vi.fn(), reviewWikiEntry: vi.fn(),
  reviewWikiPack: vi.fn(), updateWikiPackStatus: vi.fn(),
}));
const appMocks = vi.hoisted(() => ({ showToast: vi.fn() }));

vi.mock('../services/api', () => apiMocks);
vi.mock('../context/AppContext', () => ({ useApp: () => appMocks }));

import WikiAssetManager from '../pages/admin/WikiAssetManager';

beforeEach(() => {
  vi.clearAllMocks();
  apiMocks.listProjects.mockResolvedValue({ items: [{ id: 3, projectName: '通用项目' }] });
  apiMocks.listAdminWikiPacks.mockResolvedValue([{ id: 11, projectId: 3, scope: 'SYSTEM', name: '全局质量规则', status: 'DRAFT', reviewStatus: 'PENDING' }]);
  apiMocks.listWikiEntries.mockResolvedValue([{ id: 21, packId: 11, entryType: 'RULE', title: '幂等规则', content: '重复提交不得产生重复数据', reviewStatus: 'PENDING', effectiveStatus: 'INACTIVE' }]);
});

describe('WikiAssetManager', () => {
  it('shows all Wiki layers and renders enum values in Chinese', async () => {
    render(<WikiAssetManager />);
    expect(await screen.findByText('全局质量规则')).toBeInTheDocument();
    expect(screen.getAllByText('系统级').length).toBeGreaterThan(0);
    expect(screen.getByText('草稿')).toBeInTheDocument();
    expect(screen.getByText('待处理')).toBeInTheDocument();
    expect(screen.queryByText('SYSTEM')).not.toBeInTheDocument();
    expect(screen.queryByText('DRAFT')).not.toBeInTheDocument();
  });

  it('loads entries and exposes review controls', async () => {
    render(<WikiAssetManager />);
    fireEvent.click(await screen.findByText('全局质量规则'));
    expect(await screen.findByText('幂等规则')).toBeInTheDocument();
    expect(screen.getAllByText('业务规则').length).toBeGreaterThan(0);
    expect(apiMocks.listWikiEntries).toHaveBeenCalledWith(11);
  });

  it('filters by reusable scope through the admin API', async () => {
    render(<WikiAssetManager />);
    fireEvent.change(await screen.findByDisplayValue('全部 Wiki 层级'), { target: { value: 'REUSABLE' } });
    await waitFor(() => expect(apiMocks.listAdminWikiPacks).toHaveBeenLastCalledWith(expect.objectContaining({ scope: 'REUSABLE' })));
  });
});
