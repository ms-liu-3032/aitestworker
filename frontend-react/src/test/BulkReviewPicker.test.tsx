import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

const showToast = vi.fn();
vi.mock('../context/AppContext', () => ({ useApp: () => ({ showToast }) }));
import BulkReviewPicker from '../components/BulkReviewPicker';

describe('BulkReviewPicker', () => {
  it('reviews only selected items and reports partial failure', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const onReview = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('审核失败'));
    render(<BulkReviewPicker title="知识条目" items={[{ id: 1, name: '规则 A' }, { id: 2, name: '规则 B' }]}
      onReview={onReview} />);
    fireEvent.click(screen.getByText(/批量审核知识条目/));
    fireEvent.click(await screen.findByText('全选当前列表'));
    fireEvent.click(screen.getByText('批量通过'));
    await waitFor(() => expect(onReview).toHaveBeenCalledTimes(2));
    expect(onReview).toHaveBeenNthCalledWith(1, 1, true, undefined);
    expect(onReview).toHaveBeenNthCalledWith(2, 2, true, undefined);
    expect(showToast).toHaveBeenCalledWith(expect.stringContaining('失败 1 条'), 'error');
    expect(screen.getByText('已选 1 条')).toBeInTheDocument();
    vi.restoreAllMocks();
  });

  it('requires a shared reason before bulk rejection when the review API requires one', async () => {
    showToast.mockClear();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const onReview = vi.fn().mockResolvedValue(undefined);
    render(<BulkReviewPicker title="轨迹摘要" rejectReasonRequired items={[{ id: 3, name: '摘要 A' }]}
      onReview={onReview} />);
    fireEvent.click(screen.getByText(/批量审核轨迹摘要/));
    fireEvent.click(await screen.findByText('全选当前列表'));
    fireEvent.click(screen.getByText('批量驳回'));
    expect(onReview).not.toHaveBeenCalled();
    expect(showToast).toHaveBeenCalledWith('请填写批量驳回原因', 'error');
    fireEvent.change(screen.getByLabelText('批量驳回原因'), { target: { value: '信息不准确' } });
    fireEvent.click(screen.getByText('批量驳回'));
    await waitFor(() => expect(onReview).toHaveBeenCalledWith(3, false, '信息不准确'));
    vi.restoreAllMocks();
  });
});
