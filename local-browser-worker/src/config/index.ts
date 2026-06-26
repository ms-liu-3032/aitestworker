import * as path from 'path';
import * as fs from 'fs';
import * as platform from '../platform';

export interface WorkerConfig {
  serverUrl: string;
  port: number;
  preferSystemChrome: boolean;
  executablePath: string;
  npmRegistry: string;
  playwrightDownloadHost: string;
  maxRecordingMinutes: number;
  maxVideoSizeMB: number;
  maxConcurrentRecordings: number;
  allowedOrigins: string[];
  bindStatus: 'UNBOUND' | 'BOUND_PLACEHOLDER' | 'BOUND';
  lastBindCodeMasked: string;
  deviceId: number;
  workerTokenCipher: string;
  localToken: string;
  boundAt: string;
}

export const DEFAULT_CONFIG: WorkerConfig = {
  serverUrl: '',
  port: 17321,
  preferSystemChrome: true,
  executablePath: '',
  npmRegistry: '',
  playwrightDownloadHost: '',
  maxRecordingMinutes: 60,
  maxVideoSizeMB: 500,
  maxConcurrentRecordings: 3,
  allowedOrigins: [],
  bindStatus: 'UNBOUND',
  lastBindCodeMasked: '',
  deviceId: 0,
  workerTokenCipher: '',
  localToken: '',
  boundAt: '',
};

const KNOWN_KEYS = Object.keys(DEFAULT_CONFIG) as (keyof WorkerConfig)[];

/**
 * 返回 config.json 的完整路径。
 */
export function getConfigPath(platformName: string): string {
  const dir = platform.getDataDir(platformName);
  return path.join(dir, 'config.json');
}

/**
 * 加载配置文件。缺文件时返回默认配置，损坏时抛出中文错误。
 */
export function loadConfig(
  platformName: string,
  readFileSync?: (p: string, e: BufferEncoding) => string,
  existsSync?: (p: string) => boolean,
): WorkerConfig {
  const read = readFileSync || fs.readFileSync;
  const exists = existsSync || fs.existsSync;
  const configPath = getConfigPath(platformName);

  if (!exists(configPath)) {
    return { ...DEFAULT_CONFIG };
  }

  let content: string;
  try {
    content = read(configPath, 'utf-8');
  } catch {
    throw new Error(
      `无法读取配置文件：${configPath}\n` +
        `建议：检查文件权限，或重新运行 ai-test-worker install 生成新配置。`,
    );
  }

  let raw: unknown;
  try {
    raw = JSON.parse(content);
  } catch {
    throw new Error(
      `配置文件损坏，无法解析 JSON：${configPath}\n` +
        `建议：将 ${configPath} 重命名为 ${configPath}.bak 后重新运行 ai-test-worker install 生成新配置。`,
    );
  }

  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new Error(
      `配置文件格式错误，期望 JSON 对象：${configPath}\n` +
        `建议：删除 ${configPath} 后重新运行 ai-test-worker install 生成新配置。`,
    );
  }

  const data = raw as Record<string, unknown>;
  const merged = { ...DEFAULT_CONFIG };

  for (const key of KNOWN_KEYS) {
    const val = data[key];
    if (val !== undefined) {
      const typed = coerce(key, val, configPath);
      (merged as unknown as Record<string, unknown>)[key] = typed;
    }
  }

  return merged;
}

function coerce(key: string, val: unknown, configPath: string): unknown {
  const defaults = DEFAULT_CONFIG as unknown as Record<string, unknown>;
  const expected = defaults[key];
  if (expected === undefined) return val;

  if (typeof expected === 'number') {
    if (typeof val !== 'number') {
      throw new Error(
        `配置文件字段 "${key}" 类型错误，期望 number，实际 ${typeof val}：${configPath}\n` +
          `建议：修正 ${configPath} 中 "${key}" 为该字段的正确类型，或删除配置文件重新生成。`,
      );
    }
    validateNumber(key, val, configPath);
    return val;
  }

  if (typeof expected === 'boolean') {
    if (typeof val !== 'boolean') {
      throw new Error(
        `配置文件字段 "${key}" 类型错误，期望 boolean，实际 ${typeof val}：${configPath}\n` +
          `建议：修正 ${configPath} 中 "${key}" 为该字段的正确类型，或删除配置文件重新生成。`,
      );
    }
    return val;
  }

  if (Array.isArray(expected)) {
    if (!Array.isArray(val)) {
      throw new Error(
        `配置文件字段 "${key}" 类型错误，期望 array，实际 ${typeof val}：${configPath}\n` +
          `建议：修正 ${configPath} 中 "${key}" 为该字段的正确类型，或删除配置文件重新生成。`,
      );
    }
    if (key === 'allowedOrigins' && val.some((item) => typeof item !== 'string')) {
      throw new Error(
        `配置文件字段 "${key}" 类型错误，期望 string[]：${configPath}\n` +
          `建议：allowedOrigins 中每一项都必须是字符串，例如 "http://localhost:5173"。`,
      );
    }
    return val;
  }

  if (typeof val !== 'string') {
    throw new Error(
      `配置文件字段 "${key}" 类型错误，期望 string，实际 ${typeof val}：${configPath}\n` +
        `建议：修正 ${configPath} 中 "${key}" 为该字段的正确类型，或删除配置文件重新生成。`,
    );
  }
  validateString(key, val, configPath);
  return val;
}

