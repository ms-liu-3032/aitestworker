import { Command } from 'commander';

export function registerLogs(program: Command): void {
  program
    .command('logs')
    .description('查看本地采集服务日志')
    .action(() => {
      console.log('');
      console.log('═══════════════════════════════════════');
      console.log('  本地服务日志');
      console.log('═══════════════════════════════════════');
      console.log('');
      console.log('  日志文件路径：');
      console.log('  ~/Library/Logs/AI-Test-Worker/worker.log');
      console.log('');
      console.log('  日志级别：INFO');
      console.log('  轮转策略：单文件最大 10 MB，保留最近 5 个');
      console.log('');
      console.log('  提示：当前为骨架版本，尚无实际日志内容。');
      console.log('  完整日志功能将在后续版本实现。');
      console.log('');
      console.log('═══════════════════════════════════════');
      console.log('');
    });
}
