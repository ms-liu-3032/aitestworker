import { describe, it, expect } from 'vitest';
import * as pw from 'playwright-core';
import { chromium } from 'playwright-core';

describe('playwright-core 1.61 upgrade regression', () => {
  it('exports chromium launcher with expected methods', () => {
    expect(chromium).toBeDefined();
    expect(typeof chromium.launch).toBe('function');
    expect(typeof chromium.launchPersistentContext).toBe('function');
    expect(typeof chromium.connect).toBe('function');
    expect(typeof chromium.connectOverCDP).toBe('function');
  });

  it('version is ^1.61.x', () => {
    const pkg = require('playwright-core/package.json');
    expect(pkg.version).toMatch(/^1\.61\./);
  });

  it('named exports match capture/index.ts usage', () => {
    // capture/index.ts line 3: import { chromium, type Browser, type BrowserContext, type CDPSession, type Page, type Request }
    // Verify runtime exports (chromium) exist; types are compile-time only
    expect(pw.chromium).toBeDefined();
  });
});
