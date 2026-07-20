import * as fs from 'fs';
import * as path from 'path';
import { chromium, type Browser, type BrowserContext, type CDPSession, type Page, type Request } from 'playwright-core';
import type { BrowserInfo } from '../browser';
import * as platform from '../platform';

export interface StartCaptureRequest {
  traceGroupId: number;
  sessionId: number;
  profileId: number;
  profileName?: string;
  targetHost?: string;
  storageStatePath?: string;
  autoFill?: {
    username?: string;
    password?: string;
  };
}

export interface OpenProfileRequest {
  profileId: number;
  targetUrl: string;
  storageStatePath?: string;
  autoFill?: {
    username?: string;
    password?: string;
  };
}

export interface StopCaptureResponse {
  sessionId: number;
  status: 'STOPPED';
  events: CapturedEvent[];
  networks: CapturedNetwork[];
  videoPath: string | null;
  traceFilePath: string | null;
  /** Sprint 4 · M4.2：本 session 的浏览器主录屏 webm 路径（绝对路径） */
  screencastPath: string | null;
  /** 录屏开始 UTC ISO，与 session.recording_started_at_utc 对齐用 */
  screencastStartedAtUtc: string | null;
  /** 录屏停止 UTC ISO */
  screencastStoppedAtUtc: string | null;
  /** 录屏时长（毫秒） */
  screencastDurationMs: number | null;
}

export interface CapturedEvent {
  eventType: string;
  pageUrl: string;
  pageTitle: string;
  elementText: string;
  elementRole: string;
  selector: string;
  normalizedLocator: string;
  sectionTitle: string;
  dialogTitle: string;
  objectLabel: string;
  valueSummary: string;
  screenshotPath: string | null;
  happenedAtUtc: string;
  happenedAtLocal: string;
  timezone: string;
  relativeMs: number;
}

export interface CapturedNetwork {
  url: string;
  method: string;
  statusCode: number | null;
  durationMs: number | null;
  failed: boolean;
  errorMessage: string | null;
  requestSummary: string | null;
  responseSummary: string | null;
  requestStartedAtUtc: string;
  requestStartedAtLocal: string;
  responseEndedAtUtc: string | null;
  responseEndedAtLocal: string | null;
  timezone: string;
  relativeMs: number;
}

interface ProfileContext {
  context: BrowserContext;
  page: Page;
  profileId: number;
  storageStatePath: string;
}

interface ScreencastFrameEntry {
  index: number;
  relativeMs: number;
  filename: string;
}

interface ActiveSession {
  request: StartCaptureRequest;
  page: Page;
  context: BrowserContext;
  startedAtMs: number;
  /** session 实际开始录像的 UTC 时间（ISO 字符串），用于和 backend 对齐 */
  videoStartedAtUtc: string | null;
  events: CapturedEvent[];
  networks: CapturedNetwork[];
  requestStarted: Map<Request, { startedAtMs: number; method: string; url: string }>;
  sessionDir: string;
  traceFilePath: string;
  lastScreenshotPath: string | null;
  lastScreenshotAtMs: number;
  /** Sprint 4 · M4.7：CDP Page.startScreencast 用到的 page-level CDP session */
  cdpScreencast: CDPSession | null;
  /** 落帧的根目录：<sessionDir>/screencast/ */
  screencastDir: string;
  /** 实际写盘的 jpeg 帧（按时间递增） */
  screencastFrames: ScreencastFrameEntry[];
  /** 已写盘帧的索引（自增计数器） */
  screencastFrameCount: number;
}

export function getProfileDownloadsDir(platformName: string, profileId: number): string {
  return path.join(platform.getDataDir(platformName), 'profiles', String(profileId), 'downloads');
}

export function getTraceSessionDir(platformName: string, sessionId: number): string {
  return path.join(platform.getDataDir(platformName), 'traces', String(sessionId));
}

export function getTraceScreencastManifestPath(platformName: string, sessionId: number): string {
  return path.join(getTraceSessionDir(platformName, sessionId), 'screencast', 'manifest.json');
}

export function getTraceScreencastFramePath(platformName: string, sessionId: number, filename: string): string {
  return path.join(getTraceSessionDir(platformName, sessionId), 'screencast', 'frames', filename);
}

export function resolveCreatedBrowserContextId(before: string[], after: string[]): string | null {
  const created = after.filter((id) => !before.includes(id));
  if (created.length === 1) return created[0];
  return null;
}

export class CaptureManager {
  private readonly sessions = new Map<number, ActiveSession>();
  private readonly profileContexts = new Map<number, ProfileContext>();
  private readonly hookedPages = new WeakSet<Page>();
  private readonly browserContextIds = new WeakMap<BrowserContext, string>();
  private sharedBrowser: Browser | null = null;

  constructor(
    private readonly browserInfo: BrowserInfo | null,
    private readonly platformName: string,
  ) {
  }

  private async ensureBrowser(): Promise<Browser> {
    if (this.sharedBrowser?.isConnected()) return this.sharedBrowser;
    if (!this.browserInfo) {
      throw new Error('未找到可用浏览器，请先安装 Chrome 或执行 ai-test-worker doctor 检查环境。');
    }
    this.sharedBrowser = await chromium.launch({
      executablePath: this.browserInfo.executablePath,
      headless: false,
      args: [
        '--disable-blink-features=AutomationControlled',
        '--start-maximized',
      ],
    });
    this.sharedBrowser.on('disconnected', () => {
      this.sharedBrowser = null;
      this.profileContexts.clear();
      this.sessions.clear();
    });
    return this.sharedBrowser;
  }

  isBrowserAlive(): boolean {
    return this.sharedBrowser?.isConnected() ?? false;
  }

  private profileDir(profileId: number): string {
    return path.join(platform.getDataDir(this.platformName), 'profiles', String(profileId));
  }

  private profileDownloadsDir(profileId: number): string {
    return getProfileDownloadsDir(this.platformName, profileId);
  }

  private traceRootDir(): string {
    return path.join(platform.getDataDir(this.platformName), 'traces');
  }

  private async getOrCreateProfileContext(
    req: { profileId: number; storageStatePath?: string },
  ): Promise<ProfileContext> {
    const existing = this.profileContexts.get(req.profileId);
    if (existing) {
      try {
        await existing.page.bringToFront();
      } catch { /* page may have been closed */ }
      return existing;
    }
    return await this.createProfileContext(req);
  }

