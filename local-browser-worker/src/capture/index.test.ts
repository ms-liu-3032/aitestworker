import { describe, expect, it } from 'vitest';
import {
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
