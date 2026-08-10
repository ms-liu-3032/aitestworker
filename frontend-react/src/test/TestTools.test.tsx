import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

const appMocks = vi.hoisted(() => ({
  showToast: vi.fn(),
}));

vi.mock('../context/AppContext', () => ({
  useApp: () => appMocks,
}));

import TestTools from '../pages/project/TestTools';

describe('TestTools clipboard actions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: vi.fn().mockRejectedValue(new Error('clipboard denied')),
      },
    });
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: vi.fn().mockReturnValue(true),
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('falls back to textarea copy when Clipboard API rejects', async () => {
    render(<TestTools />);

    fireEvent.click(screen.getByRole('button', { name: '生成' }));
    fireEvent.click(screen.getByRole('button', { name: '复制全部' }));

    await waitFor(() => {
      expect(document.execCommand).toHaveBeenCalledWith('copy');
      expect(appMocks.showToast).toHaveBeenCalledWith('已复制');
    });
  });

  it('shows an error toast when all copy methods fail', async () => {
    vi.mocked(document.execCommand).mockReturnValue(false);
    render(<TestTools />);

    fireEvent.click(screen.getByRole('button', { name: /时间戳/ }));
    fireEvent.click(screen.getAllByRole('button', { name: '复制' })[0]);

    await waitFor(() => {
      expect(appMocks.showToast).toHaveBeenCalledWith('复制失败，请手动复制', 'error');
    });
  });

  it('uses Clipboard API directly when it is available', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
    render(<TestTools />);

    fireEvent.click(screen.getByRole('button', { name: /JSON 格式化/ }));
    fireEvent.change(screen.getByPlaceholderText('{"key": "value"}'), {
      target: { value: '{"name":"demo"}' },
    });
    fireEvent.click(screen.getByRole('button', { name: '格式化' }));
    fireEvent.click(screen.getByRole('button', { name: '复制' }));

    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith('{\n  "name": "demo"\n}');
      expect(appMocks.showToast).toHaveBeenCalledWith('已复制', 'success');
    });
  });
});
