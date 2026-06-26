import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as http from 'http';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { startWorkerServer, buildHealth } from './index';
import type { WorkerServerHandle } from './index';
import { DEFAULT_CONFIG, WorkerConfig } from '../config';
import type { BrowserInfo } from '../browser';

function freshAgent(): http.Agent {
  return new http.Agent({ keepAlive: false });
}

function get(url: string, origin?: string): Promise<{ status: number; body: unknown; headers: http.IncomingHttpHeaders }> {
  return new Promise((resolve, reject) => {
    const headers: Record<string, string> = {};
    if (origin) headers['Origin'] = origin;
    http.get(`http://127.0.0.1:17399${url}`, { headers, agent: freshAgent() }, (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => {
        try {
          resolve({ status: res.statusCode || 0, body: JSON.parse(data), headers: res.headers });
        } catch {
          resolve({ status: res.statusCode || 0, body: data, headers: res.headers });
        }
      });
    }).on('error', reject);
  });
}

function options(url: string, origin: string): Promise<{ status: number; headers: http.IncomingHttpHeaders }> {
  return new Promise((resolve, reject) => {
    const req = http.request(`http://127.0.0.1:17399${url}`, {
      method: 'OPTIONS',
      headers: { Origin: origin },
      agent: freshAgent(),
    }, (res) => {
      resolve({ status: res.statusCode || 0, headers: res.headers });
    });
    req.on('error', reject);
    req.end();
  });
}

function getRaw(url: string, headers?: Record<string, string>): Promise<{ status: number; body: string; headers: http.IncomingHttpHeaders }> {
  return new Promise((resolve, reject) => {
    http.get(`http://127.0.0.1:17399${url}`, { headers, agent: freshAgent() }, (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => {
        resolve({ status: res.statusCode || 0, body: data, headers: res.headers });
      });
    }).on('error', reject);
  });
}

const testBrowser: BrowserInfo = {
  type: 'system',
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  displayName: '系统 Chrome (/Applications/Google Chrome.app/Contents/MacOS/Google Chrome)',
};

const baseConfig: WorkerConfig = {
  ...DEFAULT_CONFIG,
  port: 17399,
};

function closeServer(h: WorkerServerHandle): Promise<void> {
  return new Promise((resolve) => {
    h.server.closeAllConnections();
    h.server.close(() => resolve());
  });
}

async function restartServer(config: WorkerConfig, browser: BrowserInfo | null, platform: string, arch: string): Promise<WorkerServerHandle> {
  return startWorkerServer({ config, browser, platform, arch, version: '0.1.0' });
}

