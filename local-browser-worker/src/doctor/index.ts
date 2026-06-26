import * as platform from '../platform';
import {
  CheckItem,
  checkOS,
  checkNodeVersion,
  checkArchitecture,
  checkChrome,
  checkPlaywrightChromium,
  checkDataDir,
  checkCacheDir,
  checkPort,
} from './checks';

export type { CheckItem };

const DEFAULT_PORT = 17321;

/**
 * 执行所有环境检查，返回检查结果列表。
 * 此函数负责收集真实环境数据，然后调用纯函数生成 CheckItem。
 */
export async function runAllChecks(): Promise<CheckItem[]> {
  const info = platform.detectPlatform();
  const nodeVersion = process.version;
  const chromePath = platform.findChromePath(info.platform);
  const playwrightExists = platform.checkPlaywrightChromiumExists(info.platform);
  const dataDir = platform.getDataDir(info.platform);
  const cacheDir = platform.getCacheDir(info.platform);
  const portResult = await platform.checkPortAvailable(DEFAULT_PORT);

  return [
    checkOS(info.platform, info.osVersion),
    checkNodeVersion(nodeVersion),
    checkArchitecture(info.arch),
    checkChrome(chromePath, info.platform),
    checkPlaywrightChromium(playwrightExists),
    checkDataDir(dataDir),
    checkCacheDir(cacheDir),
    checkPort(portResult.available, DEFAULT_PORT, portResult.errorCode),
  ];
}

/**
 * 格式化检查结果为控制台输出（纯函数）。
 */
export function formatReport(checks: CheckItem[]): string {
  const lines: string[] = [];

  lines.push('');
  lines.push('═══════════════════════════════════════');
  lines.push('  环境检查报告');
  lines.push('═══════════════════════════════════════');
  lines.push('');

  let passCount = 0;
  let warnCount = 0;
  let failCount = 0;

  for (const check of checks) {
    const icon = check.status === '通过' ? '✓' : check.status === '警告' ? '△' : '✗';
    lines.push(`  ${icon} ${check.name}`);
    lines.push(`    状态: ${check.status}`);
    lines.push(`    详情: ${check.message}`);
    if (check.suggestion) {
      lines.push(`    建议: ${check.suggestion}`);
    }
    lines.push('');

    if (check.status === '通过') passCount++;
    else if (check.status === '警告') warnCount++;
    else failCount++;
  }

  lines.push('─────────────────────────────────────');
  lines.push(`  通过 ${passCount} 项  |  警告 ${warnCount} 项  |  失败 ${failCount} 项`);
  lines.push('═══════════════════════════════════════');

  if (failCount > 0) {
    lines.push('');
    lines.push('存在未通过项，请根据上述建议修复后再启动。');
  } else if (warnCount > 0) {
    lines.push('');
    lines.push('环境基本就绪，警告项不影响核心功能。');
  } else {
    lines.push('');
    lines.push('环境完全就绪，可以执行 ai-test-worker start 启动服务。');
  }
  lines.push('');

  return lines.join('\n');
}