function validateNumber(key: string, val: number, configPath: string): void {
  if (!Number.isFinite(val)) {
    throw new Error(
      `配置文件字段 "${key}" 数值非法：${configPath}\n` +
        `建议：请填写有限数字，或删除配置文件重新生成。`,
    );
  }
  if (key === 'port' && (!Number.isInteger(val) || val < 1 || val > 65535)) {
    throw new Error(
      `配置文件字段 "port" 取值非法，端口必须是 1 到 65535 的整数：${configPath}\n` +
        `建议：使用默认端口 17321，或填写其他可用本地端口。`,
    );
  }
  const positiveIntegerFields = ['maxRecordingMinutes', 'maxVideoSizeMB', 'maxConcurrentRecordings'];
  if (positiveIntegerFields.includes(key) && (!Number.isInteger(val) || val <= 0)) {
    throw new Error(
      `配置文件字段 "${key}" 取值非法，必须是大于 0 的整数：${configPath}\n` +
        `建议：删除该字段使用默认值，或填写合理的正整数。`,
    );
  }
}

function validateString(key: string, val: string, configPath: string): void {
  if (key === 'bindStatus' && val !== 'UNBOUND' && val !== 'BOUND_PLACEHOLDER' && val !== 'BOUND') {
    throw new Error(
      `配置文件字段 "bindStatus" 取值非法：${configPath}\n` +
        `建议：bindStatus 只能是 UNBOUND、BOUND_PLACEHOLDER 或 BOUND。`,
    );
  }
}

/**
 * 保存配置到磁盘，自动创建目录。
 */
export function saveConfig(
  platformName: string,
  config: WorkerConfig,
  writeFileSync?: (p: string, d: string, e: BufferEncoding) => void,
  mkdirSync?: (p: string, o?: { recursive: boolean }) => void,
  existsSync?: (p: string) => boolean,
): void {
  const write = writeFileSync || fs.writeFileSync;
  const mkdir = mkdirSync || fs.mkdirSync;
  const exists = existsSync || fs.existsSync;

  const configPath = getConfigPath(platformName);
  const dir = path.dirname(configPath);
  const checkedConfig = validateConfig(config, configPath);

  if (!exists(dir)) {
    try {
      mkdir(dir, { recursive: true });
    } catch (err) {
      throw new Error(
        `无法创建配置目录：${dir}\n` +
          `原因：${err instanceof Error ? err.message : String(err)}\n` +
          `建议：检查目录权限或手动创建该目录后重试。`,
      );
    }
  }

  try {
    write(configPath, JSON.stringify(checkedConfig, null, 2), 'utf-8');
  } catch (err) {
    throw new Error(
      `无法写入配置文件：${configPath}\n` +
        `原因：${err instanceof Error ? err.message : String(err)}\n` +
        `建议：检查目录权限或磁盘空间后重试。`,
    );
  }
}

function validateConfig(config: WorkerConfig, configPath: string): WorkerConfig {
  const checked = { ...DEFAULT_CONFIG };
  const data = config as unknown as Record<string, unknown>;
  for (const key of KNOWN_KEYS) {
    const val = data[key];
    if (val === undefined) {
      throw new Error(
        `配置字段 "${key}" 缺失：${configPath}\n` +
          `建议：重新运行 ai-test-worker install 生成完整配置。`,
      );
    }
    (checked as unknown as Record<string, unknown>)[key] = coerce(key, val, configPath);
  }
  return checked;
}
