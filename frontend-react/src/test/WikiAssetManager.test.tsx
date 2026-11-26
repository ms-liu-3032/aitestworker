import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const apiMocks = vi.hoisted(() => ({
  listAdminWikiPacks: vi.fn(), listAllProjects: vi.fn(), listWikiEntries: vi.fn(),
  createWikiPack: vi.fn(), createWikiEntry: vi.fn(), reviewWikiEntry: vi.fn(),
  reviewWikiPack: vi.fn(), updateWikiPackStatus: vi.fn(), updateWikiPack: vi.fn(), updateWikiEntry: vi.fn(),
  submitWikiPromotion: vi.fn(),
  listActiveTomModels: vi.fn(), listWikiTomLinks: vi.fn(), updateWikiTomLinks: vi.fn(),
}));
const appMocks = vi.hoisted(() => ({ showToast: vi.fn() }));

vi.mock('../services/api', () => apiMocks);
vi.mock('../context/AppContext', () => ({ useApp: () => appMocks }));

import WikiAssetManager from '../pages/admin/WikiAssetManager';

function renderWiki(initialEntry = '/admin/asset-center?tab=catalog&assetType=wiki') {
  return render(<MemoryRouter initialEntries={[initialEntry]}><WikiAssetManager /></MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
  apiMocks.listAllProjects.mockResolvedValue([{ id: 3, projectName: '通用项目' }]);
  apiMocks.listAdminWikiPacks.mockResolvedValue([{ id: 11, projectId: 3, scope: 'SYSTEM', name: '全局质量规则', status: 'DRAFT', reviewStatus: 'PENDING' }]);
  apiMocks.listWikiEntries.mockResolvedValue([{ id: 21, packId: 11, entryType: 'RULE', title: '幂等规则', content: '重复提交不得产生重复数据', reviewStatus: 'PENDING', effectiveStatus: 'INACTIVE' }]);
  apiMocks.listActiveTomModels.mockResolvedValue([]);
  apiMocks.listWikiTomLinks.mockResolvedValue([]);
});

describe('WikiAssetManager', () => {
  it('opens a Wiki entry from the unified review link, including system-scope packs', async () => {
    renderWiki('/admin/asset-center?tab=catalog&assetType=wiki&packId=11&entryId=21');
    expect(await screen.findByRole('heading', { name: '幂等规则' })).toBeInTheDocument();
    expect(apiMocks.listWikiEntries).toHaveBeenCalledWith(11);
  });

  it('shows all Wiki layers and renders enum values in Chinese', async () => {
    renderWiki();
    expect(await screen.findByText('全局质量规则')).toBeInTheDocument();
    expect(screen.getAllByText('系统级').length).toBeGreaterThan(0);
    expect(screen.getByText('草稿')).toBeInTheDocument();
    expect(screen.getByText('待处理')).toBeInTheDocument();
    expect(screen.queryByText('SYSTEM')).not.toBeInTheDocument();
    expect(screen.queryByText('DRAFT')).not.toBeInTheDocument();
  });

  it('loads entries and exposes review controls', async () => {
    renderWiki();
    fireEvent.click(await screen.findByText('全局质量规则'));
    expect(await screen.findByText('幂等规则')).toBeInTheDocument();
    expect(screen.getAllByText('业务规则').length).toBeGreaterThan(0);
    expect(apiMocks.listWikiEntries).toHaveBeenCalledWith(11);
  });

  it('filters by reusable scope through the admin API', async () => {
    renderWiki();
    fireEvent.change(await screen.findByDisplayValue('全部 Wiki 层级'), { target: { value: 'REUSABLE' } });
    await waitFor(() => expect(apiMocks.listAdminWikiPacks).toHaveBeenLastCalledWith(expect.objectContaining({ scope: 'REUSABLE' })));
  });

  it('lets administrators inspect and edit an entry before re-review', async () => {
    apiMocks.updateWikiEntry.mockResolvedValue({ id: 21, packId: 11, entryType: 'RULE', title: '修订后的幂等规则', content: '重复提交不得产生重复数据', reviewStatus: 'PENDING', effectiveStatus: 'INACTIVE' });
    renderWiki();
    fireEvent.click(await screen.findByText('全局质量规则'));
    fireEvent.click(await screen.findByText('查看详情'));
    expect((await screen.findAllByText('重复提交不得产生重复数据')).length).toBeGreaterThan(1);
    fireEvent.click(screen.getAllByText('编辑')[0]);
    fireEvent.change(screen.getByDisplayValue('幂等规则'), { target: { value: '修订后的幂等规则' } });
    fireEvent.click(screen.getByText('保存并重新审核'));
    await waitFor(() => expect(apiMocks.updateWikiEntry).toHaveBeenCalledWith(21, 'RULE', '修订后的幂等规则', '重复提交不得产生重复数据'));
    expect(appMocks.showToast).toHaveBeenCalledWith('知识条目已保存，已回到待审核状态');
  });

  it('submits a reviewed reusable pack as a pending system candidate', async () => {
    apiMocks.listAdminWikiPacks.mockResolvedValue([{ id: 11, projectId: 3, scope: 'REUSABLE', name: '复用规则', status: 'ACTIVE', reviewStatus: 'APPROVED' }]);
    apiMocks.submitWikiPromotion.mockResolvedValue({ id: 20, projectId: 3, scope: 'SYSTEM', name: '系统候选', status: 'DRAFT', reviewStatus: 'PENDING', promotionSourcePackId: 11 });
    renderWiki();
    fireEvent.click(await screen.findByText('复用规则'));
    fireEvent.click(await screen.findByText('提交至系统级'));
    await waitFor(() => expect(apiMocks.submitWikiPromotion).toHaveBeenCalledWith(11, 'SYSTEM'));
    expect(await screen.findByText('系统候选')).toBeInTheDocument();
  });
});
