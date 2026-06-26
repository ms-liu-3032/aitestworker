import { Command } from 'commander';
import * as readline from 'readline';
import { createDefaultDeps, buildInstallConfig } from '../actions';

export function registerInstall(program: Command): void {
  program
    .command('install')
    .description('运行安装向导，配置本地工作环境')
    .option('--cn', '使用中国区镜像加速安装')
    .action(async (options: { cn?: boolean }) => {
      const deps = createDefaultDeps();

      console.log('');
      console.log('═══════════════════════════════════════');
      console.log('  AI 测试工作者 - 安装向导');
      console.log('═══════════════════════════════════════');
      console.log('');

      if (options.cn) {
        console.log('✓ 已启用中国区优化模式');
        console.log('  npm 镜像: https://registry.npmmirror.com');
        console.log('  Playwright 下载: 使用国内镜像');
        console.log('');
      }

      const rl = readline.createInterface({
        input: process.stdin,
        output: process.stdout,
      });

      const ask = (question: string): Promise<string> =>
        new Promise((resolve) => rl.question(question, resolve));

      console.log('请按提示完成配置（直接回车使用默认值）：');
      console.log('');

      const serverUrl = await ask('1. 主平台后端地址 [http://localhost:8080]: ');
      const resolvedServerUrl = serverUrl || 'http://localhost:8080';

      const frontendUrl = await ask(
        '2. 前端访问地址（用于页面检测本地采集器，留空跳过）: ',
      );

      const portStr = await ask('3. 本地监听端口 [17321]: ');
      const resolvedPort = parseInt(portStr || '17321', 10);

      const chromePath = await ask('4. Chrome 浏览器路径（留空自动检测）: ');
      rl.close();

      const config = buildInstallConfig(
        resolvedServerUrl,
        frontendUrl,
        resolvedPort,
        chromePath,
        Boolean(options.cn),
      );

      try {
        deps.saveConfig(deps.platform, config);
        const configPath = deps.getConfigPath(deps.platform);
        console.log('');
        console.log('配置摘要：');
        console.log('─────────────────────────────────────');
        console.log('  服务器地址 : ' + config.serverUrl);
        console.log('  前端来源   : ' + (config.allowedOrigins[0] || '(未额外配置)'));
        console.log('  本地端口   : ' + config.port);
        console.log('  Chrome 路径: ' + (config.executablePath || '(自动检测)'));
        console.log('  npm 源     : ' + (options.cn ? 'npmmirror' : '默认'));
        console.log('─────────────────────────────────────');
        console.log('');
        console.log('配置已保存到: ' + configPath);
        console.log('请执行 ai-test-worker doctor 检查环境。');
      } catch (err) {
        console.error('配置保存失败: ' + (err instanceof Error ? err.message : String(err)));
        process.exit(1);
      }
    });
}
