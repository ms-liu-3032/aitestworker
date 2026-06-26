import * as os from 'os';
import * as fs from 'fs';
import * as net from 'net';
import * as path from 'path';

export interface PlatformInfo {
  platform: string;
  osName: string;
  osVersion: string;
  arch: string;
}

export interface PortCheckResult {
  available: boolean;
  errorCode?: string;
  errorMessage?: string;
}

/**
 * 检测当前操作系统和架构信息。
 */
export function detectPlatform(): PlatformInfo {
  const platform = process.platform;
  const arch = os.arch();
  return {
    platform,
    osName: resolveOsName(platform),
    osVersion: resolveOsVersion(platform),
    arch,
  };
}

function resolveOsName(platform: string): string {
  switch (platform) {
    case 'darwin':
      return 'macOS';
    case 'win32':
      return 'Windows';
    case 'linux':
      return 'Linux';
    default:
      return platform;
  }
}

function resolveOsVersion(platform: string): string {
  const release = os.release();
  if (platform === 'darwin') {
    const major = parseInt(release.split('.')[0], 10);
    // Darwin major version → macOS version mapping
    if (major >= 25) return '16+';
    if (major === 24) return '15';
    if (major === 23) return '14';
    if (major === 22) return '13';
    if (major === 21) return '12';
    if (major === 20) return '11';
    return release;
  }
  if (platform === 'win32') {
    const parts = release.split('.');
    const major = parseInt(parts[0] || '0', 10);
    const minor = parseInt(parts[1] || '0', 10);
    const build = parseInt(parts[2] || '0', 10);
    if (major === 10 && minor === 0) {
      if (build >= 22000) return '11';
      return '10';
    }
    return release;
  }
  return release;
}

/**
 * 返回当前操作系统下 Chrome 的候选安装路径列表。
 * 不检查文件是否存在，只返回路径。
 */
export function getChromeCandidatePaths(platform: string): string[] {
  const home = os.homedir();
  if (platform === 'darwin') {
    return [
      '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      path.join(home, 'Applications/Google Chrome.app/Contents/MacOS/Google Chrome'),
    ];
  }
  if (platform === 'win32') {
    const localAppData = process.env.LOCALAPPDATA || path.join(home, 'AppData', 'Local');
    return [
      'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
      'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
      path.join(localAppData, 'Google', 'Chrome', 'Application', 'chrome.exe'),
    ];
  }
  return [];
}

/**
 * 在候选路径中查找 Chrome，返回第一个存在的路径，未找到返回 null。
 */
export function findChromePath(platform: string, existsSync?: (p: string) => boolean): string | null {
  const stat = existsSync || fs.existsSync;
  const paths = getChromeCandidatePaths(platform);
  for (const p of paths) {
    if (stat(p)) {
      return p;
    }
  }
  return null;
}

/**
 * 返回 Playwright Chromium 的默认缓存目录路径。
 */
export function getPlaywrightChromiumCacheDir(platform: string): string {
  const home = os.homedir();
  if (platform === 'darwin') {
    return path.join(home, 'Library', 'Caches', 'ms-playwright');
  }
  if (platform === 'win32') {
    return path.join(process.env.LOCALAPPDATA || path.join(home, 'AppData', 'Local'), 'ms-playwright');
  }
  if (platform === 'linux') {
    return path.join(home, '.cache', 'ms-playwright');
  }
  return path.join(home, '.cache', 'ms-playwright');
}

/**
 * 检测 Playwright Chromium 缓存目录是否存在（至少包含 chromium-* 子目录）。
 */
export function checkPlaywrightChromiumExists(platform: string, existsSync?: (p: string) => boolean, readdirSync?: (p: string) => string[]): boolean {
  return getPlaywrightChromiumExecutablePath(platform, existsSync, readdirSync) !== null;
}

/**
 * 返回应用数据目录（配置文件存放位置）。
 */
export function getDataDir(platform: string): string {
  const home = os.homedir();
  if (platform === 'darwin') {
    return path.join(home, 'Library', 'Application Support', 'AI-Test-Worker');
  }
  if (platform === 'win32') {
    return path.join(process.env.APPDATA || path.join(home, 'AppData', 'Roaming'), 'AI-Test-Worker');
  }
  if (platform === 'linux') {
    return path.join(home, '.config', 'AI-Test-Worker');
  }
  return path.join(home, '.ai-test-worker');
}

/**
 * 返回缓存目录。
 */
export function getCacheDir(platform: string): string {
  const home = os.homedir();
  if (platform === 'darwin') {
    return path.join(home, 'Library', 'Caches', 'AI-Test-Worker');
  }
  if (platform === 'win32') {
    return path.join(process.env.LOCALAPPDATA || path.join(home, 'AppData', 'Local'), 'AI-Test-Worker');
  }
  if (platform === 'linux') {
    return path.join(home, '.cache', 'AI-Test-Worker');
  }
  return path.join(home, '.cache', 'AI-Test-Worker');
}

/**
 * 查找 Playwright Chromium 可执行文件路径。
 * 在缓存目录中定位 chromium-* 子目录，返回平台对应的可执行文件路径。
 * 未找到返回 null。
 */
export function getPlaywrightChromiumExecutablePath(
  platformName: string,
  existsSync?: (p: string) => boolean,
  readdirSync?: (p: string) => string[],
): string | null {
  if (platformName !== 'darwin' && platformName !== 'win32') {
    return null;
  }

  const stat = existsSync || fs.existsSync;
  const readdir = readdirSync || fs.readdirSync;
  const cacheDir = getPlaywrightChromiumCacheDir(platformName);

  if (!stat(cacheDir)) return null;

  let chromiumDirs: string[];
  try {
    chromiumDirs = readdir(cacheDir)
      .filter((e) => e.startsWith('chromium-'))
      .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  } catch {
    return null;
  }

  for (const chromiumDir of chromiumDirs) {
    const base = path.join(cacheDir, chromiumDir);
    const executablePath =
      platformName === 'darwin'
        ? path.join(base, 'chrome-mac', 'Chromium.app', 'Contents', 'MacOS', 'Chromium')
        : path.join(base, 'chrome-win', 'chrome.exe');

    if (stat(executablePath)) {
      return executablePath;
    }
  }

  return null;
}

/**
 * 检测端口是否可用。返回错误码，便于 doctor 输出可理解的中文建议。
 */
export function checkPortAvailable(port: number): Promise<PortCheckResult> {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', (error: NodeJS.ErrnoException) => {
      resolve({
        available: false,
        errorCode: error.code,
        errorMessage: error.message,
      });
    });
    server.once('listening', () => {
      server.close(() => resolve({ available: true }));
    });
    server.listen(port, '127.0.0.1');
  });
}
