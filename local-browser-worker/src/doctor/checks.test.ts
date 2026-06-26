import { describe, it, expect } from 'vitest';
import {
  checkOS,
  checkNodeVersion,
  checkArchitecture,
  checkChrome,
  checkPlaywrightChromium,
  checkDataDir,
  checkCacheDir,
  checkPort,
} from './checks';

describe('checkOS', () => {
  it('macOS 14 is supported', () => {
    const r = checkOS('darwin', '14');
    expect(r.status).toBe('通过');
    expect(r.message).toContain('macOS');
  });

  it('macOS 12 is supported (minimum)', () => {
    const r = checkOS('darwin', '12');
    expect(r.status).toBe('通过');
  });

  it('macOS 15 is supported', () => {
    const r = checkOS('darwin', '15');
    expect(r.status).toBe('通过');
  });

  it('macOS below 12 fails', () => {
    const r = checkOS('darwin', '11');
    expect(r.status).toBe('失败');
    expect(r.suggestion).toContain('12');
  });

  it('Windows 10 passes', () => {
    const r = checkOS('win32', '10');
    expect(r.status).toBe('通过');
    expect(r.message).toContain('Windows 10');
  });

  it('Windows 11 passes', () => {
    const r = checkOS('win32', '11');
    expect(r.status).toBe('通过');
    expect(r.message).toContain('Windows 11');
  });

  it('Windows below 10 fails', () => {
    const r = checkOS('win32', '6.3.9600');
    expect(r.status).toBe('失败');
    expect(r.suggestion).toContain('Windows 10');
  });

  it('Linux fails in phase 1', () => {
    const r = checkOS('linux', '6.5.0');
    expect(r.status).toBe('失败');
    expect(r.suggestion).toContain('Linux');
  });

  it('unknown platform fails', () => {
    const r = checkOS('freebsd', '13');
    expect(r.status).toBe('失败');
    expect(r.message).toContain('freebsd');
  });
});

describe('checkNodeVersion', () => {
  it('Node 20 LTS passes as recommended', () => {
    const r = checkNodeVersion('v20.18.0');
    expect(r.status).toBe('通过');
    expect(r.message).toContain('推荐');
  });

  it('Node 22 passes as recommended', () => {
    const r = checkNodeVersion('v22.0.0');
    expect(r.status).toBe('通过');
    expect(r.message).toContain('推荐');
  });

  it('Node 18 passes with upgrade suggestion', () => {
    const r = checkNodeVersion('v18.19.0');
    expect(r.status).toBe('通过');
    expect(r.suggestion).toContain('20 LTS');
  });

  it('Node 16 fails', () => {
    const r = checkNodeVersion('v16.20.0');
    expect(r.status).toBe('失败');
    expect(r.suggestion).toContain('18');
  });

  it('handles version without v prefix', () => {
    const r = checkNodeVersion('20.10.0');
    expect(r.status).toBe('通过');
  });
});

describe('checkArchitecture', () => {
  it('x64 passes', () => {
    const r = checkArchitecture('x64');
    expect(r.status).toBe('通过');
    expect(r.message).toBe('x64');
  });

  it('arm64 passes', () => {
    const r = checkArchitecture('arm64');
    expect(r.status).toBe('通过');
    expect(r.message).toBe('arm64');
  });

  it('ia32 fails', () => {
    const r = checkArchitecture('ia32');
    expect(r.status).toBe('失败');
    expect(r.suggestion).toContain('x64');
  });
});

describe('checkChrome', () => {
  it('detects Chrome when path is provided', () => {
    const r = checkChrome('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', 'darwin');
    expect(r.status).toBe('通过');
    expect(r.message).toContain('/Applications/Google Chrome.app');
  });

  it('warns when Chrome not found on macOS', () => {
    const r = checkChrome(null, 'darwin');
    expect(r.status).toBe('警告');
    expect(r.suggestion).toContain('Chrome');
  });

  it('warns when Chrome not found on Windows', () => {
    const r = checkChrome(null, 'win32');
    expect(r.status).toBe('警告');
  });

  it('fails on Linux regardless of Chrome presence', () => {
    const r = checkChrome('/usr/bin/google-chrome', 'linux');
    expect(r.status).toBe('失败');
  });
});

describe('checkPlaywrightChromium', () => {
  it('passes when cache exists', () => {
    const r = checkPlaywrightChromium(true);
    expect(r.status).toBe('通过');
    expect(r.message).toContain('已安装');
  });

  it('warns when cache missing', () => {
    const r = checkPlaywrightChromium(false);
    expect(r.status).toBe('警告');
    expect(r.suggestion).toContain('playwright install');
  });
});

describe('checkDataDir', () => {
  it('returns path in message', () => {
    const r = checkDataDir('/home/user/Library/Application Support/AI-Test-Worker');
    expect(r.status).toBe('通过');
    expect(r.message).toContain('AI-Test-Worker');
  });
});

describe('checkCacheDir', () => {
  it('returns path in message', () => {
    const r = checkCacheDir('/home/user/Library/Caches/AI-Test-Worker');
    expect(r.status).toBe('通过');
    expect(r.message).toContain('AI-Test-Worker');
  });
});

describe('checkPort', () => {
  it('passes when port available', () => {
    const r = checkPort(true, 17321);
    expect(r.status).toBe('通过');
    expect(r.message).toContain('17321');
  });

  it('fails when port occupied', () => {
    const r = checkPort(false, 17321, 'EADDRINUSE');
    expect(r.status).toBe('失败');
    expect(r.suggestion).toContain('17321');
  });

  it('explains local bind permission errors', () => {
    const r = checkPort(false, 17321, 'EPERM');
    expect(r.status).toBe('失败');
    expect(r.message).toContain('不允许监听');
    expect(r.suggestion).toContain('普通终端');
  });

  it('works with different port', () => {
    const r = checkPort(false, 8080);
    expect(r.status).toBe('失败');
    expect(r.message).toContain('8080');
  });
});
