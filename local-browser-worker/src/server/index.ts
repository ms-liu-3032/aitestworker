import * as http from 'http';
import * as fs from 'fs';
import type { WorkerConfig } from '../config';
import type { BrowserInfo } from '../browser';
import {
  getTraceScreencastFramePath,
  getTraceScreencastManifestPath,
  type CaptureManager,
  type StartCaptureRequest,
} from '../capture';

const DEFAULT_ALLOWED_ORIGINS = [
  'http://localhost:5173',
  'http://127.0.0.1:5173',
  'http://192.168.41.205:5173',
  'http://192.168.204.37:5173',
];

function deriveAllowedOrigins(config: WorkerConfig): string[] {
  const merged = new Set(DEFAULT_ALLOWED_ORIGINS);
  for (const origin of config.allowedOrigins) {
    if (origin && origin.trim().length > 0) {
      merged.add(origin.trim());
    }
  }
  return Array.from(merged);
}

export interface WorkerServerOptions {
  config: WorkerConfig;
  browser: BrowserInfo | null;
  platform: string;
  arch: string;
  version: string;
  serverConnected?: boolean;
  captureManager?: CaptureManager;
}

export interface WorkerServerHandle {
  server: http.Server;
  url: string;
}

export function startWorkerServer(options: WorkerServerOptions): Promise<WorkerServerHandle> {
  const allowedOrigins = deriveAllowedOrigins(options.config);

  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      handleRequest(req, res, options, allowedOrigins, () => {
        server.closeAllConnections();
        server.close(() => process.exit(0));
        setTimeout(() => process.exit(0), 2000);
      });
    });

    server.once('error', reject);
    server.listen(options.config.port, '127.0.0.1', () => {
      server.off('error', reject);
      resolve({
        server,
        url: `http://127.0.0.1:${options.config.port}`,
      });
    });
  });
}

