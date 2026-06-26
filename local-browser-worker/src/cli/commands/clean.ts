import { Command } from 'commander';

export function registerClean(program: Command): void {
  program
    .command('clean')
    .description('清理本地缓存和临时数据')
    .action(() => {
      console.log('');
      console.log('═══════════════════════════════════════');
      console.log('  清理本地数据');
      console.log('═══════════════════════════════════════');
      console.log('');
      console.log('  将清理以下内容：');
      console.log('  1. 本地缓存目录');
      console.log('     ~/Library/Caches/AI-Test-Worker');
      console.log('  2. 临时截图文件');
      console.log('  3. 过期日志文件');
      console.log('');
      console.log('  保留以下内容：');
      console.log('  1. 配置文件 (config.json)');
      console.log('  2. 已上传的采集数据');
      console.log('');
      console.log('  提示：当前为骨架版本，清理功能将在后续版本实现。');
      console.log('');
      console.log('═══════════════════════════════════════');
      console.log('');
    });
}