  private async createProfileContext(
    req: { profileId: number; storageStatePath?: string },
  ): Promise<ProfileContext> {
    const dir = this.profileDir(req.profileId);
    const storageStatePath = req.storageStatePath || path.join(dir, 'storage-state.json');
    fs.mkdirSync(dir, { recursive: true });

    const browser = await this.ensureBrowser();
    const browserSession = await browser.newBrowserCDPSession();
    const before = await browserSession.send('Target.getBrowserContexts');
    const context = await browser.newContext({
      storageState: fs.existsSync(storageStatePath) ? storageStatePath : undefined,
      viewport: null,
    });
    const after = await browserSession.send('Target.getBrowserContexts');
    const browserContextId = resolveCreatedBrowserContextId(before.browserContextIds, after.browserContextIds);
    if (browserContextId) {
      this.browserContextIds.set(context, browserContextId);
    }
    await browserSession.detach().catch(() => undefined);
    const page = await context.newPage();
    await this.configureDownloadBehavior(context, page, req.profileId);
    const pc: ProfileContext = {
      context,
      page,
      profileId: req.profileId,
      storageStatePath,
    };
    this.profileContexts.set(req.profileId, pc);

    context.on('close', () => {
      if (this.profileContexts.get(req.profileId) === pc) {
        this.profileContexts.delete(req.profileId);
      }
    });

    return pc;
  }

  private async configureDownloadBehavior(context: BrowserContext, page: Page, profileId: number): Promise<void> {
    const downloadsDir = this.profileDownloadsDir(profileId);
    fs.mkdirSync(downloadsDir, { recursive: true });

    const browserContextId = this.browserContextIds.get(context);
    if (browserContextId) {
      const browser = await this.ensureBrowser();
      const browserSession = await browser.newBrowserCDPSession();
      try {
        await browserSession.send('Browser.setDownloadBehavior', {
          behavior: 'allow',
          browserContextId,
          downloadPath: downloadsDir,
          eventsEnabled: true,
        } as never);
        return;
      } finally {
        await browserSession.detach().catch(() => undefined);
      }
    }

    const pageSession = await context.newCDPSession(page);
    try {
      await pageSession.send('Page.setDownloadBehavior', {
        behavior: 'allow',
        downloadPath: downloadsDir,
      } as never);
    } finally {
      await pageSession.detach().catch(() => undefined);
    }
  }

