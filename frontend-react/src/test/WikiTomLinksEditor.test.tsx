import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

const api = vi.hoisted(() => ({
  listActiveTomModels: vi.fn(), listWikiTomLinks: vi.fn(), updateWikiTomLinks: vi.fn(),
}));
vi.mock('../services/api', () => api);
import WikiTomLinksEditor from '../components/WikiTomLinksEditor';

beforeEach(() => {
  vi.clearAllMocks();
  api.listActiveTomModels.mockResolvedValue([
    { id: 11, modelType: 'ROLE', name: '审批人', scope: 'PROJECT' },
    { id: 12, modelType: 'FLOW', name: '访客审批流程', scope: 'PROJECT' },
  ]);
  api.listWikiTomLinks.mockResolvedValue([11]);
  api.updateWikiTomLinks.mockResolvedValue([11, 12]);
});

it('saves a reviewed Wiki to TOM selection through the governed API', async () => {
  const changed = vi.fn();
  render(<WikiTomLinksEditor entryId={7} projectId={2} onChanged={changed} />);
  expect(await screen.findByText('FLOW · 访客审批流程 · 项目')).toBeInTheDocument();
  fireEvent.click(screen.getByLabelText('FLOW · 访客审批流程 · 项目'));
  fireEvent.click(screen.getByText('保存关联'));
  await waitFor(() => expect(api.updateWikiTomLinks).toHaveBeenCalledWith(7, [11, 12]));
  expect(changed).toHaveBeenCalledOnce();
});
