import { Command } from 'commander';
import { runAllChecks, formatReport } from '../../doctor/index';
import { createDefaultDeps } from '../actions';

export function registerDoctor(program: Command): void {
  program
    .command('doctor')
    .description('检查本地开发环境是否就绪')
    .action(async () => {
      const checks = await runAllChecks();
      console.log(formatReport(checks));

      // Also show browser resolution result
      const deps = createDefaultDeps();
      let config: ReturnType<typeof deps.loadConfig> | null = null;
      try {
        config = deps.loadConfig(deps.platform);
      } catch {
        // config stays null
      }

      if (config && (deps.platform === 'darwin' || deps.platform === 'win32')) {
        const browser = deps.resolveBrowser(config, deps.platform);
        if (browser) {
          console.log('浏览器选择结果: ' + browser.displayName);
          console.log('');
        }
      }
    });
}