function handleRequest(
  req: http.IncomingMessage,
  res: http.ServerResponse,
  options: WorkerServerOptions,
  allowedOrigins: string[],
  shutdown: () => void,
): void {
  const origin = req.headers['origin'];
  const corsOrigin = resolveCorsOrigin(origin, allowedOrigins);

  if (req.method === 'OPTIONS') {
    if (corsOrigin) {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': corsOrigin,
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Local-Token',
        'Access-Control-Max-Age': '86400',
        Vary: 'Origin',
      });
    } else {
      res.writeHead(204);
    }
    res.end();
    return;
  }

  const requestUrl = new URL(req.url || '/', 'http://127.0.0.1');
  const url = requestUrl.pathname;

  if (req.method === 'GET' && url === '/') {
    sendHtml(res, 200, buildStatusPage(options), corsOrigin);
    return;
  }

  if (req.method === 'GET' && url === '/health') {
    sendJson(res, 200, buildHealth(options), corsOrigin);
    return;
  }

  if (req.method === 'GET' && url === '/sessions/active') {
    if (!ensureLocalToken(req, res, options, corsOrigin)) return;
    sendJson(res, 200, { sessionIds: options.captureManager?.listActiveSessions() || [] }, corsOrigin);
    return;
  }

  if (req.method === 'POST' && url === '/sessions/start') {
    if (!ensureLocalToken(req, res, options, corsOrigin)) return;
    handleJson(req, res, corsOrigin, async (body) => {
      if (!options.captureManager) throw new Error('本地采集模块未初始化。');
      const result = await options.captureManager.start(body as StartCaptureRequest);
      sendJson(res, 200, { success: true, data: result, message: null }, corsOrigin);
    });
    return;
  }

  if (req.method === 'POST' && url === '/sessions/stop') {
    if (!ensureLocalToken(req, res, options, corsOrigin)) return;
    handleJson(req, res, corsOrigin, async (body) => {
      if (!options.captureManager) throw new Error('本地采集模块未初始化。');
      const sessionId = Number((body as { sessionId?: number }).sessionId);
      if (!Number.isFinite(sessionId) || sessionId <= 0) throw new Error('sessionId 不合法。');
      const result = await options.captureManager.stop(sessionId);
      sendJson(res, 200, { success: true, data: result, message: null }, corsOrigin);
    });
    return;
  }

  const screencastManifestMatch = req.method === 'GET'
    ? url.match(/^\/sessions\/(\d+)\/screencast\/manifest$/)
    : null;
  if (screencastManifestMatch) {
    if (!ensureLocalToken(req, res, options, corsOrigin)) return;
    const sessionId = Number(screencastManifestMatch[1]);
    if (!Number.isFinite(sessionId) || sessionId <= 0) {
      sendJson(res, 400, { success: false, message: 'sessionId 不合法。' }, corsOrigin);
      return;
    }
    const manifestPath = getTraceScreencastManifestPath(options.platform, sessionId);
    if (!fs.existsSync(manifestPath) || !fs.statSync(manifestPath).isFile()) {
      sendJson(res, 404, {
        success: false,
        message: `本地录屏 manifest 不存在：${manifestPath}`,
      }, corsOrigin);
      return;
    }
    sendFile(res, 200, manifestPath, 'application/json; charset=utf-8', corsOrigin);
    return;
  }

  const screencastFrameMatch = req.method === 'GET'
    ? url.match(/^\/sessions\/(\d+)\/screencast\/frame\/(\d{6}\.jpg)$/)
    : null;
  if (screencastFrameMatch) {
    if (!ensureLocalToken(req, res, options, corsOrigin)) return;
    const sessionId = Number(screencastFrameMatch[1]);
    const filename = screencastFrameMatch[2];
    if (!Number.isFinite(sessionId) || sessionId <= 0) {
      sendJson(res, 400, { success: false, message: 'sessionId 不合法。' }, corsOrigin);
      return;
    }
    const framePath = getTraceScreencastFramePath(options.platform, sessionId, filename);
    if (!fs.existsSync(framePath) || !fs.statSync(framePath).isFile()) {
      sendJson(res, 404, {
        success: false,
        message: `本地录屏帧不存在：${framePath}`,
      }, corsOrigin);
      return;
    }
    sendFile(res, 200, framePath, 'image/jpeg', corsOrigin);
    return;
  }

  if (req.method === 'POST' && url === '/shutdown') {
    if (!ensureLocalToken(req, res, options, corsOrigin)) return;
    sendJson(res, 200, { success: true, data: null, message: 'worker 正在关闭...' }, corsOrigin);
    setTimeout(() => {
      options.captureManager?.shutdown().finally(() => shutdown());
    }, 100);
    return;
  }

  if (req.method === 'POST' && url === '/browser/open-profile') {
    if (!ensureLocalToken(req, res, options, corsOrigin)) return;
    handleJson(req, res, corsOrigin, async (body) => {
      if (!options.captureManager) throw new Error('本地采集模块未初始化。');
      const result = await options.captureManager.navigateToUrl(body as {
        profileId: number; targetUrl: string; storageStatePath?: string;
      });
      sendJson(res, 200, { success: true, data: result, message: null }, corsOrigin);
    });
    return;
  }

  if (req.method === 'POST' && url === '/browser/close-profile') {
    if (!ensureLocalToken(req, res, options, corsOrigin)) return;
    handleJson(req, res, corsOrigin, async (body) => {
      if (!options.captureManager) throw new Error('本地采集模块未初始化。');
      const profileId = Number((body as { profileId?: number }).profileId);
      if (!Number.isFinite(profileId) || profileId <= 0) throw new Error('profileId 不合法。');
      await options.captureManager.closeProfileContext(profileId);
      sendJson(res, 200, { success: true, data: null, message: '身份空间浏览器已关闭' }, corsOrigin);
    });
    return;
  }

  if (req.method === 'POST' && url === '/browser/shutdown') {
    if (!ensureLocalToken(req, res, options, corsOrigin)) return;
    handleJson(req, res, corsOrigin, async () => {
      if (!options.captureManager) throw new Error('本地采集模块未初始化。');
      await options.captureManager.shutdown();
      sendJson(res, 200, { success: true, data: null, message: '浏览器已关闭' }, corsOrigin);
    });
    return;
  }

  sendJson(res, 404, {
    success: false,
    message: '接口不存在',
  }, corsOrigin);
}

function resolveCorsOrigin(origin: string | string[] | undefined, allowed: string[]): string | null {
  if (!origin) return null;
  const originStr = Array.isArray(origin) ? origin[0] : origin;
  if (allowed.includes(originStr)) return originStr;
  return null;
}

export function buildHealth(options: WorkerServerOptions) {
  return {
    status: 'UP',
    version: options.version,
    protocolVersion: '1',
    platform: options.platform,
    arch: options.arch,
    browserReady: options.browser !== null,
    playwrightReady: options.browser?.type === 'playwright',
    bound: options.config.bindStatus === 'BOUND',
    serverConnected: Boolean(options.serverConnected),
    activeSessions: options.captureManager?.listActiveSessions() || [],
    browserAlive: options.captureManager?.isBrowserAlive() ?? false,
    browser: options.browser
      ? {
          type: options.browser.type,
          displayName: options.browser.displayName,
        }
      : null,
  };
}

function ensureLocalToken(
  req: http.IncomingMessage,
  res: http.ServerResponse,
  options: WorkerServerOptions,
  corsOrigin: string | null,
): boolean {
  const expected = options.config.localToken;
  if (!expected) return true;
  const header = req.headers['x-local-token'];
  const authorization = req.headers['authorization'];
  const requestUrl = new URL(req.url || '/', 'http://127.0.0.1');
  const queryToken = requestUrl.searchParams.get('localToken') || '';
  const actual = Array.isArray(header) ? header[0] : header;
  const bearer = Array.isArray(authorization) ? authorization[0] : authorization;
  const bearerToken = bearer?.startsWith('Bearer ') ? bearer.slice(7).trim() : '';
  if (actual === expected || bearerToken === expected || queryToken === expected) return true;
  sendJson(res, 401, { success: false, message: '本地访问令牌无效，请在页面填写 local_token。' }, corsOrigin);
  return false;
}

