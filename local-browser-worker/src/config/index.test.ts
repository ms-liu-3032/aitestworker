import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import * as os from 'os';
import * as path from 'path';
import * as fs from 'fs';
import {
  DEFAULT_CONFIG,
  getConfigPath,
  loadConfig,
  saveConfig,
  WorkerConfig,
} from './index';

describe('DEFAULT_CONFIG', () => {
  it('has all required fields with correct defaults', () => {
    expect(DEFAULT_CONFIG.serverUrl).toBe('');
    expect(DEFAULT_CONFIG.port).toBe(17321);
    expect(DEFAULT_CONFIG.preferSystemChrome).toBe(true);
    expect(DEFAULT_CONFIG.executablePath).toBe('');
    expect(DEFAULT_CONFIG.npmRegistry).toBe('');
    expect(DEFAULT_CONFIG.playwrightDownloadHost).toBe('');
    expect(DEFAULT_CONFIG.maxRecordingMinutes).toBe(60);
    expect(DEFAULT_CONFIG.maxVideoSizeMB).toBe(500);
    expect(DEFAULT_CONFIG.maxConcurrentRecordings).toBe(3);
    expect(DEFAULT_CONFIG.allowedOrigins).toEqual([]);
    expect(DEFAULT_CONFIG.bindStatus).toBe('UNBOUND');
    expect(DEFAULT_CONFIG.lastBindCodeMasked).toBe('');
    expect(DEFAULT_CONFIG.deviceId).toBe(0);
    expect(DEFAULT_CONFIG.workerTokenCipher).toBe('');
    expect(DEFAULT_CONFIG.localToken).toBe('');
    expect(DEFAULT_CONFIG.boundAt).toBe('');
  });
});

describe('getConfigPath', () => {
  it('macOS path under Application Support', () => {
    const p = getConfigPath('darwin');
    expect(p).toContain('Application Support/AI-Test-Worker/config.json');
  });

  it('Windows path under AppData', () => {
    const p = getConfigPath('win32');
    expect(p).toContain('AI-Test-Worker');
    expect(p).toContain('config.json');
  });

  it('Linux path under .config', () => {
    const p = getConfigPath('linux');
    expect(p).toContain('.config/AI-Test-Worker/config.json');
  });
});

describe('loadConfig', () => {
  it('returns defaults when file does not exist', () => {
    const config = loadConfig('darwin', undefined, () => false);
    expect(config).toEqual(DEFAULT_CONFIG);
  });

  it('merges partial config with defaults', () => {
    const partial = JSON.stringify({ serverUrl: 'http://example.com', port: 8080 });
    const config = loadConfig('darwin', () => partial, () => true);
    expect(config.serverUrl).toBe('http://example.com');
    expect(config.port).toBe(8080);
    expect(config.preferSystemChrome).toBe(true);
    expect(config.maxRecordingMinutes).toBe(60);
  });

  it('throws Chinese error for invalid JSON', () => {
    expect(() => loadConfig('darwin', () => '{not json', () => true)).toThrow(
      '配置文件损坏',
    );
  });

  it('throws Chinese error when parsed value is array', () => {
    expect(() => loadConfig('darwin', () => '[]', () => true)).toThrow(
      '格式错误',
    );
  });

  it('throws Chinese error for wrong field type', () => {
    const bad = JSON.stringify({ port: 'not-a-number' });
    expect(() => loadConfig('darwin', () => bad, () => true)).toThrow('port');
  });

  it('throws Chinese error for boolean field with wrong type', () => {
    const bad = JSON.stringify({ preferSystemChrome: 'yes' });
    expect(() => loadConfig('darwin', () => bad, () => true)).toThrow('preferSystemChrome');
  });

  it('throws Chinese error for array field with wrong type', () => {
    const bad = JSON.stringify({ allowedOrigins: 'not-array' });
    expect(() => loadConfig('darwin', () => bad, () => true)).toThrow('allowedOrigins');
  });

  it('throws Chinese error when allowedOrigins contains non-string item', () => {
    const bad = JSON.stringify({ allowedOrigins: ['http://localhost:5173', 123] });
    expect(() => loadConfig('darwin', () => bad, () => true)).toThrow('string[]');
  });

  it('throws Chinese error for invalid port range', () => {
    const bad = JSON.stringify({ port: 70000 });
    expect(() => loadConfig('darwin', () => bad, () => true)).toThrow('1 到 65535');
  });

  it('throws Chinese error for invalid bindStatus value', () => {
    const bad = JSON.stringify({ bindStatus: 'INVALID' });
    expect(() => loadConfig('darwin', () => bad, () => true)).toThrow('bindStatus');
  });

  it('throws Chinese error when config file cannot be read', () => {
    expect(() =>
      loadConfig(
        'darwin',
        () => {
          throw new Error('EACCES');
        },
        () => true,
      ),
    ).toThrow('无法读取配置文件');
  });

  it('ignores unknown fields', () => {
    const extra = JSON.stringify({ serverUrl: 'http://a.com', unknownField: 'should be ignored' });
    const config = loadConfig('darwin', () => extra, () => true);
    expect(config.serverUrl).toBe('http://a.com');
    expect((config as Record<string, unknown>).unknownField).toBeUndefined();
  });
});

describe('saveConfig and loadConfig round-trip', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ai-test-worker-test-'));
    fs.mkdirSync(tmpDir, { recursive: true });
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('saves and loads config correctly', () => {
    let writtenPath = '';
    let writtenContent = '';
    const config: WorkerConfig = {
      ...DEFAULT_CONFIG,
      serverUrl: 'http://test-server:8080',
      port: 9999,
      preferSystemChrome: false,
    };
    saveConfig(
      'darwin',
      config,
      (p, d) => {
        writtenPath = p;
        writtenContent = d;
      },
      () => undefined,
      () => true,
    );
    const loaded = loadConfig('darwin', () => writtenContent, (p) => p === writtenPath);
    expect(loaded.serverUrl).toBe('http://test-server:8080');
    expect(loaded.port).toBe(9999);
    expect(loaded.preferSystemChrome).toBe(false);
    expect(loaded.maxRecordingMinutes).toBe(60);
  });

  it('new config file produces defaults on load', () => {
    const loaded = loadConfig('darwin', undefined, () => false);
    expect(loaded).toEqual(DEFAULT_CONFIG);
  });

  it('save creates parent directory if needed', () => {
    let mkdirCalled = false;
    saveConfig(
      'darwin',
      { ...DEFAULT_CONFIG, port: 4000 },
      () => undefined,
      () => {
        mkdirCalled = true;
      },
      () => false,
    );
    expect(mkdirCalled).toBe(true);
  });

  it('corrupted file throws with recovery suggestion', () => {
    expect(() => loadConfig('darwin', () => '{{{broken', () => true)).toThrow('重命名为');
  });

  it('save rejects invalid runtime config', () => {
    const bad = { ...DEFAULT_CONFIG, port: 0 };
    expect(() => saveConfig('darwin', bad, () => undefined, () => undefined, () => true)).toThrow(
      '端口必须',
    );
  });
});
