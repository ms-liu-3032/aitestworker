import { Command } from 'commander';
import { createDefaultDeps, buildUnbindConfig } from '../actions';

export function registerUnbind(program: Command): void {
  program
    .command('unbind')
    .description('解除与 AI 测试平台服务器的绑定')
    .action(() => {
      const deps = createDefaultDeps();

      let existing: ReturnType<typeof deps.loadConfig>;
      try {
        existing = deps.loadConfig(deps.platform);
      } catch (err) {
        console.error('读取配置文件失败: ' + (err instanceof Error ? err.message : String(err)));
        process.exit(1);
      }

      const updated = buildUnbindConfig(existing);

      try {
        deps.saveConfig(deps.platform, updated);
      } catch (err) {
        console.error('写入配置文件失败: ' + (err instanceof Error ? err.message : String(err)));
        process.exit(1);
      }

      const configPath = deps.getConfigPath(deps.platform);

      console.log('');
      console.log('═══════════════════════════════════════');
      console.log('  解除绑定');
      console.log('═══════════════════════════════════════');
      console.log('');
      console.log('  已清除本地占位绑定状态。');
      console.log('  配置文件: ' + configPath);
      console.log('  服务器地址、本地采集数据和其余配置已保留。');
      console.log('');
      console.log('═══════════════════════════════════════');
      console.log('');
    });
}
