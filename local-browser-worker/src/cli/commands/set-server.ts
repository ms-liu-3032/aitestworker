import { Command } from 'commander';
import { createDefaultDeps, mergeAllowedOrigins, normalizeFrontendOrigin } from '../actions';

export function registerSetServer(program: Command): void {
  program
    .command('set-server')
    .description('设置主平台服务器地址，并保存到 worker 配置文件')
    .argument('<url>', '服务器地址，例如 http://192.168.1.10:8080')
    .option('--frontend-url <前端地址>', '前端访问地址，用于页面检测本地采集器')
    .action((url: string, options: { frontendUrl?: string }) => {
      const deps = createDefaultDeps();
      const trimmed = String(url || '').trim();
      if (!trimmed) {
        console.error('错误: 服务器地址不能为空');
        process.exit(1);
      }
      if (!/^https?:\/\//i.test(trimmed)) {
        console.error('错误: 服务器地址必须以 http:// 或 https:// 开头');
        process.exit(1);
      }
      let existing;
      try {
        existing = deps.loadConfig(deps.platform);
      } catch (err) {
        console.error('读取配置文件失败: ' + (err instanceof Error ? err.message : String(err)));
        process.exit(1);
      }
      try {
        deps.saveConfig(deps.platform, {
          ...existing,
          serverUrl: trimmed,
          allowedOrigins: mergeAllowedOrigins(existing.allowedOrigins, options.frontendUrl || ''),
        });
      } catch (err) {
        console.error('写入配置文件失败: ' + (err instanceof Error ? err.message : String(err)));
        process.exit(1);
      }
      console.log('已保存服务器地址: ' + trimmed);
      if (options.frontendUrl && options.frontendUrl.trim()) {
        console.log('已保存前端来源: ' + normalizeFrontendOrigin(options.frontendUrl));
      }
      console.log('配置文件: ' + deps.getConfigPath(deps.platform));
    });
}
