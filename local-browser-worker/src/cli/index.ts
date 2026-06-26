#!/usr/bin/env node

import { Command } from 'commander';
import { registerInstall } from './commands/install';
import { registerStart } from './commands/start';
import { registerDoctor } from './commands/doctor';
import { registerBind } from './commands/bind';
import { registerUnbind } from './commands/unbind';
import { registerClean } from './commands/clean';
import { registerLogs } from './commands/logs';
import { registerSetServer } from './commands/set-server';

const program = new Command();

program
  .name('ai-test-worker')
  .description('AI 测试工作者 - 本地浏览器测试执行轨迹采集器')
  .version('0.1.0');

registerInstall(program);
registerStart(program);
registerDoctor(program);
registerBind(program);
registerUnbind(program);
registerClean(program);
registerLogs(program);
registerSetServer(program);

program.parse(process.argv);