  async navigateToUrl(req: OpenProfileRequest): Promise<{ url: string; pageTitle: string }> {
    const pc = await this.getOrCreateProfileContext(req);
    const targetUrl = normalizeTargetUrl(req.targetUrl);
    await pc.page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => undefined);
    await this.tryAutoFill(pc.page, req.autoFill);
    const pageTitle = await pc.page.title().catch(() => '');
    return { url: pc.page.url(), pageTitle };
  }

  async start(request: StartCaptureRequest): Promise<{ sessionId: number; status: 'RECORDING'; url: string }> {
    if (this.sessions.has(request.sessionId)) {
      throw new Error('该采集会话已经在录制中。');
    }
    for (const session of this.sessions.values()) {
      if (session.request.profileId === request.profileId) {
        throw new Error('该身份空间已在采集中，请先停止后再开始。');
      }
    }

    // Sprint 4 · M4.7：CDP `Page.startScreencast` 是 page 级，profileContext 不再需要重建
    const hadExistingContext = this.profileContexts.has(request.profileId);
    const pc = await this.getOrCreateProfileContext({
      profileId: request.profileId,
      storageStatePath: request.storageStatePath,
    });

    // 已经打开并停留在业务页的身份空间，不要在“开始采集”时再强制跳回 targetHost。
    // 否则会让用户误以为“旧窗口被关掉/又打开了新窗口”，并破坏连续分段采集。
    const targetUrl = normalizeTargetUrl(request.targetHost);
    const currentUrl = safePageUrl(pc.page);
    const shouldNavigateOnStart = !hadExistingContext || isBlankLikeUrl(currentUrl);
    if (request.targetHost && request.targetHost.trim() && shouldNavigateOnStart) {
      await pc.page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => undefined);
    }
    if (shouldNavigateOnStart) {
      await this.tryAutoFill(pc.page, request.autoFill);
    }

    // Ensure hooks are installed (idempotent via initScript)
    await this.installPageHooks(pc.page);

    const sessionDir = path.join(this.traceRootDir(), String(request.sessionId));
    fs.mkdirSync(sessionDir, { recursive: true });
    const traceFilePath = path.join(sessionDir, 'playwright-trace.zip');
    const screencastDir = path.join(sessionDir, 'screencast');
    fs.mkdirSync(path.join(screencastDir, 'frames'), { recursive: true });

    await pc.context.tracing.start({
      screenshots: true,
      snapshots: true,
      sources: false,
      title: `session-${request.sessionId}`,
    }).catch(() => undefined);

    const videoStartedAtUtc = new Date().toISOString();
    const active: ActiveSession = {
      request,
      page: pc.page,
      context: pc.context,
      startedAtMs: Date.now(),
      videoStartedAtUtc,
      events: [],
      networks: [],
      requestStarted: new Map(),
      sessionDir,
      traceFilePath,
      lastScreenshotPath: null,
      lastScreenshotAtMs: 0,
      cdpScreencast: null,
      screencastDir,
      screencastFrames: [],
      screencastFrameCount: 0,
    };

    // 启动 CDP 主录屏（page 级），不重建 context、无视觉闪动
    await this.startCdpScreencast(active, pc.page).catch((err: unknown) => {
      // 录屏失败不阻塞 session start；仍然继续事件采集
      // eslint-disable-next-line no-console
      console.error('[capture] startScreencast failed:', err);
    });

    await this.recordEvent(active, {
      eventType: 'PAGE_OPEN',
      pageUrl: pc.page.url(),
      pageTitle: await this.resolvePageTitle(pc.page),
      elementText: request.profileName || '',
      elementRole: 'browser_profile',
      selector: '',
      valueSummary: '',
    });
    this.sessions.set(request.sessionId, active);
    return { sessionId: request.sessionId, status: 'RECORDING', url: pc.page.url() };
  }

  /**
   * 用 Chromium DevTools Protocol 的 Page.startScreencast 启动主录屏。
   * 帧以 JPEG 形式保存到 <sessionDir>/screencast/frames/，并在 stop 时
   * 写入 manifest.json，供前端按时间索引回放。
   */
  private async startCdpScreencast(active: ActiveSession, page: Page): Promise<void> {
    const cdp = await active.context.newCDPSession(page);
    active.cdpScreencast = cdp;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    cdp.on('Page.screencastFrame', async (frame: any) => {
      // frame: { data: base64 jpeg, metadata: { timestamp: number }, sessionId: number }
      const ackSessionId = frame?.sessionId;
      try {
        const index = active.screencastFrameCount++;
        const relativeMs = Math.max(0, Date.now() - active.startedAtMs);
        const filename = String(index).padStart(6, '0') + '.jpg';
        const framePath = path.join(active.screencastDir, 'frames', filename);
        fs.writeFileSync(framePath, Buffer.from(String(frame?.data ?? ''), 'base64'));
        active.screencastFrames.push({ index, relativeMs, filename });
      } catch { /* ignore frame write errors */ }
      try {
        if (ackSessionId !== undefined && ackSessionId !== null) {
          await cdp.send('Page.screencastFrameAck' as 'Page.startScreencast', { sessionId: ackSessionId } as never);
        }
      } catch { /* ignore ack errors (page may have closed) */ }
    });

    await cdp.send('Page.startScreencast' as 'Page.startScreencast', {
      format: 'jpeg',
      quality: 70,
      everyNthFrame: 2,
    } as never);
  }

  async stop(sessionId: number): Promise<StopCaptureResponse> {
    const active = this.sessions.get(sessionId);
    if (!active) {
      throw new Error('采集会话未在本地运行，可能已经停止。');
    }

    await this.recordEvent(active, {
      eventType: 'SESSION_STOP',
      pageUrl: active.page.url(),
      pageTitle: await this.resolvePageTitle(active.page),
      elementText: '',
      elementRole: '',
      selector: '',
      valueSummary: '',
    });

    await active.context.tracing.stop({ path: active.traceFilePath }).catch(() => undefined);
    active.lastScreenshotPath = await this.captureScreenshot(active, 'stop');

    // 找到对应的 profile context，保存 storageState 供下次复用
    let owningPc: ProfileContext | undefined;
    for (const pc of this.profileContexts.values()) {
      if (pc.page === active.page) {
        owningPc = pc;
        break;
      }
    }
    if (owningPc) {
      await owningPc.context.storageState({ path: owningPc.storageStatePath }).catch(() => undefined);
    }

    this.sessions.delete(sessionId);

    // Sprint 4 · M4.7：用 CDP Page.stopScreencast 收尾，并写 manifest.json。
    // profileContext **不再** 重建，浏览器窗口/标签保持不动，无视觉闪动。
    const screencastStartedAtUtc = active.videoStartedAtUtc;
    const screencastStoppedAtUtc = new Date().toISOString();
    let screencastDurationMs: number | null = null;
    if (screencastStartedAtUtc) {
      screencastDurationMs = Math.max(0, Date.parse(screencastStoppedAtUtc) - Date.parse(screencastStartedAtUtc));
    }
    let screencastPath: string | null = null;

    if (active.cdpScreencast) {
      try {
        await active.cdpScreencast.send('Page.stopScreencast' as 'Page.startScreencast', {} as never);
      } catch { /* ignore */ }
      try { await active.cdpScreencast.detach(); } catch { /* ignore */ }
      active.cdpScreencast = null;
    }

    // 不论有没有真采到帧，都落一份 manifest.json，便于审计与前端"空录屏"提示
    try {
      const manifestPath = path.join(active.screencastDir, 'manifest.json');
      const manifest = {
        sessionId,
        format: 'cdp-jpeg-manifest',
        startedAtUtc: screencastStartedAtUtc,
        stoppedAtUtc: screencastStoppedAtUtc,
        durationMs: screencastDurationMs,
        frameCount: active.screencastFrames.length,
        frames: active.screencastFrames,
      };
      fs.writeFileSync(manifestPath, JSON.stringify(manifest));
      if (active.screencastFrames.length > 0) {
        screencastPath = manifestPath;
      }
    } catch { /* ignore manifest write errors */ }

    return {
      sessionId,
      status: 'STOPPED',
      events: active.events,
      networks: active.networks,
      videoPath: active.lastScreenshotPath,
      traceFilePath: active.traceFilePath,
      screencastPath,
      screencastStartedAtUtc,
      screencastStoppedAtUtc,
      screencastDurationMs,
    };
  }

  async shutdown(): Promise<void> {
    for (const sid of this.sessions.keys()) {
      await this.stop(sid).catch(() => undefined);
    }
    for (const pc of this.profileContexts.values()) {
      await pc.context.close().catch(() => undefined);
    }
    this.profileContexts.clear();
    if (this.sharedBrowser) {
      await this.sharedBrowser.close().catch(() => undefined);
      this.sharedBrowser = null;
    }
  }

  listActiveSessions(): number[] {
    return Array.from(this.sessions.keys());
  }

  /** Close a specific profile's context (used for CLEAR_AUTH / RESET) */
  async closeProfileContext(profileId: number): Promise<void> {
    const pc = this.profileContexts.get(profileId);
    if (pc) {
      // If there's a session using this page, stop it first
      for (const [sid, session] of this.sessions) {
        if (session.page === pc.page) {
          await this.stop(sid).catch(() => undefined);
          break;
        }
      }
      await pc.context.close().catch(() => undefined);
      this.profileContexts.delete(profileId);
    }
  }

  private async installPageHooks(page: Page): Promise<void> {
    if (this.hookedPages.has(page)) return;

    const installDomHooks = () => {
      if ((window as any).__aiTestTraceHooksInstalled) return;
      (window as any).__aiTestTraceHooksInstalled = true;
      const cleanText = (value: string) => String(value || '').replace(/\s+/g, ' ').trim();
      const normalizePromptText = (value: string) => cleanText(value)
        .replace(/^请\s*输入/, '输入')
        .replace(/^请\s*选择/, '选择')
        .replace(/^请\s*搜索/, '搜索')
        .replace(/确\s*定/g, '确定')
        .replace(/提\s*交/g, '提交')
        .replace(/取\s*消/g, '取消')
        .replace(/详\s*情\s*及\s*操\s*作/g, '详情及操作');
      const sanitizeHeadingText = (value: string) => cleanText(value)
        .replace(/提\s*交\s*取\s*消/g, '')
        .replace(/取\s*消\s*确\s*定/g, '')
        .replace(/提\s*交\s*确\s*定/g, '')
        .replace(/确\s*定/g, '确定')
        .replace(/提\s*交/g, '提交')
        .replace(/取\s*消/g, '取消')
        .replace(/详\s*情\s*及\s*操\s*作/g, '详情及操作')
        .replace(/^\W+|\W+$/g, '')
        .trim();
      const isPromptLike = (text: string) => /请输入|请选择|请搜索|姓名|手机|证件|公司|邮箱|排序|全选/.test(text);
      const uniqueTexts = (values: string[]) => {
        const result: string[] = [];
        for (const value of values.map(cleanText).filter(Boolean)) {
          if (!result.includes(value)) result.push(value);
        }
        return result;
      };
      const normalizeTrail = (parts: string[]) => {
        const cleaned = parts
          .flatMap((part) => cleanText(part).split(/[/>｜|]+/))
          .map(cleanText)
          .filter(Boolean);
        const deduped: string[] = [];
        for (const part of cleaned) {
          if (deduped[deduped.length - 1] !== part) {
            deduped.push(part);
          }
        }
        return deduped.join(' / ');
      };
      const extractSemanticPageTitle = () => {
        const dialogTitles = uniqueTexts(Array.from(document.querySelectorAll([
          '[role="dialog"] .el-dialog__title',
          '[role="dialog"] .ant-modal-title',
          '[role="dialog"] .modal-title',
          '[role="dialog"] [class*="title"]',
        ].join(', '))).map((node) => node.textContent || ''));
        const breadcrumbRoots = Array.from(document.querySelectorAll([
          'nav[aria-label*="breadcrumb" i]',
          '[aria-label*="breadcrumb" i]',
          '.breadcrumb',
          '[class*="breadcrumb"]',
          '[class*="crumb"]',
        ].join(', ')));
        for (const root of breadcrumbRoots) {
          const itemTexts = uniqueTexts(
            Array.from(root.querySelectorAll([
              'a',
              'span',
              'li',
              '[class*="item"]',
              '[class*="separator"] + *',
            ].join(', ')))
              .map((node) => node.textContent || ''),
          ).filter((text) => text.length <= 40);
          if (itemTexts.length >= 2) {
            const trail = normalizeTrail(itemTexts);
            if (dialogTitles.length > 0) {
              return normalizeTrail([trail, dialogTitles[0]]);
            }
            return trail;
          }
          const compact = cleanText(root.textContent || '');
          if (compact && compact.length <= 80) {
            const trail = normalizeTrail([compact]);
            if (dialogTitles.length > 0) {
              return normalizeTrail([trail, dialogTitles[0]]);
            }
            return trail;
          }
        }

        const headingSelectors = [
          'main h1',
          '[role="main"] h1',
          'main h2',
          '[role="main"] h2',
          '.page-title',
          '[class*="page-title"]',
          '[class*="header-title"]',
          '[class*="content-title"]',
        ];
        for (const selector of headingSelectors) {
          const node = document.querySelector(selector);
          const text = cleanText(node?.textContent || '');
          if (text && text.length <= 80) {
            return dialogTitles.length > 0 ? normalizeTrail([text, dialogTitles[0]]) : text;
          }
        }

        const fallback = cleanText(document.title || '');
        return dialogTitles.length > 0 ? normalizeTrail([fallback, dialogTitles[0]]) : fallback;
      };
      (window as any).__aiTestTraceGetPageTitle = extractSemanticPageTitle;
      const trace = (event: Record<string, unknown>) => {
        const fn = (window as unknown as { __aiTestTraceEvent?: (payload: unknown) => void }).__aiTestTraceEvent;
        if (typeof fn === 'function') {
          fn({
            ...event,
            pageUrl: location.href,
            pageTitle: extractSemanticPageTitle(),
          });
        }
      };
      const buildSelector = (el: Element) => {
        const segments: string[] = [];
        let current: Element | null = el;
        let depth = 0;
        while (current && depth < 4) {
          if (current.id) {
            segments.unshift(`#${CSS.escape(current.id)}`);
            break;
          }
          const testId = current.getAttribute('data-testid');
          if (testId) {
            segments.unshift(`[data-testid="${testId.replaceAll('"', '\\"')}"]`);
            break;
          }
          const tag = current.tagName.toLowerCase();
          const className = (current.getAttribute('class') || '')
            .split(/\s+/)
            .filter(Boolean)
            .slice(0, 2)
            .map((item) => `.${CSS.escape(item)}`)
            .join('');
          segments.unshift(`${tag}${className}`);
          current = current.parentElement;
          depth += 1;
        }
        return segments.join(' > ');
      };
      const isSensitiveInput = (input: HTMLInputElement) => {
        const type = String(input.type || '').toLowerCase();
        if (type === 'password' || type === 'hidden') return true;
        const text = [
          input.name,
          input.id,
          input.autocomplete,
          input.placeholder,
          input.getAttribute('aria-label'),
        ].join(' ').toLowerCase();
        return /(password|passwd|pwd|token|secret|authorization|cookie|email|phone|mobile|idcard|identity)/.test(text);
      };
      const pickBestHeading = (root: Element | null) => {
        if (!root) return '';
        const candidates = Array.from(root.querySelectorAll([
          ':scope > h1',
          ':scope > h2',
          ':scope > h3',
          ':scope > h4',
          ':scope > legend',
          ':scope > [class*="title"]',
          ':scope > [class*="header"]',
          ':scope > .el-card__header',
          ':scope > .panel-title',
          ':scope > .section-title',
          ':scope > .table-title',
          ':scope > .list-title',
          '.el-form-item__label',
        ].join(', ')));
        for (const node of candidates) {
          const text = sanitizeHeadingText(node.textContent || '');
          if (!text || text.length > 20) continue;
          if (isPromptLike(text)) continue;
          return text;
        }
        const directChildren = Array.from(root.children).slice(0, 6);
        for (const child of directChildren) {
          const text = sanitizeHeadingText(child.textContent || '');
          if (!text || text.length > 20) continue;
          if (isPromptLike(text)) continue;
          return text;
        }
        const compact = sanitizeHeadingText(root.getAttribute('aria-label') || root.getAttribute('title') || '');
        return compact.length <= 20 ? compact : '';
      };
      const findDialogTitle = (el: Element) => {
        const dialog = el.closest('[role="dialog"], .el-dialog, .ant-modal, .modal');
        if (!dialog) return '';
        return sanitizeHeadingText(
          dialog.querySelector('.el-dialog__title, .ant-modal-title, .modal-title, [class*="dialog-title"], [class*="modal-title"], [class*="title"]')?.textContent || '',
        );
      };
      const findSectionTitle = (el: Element) => {
        const columnRoot = el.closest([
          '.el-col',
          '.ant-col',
          '[class*="col-"]',
          '[class*="panel"]',
          '[class*="section"]',
          '[class*="table"]',
          '[class*="list"]',
        ].join(', '));
        const title = pickBestHeading(columnRoot);
        if (title) return title;

        const dialog = el.closest('[role="dialog"], .el-dialog, .ant-modal, .modal');
        if (dialog) {
          const columns = Array.from(dialog.querySelectorAll([
            '.el-col',
            '.ant-col',
            '[class*="col-"]',
            '[class*="panel"]',
            '[class*="section"]',
            '[class*="table"]',
            '[class*="list"]',
          ].join(', ')));
          for (const col of columns) {
            if (!col.contains(el)) continue;
            const nestedTitle = pickBestHeading(col);
            if (nestedTitle) return nestedTitle;
            const siblings = Array.from(col.parentElement?.children || []).filter((item) => item.contains(col) || item === col);
            for (const sibling of siblings) {
              const text = sanitizeHeadingText((sibling as Element).textContent || '');
              if (!text || text.length > 20) continue;
              if (isPromptLike(text)) continue;
              return text;
            }
          }
        }
        return '';
      };
      const closestInteractive = (el: Element) => el.closest([
        '[role="menuitem"]',
        '.el-dropdown-menu__item',
        '.ant-dropdown-menu-item',
        '[class*="menu-item"]',
        'button',
        'a',
        'label',
        '[role="button"]',
        '[role="checkbox"]',
        '[role="radio"]',
        '[role="switch"]',
        '.el-checkbox',
        '.ant-checkbox-wrapper',
        '[class*="checkbox"]',
        '[class*="radio"]',
        '[class*="switch"]',
      ].join(', ')) || el;
      const inferIconAction = (el: Element) => {
        const useRef = cleanText(
          (el.querySelector('use')?.getAttribute('href')
            || el.querySelector('use')?.getAttribute('xlink:href')
            || ''),
        ).toLowerCase();
        const text = cleanText([
          (el as HTMLElement).className || '',
          el.getAttribute('title') || '',
          el.getAttribute('aria-label') || '',
          useRef,
        ].join(' ')).toLowerCase();
        if (!text) return '';
        if (/edit|modify|bianji|xiugai|pencil/.test(text)) return '修改';
        if (/delete|remove|trash|del|shanchu/.test(text)) return '删除';
        if (/search|lookup/.test(text)) return '搜索';
        if (/close|cancel/.test(text)) return '关闭';
        if (/plus|add/.test(text)) return '新增';
        if (/check|confirm|ok/.test(text)) return '确定';
        return '';
      };
      const isActionLikeText = (text: string) => /^(修改|编辑|删除|搜索|关闭|新增|确定|全选|排序|详情|查看|取消|签到|再次邀请|详情及操作|展开)$/.test(text);
      const isLikelyPhone = (text: string) => /^\d{6,}$/.test(text.replace(/\s+/g, ''));
      const stripActionWords = (text: string) => text
        .replace(/详情及操作/g, '')
        .replace(/再次邀请/g, '')
        .replace(/取消邀请/g, '')
        .replace(/签到/g, '')
        .replace(/关闭/g, '')
        .replace(/展开/g, '')
        .replace(/修改/g, '')
        .replace(/编辑/g, '')
        .replace(/删除/g, '')
        .replace(/搜索/g, '')
        .replace(/新增/g, '')
        .replace(/确定/g, '')
        .replace(/全选/g, '')
        .replace(/排序/g, '')
        .replace(/查看/g, '')
        .replace(/取消/g, '')
        .replace(/提交/g, '')
        .replace(/提 交/g, '')
        .replace(/\s+/g, ' ')
        .trim();
      const findRowText = (el: Element) => {
        const row = el.closest([
          'tr',
          'li',
          '[class*="row"]',
          '[class*="item"]',
          '[class*="record"]',
          '[class*="list"] > *',
        ].join(', '));
        if (!row) return '';

        const pieces: string[] = [];
        const candidates = Array.from(row.querySelectorAll('span, div, p, a, strong, em, small'));
        for (const node of candidates) {
          if (!(node instanceof HTMLElement)) continue;
          if (node.closest('button, a[href], [role="button"], [role="checkbox"], .el-checkbox, .ant-checkbox-wrapper')) {
            continue;
          }
          const text = sanitizeHeadingText(node.textContent || '');
          if (!text || text.length > 30) continue;
          if (isPromptLike(text) || isActionLikeText(text)) continue;
          if (/^\d+\s*\/\s*\d+$/.test(text)) continue;
          if (!pieces.includes(text)) pieces.push(text);
        }
        if (pieces.length > 0) {
          const first = pieces[0];
          const second = pieces[1] || '';
          if (second && isLikelyPhone(second) && !isLikelyPhone(first)) {
            return `${first} ${second}`.trim();
          }
          return first;
        }

        const text = cleanText(row.textContent || '');
        if (!text || text.length > 60) return '';
        return stripActionWords(sanitizeHeadingText(text));
      };
      const findFieldLabel = (el: Element) => {
        const htmlEl = el as HTMLElement;
        const aria = cleanText(htmlEl.getAttribute('aria-label') || '');
        if (aria) return aria;
        const title = cleanText(htmlEl.getAttribute('title') || '');
        if (title) return title;
        if (htmlEl instanceof HTMLInputElement || htmlEl instanceof HTMLTextAreaElement || htmlEl instanceof HTMLSelectElement) {
          let labelText = '';
          if (htmlEl.id) {
            const label = document.querySelector(`label[for="${CSS.escape(htmlEl.id)}"]`);
            labelText = cleanText(label?.textContent || '');
          }
          if (!labelText) {
            const parentLabel = htmlEl.closest('label');
            labelText = cleanText(parentLabel?.textContent || '');
          }
          const placeholder = htmlEl instanceof HTMLSelectElement
            ? ''
            : normalizePromptText(htmlEl.placeholder || '');
          if (placeholder) {
            return placeholder;
          }
          if (labelText) return labelText;
          const nameText = cleanText(htmlEl.getAttribute('name') || htmlEl.getAttribute('id') || '');
          if (nameText) return nameText;
        }
        return '';
      };
      const findCheckboxLabel = (el: Element) => {
        const checkboxRoot = el.closest([
          'label',
          '.el-checkbox',
          '.ant-checkbox-wrapper',
          '[role="checkbox"]',
          '[class*="checkbox"]',
        ].join(', '));
        if (!checkboxRoot) return '';
        const labelCandidates = Array.from(checkboxRoot.querySelectorAll([
          '.el-checkbox__label',
          '.ant-checkbox + span',
          'span',
          'div',
          'p',
        ].join(', ')));
        for (const node of labelCandidates) {
          const text = sanitizeHeadingText(node.textContent || '');
          if (!text || text.length > 24) continue;
          if (/已勾选|已取消勾选/.test(text)) continue;
          if (isActionLikeText(text) || isPromptLike(text)) continue;
          return text;
        }
        const text = sanitizeHeadingText(checkboxRoot.textContent || '');
        if (!text || text.length > 40) return '';
        if (/已勾选|已取消勾选/.test(text)) return '';
        if (isActionLikeText(text) || isPromptLike(text)) return '';
        return text;
      };
      const implicitAriaRole = (el: Element): string => {
        const explicit = cleanText(el.getAttribute('role') || '');
        if (explicit) return explicit;
        const tag = el.tagName.toLowerCase();
        if (tag === 'button') return 'button';
        if (tag === 'a' && (el as HTMLAnchorElement).href) return 'link';
        if (tag === 'select') return 'combobox';
        if (tag === 'textarea') return 'textbox';
        if (tag === 'input') {
          const type = String((el as HTMLInputElement).type || 'text').toLowerCase();
          if (type === 'checkbox') return 'checkbox';
          if (type === 'radio') return 'radio';
          if (type === 'submit' || type === 'button' || type === 'reset') return 'button';
          if (type === 'range') return 'slider';
          return 'textbox';
        }
        return '';
      };
      const quoteLocatorValue = (value: string) => `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
      const buildNormalizedLocator = (
        interactive: Element,
        original: Element,
        ctx: { text: string; role: string; fieldLabel: string; iconAction: string; isSecret: boolean },
      ): string => {
        const testId = cleanText(
          interactive.getAttribute('data-testid')
            || interactive.getAttribute('data-test-id')
            || interactive.getAttribute('data-test')
            || '',
        );
        if (testId) return `testid=${quoteLocatorValue(testId)}`;

        const ariaRole = implicitAriaRole(interactive);
        const accessibleName = cleanText(
          interactive.getAttribute('aria-label')
            || interactive.getAttribute('title')
            || ctx.iconAction
            || (ariaRole === 'textbox' || ariaRole === 'combobox' ? ctx.fieldLabel : '')
            || ctx.text
            || '',
        ).slice(0, 80);

        if (ariaRole === 'textbox' || ariaRole === 'combobox' || ariaRole === 'searchbox') {
          const labelText = ctx.fieldLabel || accessibleName;
          if (labelText) return `label=${quoteLocatorValue(labelText)}`;
          const placeholder = cleanText((original as HTMLInputElement).placeholder || '');
          if (placeholder) return `placeholder=${quoteLocatorValue(placeholder)}`;
        }

        if (ariaRole && accessibleName) {
          return `role=${ariaRole}[name=${quoteLocatorValue(accessibleName)}]`;
        }

        if (accessibleName && !ctx.isSecret && accessibleName.length <= 40) {
          return `text=${quoteLocatorValue(accessibleName)}`;
        }

        if (ariaRole) {
          return `role=${ariaRole}`;
        }
        return '';
      };
      const describe = (target: EventTarget | null) => {
        const el = target instanceof Element ? target : null;
        if (!el) {
          return {
            text: '', role: '', selector: '', value: '',
            normalizedLocator: '', sectionTitle: '', dialogTitle: '', objectLabel: '',
          };
        }
        const directControl = el.closest('input, textarea, select, [contenteditable="true"], .el-input__inner, .ant-input') as Element | null;
        const interactive = ((el instanceof HTMLInputElement && el.type === 'checkbox') ? el : directControl) || closestInteractive(el);
        const input = el as HTMLInputElement;
        const isSecret = isSensitiveInput(input);
        const value = 'value' in input && !isSecret ? String(input.value || '') : '';
        const sectionTitle = findSectionTitle(interactive);
        const dialogTitle = findDialogTitle(interactive);
        const sectionOrDialog = sectionTitle || dialogTitle;
        const objectLabel = findRowText(interactive);
        const checkboxLabel = findCheckboxLabel(el);
        const checkboxText = input instanceof HTMLInputElement && input.type === 'checkbox'
          ? (input.checked ? '已勾选' : '已取消勾选')
          : '';
        const rowText = objectLabel;
        const iconAction = inferIconAction(interactive);
        const fieldLabel = findFieldLabel(el);
        const isCheckbox = input instanceof HTMLInputElement && input.type === 'checkbox';
        const rawInteractiveText = sanitizeHeadingText(interactive.textContent || '');
        const meaningfulInteractiveText =
          rawInteractiveText
          && rawInteractiveText.length <= 40
          && !isPromptLike(rawInteractiveText)
          && rawInteractiveText !== 'svg'
          && rawInteractiveText !== 'use'
          && rawInteractiveText !== 'on'
          ? rawInteractiveText
          : '';
        const rawFallbackText = cleanText(interactive.textContent || '');
        const safeFallbackText = rawFallbackText.length <= 80 ? rawFallbackText : '';
        const text = String(
          (isCheckbox ? '' : fieldLabel)
          || checkboxLabel
          || rowText
          || cleanText(interactive.getAttribute('aria-label') || '')
          || cleanText(interactive.getAttribute('title') || '')
          || meaningfulInteractiveText
          || iconAction
          || safeFallbackText
          || value
          || checkboxText
          || ''
        ).trim().slice(0, 200);
        const normalizedText =
          (meaningfulInteractiveText && !isActionLikeText(meaningfulInteractiveText) ? meaningfulInteractiveText : '')
          || (text === 'svg' || text === 'use' || text === 'on'
            ? (checkboxLabel || rowText || fieldLabel || '未命名操作')
            : text)
          || iconAction;
        const role = interactive.getAttribute('role') || interactive.tagName.toLowerCase();
        const normalizedLocator = buildNormalizedLocator(
          interactive,
          el,
          { text: normalizedText, role, fieldLabel, iconAction, isSecret },
        );
        return {
          text: sectionOrDialog && normalizedText ? `${sectionOrDialog} · ${normalizedText}` : normalizedText,
          role,
          selector: buildSelector(interactive),
          value: checkboxText || value,
          normalizedLocator,
          sectionTitle,
          dialogTitle,
          objectLabel: rowText,
        };
      };

      document.addEventListener('click', (event) => {
        const info = describe(event.target);
        trace({ eventType: 'CLICK', elementText: info.text, elementRole: info.role, selector: info.selector, valueSummary: '', normalizedLocator: info.normalizedLocator, sectionTitle: info.sectionTitle, dialogTitle: info.dialogTitle, objectLabel: info.objectLabel });
      }, true);
      document.addEventListener('input', (event) => {
        const inputEvent = event as InputEvent;
        if (typeof inputEvent.isComposing === 'boolean' && inputEvent.isComposing) return;
        const info = describe(event.target);
        trace({ eventType: 'INPUT', elementText: info.text, elementRole: info.role, selector: info.selector, valueSummary: info.value, normalizedLocator: info.normalizedLocator, sectionTitle: info.sectionTitle, dialogTitle: info.dialogTitle, objectLabel: info.objectLabel });
      }, true);
      document.addEventListener('change', (event) => {
        const info = describe(event.target);
        trace({ eventType: 'CHANGE', elementText: info.text, elementRole: info.role, selector: info.selector, valueSummary: info.value, normalizedLocator: info.normalizedLocator, sectionTitle: info.sectionTitle, dialogTitle: info.dialogTitle, objectLabel: info.objectLabel });
      }, true);
      document.addEventListener('focusout', (event) => {
        const info = describe(event.target);
        trace({ eventType: 'BLUR', elementText: info.text, elementRole: info.role, selector: info.selector, valueSummary: info.value, normalizedLocator: info.normalizedLocator, sectionTitle: info.sectionTitle, dialogTitle: info.dialogTitle, objectLabel: info.objectLabel });
      }, true);
      document.addEventListener('submit', (event) => {
        const info = describe(event.target);
        trace({ eventType: 'SUBMIT', elementText: info.text, elementRole: info.role, selector: info.selector, valueSummary: '', normalizedLocator: info.normalizedLocator, sectionTitle: info.sectionTitle, dialogTitle: info.dialogTitle, objectLabel: info.objectLabel });
      }, true);
      document.addEventListener('keydown', (event) => {
        const keyboardEvent = event as KeyboardEvent;
        const info = describe(event.target);
        trace({ eventType: 'KEYDOWN', elementText: info.text, elementRole: 'keyboard', selector: info.selector, valueSummary: keyboardEvent.key, normalizedLocator: info.normalizedLocator, sectionTitle: info.sectionTitle, dialogTitle: info.dialogTitle, objectLabel: info.objectLabel });
      }, true);
      let lastScrollAt = 0;
      document.addEventListener('scroll', () => {
        const now = Date.now();
        if (now - lastScrollAt < 800) return;
        lastScrollAt = now;
        trace({ eventType: 'SCROLL', elementText: '', elementRole: 'page', selector: '', valueSummary: String(window.scrollY) });
      }, true);
      const seenMessages = new Set<string>();
      const emitMessage = (type: string, text: string) => {
        const message = cleanText(text);
        if (!message || seenMessages.has(`${type}:${message}`)) return;
        seenMessages.add(`${type}:${message}`);
        trace({ eventType: type, elementText: message, elementRole: 'message', selector: '', valueSummary: '' });
        setTimeout(() => seenMessages.delete(`${type}:${message}`), 5000);
      };
      const observer = new MutationObserver((mutations) => {
        for (const mutation of mutations) {
          mutation.addedNodes.forEach((node) => {
            if (!(node instanceof HTMLElement)) return;
            const text = cleanText(node.textContent || '');
            if (!text) return;
            const role = cleanText(node.getAttribute('role') || '').toLowerCase();
            const className = node.className || '';
            if (role === 'alert' || /error|warning|message|toast|notification|alert/i.test(className)) {
              emitMessage('ALERT', text);
            }
            const dialogTitle = node.matches?.('[role="dialog"], .el-dialog, .ant-modal, .modal')
              ? cleanText(node.querySelector('.el-dialog__title, .ant-modal-title, .modal-title, [class*="title"]')?.textContent || '')
              : '';
            if (dialogTitle) {
              emitMessage('DIALOG_OPEN', dialogTitle);
            }
          });
        }
      });
      observer.observe(document.body, { childList: true, subtree: true });
    };

    page.on('framenavigated', async (frame) => {
      if (frame !== page.mainFrame()) return;
      for (const session of this.sessions.values()) {
        if (session.page === page) {
          this.recordEvent(session, {
            eventType: 'NAVIGATION',
            pageUrl: page.url(),
            pageTitle: await this.resolvePageTitle(page),
            elementText: '',
            elementRole: 'page',
            selector: '',
            valueSummary: '',
          });
        }
      }
    });

    page.on('request', (request) => {
      if (!shouldTrackRequest(request)) return;
      for (const session of this.sessions.values()) {
        if (session.page === page) {
          session.requestStarted.set(request, {
            startedAtMs: Date.now(),
            method: request.method(),
            url: request.url(),
          });
        }
      }
    });

    page.on('response', async (response) => {
      const req = response.request();
      const url = req.url();
      if (!shouldTrackRequest(req)) return;
      for (const session of this.sessions.values()) {
        if (session.page === page) {
          const started = session.requestStarted.get(req);
          const responseEndedAtMs = Date.now();
          const requestStartedAtMs = started?.startedAtMs || responseEndedAtMs;
          session.requestStarted.delete(req);
          let responseSummary = '';
          try {
            const contentType = (await response.headerValue('content-type')) || '';
            if (/json|text|xml|html/i.test(contentType)) {
              responseSummary = sanitizeSummary(await response.text());
            } else {
              const contentLength = (await response.headerValue('content-length')) || '';
              responseSummary = sanitizeSummary(`${contentType || 'binary'} ${contentLength ? `(${contentLength} bytes)` : ''}`);
            }
          } catch {
            responseSummary = '';
          }
          this.recordNetwork(session, {
            url,
            method: req.method(),
            statusCode: response.status(),
            durationMs: Math.max(0, responseEndedAtMs - requestStartedAtMs),
            failed: response.status() >= 400,
            errorMessage: null,
            requestSummary: sanitizeSummary(req.postData() || ''),
            responseSummary,
            requestStartedAtMs,
            responseEndedAtMs,
          });
        }
      }
    });

    page.on('requestfailed', (request) => {
      const url = request.url();
      if (!shouldTrackRequest(request)) return;
      for (const session of this.sessions.values()) {
        if (session.page === page) {
          const started = session.requestStarted.get(request);
          const now = Date.now();
          session.requestStarted.delete(request);
          this.recordNetwork(session, {
            url,
            method: request.method(),
            statusCode: null,
            durationMs: null,
            failed: true,
            errorMessage: request.failure()?.errorText || 'request failed',
            requestSummary: sanitizeSummary(request.postData() || ''),
            responseSummary: null,
            requestStartedAtMs: started?.startedAtMs || now,
            responseEndedAtMs: now,
          });
        }
      }
    });

    await page.exposeFunction('__aiTestTraceEvent', async (event: Partial<CapturedEvent>) => {
      for (const session of this.sessions.values()) {
        if (session.page === page) {
          await this.recordEvent(session, {
            eventType: event.eventType || 'UNKNOWN',
            pageUrl: event.pageUrl || page.url(),
            pageTitle: event.pageTitle || await this.resolvePageTitle(page),
            elementText: event.elementText || '',
            elementRole: event.elementRole || '',
            selector: event.selector || '',
            normalizedLocator: event.normalizedLocator || '',
            sectionTitle: event.sectionTitle || '',
            dialogTitle: event.dialogTitle || '',
            objectLabel: event.objectLabel || '',
            valueSummary: sanitizeSummary(event.valueSummary || ''),
          });
        }
      }
    });

    await page.addInitScript(installDomHooks);
    await page.evaluate(installDomHooks).catch(() => undefined);
    this.hookedPages.add(page);
  }

  private async resolvePageTitle(page: Page): Promise<string> {
    const semanticTitle = await page.evaluate(() => {
      const getter = (window as unknown as { __aiTestTraceGetPageTitle?: () => string }).__aiTestTraceGetPageTitle;
      return typeof getter === 'function' ? getter() : document.title;
    }).catch(() => '');
    if (semanticTitle && semanticTitle.trim()) {
      return semanticTitle.trim();
    }
    return await page.title().catch(() => '');
  }

  private async tryAutoFill(page: Page, autoFill?: { username?: string; password?: string }): Promise<void> {
    const username = autoFill?.username?.trim();
    const password = autoFill?.password || '';
    if (!username && !password) return;

    // Wait for page to settle so SPAs can render login forms
    await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => undefined);
    // Extra settle time for JS-heavy login pages
    await new Promise(r => setTimeout(r, 800));

    if (username) {
      const usernameSelector = [
        'input[autocomplete="username"]',
        'input[autocomplete="email"]',
        'input[type="email"]',
        'input[name*="user" i]',
        'input[id*="user" i]',
        'input[name*="account" i]',
        'input[id*="account" i]',
        'input[name*="login" i]',
        'input[id*="login" i]',
        'input[name*="phone" i]',
        'input[id*="phone" i]',
        'input:not([type="password"]):not([type="hidden"]):not([type="submit"]):not([type="button"])',
      ].join(', ');
      await page.locator(usernameSelector).first().fill(username, { timeout: 3000 }).catch(() => undefined);
    }

    if (password) {
      await page.locator('input[type="password"]').first().fill(password, { timeout: 3000 }).catch(() => undefined);
    }
  }

  private async recordEvent(active: ActiveSession, event: Partial<CapturedEvent> & { eventType: string }): Promise<void> {
    const now = new Date();
    const shouldScreenshot = ['PAGE_OPEN', 'NAVIGATION', 'CLICK'].includes(event.eventType);
    const screenshotPath = shouldScreenshot
      ? await this.captureScreenshot(active, event.eventType.toLowerCase())
      : null;
    active.events.push({
      eventType: event.eventType,
      pageUrl: event.pageUrl || '',
      pageTitle: event.pageTitle || '',
      elementText: event.elementText || '',
      elementRole: event.elementRole || '',
      selector: event.selector || '',
      normalizedLocator: event.normalizedLocator || '',
      sectionTitle: event.sectionTitle || '',
      dialogTitle: event.dialogTitle || '',
      objectLabel: event.objectLabel || '',
      valueSummary: event.valueSummary || '',
      screenshotPath: event.screenshotPath ?? screenshotPath,
      happenedAtUtc: utcDateTime(now),
      happenedAtLocal: beijingDateTime(now),
      timezone: 'Asia/Shanghai',
      relativeMs: Date.now() - active.startedAtMs,
    });
  }

  private async captureScreenshot(active: ActiveSession, prefix: string): Promise<string | null> {
    const now = Date.now();
    if (now - active.lastScreenshotAtMs < 1200) {
      return active.lastScreenshotPath;
    }
    const screenshotDir = path.join(active.sessionDir, 'screenshots');
    fs.mkdirSync(screenshotDir, { recursive: true });
    const file = path.join(screenshotDir, `${prefix}-${now}.png`);
    try {
      await active.page.screenshot({ path: file, fullPage: false, timeout: 5000 });
      active.lastScreenshotAtMs = now;
      active.lastScreenshotPath = file;
      return file;
    } catch {
      return active.lastScreenshotPath;
    }
  }

  private recordNetwork(active: ActiveSession, item: Omit<CapturedNetwork, 'requestStartedAtUtc' | 'requestStartedAtLocal' | 'responseEndedAtUtc' | 'responseEndedAtLocal' | 'timezone' | 'relativeMs'> & { requestStartedAtMs: number; responseEndedAtMs: number }): void {
    const started = new Date(item.requestStartedAtMs);
    const ended = new Date(item.responseEndedAtMs);
    active.networks.push({
      url: item.url,
      method: item.method,
      statusCode: item.statusCode,
      durationMs: item.durationMs,
      failed: item.failed,
      errorMessage: item.errorMessage,
      requestSummary: item.requestSummary,
      responseSummary: item.responseSummary,
      requestStartedAtUtc: utcDateTime(started),
      requestStartedAtLocal: beijingDateTime(started),
      responseEndedAtUtc: utcDateTime(ended),
      responseEndedAtLocal: beijingDateTime(ended),
      timezone: 'Asia/Shanghai',
      relativeMs: Math.max(0, item.requestStartedAtMs - active.startedAtMs),
    });
  }
}

function normalizeTargetUrl(targetHost?: string): string {
  const raw = (targetHost || '').trim();
  if (!raw) return 'about:blank';
  if (/^about:/i.test(raw)) return raw;
  if (/^https?:\/\//i.test(raw)) return raw;
  return `https://${raw}`;
}

function safePageUrl(page: Page): string {
  try {
    return page.url();
  } catch {
    return '';
  }
}

function isBlankLikeUrl(url: string): boolean {
  const text = (url || '').trim().toLowerCase();
  return !text || text === 'about:blank' || text === 'chrome://newtab/' || text === 'chrome://new-tab-page/';
}


function utcDateTime(value: Date): string {
  return value.toISOString().replace(/\.\d{3}Z$/, '');
}

function beijingDateTime(value: Date): string {
  const bj = new Date(value.getTime() + 8 * 60 * 60 * 1000);
  const year = bj.getUTCFullYear();
  const month = String(bj.getUTCMonth() + 1).padStart(2, '0');
  const day = String(bj.getUTCDate()).padStart(2, '0');
  const hours = String(bj.getUTCHours()).padStart(2, '0');
  const minutes = String(bj.getUTCMinutes()).padStart(2, '0');
  const seconds = String(bj.getUTCSeconds()).padStart(2, '0');
  return `${year}-${month}-${day}T${hours}:${minutes}:${seconds}`;
}

function isStaticResource(url: string): boolean {
  return /\.(js|css|png|jpg|jpeg|gif|svg|webp|woff|woff2|ico|map)(\?|$)/i.test(url);
}

function shouldTrackRequest(request: { url(): string; resourceType(): string }): boolean {
  if (isStaticResource(request.url())) return false;
  const type = request.resourceType();
  return type === 'fetch' || type === 'xhr';
}

function sanitizeSummary(value: string): string {
  if (!value) return '';
  return value
    .replace(/("?(password|token|secret|authorization|cookie)"?\s*[:=]\s*)("[^"]*"|[^\s&,}]+)/gi, '$1"***"')
    .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, '***@***')
    .replace(/\b1[3-9]\d{9}\b/g, '1**********')
    .slice(0, 4096);
}
