import { describe, expect, it, vi } from 'vitest';
import {
  CaptureManager,
  getProfileDownloadsDir,
  getTraceScreencastFramePath,
  getTraceScreencastManifestPath,
  getTraceSessionDir,
  resolveCreatedBrowserContextId,
} from './index';

describe('capture download helpers', () => {
  it('builds profile downloads dir under worker data dir', () => {
    const dir = getProfileDownloadsDir('darwin', 42);
    expect(dir).toContain('Application Support/AI-Test-Worker/profiles/42/downloads');
  });

  it('builds trace artifact paths under worker data dir', () => {
    expect(getTraceSessionDir('darwin', 4)).toContain('Application Support/AI-Test-Worker/traces/4');
    expect(getTraceScreencastManifestPath('darwin', 4)).toContain('Application Support/AI-Test-Worker/traces/4/screencast/manifest.json');
    expect(getTraceScreencastFramePath('darwin', 4, '000123.jpg')).toContain('Application Support/AI-Test-Worker/traces/4/screencast/frames/000123.jpg');
  });

  it('detects the newly created browser context id from CDP snapshots', () => {
    expect(resolveCreatedBrowserContextId([], ['ctx-1'])).toBe('ctx-1');
    expect(resolveCreatedBrowserContextId(['ctx-1'], ['ctx-1', 'ctx-2'])).toBe('ctx-2');
  });

  it('returns null when created context id is ambiguous', () => {
    expect(resolveCreatedBrowserContextId([], [])).toBeNull();
    expect(resolveCreatedBrowserContextId([], ['ctx-1', 'ctx-2'])).toBeNull();
  });
});

describe('profile window cleanup', () => {
  it('closes only the selected profile context', async () => {
    const manager = new CaptureManager(null, 'darwin');
    const profiles = (manager as any).profileContexts as Map<number, unknown>;
    const selected = { context: { close: vi.fn().mockResolvedValue(undefined) }, page: { isClosed: () => false } };
    const other = { context: { close: vi.fn().mockResolvedValue(undefined) }, page: { isClosed: () => false } };
    profiles.set(1, selected);
    profiles.set(2, other);
    await manager.closeProfileContext(1);
    expect(selected.context.close).toHaveBeenCalledOnce();
    expect(other.context.close).not.toHaveBeenCalled();
    expect(profiles.has(1)).toBe(false);
    expect(profiles.has(2)).toBe(true);
  });

  it('keeps a failed context registered for retry', async () => {
    const manager = new CaptureManager(null, 'darwin');
    const profiles = (manager as any).profileContexts as Map<number, unknown>;
    profiles.set(3, { context: { close: vi.fn().mockRejectedValue(new Error('close failed')) },
      page: { isClosed: () => false } });
    await expect(manager.closeProfileContext(3)).rejects.toThrow('close failed');
    expect(profiles.has(3)).toBe(true);
  });
});
