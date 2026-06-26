import { Command } from 'commander';
import * as os from 'os';
import * as crypto from 'crypto';
import { createDefaultDeps, buildBoundConfig } from '../actions';
import { consumeBindCode } from '../../platform-api';
import { encryptSecret } from '../../security';

export function registerBind(program: Command): void {
  program
    .command('bind')
    .description('绑定到 AI 测试平台服务器')
    .requiredOption('--code <绑定码>', '服务器生成的设备绑定码')
    .option('--server-url <服务器地址>', 'AI 测试平台地址，默认读取配置文件')
    .option('--frontend-url <前端地址>', '前端访问地址，用于页面检测本地采集器')
    .option('--device-name <设备名称>', '设备名称，默认使用本机主机名')
    .action(async (options: { code: string; serverUrl?: string; frontendUrl?: string; deviceName?: string }) => {
      const deps = createDefaultDeps();
      const code = options.code;

      if (!code || code.trim().length === 0) {
        console.error('错误: --code 参数不能为空');
        process.exit(1);
      }

      if (code.trim().length < 6) {
        console.error('错误: 绑定码格式不正确，长度至少为 6 位');
        process.exit(1);
      }

      let existing: ReturnType<typeof deps.loadConfig>;
      try {
        existing = deps.loadConfig(deps.platform);
      } catch (err) {
        console.error('读取配置文件失败: ' + (err instanceof Error ? err.message : String(err)));
        process.exit(1);
      }

      const serverUrl = (options.serverUrl || existing.serverUrl || 'http://localhost:8080').trim();
      const deviceName = (options.deviceName || os.hostname() || '本地采集器').trim();

      console.log('');
      console.log('正在连接主平台完成设备绑定...');
      console.log('  服务器地址: ' + serverUrl);
      console.log('  设备名称: ' + deviceName);

      let bindResult: Awaited<ReturnType<typeof consumeBindCode>>;
      try {
        bindResult = await consumeBindCode(serverUrl, {
          code: code.trim(),
          deviceName,
          platform: deps.platform,
          arch: os.arch(),
          workerVersion: '0.1.0',
          protocolVersion: '1',
        });
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        console.error('设备绑定失败: ' + message);
        if (/fetch failed|ECONNREFUSED|ETIMEDOUT|ENOTFOUND|getaddrinfo/i.test(message)) {
          console.error('无法连接到 ' + serverUrl);
          console.error('请检查：');
          console.error('  1) 主平台后端是否已启动（默认 8080 端口）');
          console.error('  2) 服务器地址 / IP / 端口是否拼写正确');
          console.error('  3) 客户端机器能否访问该地址（防火墙、跨网段、VPN）');
          console.error('  4) 若已用 set-server 保存过地址，可执行 ai-test-worker set-server <url> 覆盖');
        } else {
          console.error('建议：确认主平台已启动、绑定码未过期，并重新执行 ai-test-worker bind --code <绑定码>');
        }
        process.exit(1);
      }

      const updated = buildBoundConfig(
        { ...existing, serverUrl },
        code.trim(),
        bindResult.deviceId,
        encryptSecret(bindResult.workerToken),
        cryptoRandomToken(),
        options.frontendUrl || '',
      );

      try {
        deps.saveConfig(deps.platform, updated);
      } catch (err) {
        console.error('写入配置文件失败: ' + (err instanceof Error ? err.message : String(err)));
        process.exit(1);
      }

      const configPath = deps.getConfigPath(deps.platform);

      console.log('');
      console.log('═══════════════════════════════════════');
      console.log('  设备绑定');
      console.log('═══════════════════════════════════════');
      console.log('');
      console.log('  绑定码: ' + updated.lastBindCodeMasked);
      console.log('  绑定状态: 已绑定');
      console.log('  设备 ID: ' + updated.deviceId);
      console.log('  前端来源: ' + (updated.allowedOrigins[0] || '(未额外配置)'));
      console.log('  配置已保存到: ' + configPath);
      console.log('');
      console.log('  下一步：');
      console.log('  执行 ai-test-worker start 启动本地采集器。');
      console.log('');
      console.log('═══════════════════════════════════════');
      console.log('');
    });
}

function cryptoRandomToken(): string {
  return crypto.randomBytes(24).toString('hex');
}
