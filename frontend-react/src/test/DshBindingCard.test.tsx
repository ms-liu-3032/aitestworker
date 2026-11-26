// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import DshBindingCard from '../components/DshBindingCard';
import { api } from '../services/api';

vi.mock('../services/api', () => ({ api: vi.fn() }));

beforeEach(() => { vi.mocked(api).mockReset(); });

it('creates a one-time DSH code and revokes a bound device', async () => {
  vi.mocked(api).mockImplementation(async (url) => {
    if (url === '/api/aitestwork/dsh/connections') return [{ id: 5, deviceName: 'DSH Mac', status: 'ACTIVE', lastUsedAt: null }] as never;
    if (url === '/api/aitestwork/dsh/bind-codes') return { code: 'ABCDEFGHJKLM', expiresAt: '2026-11-03T12:00:00' } as never;
    if (url === '/api/aitestwork/dsh/connections/5/revoke') return null as never;
    throw new Error(`unexpected ${url}`);
  });

  render(<DshBindingCard />);
  fireEvent.click(screen.getByRole('button', { name: '生成绑定码' }));
  await waitFor(() => expect(screen.getByText('ABCDEFGHJKLM')).toBeTruthy());
  expect(screen.getByText(/\/aitestwork-bind/).textContent).toContain('ABCDEFGHJKLM');
  fireEvent.change(screen.getByRole('textbox', { name: 'AITestWork 后端服务地址' }), {
    target: { value: 'https://api.example.test/' },
  });
  expect(screen.getByText(/\/aitestwork-bind/).textContent).toContain('https://api.example.test');
  fireEvent.change(screen.getByRole('textbox', { name: 'AITestWork 后端服务地址' }), {
    target: { value: 'http://192.0.2.10:5174/' },
  });
  expect(screen.getByText(/\/aitestwork-bind/).textContent).toContain('http://192.0.2.10:5174');
  expect(screen.getByRole('status').textContent).toContain('明文传输');
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
  fireEvent.click(screen.getByRole('button', { name: '复制完整绑定命令' }));
  await waitFor(() => expect(writeText).toHaveBeenCalledWith('/aitestwork-bind http://192.0.2.10:5174 ABCDEFGHJKLM'));
  fireEvent.click(screen.getByRole('button', { name: '撤销连接' }));
  await waitFor(() => expect(api).toHaveBeenCalledWith('/api/aitestwork/dsh/connections/5/revoke', { method: 'POST' }));
});

it('copies the binding command on an HTTP page without Clipboard API', async () => {
  vi.mocked(api).mockImplementation(async (url) => {
    if (url === '/api/aitestwork/dsh/connections') return [] as never;
    if (url === '/api/aitestwork/dsh/bind-codes') return { code: 'ABCDEFGHJKLM', expiresAt: '2026-11-03T12:00:00' } as never;
    throw new Error(`unexpected ${url}`);
  });
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
  const copy = vi.fn().mockReturnValue(true);
  Object.defineProperty(document, 'execCommand', { configurable: true, value: copy });
  render(<DshBindingCard />);
  fireEvent.click(screen.getByRole('button', { name: '生成绑定码' }));
  await screen.findByRole('button', { name: '复制完整绑定命令' });
  fireEvent.click(screen.getByRole('button', { name: '复制完整绑定命令' }));
  expect(copy).toHaveBeenCalledWith('copy');
  expect(screen.getByText('完整绑定命令已复制。')).toBeTruthy();
});
