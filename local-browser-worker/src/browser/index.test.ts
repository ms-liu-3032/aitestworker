import { describe, it, expect } from 'vitest';
import * as os from 'os';
import * as path from 'path';
import { resolveBrowser, BrowserInfo } from './index';
import { DEFAULT_CONFIG, WorkerConfig } from '../config';

describe('resolveBrowser', () => {
  const home = os.homedir();
  const darwinChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
  const darwinPwCacheDir = path.join(home, 'Library/Caches/ms-playwright');
  const darwinPwChromium = path.join(
    darwinPwCacheDir,
    'chromium-1150',
    'chrome-mac/Chromium.app/Contents/MacOS/Chromium',
  );

  it('returns null when no browser available', () => {
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: '' };
    const result = resolveBrowser(config, 'darwin', () => false, () => []);
    expect(result).toBeNull();
  });

  it('user-configured path takes priority (macOS)', () => {
    const userPath = '/custom/path/chrome';
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: userPath };
    const result = resolveBrowser(
      config,
      'darwin',
      (p) => p === userPath,
      () => [],
    );
    expect(result).not.toBeNull();
    expect(result!.type).toBe('user');
    expect(result!.executablePath).toBe(userPath);
  });

  it('user-configured path takes priority (Windows)', () => {
    const userPath = 'C:\\MyChrome\\chrome.exe';
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: userPath };
    const result = resolveBrowser(
      config,
      'win32',
      (p) => p === userPath,
      () => [],
    );
    expect(result).not.toBeNull();
    expect(result!.type).toBe('user');
    expect(result!.executablePath).toBe(userPath);
  });

  it('trims whitespace from executablePath', () => {
    const userPath = '/custom/path/chrome';
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: '  ' + userPath + '  ' };
    const result = resolveBrowser(
      config,
      'darwin',
      (p) => p === userPath,
      () => [],
    );
    expect(result).not.toBeNull();
    expect(result!.executablePath).toBe(userPath);
  });

  it('ignores user path if file does not exist, falls back to system Chrome', () => {
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: '/missing/chrome' };
    const result = resolveBrowser(
      config,
      'darwin',
      (p) => p === darwinChrome,
      () => [],
    );
    expect(result).not.toBeNull();
    expect(result!.type).toBe('system');
    expect(result!.executablePath).toBe(darwinChrome);
  });

  it('system Chrome detected on macOS', () => {
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: '' };
    const result = resolveBrowser(
      config,
      'darwin',
      (p) => p === darwinChrome,
      () => [],
    );
    expect(result).not.toBeNull();
    expect(result!.type).toBe('system');
    expect(result!.executablePath).toBe(darwinChrome);
  });

  it('system Chrome detected on Windows', () => {
    const winChrome = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: '' };
    const result = resolveBrowser(
      config,
      'win32',
      (p) => p === winChrome,
      () => [],
    );
    expect(result).not.toBeNull();
    expect(result!.type).toBe('system');
    expect(result!.executablePath).toBe(winChrome);
  });

  it('falls back to Playwright Chromium when no system Chrome', () => {
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: '' };
    const existsMap = new Set([darwinPwCacheDir, darwinPwChromium]);
    const dirEntries: Record<string, string[]> = {
      [darwinPwCacheDir]: ['chromium-1150'],
    };
    const result = resolveBrowser(
      config,
      'darwin',
      (p) => existsMap.has(p),
      (p) => dirEntries[p] || [],
    );
    expect(result).not.toBeNull();
    expect(result!.type).toBe('playwright');
    expect(result!.executablePath).toBe(darwinPwChromium);
  });

  it('returns null when Playwright executable file is missing', () => {
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: '' };
    const existsMap = new Set([darwinPwCacheDir]);
    const dirEntries: Record<string, string[]> = {
      [darwinPwCacheDir]: ['chromium-1150'],
    };
    const result = resolveBrowser(
      config,
      'darwin',
      (p) => existsMap.has(p),
      (p) => dirEntries[p] || [],
    );
    expect(result).toBeNull();
  });

  it('returns null on Linux in phase 1', () => {
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: '/usr/bin/google-chrome' };
    const result = resolveBrowser(config, 'linux', () => true, () => ['chromium-1150']);
    expect(result).toBeNull();
  });

  it('user path takes priority over system Chrome and Playwright', () => {
    const userPath = '/custom/chrome';
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: userPath };
    // All paths exist, but user should be chosen
    const allPaths = new Set([userPath, darwinChrome, darwinPwChromium]);
    const result = resolveBrowser(
      config,
      'darwin',
      (p) => allPaths.has(p),
      () => ['chromium-1150'],
    );
    expect(result!.type).toBe('user');
    expect(result!.executablePath).toBe(userPath);
  });

  it('system Chrome takes priority over Playwright', () => {
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: '' };
    // Both system Chrome and Playwright exist, system should win
    const allPaths = new Set([darwinChrome, darwinPwChromium]);
    const result = resolveBrowser(
      config,
      'darwin',
      (p) => allPaths.has(p),
      () => ['chromium-1150'],
    );
    expect(result!.type).toBe('system');
    expect(result!.executablePath).toBe(darwinChrome);
  });

  it('displayName contains human-readable description', () => {
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: '' };
    const result = resolveBrowser(
      config,
      'darwin',
      (p) => p === darwinChrome,
      () => [],
    );
    expect(result!.displayName).toContain('系统 Chrome');
    expect(result!.displayName).toContain(darwinChrome);
  });

  it('returns null when Playwright cache exists but chromium dir missing', () => {
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: '' };
    // Playwright cache dir exists but has no chromium-* subdir
    const existsMap = new Set([darwinPwCacheDir]);
    const result = resolveBrowser(
      config,
      'darwin',
      (p) => existsMap.has(p),
      (p) => p === darwinPwCacheDir ? [] : [],
    );
    expect(result).toBeNull();
  });

  it('handles empty executablePath as not configured', () => {
    const config: WorkerConfig = { ...DEFAULT_CONFIG, executablePath: '' };
    const result = resolveBrowser(
      config,
      'darwin',
      (p) => p === darwinChrome,
      () => [],
    );
    expect(result!.type).toBe('system');
  });
});