describe('Worker Server', () => {
  let handle: WorkerServerHandle;

  afterAll(async () => {
    if (handle) await closeServer(handle);
  });

  describe('/health endpoint', () => {
    beforeAll(async () => {
      handle = await restartServer(baseConfig, testBrowser, 'darwin', 'arm64');
    });

    it('returns UP status with all required fields', async () => {
      const { status, body } = await get('/health');
      expect(status).toBe(200);
      const data = body as Record<string, unknown>;
      expect(data.status).toBe('UP');
      expect(data.version).toBe('0.1.0');
      expect(data.protocolVersion).toBe('1');
      expect(data.platform).toBe('darwin');
      expect(data.arch).toBe('arm64');
      expect(data.browserReady).toBe(true);
      expect(data.playwrightReady).toBe(false);
      expect(data.bound).toBe(false);
      expect(data.serverConnected).toBe(false);
      expect((data.browser as Record<string, unknown>).type).toBe('system');
    });

    it('returns bound=true when config has BOUND', async () => {
      await closeServer(handle);
      const boundConfig: WorkerConfig = { ...baseConfig, bindStatus: 'BOUND' };
      handle = await restartServer(boundConfig, testBrowser, 'darwin', 'arm64');
      const { body } = await get('/health');
      expect((body as Record<string, unknown>).bound).toBe(true);
      await closeServer(handle);
      handle = await restartServer(baseConfig, testBrowser, 'darwin', 'arm64');
    });

    it('returns browser=null when no browser found', async () => {
      await closeServer(handle);
      handle = await restartServer(baseConfig, null, 'linux', 'x64');
      const { body } = await get('/health');
      expect((body as Record<string, unknown>).browser).toBeNull();
      expect((body as Record<string, unknown>).browserReady).toBe(false);
      await closeServer(handle);
      handle = await restartServer(baseConfig, testBrowser, 'darwin', 'arm64');
    });

    it('buildHealth returns all required fields', () => {
      const health = buildHealth({
        config: baseConfig,
        browser: testBrowser,
        platform: 'darwin',
        arch: 'arm64',
        version: '0.1.0',
      });
      expect(health).toHaveProperty('status');
      expect(health).toHaveProperty('version');
      expect(health).toHaveProperty('protocolVersion');
      expect(health).toHaveProperty('platform');
      expect(health).toHaveProperty('arch');
      expect(health).toHaveProperty('browserReady');
      expect(health).toHaveProperty('playwrightReady');
      expect(health).toHaveProperty('bound');
      expect(health).toHaveProperty('serverConnected');
      expect(health).toHaveProperty('browser');
    });

    it('returns serverConnected from startup heartbeat result', () => {
      const health = buildHealth({
        config: baseConfig,
        browser: testBrowser,
        platform: 'darwin',
        arch: 'arm64',
        version: '0.1.0',
        serverConnected: true,
      });
      expect(health.serverConnected).toBe(true);
    });
  });

  describe('CORS', () => {
    beforeAll(async () => {
      if (handle) await closeServer(handle);
      handle = await restartServer(baseConfig, testBrowser, 'darwin', 'arm64');
    });

    it('allows localhost:5173', async () => {
      const { headers } = await get('/health', 'http://localhost:5173');
      expect(headers['access-control-allow-origin']).toBe('http://localhost:5173');
    });

    it('allows 127.0.0.1:5173', async () => {
      const { headers } = await get('/health', 'http://127.0.0.1:5173');
      expect(headers['access-control-allow-origin']).toBe('http://127.0.0.1:5173');
    });

    it('rejects unknown origin', async () => {
      const { headers } = await get('/health', 'http://evil.example.com');
      expect(headers['access-control-allow-origin']).toBeUndefined();
    });

    it('does not set Access-Control-Allow-Origin: *', async () => {
      const { headers } = await get('/health', 'http://localhost:5173');
      expect(headers['access-control-allow-origin']).not.toBe('*');
    });

    it('allows custom origin from config.allowedOrigins', async () => {
      await closeServer(handle);
      const customConfig: WorkerConfig = { ...baseConfig, allowedOrigins: ['http://custom.example.com'] };
      handle = await restartServer(customConfig, testBrowser, 'darwin', 'arm64');
      const { headers } = await get('/health', 'http://custom.example.com');
      expect(headers['access-control-allow-origin']).toBe('http://custom.example.com');
      await closeServer(handle);
      handle = await restartServer(baseConfig, testBrowser, 'darwin', 'arm64');
    });

    it('still allows defaults when custom origins are set', async () => {
      await closeServer(handle);
      const customConfig: WorkerConfig = { ...baseConfig, allowedOrigins: ['http://custom.example.com'] };
      handle = await restartServer(customConfig, testBrowser, 'darwin', 'arm64');
      const { headers } = await get('/health', 'http://localhost:5173');
      expect(headers['access-control-allow-origin']).toBe('http://localhost:5173');
      await closeServer(handle);
      handle = await restartServer(baseConfig, testBrowser, 'darwin', 'arm64');
    });
  });

  describe('OPTIONS preflight', () => {
    beforeAll(async () => {
      if (handle) await closeServer(handle);
      handle = await restartServer(baseConfig, testBrowser, 'darwin', 'arm64');
    });

    it('returns 204 with CORS headers for allowed origin', async () => {
      const { status, headers } = await options('/health', 'http://localhost:5173');
      expect(status).toBe(204);
      expect(headers['access-control-allow-origin']).toBe('http://localhost:5173');
      expect(headers['access-control-allow-methods']).toContain('GET');
      expect(headers['access-control-allow-headers']).toContain('Content-Type');
    });

    it('returns 204 without CORS headers for unknown origin', async () => {
      const { status, headers } = await options('/health', 'http://evil.example.com');
      expect(status).toBe(204);
      expect(headers['access-control-allow-origin']).toBeUndefined();
    });
  });

  describe('listen address', () => {
    it('responds on 127.0.0.1', async () => {
      const { status, body } = await get('/health');
      expect(status).toBe(200);
      expect((body as Record<string, unknown>).status).toBe('UP');
    });
  });

  describe('404 for unknown routes', () => {
    beforeAll(async () => {
      if (handle) await closeServer(handle);
      handle = await restartServer(baseConfig, testBrowser, 'darwin', 'arm64');
    });

    it('returns 404 for unknown path', async () => {
      const { status, body } = await get('/unknown');
      expect(status).toBe(404);
      expect((body as Record<string, unknown>).success).toBe(false);
    });
  });

  describe('local screencast file serving', () => {
    const testDataDir = path.join(os.tmpdir(), `aitest-worker-server-${Date.now()}`);

    beforeAll(async () => {
      process.env.HOME = testDataDir;
      const manifestPath = path.join(
        testDataDir,
        'Library/Application Support/AI-Test-Worker/traces/4/screencast/manifest.json',
      );
      const framePath = path.join(
        testDataDir,
        'Library/Application Support/AI-Test-Worker/traces/4/screencast/frames/000001.jpg',
      );
      fs.mkdirSync(path.dirname(framePath), { recursive: true });
      fs.writeFileSync(
        manifestPath,
        JSON.stringify({ sessionId: 4, frameCount: 1, frames: [{ index: 1, relativeMs: 0, filename: '000001.jpg' }] }),
      );
      fs.writeFileSync(framePath, 'jpeg-data');

      if (handle) await closeServer(handle);
      const tokenConfig: WorkerConfig = { ...baseConfig, localToken: 'local-test-token' };
      handle = await restartServer(tokenConfig, testBrowser, 'darwin', 'arm64');
    });

    it('serves local screencast manifest via worker endpoint', async () => {
      const { status, body, headers } = await getRaw('/sessions/4/screencast/manifest', {
        'X-Local-Token': 'local-test-token',
      });
      expect(status).toBe(200);
      expect(headers['content-type']).toContain('application/json');
      expect(JSON.parse(body).sessionId).toBe(4);
    });

    it('serves screencast frame via query token', async () => {
      const { status, body, headers } = await getRaw('/sessions/4/screencast/frame/000001.jpg?localToken=local-test-token');
      expect(status).toBe(200);
      expect(headers['content-type']).toContain('image/jpeg');
      expect(body).toBe('jpeg-data');
    });
  });
});
