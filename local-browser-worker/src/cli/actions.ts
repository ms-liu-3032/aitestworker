import * as configModule from '../config';
import * as browserModule from '../browser';
import type { WorkerConfig } from '../config';
import type { BrowserInfo } from '../browser';

export interface CliDeps {
  platform: string;
  loadConfig: (platform: string) => WorkerConfig;
  saveConfig: (platform: string, config: WorkerConfig) => void;
  resolveBrowser: (config: WorkerConfig, platform: string) => BrowserInfo | null;
  getConfigPath: (platform: string) => string;
}

export function createDefaultDeps(): CliDeps {
  return {
    platform: process.platform,
    loadConfig: configModule.loadConfig,
    saveConfig: configModule.saveConfig,
    resolveBrowser: browserModule.resolveBrowser,
    getConfigPath: configModule.getConfigPath,
  };
}

export function buildInstallConfig(
  serverUrl: string,
  frontendUrl: string,
  port: number,
  executablePath: string,
  cnMode: boolean,
): WorkerConfig {
  const cfg = { ...configModule.DEFAULT_CONFIG };
  cfg.serverUrl = serverUrl || configModule.DEFAULT_CONFIG.serverUrl;
  cfg.port = isNaN(port) ? configModule.DEFAULT_CONFIG.port : port;
  cfg.executablePath = executablePath.trim();
  cfg.allowedOrigins = mergeAllowedOrigins(configModule.DEFAULT_CONFIG.allowedOrigins, frontendUrl);
  if (cnMode) {
    cfg.npmRegistry = 'https://registry.npmmirror.com';
    cfg.playwrightDownloadHost = 'https://npmmirror.com/mirrors/playwright/';
  }
  return cfg;
}

export function buildBindConfig(existing: WorkerConfig, code: string): WorkerConfig {
  const cfg = { ...existing };
  cfg.bindStatus = 'BOUND_PLACEHOLDER';
  cfg.lastBindCodeMasked = maskBindCode(code);
  return cfg;
}

export function buildBoundConfig(
  existing: WorkerConfig,
  code: string,
  deviceId: number,
  workerTokenCipher: string,
  localToken: string,
  frontendUrl = '',
): WorkerConfig {
  const cfg = { ...existing };
  cfg.bindStatus = 'BOUND';
  cfg.lastBindCodeMasked = maskBindCode(code);
  cfg.deviceId = deviceId;
  cfg.workerTokenCipher = workerTokenCipher;
  cfg.localToken = localToken;
  cfg.boundAt = new Date().toISOString();
  cfg.allowedOrigins = mergeAllowedOrigins(existing.allowedOrigins, frontendUrl);
  return cfg;
}

export function buildUnbindConfig(existing: WorkerConfig): WorkerConfig {
  const cfg = { ...existing };
  cfg.bindStatus = 'UNBOUND';
  cfg.lastBindCodeMasked = '';
  cfg.deviceId = 0;
  cfg.workerTokenCipher = '';
  cfg.localToken = '';
  cfg.boundAt = '';
  return cfg;
}

function maskBindCode(code: string): string {
  const trimmed = code.trim();
  if (trimmed.length <= 4) return '****';
  return `${trimmed.slice(0, 2)}****${trimmed.slice(-2)}`;
}

export function mergeAllowedOrigins(existing: string[], frontendUrl: string): string[] {
  const merged = new Set(existing.map((origin) => origin.trim()).filter(Boolean));
  const normalized = normalizeFrontendOrigin(frontendUrl);
  if (normalized) {
    merged.add(normalized);
  }
  return Array.from(merged);
}

export function normalizeFrontendOrigin(frontendUrl: string): string {
  const trimmed = frontendUrl.trim();
  if (!trimmed) {
    return '';
  }
  if (!/^https?:\/\//i.test(trimmed)) {
    throw new Error('前端地址必须以 http:// 或 https:// 开头');
  }

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    throw new Error('前端地址格式不正确，请填写完整地址，例如 http://192.168.1.10:5173');
  }

  return parsed.origin;
}
