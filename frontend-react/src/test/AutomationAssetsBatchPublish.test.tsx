import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

const apiMocks = vi.hoisted(() => ({
  listAutomationAssets: vi.fn(), listUiCoverageTasks: vi.fn(), listPendingUiExecutionRules: vi.fn(),
  batchPublishAutomationAssets: vi.fn(), batchApproveAutomationVersions: vi.fn(),
  getAutomationVersion: vi.fn(), listAutomationRuns: vi.fn(), getAutomationApiEvidence: vi.fn(),
  proposeAutomationVersion: vi.fn(), publishAutomationAsset: vi.fn(), unblockAutomationAsset: vi.fn(),
  approveAutomationVersion: vi.fn(), archiveAutomationAsset: vi.fn(), discardAutomationCandidate: vi.fn(),
  confirmUiCoverageTask: vi.fn(), approveUiExecutionRule: vi.fn(),
}));
const showToast = vi.hoisted(() => vi.fn());

vi.mock('../context/AppContext', () => ({ useApp: () => ({ showToast }) }));
vi.mock('react-router-dom', () => ({
  useParams: () => ({ projectId: '1' }), useSearchParams: () => [new URLSearchParams()],
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
  apiMocks.listAutomationAssets.mockResolvedValue([
    asset(1), asset(2, { assetType: 'API' }), asset(3, { caseKind: 'DRAFT' }),
  ]);
  apiMocks.listUiCoverageTasks.mockResolvedValue([]);
  apiMocks.listPendingUiExecutionRules.mockResolvedValue([]);
  apiMocks.batchPublishAutomationAssets.mockResolvedValue([
    { assetId: 1, versionNo: 2, publicationId: 11 },
    { assetId: 2, versionNo: 2, publicationId: 12 },
  ]);
});

describe('AutomationAssets bulk reusable publication', () => {
  it('submits only approved formal versions in one batch', async () => {
    render(<AutomationAssets />);
    await screen.findByText('CASE-1');
    fireEvent.change(screen.getByLabelText('批量操作'), { target: { value: 'PUBLISH' } });
    await screen.findByLabelText('选择全部可发布资产');
    expect(screen.getByLabelText('选择资产 CASE-3')).toBeDisabled();
    fireEvent.click(screen.getByLabelText('选择全部可发布资产'));
    fireEvent.click(screen.getByRole('button', { name: '批量发布复用版（2）' }));
    await waitFor(() => expect(apiMocks.batchPublishAutomationAssets).toHaveBeenCalledWith(1, [
      { assetId: 1, versionNo: 2 }, { assetId: 2, versionNo: 2 },
    ]));
    expect(showToast).toHaveBeenCalledWith('已提交 2 项冻结复用版');
  });
});