function handleJson(
  req: http.IncomingMessage,
  res: http.ServerResponse,
  corsOrigin: string | null,
  handler: (body: unknown) => Promise<void>,
): void {
  let data = '';
  req.on('data', (chunk) => {
    data += chunk;
    if (data.length > 1024 * 1024) {
      req.destroy(new Error('请求体过大'));
    }
  });
  req.on('end', () => {
    void (async () => {
      try {
        const body = data ? JSON.parse(data) : {};
        await handler(body);
      } catch (err) {
        sendJson(res, 400, {
          success: false,
          message: err instanceof Error ? err.message : String(err),
        }, corsOrigin);
      }
    })();
  });
}

function sendJson(
  res: http.ServerResponse,
  statusCode: number,
  body: unknown,
  corsOrigin: string | null,
): void {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  };
  if (corsOrigin) {
    headers['Access-Control-Allow-Origin'] = corsOrigin;
    headers.Vary = 'Origin';
  }
  const text = JSON.stringify(body, null, 2);
  res.writeHead(statusCode, headers);
  res.end(text);
}

function sendFile(
  res: http.ServerResponse,
  statusCode: number,
  filePath: string,
  contentType: string,
  corsOrigin: string | null,
): void {
  const headers: Record<string, string> = {
    'Content-Type': contentType,
    'Cache-Control': 'no-store',
  };
  if (corsOrigin) {
    headers['Access-Control-Allow-Origin'] = corsOrigin;
    headers.Vary = 'Origin';
  }
  res.writeHead(statusCode, headers);
  fs.createReadStream(filePath).pipe(res);
}

function buildStatusPage(options: WorkerServerOptions): string {
  const health = buildHealth(options);
  const browserText = health.browser?.displayName || '未找到可用浏览器';
  const boundText = health.bound ? '已绑定' : '未绑定';
  const serverText = health.serverConnected ? '已连接' : '未连接';
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>AI Test Worker</title>
  <style>
    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #f6f4ef; color: #252525; }
    main { max-width: 760px; margin: 56px auto; padding: 0 24px; }
    .panel { background: #fffdf8; border: 1px solid #e5dfd2; border-radius: 8px; padding: 24px; box-shadow: 0 10px 30px rgba(40, 35, 25, .06); }
    h1 { margin: 0 0 8px; font-size: 24px; letter-spacing: 0; }
    p { margin: 0; color: #686256; line-height: 1.6; }
    dl { display: grid; grid-template-columns: 150px 1fr; gap: 12px 16px; margin: 24px 0 0; }
    dt { color: #81796a; }
    dd { margin: 0; font-weight: 600; word-break: break-all; }
    .ok { color: #16794c; }
    .warn { color: #a15c00; }
    .links { margin-top: 24px; display: flex; gap: 12px; flex-wrap: wrap; }
    a { color: #2f5f7f; text-decoration: none; font-weight: 600; }
  </style>
</head>
<body>
  <main>
    <section class="panel">
      <h1>AI Test Worker 本地采集器</h1>
      <p>本地服务已启动。请保持这个终端窗口开启，关闭后采集器会停止。</p>
      <dl>
        <dt>服务状态</dt><dd class="ok">${escapeHtml(health.status)}</dd>
        <dt>版本</dt><dd>${escapeHtml(health.version)}</dd>
        <dt>协议版本</dt><dd>${escapeHtml(health.protocolVersion)}</dd>
        <dt>平台</dt><dd>${escapeHtml(health.platform)} / ${escapeHtml(health.arch)}</dd>
        <dt>浏览器</dt><dd>${escapeHtml(browserText)}</dd>
        <dt>绑定状态</dt><dd class="${health.bound ? 'ok' : 'warn'}">${boundText}</dd>
        <dt>主平台连接</dt><dd class="${health.serverConnected ? 'ok' : 'warn'}">${serverText}</dd>
      </dl>
      <div class="links">
        <a href="/health">查看 JSON 健康检查</a>
        <a href="http://127.0.0.1:5173">打开 AI 测试平台</a>
      </div>
    </section>
  </main>
</body>
</html>`;
}

function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function sendHtml(
  res: http.ServerResponse,
  statusCode: number,
  body: string,
  corsOrigin: string | null,
): void {
  const headers: Record<string, string> = {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-store',
  };
  if (corsOrigin) {
    headers['Access-Control-Allow-Origin'] = corsOrigin;
    headers.Vary = 'Origin';
  }
  res.writeHead(statusCode, headers);
  res.end(body);
}
