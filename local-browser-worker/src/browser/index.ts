import * as fs from 'fs';
import * as platform from '../platform';
import type { WorkerConfig } from '../config';

export interface BrowserInfo {
  /** 浏览器类型：user（用户配置）、system（系统 Chrome）、playwright（Playwright Chromium） */
  type: 'user' | 'system' | 'playwright';
  /** 浏览器可执行文件完整路径 */
  executablePath: string;
  /** 人类可读的浏览器名称 */
  displayName: string;
}

/**
 * 按优先级解析浏览器可执行路径：
 *   1. 用户配置 executablePath（最高优先级）
 *   2. 系统 Chrome
 *   3. Playwright Chromium（兜底）
 *
 * 参数中的 existsSync / readdirSync 用于测试注入，不传则使用 fs 实现。
 */
export function resolveBrowser(
  config: WorkerConfig,
  platformName: string,
  existsSync?: (p: string) => boolean,
  readdirSync?: (p: string) => string[],
): BrowserInfo | null {
  const stat = existsSync || fs.existsSync;
  const readdir = readdirSync || fs.readdirSync;

  if (platformName !== 'darwin' && platformName !== 'win32') {
    return null;
  }

  // 1. 用户显式配置的路径
  if (config.executablePath && config.executablePath.trim().length > 0) {
    const userPath = config.executablePath.trim();
    if (stat(userPath)) {
      return {
        type: 'user',
        executablePath: userPath,
        displayName: `用户配置 (${userPath})`,
      };
    }
  }

  // 2. 系统 Chrome
  const chromePath = platform.findChromePath(platformName, stat);
  if (chromePath) {
    return {
      type: 'system',
      executablePath: chromePath,
      displayName: `系统 Chrome (${chromePath})`,
    };
  }

  // 3. Playwright Chromium 兜底
  const pwPath = platform.getPlaywrightChromiumExecutablePath(platformName, stat, readdir);
  if (pwPath) {
    return {
      type: 'playwright',
      executablePath: pwPath,
      displayName: `Playwright Chromium (${pwPath})`,
    };
  }

  return null;
}
