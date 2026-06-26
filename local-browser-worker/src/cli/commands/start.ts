import { Command } from 'commander';
import * as os from 'os';
import { createDefaultDeps } from '../actions';
import { startWorkerServer } from '../../server';
import { decryptSecret } from '../../security';
import { sendHeartbeat } from '../../platform-api';
import { CaptureManager } from '../../capture';

export function registerStart(program: Command): void {
  program
    .command('start')
    .description('启动本地浏览器测试采集服务')
    .action(async () => {
      const deps = createDefaultDeps();

      let config: ReturnType<typeof deps.loadConfig>;
      try {
        config = deps.loadConfig(deps.platform);
      } catch (err) {
        console.error('读取配置文件失败: ' + (err instanceof Error ? err.message : String(err)));
        process.exit(1);
      }

      const browser = deps.resolveBrowser(config, deps.platform);
      const configPath = deps.getConfigPath(deps.platform);

      console.log('');
      console.log('═══════════════════════════════════════');
      console.log('  本地采集服务启动计划');
      console.log('═══════════════════════════════════════');
      console.log('');
      console.log('配置文件: ' + configPath);
      console.log('');

      console.log('启动步骤：');
      console.log('');

      console.log('  1. 已加载配置');
      console.log('     服务器地址: ' + (config.serverUrl || '(未配置)'));
      console.log('     绑定状态:   ' + (config.bindStatus === 'BOUND' ? '已绑定' : '未绑定'));
      console.log('     本地端口:   ' + config.port);
      console.log('     最大录制时长: ' + config.maxRecordingMinutes + ' 分钟');
      console.log('     最大视频大小: ' + config.maxVideoSizeMB + ' MB');
      console.log('     最大并发录制: ' + config.maxConcurrentRecordings);
      console.log('');

      console.log('  2. 浏览器选择');
      if (browser) {
        console.log('     ' + browser.displayName);
        console.log('     路径: ' + browser.executablePath);
        console.log('     类型: ' + browser.type);
      } else if (deps.platform !== 'darwin' && deps.platform !== 'win32') {
        console.log('     当前平台不支持本地浏览器采集');
      } else {
        console.log('     未找到可用浏览器');
        console.log('     请安装 Chrome 或执行 npx playwright install chromium');
      }
      console.log('');

      console.log('  3. 检查本地环境');
      console.log('     → 请先执行 ai-test-worker doctor 确认环境就绪');
      console.log('');

      console.log('  4. 启动本地 HTTP 服务');
      console.log('     默认端口：' + config.port);
      console.log('     健康检查端点：/health');
      console.log('');

      let serverConnected = false;
      if (config.bindStatus === 'BOUND') {
        console.log('  5. 校验主平台连接');
        console.log('     服务器地址: ' + (config.serverUrl || '(未配置，后续需在 install 中设置)'));
        console.log('     设备 ID: ' + (config.deviceId || '(无)'));
        try {
          const token = decryptSecret(config.workerTokenCipher);
          serverConnected = await sendHeartbeat(config.serverUrl, token);
          console.log('     连接状态: ' + (serverConnected ? '已连接' : '未连接'));
        } catch (err) {
          console.log('     连接状态: 未连接');
          console.log('     原因: ' + (err instanceof Error ? err.message : String(err)));
        }
      } else {
        console.log('  5. 注册到服务器');
        console.log('     需要先执行 ai-test-worker bind --code <绑定码>');
      }
      console.log('');

      console.log('  6. 开始采集测试执行轨迹');
      console.log('     包含：用户操作、网络请求、截图、浏览器上下文录屏文件');
      console.log('');

      console.log('═══════════════════════════════════════');
      console.log('  正在启动本地服务...');
      console.log('═══════════════════════════════════════');
      console.log('');

      try {
        const captureManager = new CaptureManager(browser, deps.platform);
        const handle = await startWorkerServer({
          config,
          browser,
          platform: deps.platform,
          arch: os.arch(),
          version: '0.1.0',
          serverConnected,
          captureManager,
        });
        console.log('本地服务已启动: ' + handle.url);
        console.log('健康检查地址: ' + handle.url + '/health');
        if (config.localToken) {
          console.log('本地访问令牌: ' + config.localToken);
        }
        console.log('请不要关闭此窗口，关闭后本地采集器会停止。');
        console.log('');

        const keepAliveTimer = setInterval(() => {
          // 保持 CLI 进程常驻；本地 HTTP 服务需要随终端窗口生命周期运行。
        }, 60_000);

        let shuttingDown = false;
        const shutdown = () => {
          if (shuttingDown) return;
          shuttingDown = true;
          console.log('');
          console.log('正在停止本地采集器...');
          clearInterval(keepAliveTimer);
          handle.server.closeAllConnections();
          handle.server.closeIdleConnections?.();
          captureManager.shutdown().finally(() => {
            handle.server.close(() => process.exit(0));
          });
          setTimeout(() => process.exit(0), 3000);
        };
        process.once('SIGINT', shutdown);
        process.once('SIGTERM', shutdown);
        process.once('SIGHUP', shutdown);
        process.once('SIGTSTP', shutdown);

        await new Promise<void>(() => {
          // 保持 CLI 进程常驻，直到用户关闭终端或收到停止信号。
        });
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        console.error('本地服务启动失败: ' + message);
        console.error('建议：确认端口 ' + config.port + ' 未被占用，并允许监听 127.0.0.1。');
        console.error('释放占用端口执行：lsof -ti:' + config.port + ' | xargs kill -9');
        process.exit(1);
      }
    });
}
