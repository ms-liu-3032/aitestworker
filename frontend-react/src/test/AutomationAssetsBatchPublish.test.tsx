import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

const apiMocks = vi.hoisted(() => ({
  listAutomationAssetsPage: vi.fn(), getAutomationAsset: vi.fn(), getAutomationPermissions: vi.fn(),
  listAutomationPublications: vi.fn(), listAutomationAdoptions: vi.fn(),
  requestSystemAutomationPublication: vi.fn(), reviewSystemAutomationPublication: vi.fn(),
  withdrawAutomationPublication: vi.fn(), listUiCoverageTasks: vi.fn(), listPendingUiExecutionRules: vi.fn(),
  batchPromoteAutomationDrafts: vi.fn(), batchPublishAutomationAssets: vi.fn(),
  batchRequestSystemAutomation: vi.fn(), batchApproveSystemAutomation: vi.fn(), batchApproveAutomationVersions: vi.fn(),
  getAutomationVersion: vi.fn(), listAutomationRuns: vi.fn(), getAutomationApiEvidence: vi.fn(),
  proposeAutomationVersion: vi.fn(), publishAutomationAsset: vi.fn(), unblockAutomationAsset: vi.fn(),
  approveAutomationVersion: vi.fn(), archiveAutomationAsset: vi.fn(), discardAutomationCandidate: vi.fn(),
  confirmUiCoverageTask: vi.fn(), approveUiExecutionRule: vi.fn(),
}));
const showToast = vi.hoisted(() => vi.fn());
const location = vi.hoisted(() => ({ search: '' }));

vi.mock('../context/AppContext', () => ({ useApp: () => ({ showToast }) }));
vi.mock('react-router-dom', () => ({
  useParams: () => ({ projectId: '1' }), useSearchParams: () => [new URLSearchParams(location.search)],
}));
vi.mock('../services/api', () => apiMocks);

import AutomationAssets from '../pages/project/AutomationAssets';

const asset = (id: number, overrides: Record<string, unknown> = {}) => ({
  id, projectId: 1, caseKind: 'FORMAL', caseId: id, caseNo: `CASE-${id}`, assetType: 'UI',
  status: 'VERIFIED', currentVersion: 2, latestVersion: 2, healCount: 0, createdBy: 1,
  sourceCaseVersion: 1, caseVersion: 1, caseStale: false, caseMissing: false,
  reviewStatus: 'APPROVED', ...overrides,
});

beforeEach(() => {
  vi.clearAllMocks();
  location.search = '';
  apiMocks.listAutomationAssetsPage.mockResolvedValue({
    items: [asset(1), asset(2, { assetType: 'API' }), asset(3, { caseKind: 'DRAFT' }),
      asset(4, { reusablePublicationId: 44 })],
    total: 4, page: 1, pageSize: 50,
  });
  apiMocks.listAutomationPublications.mockResolvedValue([]);
  apiMocks.getAutomationVersion.mockResolvedValue({ versionNo: 2, mapping: { caseNo: 'CASE-251' } });
  apiMocks.listAutomationRuns.mockResolvedValue([]);
  apiMocks.getAutomationPermissions.mockResolvedValue({ canManage: true, canReviewSystem: true });
  apiMocks.listUiCoverageTasks.mockResolvedValue([]);
  apiMocks.listPendingUiExecutionRules.mockResolvedValue([]);
  apiMocks.batchPublishAutomationAssets.mockResolvedValue([
    { assetId: 1, versionNo: 2, publicationId: 11 },
    { assetId: 2, versionNo: 2, publicationId: 12 },
  ]);
  apiMocks.batchPromoteAutomationDrafts.mockResolvedValue([{ assetId: 3, formalCaseId: 103, versionNo: 2 }]);
  apiMocks.batchRequestSystemAutomation.mockResolvedValue([{ id: 77 }]);
  apiMocks.batchApproveSystemAutomation.mockResolvedValue([{ id: 77, status: 'ACTIVE' }]);
  vi.spyOn(window, 'confirm').mockReturnValue(true);
});

describe('AutomationAssets detail navigation', () => {
  it('opens an older asset by ID even when it is outside the current page', async () => {
    location.search = 'assetId=251';
    apiMocks.getAutomationAsset.mockResolvedValue(asset(251));
    render(<AutomationAssets />);
    await waitFor(() => expect(apiMocks.getAutomationAsset).toHaveBeenCalledWith(1, 251));
    await screen.findByText(/CASE-251 · UI 映射 v2/);
  });
})

