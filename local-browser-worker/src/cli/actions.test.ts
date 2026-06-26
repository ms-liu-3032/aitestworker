import { describe, it, expect } from 'vitest';
import {
  buildInstallConfig,
  buildBindConfig,
  buildBoundConfig,
  buildUnbindConfig,
  mergeAllowedOrigins,
  normalizeFrontendOrigin,
} from './actions';
import { DEFAULT_CONFIG, WorkerConfig } from '../config';

describe('buildInstallConfig', () => {
  it('uses provided values', () => {
    const c = buildInstallConfig('http://example.com', '', 9999, '/usr/bin/chrome', false);
    expect(c.serverUrl).toBe('http://example.com');
    expect(c.port).toBe(9999);
    expect(c.executablePath).toBe('/usr/bin/chrome');
  });

  it('uses defaults when empty', () => {
    const c = buildInstallConfig('', '', NaN, '', false);
    expect(c.serverUrl).toBe(DEFAULT_CONFIG.serverUrl);
    expect(c.port).toBe(DEFAULT_CONFIG.port);
    expect(c.executablePath).toBe('');
  });

  it('cn mode sets npm registry and playwright mirror', () => {
    const c = buildInstallConfig('', '', 17321, '', true);
    expect(c.npmRegistry).toBe('https://registry.npmmirror.com');
    expect(c.playwrightDownloadHost).toContain('npmmirror');
  });

  it('non-cn mode keeps defaults for registry', () => {
    const c = buildInstallConfig('', '', 17321, '', false);
    expect(c.npmRegistry).toBe(DEFAULT_CONFIG.npmRegistry);
    expect(c.playwrightDownloadHost).toBe(DEFAULT_CONFIG.playwrightDownloadHost);
  });

  it('trims executablePath whitespace', () => {
    const c = buildInstallConfig('', '', 17321, '  /path/to/chrome  ', false);
    expect(c.executablePath).toBe('/path/to/chrome');
  });

  it('other fields remain at defaults', () => {
    const c = buildInstallConfig('http://a.com', '', 8080, '/chrome', true);
    expect(c.preferSystemChrome).toBe(DEFAULT_CONFIG.preferSystemChrome);
    expect(c.maxRecordingMinutes).toBe(DEFAULT_CONFIG.maxRecordingMinutes);
    expect(c.maxVideoSizeMB).toBe(DEFAULT_CONFIG.maxVideoSizeMB);
    expect(c.maxConcurrentRecordings).toBe(DEFAULT_CONFIG.maxConcurrentRecordings);
    expect(c.allowedOrigins).toEqual(DEFAULT_CONFIG.allowedOrigins);
    expect(c.bindStatus).toBe(DEFAULT_CONFIG.bindStatus);
    expect(c.lastBindCodeMasked).toBe(DEFAULT_CONFIG.lastBindCodeMasked);
    expect(c.deviceId).toBe(DEFAULT_CONFIG.deviceId);
    expect(c.workerTokenCipher).toBe(DEFAULT_CONFIG.workerTokenCipher);
    expect(c.localToken).toBe(DEFAULT_CONFIG.localToken);
  });

  it('normalizes frontend origin into allowedOrigins', () => {
    const c = buildInstallConfig('http://a.com', 'https://test.example.com/app/projects', 8080, '/chrome', false);
    expect(c.allowedOrigins).toEqual(['https://test.example.com']);
  });

  it('rejects invalid frontend url', () => {
    expect(() => buildInstallConfig('http://a.com', 'test.example.com', 8080, '/chrome', false)).toThrow(
      '前端地址必须以 http:// 或 https:// 开头',
    );
  });
});

describe('buildBindConfig', () => {
  it('sets placeholder bind status without storing raw code as serverUrl', () => {
    const existing: WorkerConfig = { ...DEFAULT_CONFIG, serverUrl: '' };
    const c = buildBindConfig(existing, 'ABC12345');
    expect(c.serverUrl).toBe('');
    expect(c.bindStatus).toBe('BOUND_PLACEHOLDER');
    expect(c.lastBindCodeMasked).toBe('AB****45');
  });

  it('keeps existing serverUrl unchanged', () => {
    const existing: WorkerConfig = { ...DEFAULT_CONFIG, serverUrl: 'http://old' };
    const c = buildBindConfig(existing, 'XYZ999');
    expect(c.serverUrl).toBe('http://old');
    expect(c.lastBindCodeMasked).toBe('XY****99');
  });

  it('keeps other fields unchanged', () => {
    const existing: WorkerConfig = { ...DEFAULT_CONFIG, port: 9999, preferSystemChrome: false };
    const c = buildBindConfig(existing, 'CODE');
    expect(c.port).toBe(9999);
    expect(c.preferSystemChrome).toBe(false);
  });
});

describe('buildBoundConfig', () => {
  it('sets real bound status and token metadata', () => {
    const existing: WorkerConfig = { ...DEFAULT_CONFIG, serverUrl: 'http://server' };
    const c = buildBoundConfig(existing, 'ABC12345', 12, 'cipher-text', 'local-token', 'http://192.168.1.10:5173/workbench');
    expect(c.serverUrl).toBe('http://server');
    expect(c.bindStatus).toBe('BOUND');
    expect(c.lastBindCodeMasked).toBe('AB****45');
    expect(c.deviceId).toBe(12);
    expect(c.workerTokenCipher).toBe('cipher-text');
    expect(c.localToken).toBe('local-token');
    expect(c.boundAt).not.toBe('');
    expect(c.allowedOrigins).toEqual(['http://192.168.1.10:5173']);
  });
});

describe('buildUnbindConfig', () => {
  it('clears placeholder bind status', () => {
    const existing: WorkerConfig = {
      ...DEFAULT_CONFIG,
      serverUrl: 'http://server',
      bindStatus: 'BOUND',
      lastBindCodeMasked: 'AB****45',
      deviceId: 10,
      workerTokenCipher: 'cipher',
      localToken: 'local',
      boundAt: '2026-05-19T00:00:00.000Z',
    };
    const c = buildUnbindConfig(existing);
    expect(c.serverUrl).toBe('http://server');
    expect(c.bindStatus).toBe('UNBOUND');
    expect(c.lastBindCodeMasked).toBe('');
    expect(c.deviceId).toBe(0);
    expect(c.workerTokenCipher).toBe('');
    expect(c.localToken).toBe('');
    expect(c.boundAt).toBe('');
  });

  it('keeps other fields unchanged', () => {
    const existing: WorkerConfig = {
      ...DEFAULT_CONFIG,
      serverUrl: 'http://bound',
      port: 7777,
      executablePath: '/chrome',
    };
    const c = buildUnbindConfig(existing);
    expect(c.port).toBe(7777);
    expect(c.executablePath).toBe('/chrome');
  });
});

describe('allowed origins helpers', () => {
  it('normalizes frontend origin to scheme host and port', () => {
    expect(normalizeFrontendOrigin('https://demo.example.com:8443/project/list?x=1')).toBe(
      'https://demo.example.com:8443',
    );
  });

  it('allows empty frontend origin', () => {
    expect(normalizeFrontendOrigin('   ')).toBe('');
  });

  it('deduplicates saved frontend origins', () => {
    expect(
      mergeAllowedOrigins(['http://192.168.1.10:5173', '  http://192.168.1.10:5173  '], 'http://192.168.1.10:5173/path'),
    ).toEqual(['http://192.168.1.10:5173']);
  });
});
