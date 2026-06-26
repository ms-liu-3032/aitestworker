import { describe, it, expect } from 'vitest';
import * as path from 'path';
import {
  detectPlatform,
  getChromeCandidatePaths,
  findChromePath,
  getPlaywrightChromiumCacheDir,
  checkPlaywrightChromiumExists,
  getPlaywrightChromiumExecutablePath,
  getDataDir,
  getCacheDir,
} from './index';

describe('detectPlatform', () => {
  it('returns current platform info', () => {
    const info = detectPlatform();
    expect(info.platform).toBe(process.platform);
    expect(info.arch).toBeDefined();
    expect(info.osName).toBeDefined();
    expect(info.osVersion).toBeDefined();
  });

  it('osName matches platform', () => {
    const info = detectPlatform();
    if (info.platform === 'darwin') expect(info.osName).toBe('macOS');
    else if (info.platform === 'win32') expect(info.osName).toBe('Windows');
    else if (info.platform === 'linux') expect(info.osName).toBe('Linux');
  });
});

describe('getChromeCandidatePaths', () => {
  it('returns paths for macOS', () => {
    const paths = getChromeCandidatePaths('darwin');
    expect(paths.length).toBeGreaterThan(0);
    expect(paths.every((p) => p.includes('Chrome'))).toBe(true);
  });

  it('returns paths for Windows', () => {
    const paths = getChromeCandidatePaths('win32');
    expect(paths.length).toBe(3);
    expect(paths.every((p) => p.includes('chrome.exe'))).toBe(true);
  });

  it('returns empty for Linux', () => {
    const paths = getChromeCandidatePaths('linux');
    expect(paths.length).toBe(0);
  });
});

describe('findChromePath', () => {
  it('returns first existing path', () => {
    const existsSync = (p: string) => p.includes('Program Files') && !p.includes('x86');
    const found = findChromePath('win32', existsSync);
    expect(found).toBe('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe');
  });

  it('returns null when none exist', () => {
    const existsSync = (_p: string) => false;
    const found = findChromePath('darwin', existsSync);
    expect(found).toBeNull();
  });

  it('returns second path when first missing', () => {
    const existsSync = (p: string) => p.includes('86');
    const found = findChromePath('win32', existsSync);
    expect(found).toBe('C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe');
  });
});

describe('getPlaywrightChromiumCacheDir', () => {
  it('macOS cache dir in Library/Caches', () => {
    const dir = getPlaywrightChromiumCacheDir('darwin');
    expect(dir).toContain('Library/Caches/ms-playwright');
  });

  it('Windows cache dir in LocalAppData', () => {
    const dir = getPlaywrightChromiumCacheDir('win32');
    expect(dir).toContain('ms-playwright');
  });

  it('Linux cache dir in .cache', () => {
    const dir = getPlaywrightChromiumCacheDir('linux');
    expect(dir).toContain('.cache/ms-playwright');
  });
});

describe('getPlaywrightChromiumExecutablePath', () => {
  it('returns macOS executable when cache and executable exist', () => {
    const cacheDir = getPlaywrightChromiumCacheDir('darwin');
    const executablePath = path.join(
      cacheDir,
      'chromium-1150',
      'chrome-mac',
      'Chromium.app',
      'Contents',
      'MacOS',
      'Chromium',
    );
    const found = getPlaywrightChromiumExecutablePath(
      'darwin',
      (p) => p === cacheDir || p === executablePath,
      (p) => (p === cacheDir ? ['chromium-1150'] : []),
    );
    expect(found).toBe(executablePath);
  });

  it('returns null when chromium directory exists but executable is missing', () => {
    const cacheDir = getPlaywrightChromiumCacheDir('darwin');
    const found = getPlaywrightChromiumExecutablePath(
      'darwin',
      (p) => p === cacheDir,
      (p) => (p === cacheDir ? ['chromium-1150'] : []),
    );
    expect(found).toBeNull();
    expect(checkPlaywrightChromiumExists('darwin', (p) => p === cacheDir, () => ['chromium-1150'])).toBe(false);
  });

  it('does not resolve Linux executable in phase 1', () => {
    const found = getPlaywrightChromiumExecutablePath('linux', () => true, () => ['chromium-1150']);
    expect(found).toBeNull();
  });
});

describe('getDataDir', () => {
  it('macOS data dir in Application Support', () => {
    const dir = getDataDir('darwin');
    expect(dir).toContain('Application Support/AI-Test-Worker');
  });

  it('Windows data dir in AppData/Roaming', () => {
    const dir = getDataDir('win32');
    expect(dir).toContain('AI-Test-Worker');
  });
});

describe('getCacheDir', () => {
  it('macOS cache dir in Caches', () => {
    const dir = getCacheDir('darwin');
    expect(dir).toContain('Caches/AI-Test-Worker');
  });

  it('Windows cache dir in Local', () => {
    const dir = getCacheDir('win32');
    expect(dir).toContain('AI-Test-Worker');
    expect(dir).not.toContain('Program Files');
  });
});