describe('AutomationAssets system publication review', () => {
  it('routes a pending system-level request to the admin review action', async () => {
    const current = asset(5, { reusablePublicationId: 44, systemRequestId: 77 });
    apiMocks.listAutomationAssetsPage.mockResolvedValue({ items: [current], total: 1, page: 1, pageSize: 50 });
    apiMocks.listAutomationPublications.mockResolvedValue([{ id: 77, assetId: 5, projectId: 1,
      versionNo: 2, scope: 'SYSTEM', status: 'PENDING_REVIEW', publishedBy: 1,
      publishedAt: '2026-11-27T00:00:00', adoptionCount: 0 }]);
    apiMocks.reviewSystemAutomationPublication.mockResolvedValue({ status: 'ACTIVE' });
    apiMocks.getAutomationAsset.mockResolvedValue(asset(5, { reusablePublicationId: 44, systemPublicationId: 77 }));
    render(<AutomationAssets />);
    await screen.findByText('CASE-5');
    fireEvent.click(screen.getByRole('button', { name: '查看详情' }));
    fireEvent.click(await screen.findByRole('button', { name: '审核通过' }));
    await waitFor(() => expect(apiMocks.reviewSystemAutomationPublication)
      .toHaveBeenCalledWith(1, 5, 77, true, ''));
  });
})

describe('AutomationAssets bulk reusable publication', () => {
  it('exposes the missing draft-to-project batch path for verified personal assets', async () => {
    render(<AutomationAssets />);
    await screen.findByText('CASE-3');
    expect(screen.getByLabelText('批量操作')).toHaveValue('PROMOTE');
    expect(screen.getByLabelText('选择资产 CASE-3')).toBeEnabled();
    fireEvent.click(screen.getByLabelText('选择全部可提交草稿资产'));
    fireEvent.click(screen.getByRole('button', { name: '批量提交项目正式资产（1）' }));
    await waitFor(() => expect(apiMocks.batchPromoteAutomationDrafts).toHaveBeenCalledWith(1, [
      { assetId: 3, versionNo: 2 },
    ]));
  });

  it('submits only approved formal versions in one batch', async () => {
    render(<AutomationAssets />);
    await screen.findByText('CASE-1');
    fireEvent.change(screen.getByLabelText('批量操作'), { target: { value: 'PUBLISH' } });
    await screen.findByLabelText('选择全部可发布资产');
    expect(screen.getByLabelText('选择资产 CASE-3')).toBeDisabled();
    expect(screen.getByLabelText('选择资产 CASE-4')).toBeDisabled();
    fireEvent.click(screen.getByLabelText('选择全部可发布资产'));
    fireEvent.click(screen.getByRole('button', { name: '批量发布复用版（2）' }));
    await waitFor(() => expect(apiMocks.batchPublishAutomationAssets).toHaveBeenCalledWith(1, [
      { assetId: 1, versionNo: 2 }, { assetId: 2, versionNo: 2 },
    ]));
    expect(showToast).toHaveBeenCalledWith('已提交 2 项冻结复用版');
  });

  it('submits reusable mappings for system review in one batch', async () => {
    render(<AutomationAssets />);
    await screen.findByText('CASE-4');
    fireEvent.change(screen.getByLabelText('批量操作'), { target: { value: 'SYSTEM' } });
    fireEvent.click(await screen.findByLabelText('选择全部可申请系统级资产'));
    fireEvent.click(screen.getByRole('button', { name: '批量申请系统级审核（1）' }));
    await waitFor(() => expect(apiMocks.batchRequestSystemAutomation).toHaveBeenCalledWith(1, [
      { assetId: 4, versionNo: 2 },
    ]));
  });

  it('lets platform admin approve pending system applications in one batch', async () => {
    apiMocks.listAutomationAssetsPage.mockResolvedValue({
      items: [asset(5, { reusablePublicationId: 44, systemRequestId: 77 })], total: 1, page: 1, pageSize: 50,
    });
    render(<AutomationAssets />);
    await screen.findByText('CASE-5');
    fireEvent.change(screen.getByLabelText('批量操作'), { target: { value: 'SYSTEM_REVIEW' } });
    fireEvent.click(await screen.findByLabelText('选择全部待审系统级申请'));
    fireEvent.click(screen.getByRole('button', { name: '批量通过系统级申请（1）' }));
    await waitFor(() => expect(apiMocks.batchApproveSystemAutomation).toHaveBeenCalledWith(1, [
      { assetId: 5, publicationId: 77 },
    ]));
  });
});
