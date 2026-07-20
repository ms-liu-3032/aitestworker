import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { Link } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import {
  checkWorkerHealth,
  workerApi,
  listProfiles,
  listGroups,
  createGroup,
  startGroup,
  stopGroup,
  createSession,
  startSession,
  stopSession,
  updateSessionName,
  listGroupSessions,
  getGroupDetail,
  getGroupSummaries,
  getGroupCleanSteps,
  getGroupSkills,
  getGroupTools,
  getGroupCorrections,
  generateGroupSummary,
  regenerateSummary as apiRegenerateSummary,
  confirmSummary as apiConfirmSummary,
  rejectSummary as apiRejectSummary,
  generateGroupCases,
  generateGroupSkill,
  generateGroupTool,
  listGeneratedCasesPage as listTraceGeneratedCasesPage,
  listFormalCasesPage,
  submitGeneratedCase as submitTraceGeneratedCase,
  deleteGroup as apiDeleteGroup,
  createBindCode as apiCreateBindCode,
  createProfile,
  updateProfile,
  profileOperation,
  getProfileCredentials,
  saveStepCorrection,
  learnPattern,
  confirmCorrection as apiConfirmCorrection,
  rejectCorrection as apiRejectCorrection,
  batchSaveEvents,
  batchSaveNetworks,
  scanFromTrace,
  type WorkerHealth,
  type BrowserProfile,
  type BrowserTraceGroup,
  type BrowserTraceEvent,
  type TraceSummary,
  type TraceCorrectionSuggestion,
  type CleanStepView,
  type TraceGeneratedCase,
  type TestSkillTemplate,
  type TestToolTemplate,
  type FormalCase,
  type TraceGroupDetail,
  type WorkerStartResult,
  type WorkerStopResult,
} from '../../services/traceApi';
import { api as baseApi, listLocalCasesPage, type LocalCaseDraft } from '../../services/api';
import { type ModelConfigOption } from '../../services/generationApi';
import SegmentedControl from '../SegmentedControl';
import StatusBadge from '../StatusBadge';
import Drawer from '../Drawer';
import Modal from '../Modal';
import ScreencastPlayer from './ScreencastPlayer';

// ===== Helpers =====
const TOKEN_KEY = 'aitest_worker_local_token';

function getLocalToken() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

function saveLocalToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token.trim());
}

function fmtDate(s?: string | null): string {
  if (!s) return '-';
  const d = new Date(s);
  if (isNaN(d.getTime())) return '-';
  return d.toLocaleString('zh-CN', { hour12: false });
}

function formatDurationMs(ms?: number | null): string {
  if (ms === null || ms === undefined || ms < 0) return '-';
  const totalSec = Math.floor(ms / 1000);
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

function hasLocatorDetails(e: BrowserTraceEvent): boolean {
  return Boolean(e.normalizedLocator || e.sectionTitle || e.dialogTitle || e.objectLabel);
}

function safeParseJsonArray<T = string>(s?: string | null): T[] {
  if (!s) return [];
  try {
    const parsed = JSON.parse(s);
    if (Array.isArray(parsed)) return parsed as T[];
    return [];
  } catch {
    return [];
  }
}

function safeParseJsonObject<T = Record<string, unknown>>(s?: string | null): T | null {
  if (!s) return null;
  try {
    const parsed = JSON.parse(s);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as T;
    return null;
  } catch {
    return null;
  }
}

function eventSummary(e: BrowserTraceEvent): string {
  if (e.eventType === 'PAGE_OPEN') return '打开页面';
  if (e.eventType === 'NAVIGATION') return `页面跳转到 ${e.pageUrl || '-'}`;
  if (e.eventType === 'CLICK') return `点击 ${e.elementText || ''}`.trim();
  if (e.eventType === 'INPUT' || e.eventType === 'CHANGE') {
    const inputValue = e.valueSummary ? `输入: ${e.valueSummary}` : '输入';
    return `${inputValue} ${e.elementText || ''}`.trim();
  }
  if (e.eventType === 'KEYDOWN') return `按键 ${e.elementText || ''}`.trim();
  if (e.eventType === 'SCROLL') return `滚动 ${e.valueSummary || ''}`.trim();
  if (e.eventType === 'SESSION_STOP') return '停止采集';
  return e.elementText || '-';
}

function detectBindOs(): 'mac' | 'win' {
  const ua = (typeof navigator !== 'undefined' ? navigator.userAgent : '') || '';
  return /Windows|Win64|Win32|WOW64/i.test(ua) ? 'win' : 'mac';
}

function copyText(text: string): Promise<boolean> {
  if (!text) return Promise.resolve(false);
  if (navigator.clipboard?.writeText) {
    return navigator.clipboard.writeText(text).then(() => true).catch(() => false);
  }
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', 'true');
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.select();
  let copied = false;
  try { copied = document.execCommand('copy'); } catch { /* ignore */ }
  document.body.removeChild(textarea);
  return Promise.resolve(copied);
}

type ActionNotice = {
  tone: 'success' | 'info';
  title: string;
  description: string;
  links?: Array<{ label: string; to: string }>;
};

// ===== Sub-Components =====

function StatCard({ title, value, subtitle }: { title: string; value: string; subtitle: string }) {
  return (
    <div className="bg-white rounded-lg border border-gray-200 p-4">
      <div className="text-[11px] uppercase tracking-wider text-gray-400 font-semibold mb-1">{title}</div>
      <div className="text-lg font-bold text-gray-900">{value}</div>
      <div className="text-xs text-gray-500 mt-1">{subtitle}</div>
    </div>
  );
}

function ProfileForm({
  editing,
  onClose,
  onSave,
  projectId,
  showToast,
}: {
  editing: BrowserProfile | null;
  onClose: () => void;
  onSave: () => void;
  projectId: number;
  showToast: (msg: string, type?: 'success' | 'error' | 'info') => void;
}) {
  const [form, setForm] = useState({
    profileName: editing?.profileName || '',
    targetHost: editing?.targetHost || '',
    accountLabel: editing?.accountLabel || '',
    roleLabel: editing?.roleLabel || '',
    username: editing?.username || '',
    password: '',
  });
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    setSaving(true);
    try {
      const body: any = { ...form };
      if (!body.password) delete body.password;
      if (editing) {
        await updateProfile(projectId, editing.id, body);
      } else {
        await createProfile(projectId, body);
      }
      showToast('保存成功');
      onSave();
      onClose();
    } catch (err: any) {
      showToast(err.message || '保存失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      open
      onClose={onClose}
      title={editing ? '编辑身份空间' : '新建身份空间'}
      footer={
        <>
          <button onClick={onClose} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 transition-colors">
            取消
          </button>
          <button
            onClick={handleSave}
            disabled={saving || !form.profileName}
            className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 transition-colors disabled:opacity-50"
          >
            {saving ? '保存中...' : '保存'}
          </button>
        </>
      }
    >
      <div className="space-y-4">
        {[
          { key: 'profileName', label: '空间名称', placeholder: '如：生产环境-管理员' },
          { key: 'targetHost', label: '目标地址', placeholder: 'https://example.com' },
          { key: 'accountLabel', label: '账号标签', placeholder: '管理员账号' },
          { key: 'roleLabel', label: '角色标签', placeholder: '系统管理员' },
          { key: 'username', label: '用户名', placeholder: '登录用户名' },
          { key: 'password', label: '密码', placeholder: '登录密码', type: 'password' },
        ].map((field) => (
          <div key={field.key}>
            <label className="block text-sm font-medium text-gray-700 mb-1">{field.label}</label>
            <input
              type={(field as any).type || 'text'}
              value={(form as any)[field.key]}
              onChange={(e) => setForm({ ...form, [field.key]: e.target.value })}
              placeholder={field.placeholder}
              className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:bg-white focus:border-gray-400 outline-none transition-all"
            />
          </div>
        ))}
      </div>
    </Drawer>
  );
}

// ===== Main Component =====

export default function TracePanel({ projectId }: { projectId: number }) {
  const { showToast } = useApp();

  // Worker state
  const [workerHealth, setWorkerHealth] = useState<WorkerHealth | null>(null);
  const [workerError, setWorkerError] = useState('');
  const [checkingWorker, setCheckingWorker] = useState(false);
  const [localToken, setLocalToken] = useState(getLocalToken());
  const healthTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Data state
  const [profiles, setProfiles] = useState<BrowserProfile[]>([]);
  const [groups, setGroups] = useState<BrowserTraceGroup[]>([]);
  const [traceGeneratedCases, setTraceGeneratedCases] = useState<TraceGeneratedCase[]>([]);
  const [traceGeneratedCaseTotal, setTraceGeneratedCaseTotal] = useState(0);
  const [traceGeneratedCasePage, setTraceGeneratedCasePage] = useState(0);
  const [localCases, setLocalCases] = useState<LocalCaseDraft[]>([]);
  const [formalCases, setFormalCases] = useState<FormalCase[]>([]);
  const [localCaseTotal, setLocalCaseTotal] = useState(0);
  const [formalCaseTotal, setFormalCaseTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Capture state
  const [activeCaptures, setActiveCaptures] = useState<Record<number, { groupId: number; sessionId: number }>>({});
  const [, setProfileWindows] = useState<Set<number>>(new Set());

  // Model state
  const [enabledModels, setEnabledModels] = useState<ModelConfigOption[]>([]);
  const [traceModelConfigId, setTraceModelConfigId] = useState(0);

  // Bind code state
  const [bindCode, setBindCode] = useState<{ code: string; expiresAt: string } | null>(null);
  const [bindCopied, setBindCopied] = useState(false);
  const [bindOs, setBindOs] = useState<'mac' | 'win'>(detectBindOs);
  const [creatingBindCode, setCreatingBindCode] = useState(false);

  // Profile form state
  const [showProfileForm, setShowProfileForm] = useState(false);
  const [editingProfile, setEditingProfile] = useState<BrowserProfile | null>(null);

  // Group detail state
  const [expandedGroupId, setExpandedGroupId] = useState<number | null>(null);
  const [groupDetail, setGroupDetail] = useState<TraceGroupDetail | null>(null);
  const [detailTab, setDetailTab] = useState<'overview' | 'sessions' | 'issues' | 'assets' | 'templates'>('overview');
  const [groupSummaries, setGroupSummaries] = useState<TraceSummary[]>([]);
  const [cleanSteps, setCleanSteps] = useState<CleanStepView[]>([]);
  const [scanningGroupId, setScanningGroupId] = useState<number | null>(null);
  const [groupSkills, setGroupSkills] = useState<TestSkillTemplate[]>([]);
  const [groupTools, setGroupTools] = useState<TestToolTemplate[]>([]);
  const [groupCorrections, setGroupCorrections] = useState<TraceCorrectionSuggestion[]>([]);
  const [expandedSessionId, setExpandedSessionId] = useState<number | null>(null);
  const [openSessionVideoId, setOpenSessionVideoId] = useState<number | null>(null);
  const [openClipVideoId, setOpenClipVideoId] = useState<number | null>(null);
  const [focusedIssueClipId, setFocusedIssueClipId] = useState<number | null>(null);
  const [focusedSummaryId, setFocusedSummaryId] = useState<number | null>(null);
  const [expandedEventLocators, setExpandedEventLocators] = useState<Set<number>>(new Set());
  const issueClipRefs = useRef<Record<number, HTMLDivElement | null>>({});
  const summaryRefs = useRef<Record<number, HTMLDivElement | null>>({});

  // Action loading states
  const [generatingSummary, setGeneratingSummary] = useState(false);
  const [generatingCases, setGeneratingCases] = useState(false);
  const [generatingSkill, setGeneratingSkill] = useState(false);
  const [generatingTool, setGeneratingTool] = useState(false);
  const [confirmingSummaryId, setConfirmingSummaryId] = useState<number | null>(null);
  const [rejectingSummaryId, setRejectingSummaryId] = useState<number | null>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [submittingCaseId, setSubmittingCaseId] = useState<number | null>(null);
  const [confirmingCorrectionId, setConfirmingCorrectionId] = useState<number | null>(null);
  const [rejectingCorrectionId, setRejectingCorrectionId] = useState<number | null>(null);
  const [correctionRejectReason, setCorrectionRejectReason] = useState('');

  // Step inline editing
  const [editingStepNo, setEditingStepNo] = useState<number | null>(null);
  const [stepEditDraft, setStepEditDraft] = useState('');

  // Summary refresh hint after corrections applied
  const [summaryRefreshSuggested, setSummaryRefreshSuggested] = useState(false);
  const [actionNotice, setActionNotice] = useState<ActionNotice | null>(null);
  const [pendingDeleteGroup, setPendingDeleteGroup] = useState<BrowserTraceGroup | null>(null);
  const [deletingGroup, setDeletingGroup] = useState(false);

  // Summary editing
  const [editingSummary, setEditingSummary] = useState<TraceSummary | null>(null);
  const [summaryEditForm, setSummaryEditForm] = useState({
    overview: '',
    businessSummary: '',
    keyStepsJson: '',
    keyApiJson: '',
    exceptionSummary: '',
    caseGenerationSuggestionJson: '',
    pendingConfirmationJson: '',
    validityLabel: 'STANDARD',
  });
  const [savingSummaryEdit, setSavingSummaryEdit] = useState(false);

  // Naming dialog
  const [namingDialog, setNamingDialog] = useState<{ visible: boolean; sessionId: number; value: string }>({
    visible: false, sessionId: 0, value: ''
  });

  // Pagination
  const [groupKeyword, setGroupKeyword] = useState('');
  const [groupStatusFilter, setGroupStatusFilter] = useState<'ALL' | 'RECORDING' | 'STOPPED'>('ALL');
  const [groupPage, setGroupPage] = useState(1);
  const groupPageSize = 10;
  const filteredGroups = useMemo(() => {
    const keyword = groupKeyword.trim().toLowerCase();
    return groups.filter(group => {
      const matchesStatus = groupStatusFilter === 'ALL' || group.status === groupStatusFilter;
      if (!matchesStatus) return false;
      if (!keyword) return true;
      const profileName = profiles.find(p => p.id === group.profileId)?.profileName || '';
      const haystack = [
        group.groupName,
        group.description,
        group.status,
        profileName,
      ].filter(Boolean).join(' ').toLowerCase();
      return haystack.includes(keyword);
    });
  }, [groupKeyword, groupStatusFilter, groups, profiles]);
  const recordingGroupCount = groups.filter(group => group.status === 'RECORDING').length;
  const stoppedGroupCount = groups.filter(group => group.status === 'STOPPED').length;
  const totalGroupPages = Math.max(1, Math.ceil(filteredGroups.length / groupPageSize));
  const pagedGroups = filteredGroups.slice((groupPage - 1) * groupPageSize, groupPage * groupPageSize);

  useEffect(() => {
    setGroupPage(1);
  }, [groupKeyword, groupStatusFilter]);

  useEffect(() => {
    if (groupPage > totalGroupPages) {
      setGroupPage(totalGroupPages);
    }
  }, [groupPage, totalGroupPages]);

  // ===== API functions =====
  const checkWorker = useCallback(async () => {
    setCheckingWorker(true);
    setWorkerError('');
    const health = await checkWorkerHealth();
    setWorkerHealth(health);
    if (!health) setWorkerError('采集器未启动');
    setCheckingWorker(false);
    return health;
  }, []);

  const loadProfiles = useCallback(async () => {
    try {
      const data = await listProfiles(projectId);
      setProfiles(data);
      return data;
    }
    catch (e: any) { setError(e.message || '加载身份空间失败'); }
    return [];
  }, [projectId]);

  const loadGroups = useCallback(async () => {
    try {
      const data = await listGroups(projectId);
      setGroups(data);
      return data;
    }
    catch (e: any) { setError(e.message || '加载采集组失败'); }
    return [];
  }, [projectId]);

  const loadEnabledModels = useCallback(async () => {
    try {
      const models = await baseApi<ModelConfigOption[]>('/api/model-configs/enabled');
      setEnabledModels(models);
      setTraceModelConfigId(current => current || models[0]?.id || 0);
    } catch (e: any) { setError(e.message || '加载模型失败'); }
  }, []);

  const loadTraceGeneratedCases = useCallback(async (groupId = expandedGroupId, page = traceGeneratedCasePage) => {
    if (!groupId) return;
    try {
      const result = await listTraceGeneratedCasesPage(projectId, groupId, page, 20);
      setTraceGeneratedCases(result.items || []);
      setTraceGeneratedCaseTotal(result.total || 0);
      setTraceGeneratedCasePage(result.page || 0);
    }
    catch (e: any) { setError(e.message || '加载轨迹草稿失败'); }
  }, [projectId, expandedGroupId, traceGeneratedCasePage]);

  const loadLocalCases = useCallback(async () => {
    try {
      const page = await listLocalCasesPage(projectId, 0, 10);
      setLocalCases(page.items || []);
      setLocalCaseTotal(page.total || 0);
    }
    catch (e: any) { setError(e.message || '加载本地用例库失败'); }
  }, [projectId]);

  const loadFormalCases = useCallback(async () => {
    try {
      const page = await listFormalCasesPage(projectId, 0, 10);
      setFormalCases(page.items || []);
      setFormalCaseTotal(page.total || 0);
    }
    catch (e: any) { setError(e.message || '加载正式库失败'); }
  }, [projectId]);

  const initialize = useCallback(async () => {
    setLoading(true);
    const [, loadedGroups] = await Promise.all([loadProfiles(), loadGroups(), loadEnabledModels()]);
    setLoading(false);

    // Libraries and the local worker are secondary information. They must not hold the trace page's
    // first paint hostage, especially when a project already owns hundreds of generated cases.
    void Promise.all([loadLocalCases(), loadFormalCases()]);
    void checkWorker().then(health => syncCaptureState(health, loadedGroups));
  }, [loadProfiles, loadGroups, loadEnabledModels, loadLocalCases, loadFormalCases, checkWorker]);

  useEffect(() => {
    initialize();
    healthTimerRef.current = setInterval(checkWorker, 30000);
    return () => {
      if (healthTimerRef.current) clearInterval(healthTimerRef.current);
    };
  }, [initialize, checkWorker]);

  // Sync capture state with worker
  const syncCaptureState = useCallback(async (
    health: WorkerHealth | null = workerHealth,
    currentGroups: BrowserTraceGroup[] = groups,
  ) => {
    const activeSessionIds = new Set(health?.activeSessions || []);
    const next: Record<number, { groupId: number; sessionId: number }> = {};
    const recordingGroups = currentGroups.filter(g => g.status === 'RECORDING');
    for (const group of recordingGroups) {
      try {
        const sessions = await listGroupSessions(group.id);
        const recordingSession = sessions.find(s => s.status === 'RECORDING');
        if (!recordingSession) {
          await stopGroup(group.id);
          continue;
        }
        if (activeSessionIds.has(recordingSession.id)) {
          next[recordingSession.profileId] = { groupId: group.id, sessionId: recordingSession.id };
        } else {
          await stopSession(recordingSession.id, { videoPath: null, traceFilePath: null });
          await stopGroup(group.id);
        }
      } catch (e: any) {
        setError(e.message || '同步采集状态失败');
      }
    }
    setActiveCaptures(next);
    if (recordingGroups.length > 0) await loadGroups();
  }, [workerHealth, groups, loadGroups]);

  // ===== Profile actions =====
  const handleProfileOpen = async (p: BrowserProfile) => {
    if (!localToken.trim()) { setError('请先在顶部填写本地令牌'); return; }
    try {
      let autoFill: { username?: string; password?: string } = {};
      try {
        const creds = await getProfileCredentials(projectId, p.id);
        if (creds.username) autoFill.username = creds.username;
        if (creds.password) autoFill.password = creds.password;
      } catch { /* no creds */ }
      await workerApi('/browser/open-profile', {
        profileId: p.id, targetUrl: p.targetHost || 'about:blank', autoFill,
      }, localToken);
      await profileOperation(projectId, p.id, 'OPEN');
      setProfileWindows(prev => new Set(prev).add(p.id));
    } catch (e: any) { setError(e.message || '打开失败'); }
  };

  const handleProfileClearAuth = async (p: BrowserProfile) => {
    try { await workerApi('/browser/close-profile', { profileId: p.id }, localToken); } catch { /* ok */ }
    setProfileWindows(prev => {
      const next = new Set(prev);
      next.delete(p.id);
      return next;
    });
    await profileOperation(projectId, p.id, 'CLEAR_AUTH');
  };

  const handleProfileReset = async (p: BrowserProfile) => {
    try { await workerApi('/browser/close-profile', { profileId: p.id }, localToken); } catch { /* ok */ }
    setProfileWindows(prev => {
      const next = new Set(prev);
      next.delete(p.id);
      return next;
    });
    await profileOperation(projectId, p.id, 'RESET');
  };

  // ===== Capture actions =====
  const isProfileRecording = (profileId: number) => Boolean(activeCaptures[profileId]);

  const startCapture = async (profile: BrowserProfile) => {
    if (!localToken.trim()) { setError('请先在顶部填写本地令牌'); return; }
    try {
      await checkWorker();
      await syncCaptureState();
      if (isProfileRecording(profile.id)) {
        setError('该身份空间已在采集中，请先停止当前采集。'); return;
      }
      let autoFill: { username?: string; password?: string } = {};
      try {
        const creds = await getProfileCredentials(projectId, profile.id);
        if (creds.username) autoFill.username = creds.username;
        if (creds.password) autoFill.password = creds.password;
      } catch { /* no creds */ }
      const group = await createGroup(projectId, {
        groupName: `${profile.profileName} ${new Date().toLocaleString('zh-CN', { hour12: false })}`,
        description: '身份空间采集组',
        profileId: profile.id,
      });
      await startGroup(group.id);
      const session = await createSession(group.id, {
        profileId: profile.id,
        sessionName: '待命名轨迹',
        browserType: workerHealth?.browser?.type || 'system',
        browserExecutablePath: workerHealth?.browser?.displayName || '',
      });
      await startSession(session.id);
      await workerApi<WorkerStartResult>('/sessions/start', {
        traceGroupId: group.id, sessionId: session.id, profileId: profile.id,
        profileName: profile.profileName, targetHost: profile.targetHost || 'about:blank', autoFill,
      }, localToken);
      setActiveCaptures(prev => ({ ...prev, [profile.id]: { groupId: group.id, sessionId: session.id } }));
      await loadGroups();
      showToast('采集已开始');
    } catch (e: any) { setError(e.message || '开始采集失败'); }
  };

  const stopCapture = async (profile: BrowserProfile) => {
    const running = activeCaptures[profile.id];
    if (!running) return;
    try {
      const result = await workerApi<WorkerStopResult | null>('/sessions/stop', { sessionId: running.sessionId }, localToken)
        .catch((e: any) => {
          if (e.message?.includes('采集会话未在本地运行')) return null;
          throw e;
        });
      if (result?.events?.length) {
        await batchSaveEvents(running.sessionId, result.events);
      }
      if (result?.networks?.length) {
        await batchSaveNetworks(running.sessionId, result.networks);
      }
      await stopSession(running.sessionId, {
        videoPath: result?.videoPath || null,
        traceFilePath: result?.traceFilePath || null,
        screencastPath: result?.screencastPath || null,
        screencastStartedAtUtc: result?.screencastStartedAtUtc || null,
        screencastStoppedAtUtc: result?.screencastStoppedAtUtc || null,
        screencastDurationMs: result?.screencastDurationMs ?? null,
      });
      setNamingDialog({ visible: true, sessionId: running.sessionId, value: `${profile.profileName} 轨迹 ${new Date().toLocaleString('zh-CN', { hour12: false })}` });
      await stopGroup(running.groupId);
      setActiveCaptures(prev => {
        const next = { ...prev };
        delete next[profile.id];
        return next;
      });
      if (!result) setError('本地采集器里已没有这条会话，系统已按停止处理并同步后端状态。');
      else setError('');
      await loadGroups();
      if (expandedGroupId === running.groupId) {
        setGroupDetail(await getGroupDetail(running.groupId));
      }
      showToast('采集已停止');
    } catch (e: any) { setError(e.message || '停止采集失败'); }
  };

  const confirmNaming = async () => {
    try {
      const { sessionId, value } = namingDialog;
      const name = value.trim();
      if (sessionId > 0 && name) {
        await updateSessionName(sessionId, name);
        if (groupDetail) {
          const target = groupDetail.sessions.find(s => s.id === sessionId);
          if (target) target.sessionName = name;
        }
      }
      setNamingDialog({ visible: false, sessionId: 0, value: '' });
    } catch (e: any) { setError(e.message || '保存轨迹名称失败'); }
  };

  // ===== Group detail =====
  const toggleGroupDetail = async (g: BrowserTraceGroup) => {
    if (expandedGroupId === g.id) {
      setExpandedGroupId(null);
      setGroupDetail(null);
      setTraceGeneratedCases([]);
      setTraceGeneratedCaseTotal(0);
      setTraceGeneratedCasePage(0);
      setExpandedSessionId(null);
      setDetailTab('overview');
      setCleanSteps([]);
      setGroupSkills([]);
      setGroupTools([]);
      setGroupCorrections([]);
    } else {
      setExpandedGroupId(g.id);
      setTraceGeneratedCasePage(0);
      setExpandedSessionId(null);
      setDetailTab('overview');
      try {
        const detail = await getGroupDetail(g.id);
        setGroupDetail(detail);
        setError('');
      } catch (e: any) {
        setGroupDetail(null);
        setGroupSummaries([]);
        setCleanSteps([]);
        setGroupSkills([]);
        setGroupTools([]);
        setGroupCorrections([]);
        setError(e.message || '加载详情失败');
        return;
      }
      const [summaries, steps, skills, tools, corrections] = await Promise.allSettled([
        getGroupSummaries(g.id), getGroupCleanSteps(g.id), getGroupSkills(g.id),
        getGroupTools(g.id), getGroupCorrections(g.id),
      ]);
      setGroupSummaries(summaries.status === 'fulfilled' ? summaries.value : []);
      setCleanSteps(steps.status === 'fulfilled' ? steps.value : []);
      setGroupSkills(skills.status === 'fulfilled' ? skills.value : []);
      setGroupTools(tools.status === 'fulfilled' ? tools.value : []);
      setGroupCorrections(corrections.status === 'fulfilled' ? corrections.value : []);
      // Trace drafts are only rendered inside an expanded group. Keep this potentially large
      // project-wide query out of the initial route load.
      await loadTraceGeneratedCases(g.id, 0);
    }
  };

  // ===== Summary actions =====
  const handleGenerateSummary = async (group: BrowserTraceGroup, issueClipId?: number) => {
    if (!traceModelConfigId) { setError('请先选择 AI 模型'); return; }
    setGeneratingSummary(true);
    try {
      await generateGroupSummary(group.id, traceModelConfigId, issueClipId);
      setGroupSummaries(await getGroupSummaries(group.id));
      setSummaryRefreshSuggested(false);
      setActionNotice({
        tone: 'success',
        title: issueClipId ? '问题片段摘要已生成' : '轨迹摘要已生成',
        description: issueClipId
          ? '你可以继续确认摘要，或回到概览页查看当前项目沉淀。'
          : '你可以继续确认摘要，或回到概览页查看当前项目进展。',
        links: [
          { label: '返回项目概览', to: `/projects/${projectId}/overview` },
          { label: '继续查看轨迹页', to: `/projects/${projectId}/trace` },
        ],
      });
      showToast('摘要生成成功');
    } catch (e: any) { setError(e.message || '生成摘要失败'); }
    finally { setGeneratingSummary(false); }
  };

  const handleRegenerateSummary = async (summary: TraceSummary) => {
    if (!traceModelConfigId) { setError('请先选择 AI 模型'); return; }
    setGeneratingSummary(true);
    try {
      await apiRegenerateSummary(summary.id, traceModelConfigId);
      setGroupSummaries(await getGroupSummaries(summary.traceGroupId));
      setSummaryRefreshSuggested(false);
      setActionNotice({
        tone: 'success',
        title: '摘要已重新生成',
        description: '当前摘要已按最新轨迹和修正结果刷新，可继续确认或返回项目概览查看。',
        links: [
          { label: '返回项目概览', to: `/projects/${projectId}/overview` },
        ],
      });
      showToast('摘要已重新生成');
    } catch (e: any) { setError(e.message || '重新生成摘要失败'); }
    finally { setGeneratingSummary(false); }
  };

  const handleConfirmSummary = async (summary: TraceSummary) => {
    setConfirmingSummaryId(summary.id);
    try {
      await apiConfirmSummary(summary.id);
      setGroupSummaries(await getGroupSummaries(summary.traceGroupId));
      setActionNotice({
        tone: 'success',
        title: '摘要已确认',
        description: '这条轨迹摘要已经沉淀，可以继续生成用例，或回到项目概览查看整体进展。',
        links: [
          { label: '返回项目概览', to: `/projects/${projectId}/overview` },
          { label: '继续查看轨迹页', to: `/projects/${projectId}/trace` },
        ],
      });
      showToast('摘要已确认');
    } catch (e: any) { setError(e.message || '确认摘要失败'); }
    finally { setConfirmingSummaryId(null); }
  };

  const handleRejectSummary = async (summary: TraceSummary) => {
    if (!rejectReason.trim()) { setError('请填写驳回原因'); return; }
    setRejectingSummaryId(summary.id);
    try {
      await apiRejectSummary(summary.id, rejectReason);
      setRejectReason('');
      setGroupSummaries(await getGroupSummaries(summary.traceGroupId));
      showToast('摘要已驳回');
    } catch (e: any) { setError(e.message || '驳回摘要失败'); }
    finally { setRejectingSummaryId(null); }
  };

  const openEditSummary = (summary: TraceSummary) => {
    setEditingSummary(summary);
    setSummaryEditForm({
      overview: summary.overview || '',
      businessSummary: summary.businessSummary || '',
      keyStepsJson: summary.keyStepsJson || '',
      keyApiJson: summary.keyApiJson || '',
      exceptionSummary: summary.exceptionSummary || '',
      caseGenerationSuggestionJson: summary.caseGenerationSuggestionJson || '',
      pendingConfirmationJson: summary.pendingConfirmationJson || '',
      validityLabel: summary.validityLabel || 'STANDARD',
    });
  };

  const closeEditSummary = () => {
    setEditingSummary(null);
    setSummaryEditForm({
      overview: '', businessSummary: '', keyStepsJson: '', keyApiJson: '',
      exceptionSummary: '', caseGenerationSuggestionJson: '', pendingConfirmationJson: '', validityLabel: 'STANDARD',
    });
  };

  const handleSaveSummaryEdit = async () => {
    if (!editingSummary) return;
    setSavingSummaryEdit(true);
    try {
      await baseApi(`/api/trace/summaries/${editingSummary.id}`, {
        method: 'PATCH',
        body: JSON.stringify({
          overview: summaryEditForm.overview,
          businessSummary: summaryEditForm.businessSummary,
          keyStepsJson: summaryEditForm.keyStepsJson,
          keyApiJson: summaryEditForm.keyApiJson,
          exceptionSummary: summaryEditForm.exceptionSummary,
          caseGenerationSuggestionJson: summaryEditForm.caseGenerationSuggestionJson,
          pendingConfirmationJson: summaryEditForm.pendingConfirmationJson,
          validityLabel: summaryEditForm.validityLabel,
        }),
      });
      setGroupSummaries(await getGroupSummaries(editingSummary.traceGroupId));
      closeEditSummary();
      showToast('摘要已更新');
    } catch (e: any) { setError(e.message || '保存摘要失败'); }
    finally { setSavingSummaryEdit(false); }
  };

  // ===== Correction actions =====
  const handleConfirmCorrection = async (correction: TraceCorrectionSuggestion, confirmedStepText?: string) => {
    setConfirmingCorrectionId(correction.id);
    try {
      await apiConfirmCorrection(correction.id, { confirmedStepText: confirmedStepText ?? null });
      const [corrections, steps, summaries] = await Promise.allSettled([
        getGroupCorrections(correction.traceGroupId),
        getGroupCleanSteps(correction.traceGroupId),
        getGroupSummaries(correction.traceGroupId),
      ]);
      setGroupCorrections(corrections.status === 'fulfilled' ? corrections.value : groupCorrections);
      setCleanSteps(steps.status === 'fulfilled' ? steps.value : cleanSteps);
      setGroupSummaries(summaries.status === 'fulfilled' ? summaries.value : groupSummaries);
      setSummaryRefreshSuggested(true);
      showToast('修正已确认');
    } catch (e: any) { setError(e.message || '确认修正失败'); }
    finally { setConfirmingCorrectionId(null); }
  };

  const handleRejectCorrection = async (correction: TraceCorrectionSuggestion) => {
    if (!correctionRejectReason.trim()) { setError('请填写驳回原因'); return; }
    setRejectingCorrectionId(correction.id);
    try {
      await apiRejectCorrection(correction.id, correctionRejectReason);
      setCorrectionRejectReason('');
      setGroupCorrections(await getGroupCorrections(correction.traceGroupId));
      showToast('修正已驳回');
    } catch (e: any) { setError(e.message || '驳回修正失败'); }
    finally { setRejectingCorrectionId(null); }
  };

  const handleSaveStepEdit = async (groupId: number | undefined, step: CleanStepView, operationType: 'REWRITE' | 'DROP', confirmedStepText?: string) => {
    const gid = groupId ?? expandedGroupId;
    if (!gid) { setError('当前采集组不存在，请刷新后重试'); return; }
    try {
      await saveStepCorrection(gid, {
        stepNo: step.stepNo, sourceText: step.description,
        operationType, confirmedStepText: confirmedStepText ?? null,
      });
      await learnPattern(gid).catch(() => null);
      const [corrections, steps, summaries] = await Promise.allSettled([
        getGroupCorrections(gid), getGroupCleanSteps(gid), getGroupSummaries(gid),
      ]);
      setGroupCorrections(corrections.status === 'fulfilled' ? corrections.value : groupCorrections);
      setCleanSteps(steps.status === 'fulfilled' ? steps.value : cleanSteps);
      setGroupSummaries(summaries.status === 'fulfilled' ? summaries.value : groupSummaries);
      setEditingStepNo(null);
      setStepEditDraft('');
      if (operationType === 'REWRITE' || operationType === 'DROP') {
        setSummaryRefreshSuggested(true);
      }
      setActionNotice({
        tone: 'info',
        title: '步骤修正已保存',
        description: operationType === 'DROP'
          ? '当前步骤已从清洗结果中移除。若要让摘要正文同步最新步骤，可直接重生成摘要。'
          : '当前步骤改写已生效。若要让摘要正文同步最新步骤，可直接重生成摘要。',
        links: [
          { label: '返回项目概览', to: `/projects/${projectId}/overview` },
        ],
      });
      showToast('步骤修正已保存');
    } catch (e: any) { setError(e.message || '保存步骤修正失败'); }
  };

  // ===== Case generation =====
  const handleGenerateCases = async (group: BrowserTraceGroup, issueClipId?: number) => {
    if (!traceModelConfigId) { setError('请先选择 AI 模型'); return; }
    setGeneratingCases(true);
    try {
      await generateGroupCases(group.id, traceModelConfigId, issueClipId ? '缺陷复现用例' : '操作路径用例', issueClipId);
      await loadTraceGeneratedCases();
      setActionNotice({
        tone: 'success',
        title: issueClipId ? '问题片段用例已生成' : '轨迹用例已生成',
        description: '新的轨迹草稿已经生成，可继续在当前轨迹页查看，或直接提交到正式库。',
        links: [
          { label: '继续查看轨迹页', to: `/projects/${projectId}/trace` },
          { label: '返回项目概览', to: `/projects/${projectId}/overview` },
        ],
      });
      showToast('用例生成成功');
    } catch (e: any) { setError(e.message || '生成用例失败'); }
    finally { setGeneratingCases(false); }
  };

  const handleGenerateSkill = async (group: BrowserTraceGroup) => {
    if (!traceModelConfigId) { setError('请先选择 AI 模型'); return; }
    setGeneratingSkill(true);
    try {
      await generateGroupSkill(group.id, traceModelConfigId);
      setGroupSkills(await getGroupSkills(group.id));
      showToast('Skill 生成成功');
    } catch (e: any) { setError(e.message || '生成 Skill 失败'); }
    finally { setGeneratingSkill(false); }
  };

  const handleGenerateTool = async (group: BrowserTraceGroup) => {
    if (!traceModelConfigId) { setError('请先选择 AI 模型'); return; }
    setGeneratingTool(true);
    try {
      await generateGroupTool(group.id, traceModelConfigId);
      setGroupTools(await getGroupTools(group.id));
      showToast('Tool 生成成功');
    } catch (e: any) { setError(e.message || '生成 Tool 失败'); }
    finally { setGeneratingTool(false); }
  };

  const handleSubmitCase = async (caseId: number) => {
    setSubmittingCaseId(caseId);
    try {
      await submitTraceGeneratedCase(caseId);
      await Promise.all([loadTraceGeneratedCases(), loadFormalCases()]);
      setActionNotice({
        tone: 'success',
        title: '用例已提交正式库',
        description: '这条草稿已经进入正式库；如果你要继续核对沉淀结果，可以直接打开正式用例库。',
        links: [
          { label: '查看正式用例库', to: `/projects/${projectId}/formal-cases` },
          { label: '返回项目概览', to: `/projects/${projectId}/overview` },
        ],
      });
      showToast('已提交正式库');
    } catch (e: any) { setError(e.message || '提交正式库失败'); }
    finally { setSubmittingCaseId(null); }
  };

  // ===== Group deletion =====
  const handleDeleteGroup = async (g: BrowserTraceGroup) => {
    setDeletingGroup(true);
    try {
      if (g.status === 'RECORDING') {
        await checkWorker();
        const sessions = await listGroupSessions(g.id);
        const recordingSessions = sessions.filter(s => s.status === 'RECORDING');
        const activeSessionIds = new Set(workerHealth?.activeSessions || []);
        const hasLocalRunning = recordingSessions.some(s => activeSessionIds.has(s.id));
        if (hasLocalRunning) {
          setError('该采集组仍在本地采集中，请先停止采集后再删除。'); return;
        }
        for (const session of recordingSessions) {
          await stopSession(session.id, { videoPath: null, traceFilePath: null });
        }
        await stopGroup(g.id);
      }
      await apiDeleteGroup(projectId, g.id);
      if (expandedGroupId === g.id) {
        setExpandedGroupId(null);
        setGroupDetail(null);
        setExpandedSessionId(null);
      }
      setActiveCaptures(prev => {
        const next = { ...prev };
        for (const [profileId, capture] of Object.entries(next)) {
          if (capture.groupId === g.id) delete next[Number(profileId)];
        }
        return next;
      });
      setError('');
      await loadGroups();
      setPendingDeleteGroup(null);
      showToast('采集组已删除');
    } catch (e: any) { setError(e.message || '删除失败'); }
    finally { setDeletingGroup(false); }
  };

  const handleScanGroup = async (groupId: number) => {
    setScanningGroupId(groupId);
    try {
      const result = await scanFromTrace(groupId);
      const pages = result.scannedPages.length;
      const profiles = result.profileCount;
      const events = result.eventCount;
      showToast(`扫描完成：${events} 条事件 → ${profiles} 条页面画像（${pages} 个页面）`);
      // 刷新页面画像列表
      if (projectId) {
        const updated = await listProfiles(Number(projectId));
        setProfiles(updated);
      }
    } catch (e: any) {
      showToast(e.message || '扫描失败', 'error');
    } finally {
      setScanningGroupId(null);
    }
  };

  // ===== Bind code =====
  const handleCreateBindCode = async () => {
    setCreatingBindCode(true);
    try {
      const result = await apiCreateBindCode();
      setBindCode(result);
      showToast('绑定码已生成');
    } catch (e: any) { setError(e.message || '生成绑定码失败'); }
    finally { setCreatingBindCode(false); }
  };

  const bindCommandText = bindCode
    ? bindOs === 'win'
      ? `"%USERPROFILE%\\AI-Test-Worker\\bind.bat" --code ${bindCode.code}`
      : `~/AI-Test-Worker/bind.command --code ${bindCode.code}`
    : '';

  const handleCopyBindCommand = async () => {
    if (!bindCommandText) return;
    const copied = await copyText(bindCommandText);
    setBindCopied(copied);
    if (!copied) setError('自动复制失败，请手动复制。');
    else { setError(''); showToast('已复制'); }
  };

  // ===== Derived state =====
  const recordingProfileCount = Object.keys(activeCaptures).length;

  const detailTabOptions = [
    { label: '概览', value: 'overview' },
    { label: '会话', value: 'sessions' },
    { label: '问题片段', value: 'issues' },
    { label: '用例资产', value: 'assets' },
    { label: 'Skill / Tool', value: 'templates' },
  ];

  const sessionEvents = (sessionId: number) => {
    if (!groupDetail) return [];
    return groupDetail.events.filter(e => e.traceSessionId === sessionId);
  };

  const sessionNetworks = (sessionId: number) => {
    if (!groupDetail) return [];
    return groupDetail.networks.filter(n => n.traceSessionId === sessionId);
  };
  const sessionIssueClips = (sessionId: number) => {
    if (!groupDetail) return [];
    return groupDetail.issueClips.filter(clip => clip.traceSessionId === sessionId);
  };

  const groupTraceGeneratedCases = (groupId: number) => traceGeneratedCases.filter(c => c.traceGroupId === groupId);
  const clipTraceGeneratedCases = (issueClipId: number) => traceGeneratedCases.filter(c => c.issueClipId === issueClipId);
  const clipSummaries = (issueClipId: number) => groupSummaries.filter(s => s.issueClipId === issueClipId);
  const activeLocalCases = localCases.filter(c => c.caseStatus !== 'SUBMITTED' && c.caseStatus !== 'DEPRECATED');
  const recentLocalCases = activeLocalCases.slice(0, 5);
  const recentFormalCases = formalCases.slice(0, 5);

  const localCaseSourceLabel = (sourceType?: string | null) => sourceType === 'TRACE' ? '轨迹回放' : '需求生成';
  const localCaseSourceClass = (sourceType?: string | null) =>
    sourceType === 'TRACE' ? 'bg-purple-50 text-purple-700' : 'bg-sky-50 text-sky-700';

  useEffect(() => {
    if (detailTab === 'issues' && focusedIssueClipId) {
      const target = issueClipRefs.current[focusedIssueClipId];
      if (target) {
        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    }
  }, [detailTab, focusedIssueClipId]);

  useEffect(() => {
    if (detailTab === 'overview' && focusedSummaryId) {
      const target = summaryRefs.current[focusedSummaryId];
      if (target) {
        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    }
  }, [detailTab, focusedSummaryId]);

  const jumpToSession = (sessionId: number) => {
    setDetailTab('sessions');
    setExpandedSessionId(sessionId);
  };

  const jumpToIssueClip = (clipId: number) => {
    setDetailTab('issues');
    setFocusedIssueClipId(clipId);
  };

  const jumpToSummary = (summaryId: number) => {
    setDetailTab('overview');
    setFocusedSummaryId(summaryId);
  };

  if (loading) {
    return (
      <div className="mx-auto max-w-[1000px] px-4 py-8 sm:px-6">
        <div className="animate-pulse space-y-4">
          <div className="h-8 bg-gray-200 rounded w-1/3" />
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {[1, 2, 3, 4].map(i => <div key={i} className="h-24 bg-gray-200 rounded" />)}
          </div>
          <div className="h-64 bg-gray-200 rounded" />
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-[1000px] px-4 py-8 animate-fade-in sm:px-6">
      {/* Header */}
      <div className="mb-6 flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold text-gray-900">测试执行轨迹</h1>
          <p className="text-sm text-gray-500 mt-1">围绕身份空间、采集组和轨迹资产展开，持续采集真实操作、网络摘要与问题片段。</p>
        </div>
        <div className="flex flex-wrap items-center gap-2 lg:justify-end">
          <Link
            to={`/projects/${projectId}/overview`}
            className="min-h-10 shrink-0 rounded-lg px-3 py-2 text-sm text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-900"
          >
            返回概览
          </Link>
          <Link
            to={`/projects/${projectId}/local-cases`}
            className="min-h-10 shrink-0 rounded-lg px-3 py-2 text-sm text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-900"
          >
            本地用例库
          </Link>
          <Link
            to={`/projects/${projectId}/formal-cases`}
            className="min-h-10 shrink-0 rounded-lg px-3 py-2 text-sm text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-900"
          >
            正式用例库
          </Link>
          <button onClick={initialize} className="min-h-10 shrink-0 rounded-lg px-4 py-2 text-sm text-gray-500 transition-colors hover:bg-gray-100">
            刷新全局状态
          </button>
        </div>
      </div>

      {/* Error */}
      {error && (
        <div className="mb-4 px-4 py-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-600">
          {error}
          <button onClick={() => setError('')} className="ml-2 text-red-400 hover:text-red-600">✕</button>
        </div>
      )}

      {actionNotice && (
        <div className={`mb-4 px-4 py-3 rounded-lg border ${
          actionNotice.tone === 'success'
            ? 'bg-emerald-50 border-emerald-200'
            : 'bg-amber-50 border-amber-200'
        }`}>
          <div className="flex items-start justify-between gap-4">
            <div className="min-w-0">
              <div className={`text-sm font-semibold ${
                actionNotice.tone === 'success' ? 'text-emerald-800' : 'text-amber-800'
              }`}>
                {actionNotice.title}
              </div>
              <div className={`text-sm mt-1 ${
                actionNotice.tone === 'success' ? 'text-emerald-700' : 'text-amber-700'
              }`}>
                {actionNotice.description}
              </div>
              {actionNotice.links && actionNotice.links.length > 0 && (
                <div className="flex flex-wrap gap-3 mt-2">
                  {actionNotice.links.map((link) => (
                    <Link
                      key={link.to + link.label}
                      to={link.to}
                      className={`text-xs font-medium ${
                        actionNotice.tone === 'success'
                          ? 'text-emerald-700 hover:text-emerald-900'
                          : 'text-amber-700 hover:text-amber-900'
                      } transition-colors`}
                    >
                      {link.label}
                    </Link>
                  ))}
                </div>
              )}
            </div>
            <button
              onClick={() => setActionNotice(null)}
              className={`text-sm ${
                actionNotice.tone === 'success'
                  ? 'text-emerald-400 hover:text-emerald-700'
                  : 'text-amber-400 hover:text-amber-700'
              } transition-colors`}
            >
              ✕
            </button>
          </div>
        </div>
      )}

      {/* Stats */}
      <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          title="本地采集器"
          value={workerHealth?.status === 'UP' ? '在线' : '未连接'}
          subtitle={workerHealth ? `协议 ${workerHealth.protocolVersion} / ${workerHealth.version}` : (workerError || '尚未检测')}
        />
        <StatCard
          title="浏览器与绑定"
          value={workerHealth?.browserAlive ? '浏览器已打开' : (workerHealth?.browserReady ? '浏览器就绪' : '浏览器未就绪')}
          subtitle={workerHealth?.bound ? '设备已绑定主平台' : '设备未绑定'}
        />
        <StatCard
          title="当前采集"
          value={`${recordingProfileCount} 个身份空间`}
          subtitle={`正在采集的身份空间 / ${profiles.length} 个身份空间`}
        />
        <StatCard
          title="资产沉淀"
          value={`${localCaseTotal} 条本地用例`}
          subtitle={`正式库 ${formalCaseTotal} 条 / 采集组 ${groups.length} 个`}
        />
      </div>

      {/* Main Content */}
      <div className="flex flex-col gap-6 xl:flex-row">
        {/* Left: Worker Status + Identity Spaces */}
        <div className="w-full flex-shrink-0 space-y-4 xl:w-[340px]">
          {/* Worker Status Card */}
          <div className="bg-white rounded-lg border border-gray-200 p-4">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-sm font-semibold text-gray-900">本地采集器状态</h3>
              <button
                onClick={checkWorker}
                disabled={checkingWorker}
                className="text-xs text-gray-500 hover:text-gray-900 transition-colors"
              >
                {checkingWorker ? '检查中' : '刷新'}
              </button>
            </div>
            <div className="space-y-2 text-sm">
              <div className="flex justify-between">
                <span className="text-gray-500">采集器状态</span>
                <StatusBadge status={workerHealth?.status === 'UP' ? 'online' : 'offline'} label={workerHealth ? (workerHealth.status === 'UP' ? '在线' : workerHealth.status) : '未检查'} />
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">浏览器状态</span>
                <span className="text-gray-900">{workerHealth ? (workerHealth.browserAlive ? '已打开' : (workerHealth.browserReady ? '已就绪' : '未就绪')) : '-'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">绑定状态</span>
                <span className="text-gray-900">{workerHealth?.bound ? '已绑定' : '未绑定'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">AI 模型</span>
                <span className="text-gray-900 text-xs">{traceModelConfigId ? enabledModels.find(m => m.id === traceModelConfigId)?.configName || '未选择' : '未选择'}</span>
              </div>
            </div>

            {/* Local Token */}
            <div className="mt-3 pt-3 border-t border-gray-100">
              <label className="block text-xs text-gray-500 mb-1">本地令牌</label>
              <input
                type="password"
                value={localToken}
                onChange={e => { setLocalToken(e.target.value); saveLocalToken(e.target.value); }}
                placeholder="用于调用 127.0.0.1:17321"
                className="w-full h-8 px-2 bg-gray-50 border border-gray-200 rounded text-xs font-mono focus:bg-white focus:border-gray-400 outline-none transition-all"
              />
            </div>

            {/* Model Select */}
            <div className="mt-2">
              <label className="block text-xs text-gray-500 mb-1">AI 模型</label>
              <select
                value={traceModelConfigId || ''}
                onChange={e => setTraceModelConfigId(Number(e.target.value))}
                className="w-full h-8 px-2 bg-gray-50 border border-gray-200 rounded text-xs focus:bg-white focus:border-gray-400 outline-none transition-all"
              >
                <option value="">请选择模型</option>
                {enabledModels.map(m => (
                  <option key={m.id} value={m.id}>{m.configName} / {m.modelName}</option>
                ))}
              </select>
            </div>

            {/* Bind Code */}
            <div className="mt-4 pt-3 border-t border-gray-100">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between mb-2">
                <div className="min-w-0">
                  <div className="text-sm font-medium text-gray-900">Worker 绑定</div>
                  <p className="text-xs text-gray-500">生成一次性绑定码，复制命令到本地终端执行。</p>
                </div>
                <button
                  onClick={handleCreateBindCode}
                  disabled={creatingBindCode}
                  className="min-h-9 shrink-0 self-start whitespace-nowrap px-3 py-1.5 bg-slate-900 text-white text-xs font-semibold rounded-lg hover:bg-slate-800 transition-colors disabled:opacity-50"
                >
                  {creatingBindCode ? '生成中...' : '生成绑定码'}
                </button>
              </div>
              {bindCode && (
                <div className="mt-2 p-3 bg-gray-50 rounded-lg">
                  <p className="text-sm font-semibold">绑定码：{bindCode.code}</p>
                  <div className="flex gap-1 mt-2">
                    <button
                      onClick={() => setBindOs('mac')}
                      className={`text-xs px-2 py-1 rounded ${bindOs === 'mac' ? 'bg-slate-900 text-white' : 'bg-white text-gray-600 border border-gray-200'}`}
                    >macOS</button>
                    <button
                      onClick={() => setBindOs('win')}
                      className={`text-xs px-2 py-1 rounded ${bindOs === 'win' ? 'bg-slate-900 text-white' : 'bg-white text-gray-600 border border-gray-200'}`}
                    >Windows</button>
                  </div>
                  <input
                    value={bindCommandText}
                    readOnly
                    className="w-full mt-2 h-8 px-2 bg-white border border-gray-200 rounded text-xs font-mono"
                    onFocus={e => e.target.select()}
                  />
                  <p className="text-xs text-gray-400 mt-1">过期时间：{fmtDate(bindCode.expiresAt)}</p>
                  <button
                    onClick={handleCopyBindCommand}
                    className="mt-2 text-xs text-gray-500 hover:text-gray-900 transition-colors"
                  >
                    {bindCopied ? '已复制' : '复制命令'}
                  </button>
                </div>
              )}
            </div>
          </div>

          {/* Identity Spaces */}
          <div className="bg-white rounded-lg border border-gray-200 p-4">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-sm font-semibold text-gray-900">身份空间</h3>
              <button
                onClick={() => { setEditingProfile(null); setShowProfileForm(true); }}
                className="text-xs text-gray-500 hover:text-gray-900 transition-colors"
              >
                + 新建
              </button>
            </div>
            <div className="space-y-2">
              {profiles.map(profile => (
                <div key={profile.id} className="group border border-gray-100 rounded-lg p-3 hover:border-gray-200 transition-colors">
                  <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                    <div className="min-w-0 flex-1">
                      <div className="text-sm font-medium text-gray-900 break-words">{profile.profileName}</div>
                      <div className="mt-1 text-xs text-gray-500 font-mono leading-5 break-all">{profile.targetHost || '无目标地址'}</div>
                    </div>
                    <div className="flex flex-wrap items-center gap-2 opacity-100 transition-opacity sm:justify-end sm:opacity-40 sm:group-hover:opacity-100">
                      {isProfileRecording(profile.id) ? (
                        <button
                          onClick={() => stopCapture(profile)}
                          className="min-h-8 shrink-0 whitespace-nowrap px-2.5 py-1 bg-red-50 text-red-600 text-xs rounded hover:bg-red-100 transition-colors"
                        >
                          停止采集
                        </button>
                      ) : (
                        <button
                          onClick={() => startCapture(profile)}
                          className="min-h-8 shrink-0 whitespace-nowrap px-2.5 py-1 bg-green-50 text-green-600 text-xs rounded hover:bg-green-100 transition-colors"
                        >
                          开始采集
                        </button>
                      )}
                      <button
                        onClick={() => handleProfileOpen(profile)}
                        className="min-h-8 shrink-0 whitespace-nowrap px-2.5 py-1 text-xs text-gray-500 hover:bg-gray-100 rounded transition-colors"
                      >
                        打开浏览器
                      </button>
                    </div>
                  </div>
                  <div className="flex flex-wrap items-center gap-1 mt-2 opacity-100 transition-opacity sm:opacity-0 sm:group-hover:opacity-100">
                    <button onClick={() => { setEditingProfile(profile); setShowProfileForm(true); }} className="whitespace-nowrap text-[10px] text-gray-400 hover:text-gray-600">编辑</button>
                    <span className="text-gray-300">·</span>
                    <button onClick={() => handleProfileClearAuth(profile)} className="whitespace-nowrap text-[10px] text-gray-400 hover:text-gray-600">清除认证</button>
                    <span className="text-gray-300">·</span>
                    <button onClick={() => handleProfileReset(profile)} className="whitespace-nowrap text-[10px] text-gray-400 hover:text-gray-600">重置</button>
                  </div>
                </div>
              ))}
              {profiles.length === 0 && (
                <div className="text-center py-4 text-gray-400 text-sm">暂无身份空间</div>
              )}
            </div>
          </div>
        </div>

        {/* Right: Group List */}
        <div className="flex-1">
          <div className="bg-white rounded-lg border border-gray-200">
            <div className="px-4 py-3 border-b border-gray-200 space-y-3">
              <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0">
                  <h3 className="text-sm font-semibold text-gray-900">采集组</h3>
                  <span className="text-xs text-gray-400">当前展示 {filteredGroups.length} / {groups.length} 个采集组</span>
                </div>
                <div className="flex flex-wrap items-center gap-2 text-[11px] text-gray-500 sm:justify-end">
                  <span className="px-2 py-1 rounded bg-green-50 text-green-700">采集中 {recordingGroupCount}</span>
                  <span className="px-2 py-1 rounded bg-gray-100 text-gray-700">已停止 {stoppedGroupCount}</span>
                </div>
              </div>
              <div className="flex flex-col gap-2 md:flex-row md:items-center">
                <input
                  type="text"
                  value={groupKeyword}
                  onChange={(e) => setGroupKeyword(e.target.value)}
                  placeholder="搜索采集组名称、身份空间或状态"
                  className="flex-1 h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:bg-white focus:border-gray-400 outline-none transition-all"
                />
                <select
                  value={groupStatusFilter}
                  onChange={(e) => setGroupStatusFilter(e.target.value as 'ALL' | 'RECORDING' | 'STOPPED')}
                  className="h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:bg-white focus:border-gray-400 outline-none transition-all"
                >
                  <option value="ALL">全部状态</option>
                  <option value="RECORDING">采集中</option>
                  <option value="STOPPED">已停止</option>
                </select>
              </div>
            </div>
            <div className="divide-y divide-gray-100">
              {pagedGroups.map(group => (
                <div key={group.id}>
                  <div
                    className="group flex flex-col gap-2 px-4 py-3 transition-colors hover:bg-gray-50 sm:flex-row sm:items-center sm:justify-between cursor-pointer"
                    onClick={() => toggleGroupDetail(group)}
                  >
                    <div className="flex min-w-0 items-center gap-3">
                      <div className={`w-2 h-2 rounded-full ${group.status === 'RECORDING' ? 'bg-green-500 animate-pulse' : 'bg-gray-300'}`} />
                      <div className="min-w-0">
                        <div className="truncate text-sm font-medium text-gray-900">{group.groupName}</div>
                        <div className="truncate text-xs text-gray-500">
                          {profiles.find(p => p.id === group.profileId)?.profileName || `身份空间 #${group.profileId}`}
                          {' · '}
                          {fmtDate(group.createdAt)}
                        </div>
                      </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-2 sm:justify-end">
                      <span className={`text-[10px] font-semibold px-2 py-0.5 rounded ${
                        group.status === 'RECORDING' ? 'bg-green-50 text-green-600' :
                        group.status === 'STOPPED' ? 'bg-gray-100 text-gray-600' :
                        'bg-yellow-50 text-yellow-600'
                      }`}>
                        {group.status === 'RECORDING' ? '采集中' : group.status === 'STOPPED' ? '已停止' : group.status}
                      </span>
                      <button
                        onClick={(e) => { e.stopPropagation(); setPendingDeleteGroup(group); }}
                        className="text-xs text-gray-300 hover:text-red-500 transition-colors opacity-0 group-hover:opacity-100"
                      >
                        删除
                      </button>
                    </div>
                  </div>

                  {/* Expanded Detail */}
                  {expandedGroupId === group.id && groupDetail && (
                    <div className="px-4 pb-4 bg-gray-50">
                      <div className="flex items-center gap-2 mt-3">
                        <SegmentedControl
                          options={detailTabOptions}
                          value={detailTab}
                          onChange={v => setDetailTab(v as any)}
                        />
                        <button
                          onClick={(e) => { e.stopPropagation(); handleScanGroup(group.id); }}
                          disabled={scanningGroupId === group.id}
                          className="ml-auto shrink-0 rounded-lg bg-purple-600 px-3 py-1 text-xs font-semibold text-white transition-colors hover:bg-purple-700 disabled:opacity-50"
                        >
                          {scanningGroupId === group.id ? '扫描中...' : '扫描学习'}
                        </button>
                      </div>

                      {/* Overview Tab */}
                      {detailTab === 'overview' && (
                        <div className="mt-3 space-y-3">
                          {/* Clean Steps */}
                          {cleanSteps.length > 0 && (
                            <div className="bg-white rounded-lg border border-gray-200 p-3">
                              <div className="flex items-center justify-between mb-2">
                                <h4 className="text-xs font-semibold uppercase tracking-wider text-gray-400">清洗后步骤</h4>
                                <span className="text-xs text-gray-400">{cleanSteps.length} 步</span>
                              </div>
                              <div className="space-y-1">
                                {cleanSteps.map(step => (
                                  <div key={step.stepNo} className="flex items-start gap-2 text-sm py-1 border-b border-gray-50 last:border-b-0">
                                    <span className="text-xs font-mono text-gray-400 w-6 flex-shrink-0">{step.stepNo}</span>
                                    <div className="flex-1 min-w-0">
                                      {editingStepNo === step.stepNo ? (
                                        <div className="space-y-2">
                                          <textarea
                                            value={stepEditDraft}
                                            onChange={e => setStepEditDraft(e.target.value)}
                                            rows={3}
                                            placeholder="请输入修正后的轨迹步骤。动态内容可用 []、{} 或 ** ** 包起来"
                                            className="w-full px-2 py-1.5 bg-gray-50 border border-gray-200 rounded text-xs focus:bg-white focus:border-gray-400 outline-none resize-none"
                                          />
                                          <div className="flex items-center gap-2">
                                            <button
                                              onClick={() => handleSaveStepEdit(group.id, step, 'REWRITE', stepEditDraft.trim())}
                                              disabled={!stepEditDraft.trim()}
                                              className="text-[10px] px-2 py-1 bg-slate-900 text-white rounded hover:bg-slate-800 transition-colors disabled:opacity-50"
                                            >
                                              保存改写
                                            </button>
                                            <button
                                              onClick={() => { setEditingStepNo(null); setStepEditDraft(''); }}
                                              className="text-[10px] px-2 py-1 text-gray-500 hover:text-gray-700 transition-colors"
                                            >
                                              取消
                                            </button>
                                          </div>
                                        </div>
                                      ) : (
                                        <>
                                          <span className="text-gray-700">{step.description}</span>
                                          <div className="flex items-center gap-2 mt-0.5">
                                            <button
                                              onClick={() => { setEditingStepNo(step.stepNo); setStepEditDraft(step.description); }}
                                              className="text-[10px] text-gray-400 hover:text-gray-600 transition-colors"
                                            >
                                              修正
                                            </button>
                                            <button
                                              onClick={() => handleSaveStepEdit(group.id, step, 'DROP')}
                                              className="text-[10px] text-red-400 hover:text-red-600 transition-colors"
                                            >
                                              移除
                                            </button>
                                          </div>
                                        </>
                                      )}
                                    </div>
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}

                          {/* Summaries */}
                          {groupSummaries.length > 0 && (
                            <div className="bg-white rounded-lg border border-gray-200 p-3">
                              <div className="flex items-center justify-between mb-2">
                                <h4 className="text-xs font-semibold uppercase tracking-wider text-gray-400">摘要</h4>
                                {summaryRefreshSuggested && (
                                  <button
                                    onClick={async () => { setSummaryRefreshSuggested(false); await handleGenerateSummary(group); }}
                                    disabled={!traceModelConfigId || generatingSummary}
                                    className="text-[10px] px-2 py-1 bg-amber-50 text-amber-700 border border-amber-100 rounded hover:bg-amber-100 transition-colors disabled:opacity-50"
                                  >
                                    {generatingSummary ? '生成中...' : '应用修正并重生成摘要'}
                                  </button>
                                )}
                              </div>
                              <div className="space-y-2">
                                {groupSummaries.map(summary => {
                                  const keySteps = safeParseJsonArray(summary.keyStepsJson);
                                  const keyApis = safeParseJsonArray<{ method?: string; url?: string; status?: string; remark?: string }>(summary.keyApiJson);
                                  const caseSuggestion = safeParseJsonObject<{ fit?: string[]; unfit?: string[]; reasons?: string[] }>(summary.caseGenerationSuggestionJson);
                                  const pendingItems = safeParseJsonArray<{ type?: string; field?: string; value?: string; reason?: string }>(summary.pendingConfirmationJson);
                                  return (
                                    <div
                                      key={summary.id}
                                      ref={(node) => { summaryRefs.current[summary.id] = node; }}
                                      className={`border rounded-lg p-3 transition-colors ${
                                        focusedSummaryId === summary.id ? 'border-sky-300 ring-2 ring-sky-100' : 'border-gray-100'
                                      }`}
                                    >
                                      <div className="flex items-center gap-2 flex-wrap">
                                        <span className={`text-[10px] px-1.5 py-0.5 rounded ${
                                          summary.status === 'CONFIRMED' ? 'bg-green-50 text-green-600' :
                                          summary.status === 'REJECTED' ? 'bg-red-50 text-red-600' :
                                          'bg-yellow-50 text-yellow-600'
                                        }`}>
                                          {summary.status}
                                        </span>
                                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 text-gray-600">{summary.validityLabel}</span>
                                        <span className="text-[10px] text-gray-400">{summary.summaryScope}</span>
                                        {summary.issueClipId && (
                                          <button
                                            onClick={() => jumpToIssueClip(summary.issueClipId!)}
                                            className="text-[10px] px-1.5 py-0.5 rounded bg-sky-50 text-sky-700 hover:bg-sky-100 transition-colors"
                                          >
                                            定位问题片段
                                          </button>
                                        )}
                                      </div>
                                      {summary.overview && (
                                        <div className="mt-2 text-sm font-medium text-gray-900">{summary.overview}</div>
                                      )}
                                      {summary.businessSummary && (
                                        <div className="mt-1 text-xs text-gray-500">
                                          {safeParseJsonArray(summary.businessSummary).map((line, idx) => (
                                            <p key={idx}>{line}</p>
                                          ))}
                                          {safeParseJsonArray(summary.businessSummary).length === 0 && summary.businessSummary}
                                        </div>
                                      )}
                                      {keySteps.length > 0 && (
                                        <div className="mt-2">
                                          <div className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold">关键步骤</div>
                                          <ol className="list-decimal list-inside text-xs text-gray-700 mt-1">
                                            {keySteps.map((step, idx) => (
                                              <li key={idx}>{step}</li>
                                            ))}
                                          </ol>
                                        </div>
                                      )}
                                      {(summary.exceptionSummary || keyApis.length > 0) && (
                                        <div className="mt-2">
                                          <div className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold">关键接口与异常</div>
                                          {summary.exceptionSummary && (
                                            <p className="text-xs text-gray-700 mt-1">{summary.exceptionSummary}</p>
                                          )}
                                          {keyApis.length > 0 && (
                                            <div className="mt-1 space-y-0.5">
	                                          {keyApis.map((api, idx) => (
	                                                <div key={idx} className="break-all text-xs text-gray-700">
	                                                  <code>{api.method || '-'} {api.url || '-'}</code>
                                                  {api.status && <span className="text-gray-500 ml-2">status={api.status}</span>}
                                                  {api.remark && <span className="text-gray-500 ml-2">{api.remark}</span>}
                                                </div>
                                              ))}
                                            </div>
                                          )}
                                        </div>
                                      )}
                                      {caseSuggestion && (caseSuggestion.fit?.length || caseSuggestion.unfit?.length || caseSuggestion.reasons?.length) && (
                                        <div className="mt-2">
                                          <div className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold">用例生成建议</div>
                                          {caseSuggestion.fit && caseSuggestion.fit.length > 0 && (
                                            <div className="text-xs text-gray-700 mt-1">
                                              <strong>适合生成：</strong>
                                              <ul className="list-disc list-inside">
                                                {caseSuggestion.fit.map((item, idx) => <li key={idx}>{item}</li>)}
                                              </ul>
                                            </div>
                                          )}
                                          {caseSuggestion.unfit && caseSuggestion.unfit.length > 0 && (
                                            <div className="text-xs text-gray-700 mt-1">
                                              <strong>不适合生成：</strong>
                                              <ul className="list-disc list-inside">
                                                {caseSuggestion.unfit.map((item, idx) => <li key={idx}>{item}</li>)}
                                              </ul>
                                            </div>
                                          )}
                                          {caseSuggestion.reasons && caseSuggestion.reasons.length > 0 && (
                                            <div className="text-xs text-gray-700 mt-1">
                                              <strong>原因：</strong>
                                              <ul className="list-disc list-inside">
                                                {caseSuggestion.reasons.map((item, idx) => <li key={idx}>{item}</li>)}
                                              </ul>
                                            </div>
                                          )}
                                        </div>
                                      )}
                                      {pendingItems.length > 0 && (
                                        <div className="mt-2 p-2 bg-amber-50 rounded">
                                          <div className="text-[10px] uppercase tracking-wider text-amber-600 font-semibold">待确认信息</div>
                                          <div className="mt-1 space-y-0.5">
                                            {pendingItems.map((item, idx) => (
                                              <div key={idx} className="text-xs text-gray-700">
                                                <span className="text-amber-600 font-medium">{item.type}</span>
                                                {item.field && <span className="ml-2"><strong>{item.field}</strong> = {item.value}</span>}
                                                {item.reason && <span className="ml-2 text-gray-500">{item.reason}</span>}
                                              </div>
                                            ))}
                                          </div>
                                        </div>
                                      )}
                                      <div className="flex items-center gap-2 mt-2">
                                        {summary.status === 'PENDING_CONFIRMATION' && (
                                          <>
                                            <button
                                              onClick={() => handleConfirmSummary(summary)}
                                              disabled={confirmingSummaryId === summary.id}
                                              className="text-xs px-2 py-1 bg-green-50 text-green-600 rounded hover:bg-green-100 transition-colors"
                                            >
                                              {confirmingSummaryId === summary.id ? '确认中...' : '确认'}
                                            </button>
                                            <button
                                              onClick={() => setRejectingSummaryId(summary.id)}
                                              className="text-xs px-2 py-1 bg-red-50 text-red-600 rounded hover:bg-red-100 transition-colors"
                                            >
                                              驳回
                                            </button>
                                          </>
                                        )}
                                        {(summary.status !== 'CONFIRMED' && summary.status !== 'REJECTED') && (
                                          <>
                                            <button
                                              onClick={() => handleRegenerateSummary(summary)}
                                              disabled={!traceModelConfigId || generatingSummary}
                                              className="text-xs px-2 py-1 text-gray-500 hover:text-gray-800 transition-colors disabled:opacity-50"
                                            >
                                              重新生成
                                            </button>
                                            <button
                                              onClick={() => openEditSummary(summary)}
                                              className="text-xs px-2 py-1 text-gray-500 hover:text-gray-800 transition-colors"
                                            >
                                              编辑
                                            </button>
                                          </>
                                        )}
                                      </div>
                                      {rejectingSummaryId === summary.id && (
                                        <div className="mt-2 flex gap-2">
                                          <input
                                            type="text"
                                            value={rejectReason}
                                            onChange={e => setRejectReason(e.target.value)}
                                            placeholder="驳回原因"
                                            className="flex-1 h-7 px-2 bg-gray-50 border border-gray-200 rounded text-xs focus:bg-white focus:border-gray-400 outline-none"
                                          />
                                          <button
                                            onClick={() => handleRejectSummary(summary)}
                                            disabled={!rejectReason.trim()}
                                            className="px-2 py-1 bg-red-600 text-white text-xs rounded hover:bg-red-700 transition-colors disabled:opacity-50"
                                          >
                                            提交
                                          </button>
                                        </div>
                                      )}
                                      <div className="mt-2 text-[10px] text-gray-400 flex gap-2 flex-wrap">
                                        {summary.modelConfigId && <span>model={summary.modelConfigId}</span>}
                                        {summary.promptSnapshotId && <span>prompt={summary.promptSnapshotId}</span>}
                                        {summary.contextManifestId && <span>manifest={summary.contextManifestId}</span>}
                                        {summary.confirmedAt && <span>已确认 {fmtDate(summary.confirmedAt)}</span>}
                                        {summary.rejectedAt && <span>已驳回 {fmtDate(summary.rejectedAt)}</span>}
                                      </div>
                                    </div>
                                  );
                                })}
                              </div>
                            </div>
                          )}

                          {/* Generate Summary Button */}
                          <button
                            onClick={() => handleGenerateSummary(group)}
                            disabled={generatingSummary}
                            className="w-full py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 transition-colors disabled:opacity-50"
                          >
                            {generatingSummary ? '生成中...' : '生成摘要'}
                          </button>
                          {summaryRefreshSuggested && (
                            <p className="text-[11px] text-amber-600 mt-2">
                              已确认的修正建议已生效到当前轨迹清洗结果。点击上方“应用修正并重生成摘要”可同步摘要正文。
                            </p>
                          )}
                        </div>
                      )}

                      {/* Sessions Tab */}
                      {detailTab === 'sessions' && (
                        <div className="mt-3 space-y-2">
                          {groupDetail.sessions.map(session => (
                            <div key={session.id} className="bg-white rounded-lg border border-gray-200">
                              <div
                                className="flex items-center justify-between px-3 py-2 cursor-pointer hover:bg-gray-50 transition-colors"
                                onClick={() => setExpandedSessionId(expandedSessionId === session.id ? null : session.id)}
                              >
                                <div className="flex items-center gap-2">
                                  <span className={`w-2 h-2 rounded-full ${session.status === 'RECORDING' ? 'bg-green-500' : 'bg-gray-300'}`} />
                                  <span className="text-sm font-medium text-gray-900">{session.sessionName || `会话 #${session.id}`}</span>
                                </div>
                                <div className="flex items-center gap-2">
                                  {sessionIssueClips(session.id).length > 0 && (
                                    <button
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        jumpToIssueClip(sessionIssueClips(session.id)[0].id);
                                      }}
                                      className="text-[10px] px-2 py-0.5 rounded bg-amber-50 text-amber-700 hover:bg-amber-100 transition-colors"
                                    >
                                      问题片段 {sessionIssueClips(session.id).length}
                                    </button>
                                  )}
                                  <span className="text-xs text-gray-400">{fmtDate(session.createdAt)}</span>
                                  {session.screencastPath && (
                                    <button
                                      onClick={(e) => { e.stopPropagation(); setOpenSessionVideoId(openSessionVideoId === session.id ? null : session.id); }}
                                      className="text-xs text-blue-600 hover:text-blue-700"
                                    >
                                      {openSessionVideoId === session.id ? '关闭录屏' : '查看录屏'}
                                    </button>
                                  )}
                                </div>
                              </div>

                              {/* Session Events */}
                              {expandedSessionId === session.id && (
                                <div className="px-3 pb-3 border-t border-gray-100">
                                  <div className="mt-2 space-y-1">
                                    {sessionEvents(session.id).map(event => (
                                      <div key={event.id} className="text-xs py-1 flex items-start gap-2">
                                        <span className="font-mono text-gray-400 w-12 flex-shrink-0">{event.relativeMs}ms</span>
                                        <span className="text-gray-500 w-20 flex-shrink-0">{event.eventType}</span>
                                        <div className="flex-1 min-w-0">
                                          <span className="text-gray-700">{eventSummary(event)}</span>
                                          {hasLocatorDetails(event) && (
                                            <button
                                              onClick={() => {
                                                const next = new Set(expandedEventLocators);
                                                if (next.has(event.id)) next.delete(event.id);
                                                else next.add(event.id);
                                                setExpandedEventLocators(next);
                                              }}
                                              className="ml-2 text-[10px] text-gray-400 hover:text-gray-600 transition-colors"
                                            >
                                              {expandedEventLocators.has(event.id) ? '收起定位' : '展开定位'}
                                            </button>
                                          )}
                                          {expandedEventLocators.has(event.id) && hasLocatorDetails(event) && (
                                            <div className="mt-1 p-2 bg-gray-50 rounded text-[11px] space-y-0.5">
                                              {event.normalizedLocator && (
                                                <div><span className="text-gray-400">稳定定位：</span><code className="text-gray-700">{event.normalizedLocator}</code></div>
                                              )}
                                              {event.sectionTitle && (
                                                <div><span className="text-gray-400">所在分栏：</span>{event.sectionTitle}</div>
                                              )}
                                              {event.dialogTitle && (
                                                <div><span className="text-gray-400">所在弹窗：</span>{event.dialogTitle}</div>
                                              )}
                                              {event.objectLabel && (
                                                <div><span className="text-gray-400">所在对象：</span>{event.objectLabel}</div>
                                              )}
                                            </div>
                                          )}
                                        </div>
                                      </div>
                                    ))}
                                    {sessionEvents(session.id).length === 0 && (
                                      <div className="text-xs text-gray-400 py-2">
                                        暂无事件。可能这条会话刚创建就结束了，或者本次采集还没来得及写入页面操作。
                                      </div>
                                    )}
                                  </div>

                                  {/* Networks */}
                                  {sessionNetworks(session.id).length > 0 && (
                                    <div className="mt-2 pt-2 border-t border-gray-100">
                                      <h5 className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold mb-1">网络请求</h5>
                                      {sessionNetworks(session.id).map(net => (
                                        <div key={net.id} className="text-xs py-0.5 flex items-center gap-2">
                                          <span className={`text-[10px] px-1 rounded ${net.failed ? 'bg-red-50 text-red-600' : 'bg-green-50 text-green-600'}`}>
                                            {net.method || 'GET'}
                                          </span>
                                          <span className="text-gray-600 truncate">{net.url}</span>
                                          <span className="text-gray-400">{net.statusCode || '-'}</span>
                                        </div>
                                      ))}
                                    </div>
                                  )}
                                  {sessionNetworks(session.id).length === 0 && (
                                    <div className="mt-2 pt-2 border-t border-gray-100">
                                      <h5 className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold mb-1">网络请求</h5>
                                      <div className="text-xs text-gray-400">
                                        暂无网络摘要。若这次操作主要是前端页面交互，或请求尚未成功采集，这里会为空。
                                      </div>
                                    </div>
                                  )}

                                  {/* Screencast */}
                                  {openSessionVideoId === session.id && session.screencastPath && (
                                    <div className="mt-2 pt-2 border-t border-gray-100">
                                      <ScreencastPlayer sessionId={session.id} />
                                    </div>
                                  )}
                                </div>
                              )}
                            </div>
                          ))}
                          {groupDetail.sessions.length === 0 && (
                            <div className="text-center py-4 text-gray-400 text-sm">
                              暂无会话。先在左侧身份空间里开始一次采集，轨迹事件、录屏和网络请求会在这里沉淀。
                            </div>
                          )}
                        </div>
                      )}

                      {/* Issues Tab */}
                      {detailTab === 'issues' && (
                        <div className="mt-3 space-y-2">
                          {groupDetail.issueClips.map(clip => (
                            <div
                              key={clip.id}
                              ref={(node) => { issueClipRefs.current[clip.id] = node; }}
                              className={`bg-white rounded-lg border p-3 transition-colors ${
                                focusedIssueClipId === clip.id ? 'border-amber-300 ring-2 ring-amber-100' : 'border-gray-200'
                              }`}
                            >
                              <div className="flex items-center justify-between">
                                <div>
                                  <div className="text-sm font-medium text-gray-900">{clip.title || `问题片段 #${clip.id}`}</div>
                                  <div className="text-xs text-gray-500">{clip.description}</div>
                                </div>
                                <div className="flex items-center gap-2">
                                  {clip.traceSessionId != null && (
                                    <button
                                      onClick={() => jumpToSession(clip.traceSessionId!)}
                                      className="text-[10px] px-2 py-1 bg-gray-100 text-gray-700 rounded hover:bg-gray-200 transition-colors"
                                    >
                                      定位会话
                                    </button>
                                  )}
                                  <button
                                    onClick={() => handleGenerateSummary(group, clip.id)}
                                    disabled={!traceModelConfigId || generatingSummary}
                                    className="text-[10px] px-2 py-1 bg-slate-900 text-white rounded hover:bg-slate-800 transition-colors disabled:opacity-50"
                                  >
                                    {generatingSummary ? '生成中...' : '生成摘要'}
                                  </button>
                                  <button
                                    onClick={() => handleGenerateCases(group, clip.id)}
                                    disabled={!traceModelConfigId || generatingCases}
                                    className="text-[10px] px-2 py-1 bg-white text-gray-700 border border-gray-200 rounded hover:border-gray-400 transition-colors disabled:opacity-50"
                                  >
                                    {generatingCases ? '生成中...' : '生成用例'}
                                  </button>
                                  <button
                                    onClick={() => setOpenClipVideoId(openClipVideoId === clip.id ? null : clip.id)}
                                    className="text-xs text-blue-600 hover:text-blue-700"
                                  >
                                    {openClipVideoId === clip.id ? '关闭' : '查看'}
                                  </button>
                                </div>
                              </div>
                              <div className="text-xs text-gray-400 mt-1">
                                {formatDurationMs(clip.clipStartRelativeMs)} - {formatDurationMs(clip.clipEndRelativeMs)}
                              </div>
                              {openClipVideoId === clip.id && clip.traceSessionId != null && clip.screencastClipStartMs != null && clip.screencastClipEndMs != null && (
                                <ScreencastPlayer
                                  sessionId={clip.traceSessionId}
                                  clipStartMs={clip.screencastClipStartMs}
                                  clipEndMs={clip.screencastClipEndMs}
                                />
                              )}

                              {/* Clip Summaries */}
                              {clipSummaries(clip.id).length > 0 && (
                                <div className="mt-3 space-y-2">
                                  <div className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold">
                                    {clip.title || '该问题片段'}摘要
                                  </div>
                                  {clipSummaries(clip.id).map(summary => (
                                    <div key={summary.id} className="border border-gray-100 rounded-lg p-2">
                                      <div className="flex items-center gap-2">
                                        <span className={`text-[10px] px-1.5 py-0.5 rounded ${
                                          summary.status === 'CONFIRMED' ? 'bg-green-50 text-green-600' :
                                          summary.status === 'REJECTED' ? 'bg-red-50 text-red-600' :
                                          'bg-yellow-50 text-yellow-600'
                                        }`}>{summary.status}</span>
                                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 text-gray-600">{summary.validityLabel}</span>
                                      </div>
                                      {summary.overview && (
                                        <div className="text-xs font-medium text-gray-900 mt-1">{summary.overview}</div>
                                      )}
                                      <div className="mt-1 flex items-center gap-3">
                                        <button
                                          onClick={() => jumpToSummary(summary.id)}
                                          className="text-[10px] text-sky-600 hover:text-sky-800 transition-colors"
                                        >
                                          在概览中定位摘要
                                        </button>
                                        <button
                                          onClick={() => {
                                            jumpToSummary(summary.id);
                                            setSummaryRefreshSuggested(false);
                                          }}
                                          className="text-[10px] text-gray-500 hover:text-gray-800 transition-colors"
                                        >
                                          去概览继续处理
                                        </button>
                                      </div>
                                    </div>
                                  ))}
                                </div>
                              )}

                              {/* Clip Generated Cases */}
                              {clipTraceGeneratedCases(clip.id).length > 0 && (
                                <div className="mt-3 space-y-2">
                                  <div className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold">
                                    {clip.title || '该问题片段'}用例草稿
                                  </div>
                                  {clipTraceGeneratedCases(clip.id).map(c => (
                                    <div key={c.id} className="border border-gray-100 rounded-lg p-2">
                                      <div className="text-sm font-medium text-gray-900">{c.caseTitle}</div>
                                      <div className="text-xs text-gray-500">模块：{c.moduleName || '-'} · {c.caseType}</div>
                                      <div className="flex gap-2 mt-1">
                                        <button
                                          onClick={() => handleSubmitCase(c.id)}
                                          disabled={submittingCaseId === c.id}
                                          className="text-[10px] px-2 py-0.5 bg-green-50 text-green-600 rounded hover:bg-green-100 transition-colors disabled:opacity-50"
                                        >
                                          {submittingCaseId === c.id ? '提交中...' : '提交正式库'}
                                        </button>
                                      </div>
                                    </div>
                                  ))}
                                </div>
                              )}
                            </div>
                          ))}
                          {groupDetail.issueClips.length === 0 && (
                            <div className="text-center py-4 text-gray-400 text-sm">
                              暂无问题片段。出现异常步骤或手动截取问题时，会在这里沉淀缺陷复现片段。
                            </div>
                          )}
                        </div>
                      )}

                      {/* Assets Tab */}
                      {detailTab === 'assets' && (
                        <div className="mt-3 space-y-3">
                          {/* Generated Cases */}
                          <div className="bg-white rounded-lg border border-gray-200 p-3">
                            <div className="flex items-center justify-between mb-2">
                              <h4 className="text-xs font-semibold uppercase tracking-wider text-gray-400">生成用例</h4>
                              <div className="flex items-center gap-2">
                                <button
                                  onClick={() => handleGenerateCases(group)}
                                  disabled={generatingCases}
                                  className="text-xs px-2 py-1 bg-slate-900 text-white rounded hover:bg-slate-800 transition-colors disabled:opacity-50"
                                >
                                  {generatingCases ? '生成中...' : '生成用例'}
                                </button>
                              </div>
                            </div>
                            <div className="space-y-2">
                              {groupTraceGeneratedCases(group.id).map(c => (
                                <div key={c.id} className="border border-gray-100 rounded-lg p-2">
                                  <div className="text-sm font-medium text-gray-900">{c.caseTitle}</div>
                                  <div className="text-xs text-gray-500">{c.caseType} · {c.priority}</div>
                                  <div className="flex gap-2 mt-1">
                                    <button
                                      onClick={() => handleSubmitCase(c.id)}
                                      disabled={submittingCaseId === c.id}
                                      className="text-xs px-2 py-0.5 bg-green-50 text-green-600 rounded hover:bg-green-100 transition-colors disabled:opacity-50"
                                    >
                                      {submittingCaseId === c.id ? '提交中...' : '提交正式库'}
                                    </button>
                                  </div>
                                </div>
                              ))}
                              {groupTraceGeneratedCases(group.id).length === 0 && (
                                <div className="text-xs text-gray-400 py-2">
                                  暂无轨迹草稿，生成后可在当前采集组资产区继续查看并提交正式库。
                                </div>
                              )}
                              {traceGeneratedCaseTotal > 20 && (
                                <div className="flex items-center justify-between border-t border-gray-100 pt-2 text-xs text-gray-500">
                                  <span>共 {traceGeneratedCaseTotal} 条，第 {traceGeneratedCasePage + 1} / {Math.ceil(traceGeneratedCaseTotal / 20)} 页</span>
                                  <div className="flex gap-2">
                                    <button
                                      onClick={() => loadTraceGeneratedCases(group.id, traceGeneratedCasePage - 1)}
                                      disabled={traceGeneratedCasePage === 0}
                                      className="rounded border border-gray-200 px-2 py-1 disabled:opacity-40"
                                    >上一页</button>
                                    <button
                                      onClick={() => loadTraceGeneratedCases(group.id, traceGeneratedCasePage + 1)}
                                      disabled={(traceGeneratedCasePage + 1) * 20 >= traceGeneratedCaseTotal}
                                      className="rounded border border-gray-200 px-2 py-1 disabled:opacity-40"
                                    >下一页</button>
                                  </div>
                                </div>
                              )}
                            </div>
                          </div>
                        </div>
                      )}

                      {/* Templates Tab */}
                      {detailTab === 'templates' && (
                        <div className="mt-3 space-y-3">
                          {/* Skills */}
                          <div className="bg-white rounded-lg border border-gray-200 p-3">
                            <div className="flex items-center justify-between mb-2">
                              <h4 className="text-xs font-semibold uppercase tracking-wider text-gray-400">Skills</h4>
                              <button
                                onClick={() => handleGenerateSkill(group)}
                                disabled={generatingSkill}
                                className="text-xs px-2 py-1 bg-slate-900 text-white rounded hover:bg-slate-800 transition-colors disabled:opacity-50"
                              >
                                {generatingSkill ? '生成中...' : '生成 Skill'}
                              </button>
                            </div>
                            <div className="space-y-1">
                              {groupSkills.map(s => (
                                <div key={s.id} className="text-sm text-gray-700 py-1">{s.skillName}</div>
                              ))}
                              {groupSkills.length === 0 && (
                                <div className="text-xs text-gray-400 py-1">
                                  暂无 Skills。确认过的轨迹与摘要会更适合继续沉淀成 Skill 模板。
                                </div>
                              )}
                            </div>
                          </div>

                          {/* Tools */}
                          <div className="bg-white rounded-lg border border-gray-200 p-3">
                            <div className="flex items-center justify-between mb-2">
                              <h4 className="text-xs font-semibold uppercase tracking-wider text-gray-400">Tools</h4>
                              <button
                                onClick={() => handleGenerateTool(group)}
                                disabled={generatingTool}
                                className="text-xs px-2 py-1 bg-slate-900 text-white rounded hover:bg-slate-800 transition-colors disabled:opacity-50"
                              >
                                {generatingTool ? '生成中...' : '生成 Tool'}
                              </button>
                            </div>
                            <div className="space-y-1">
                              {groupTools.map(t => (
                                <div key={t.id} className="text-sm text-gray-700 py-1">{t.toolName}</div>
                              ))}
                              {groupTools.length === 0 && (
                                <div className="text-xs text-gray-400 py-1">
                                  暂无 Tools。稳定的操作链路更适合继续沉淀成工具模板。
                                </div>
                              )}
                            </div>
                          </div>

                          {/* Corrections */}
                          {groupCorrections.length > 0 && (
                            <div className="bg-white rounded-lg border border-gray-200 p-3">
                              <h4 className="text-xs font-semibold uppercase tracking-wider text-gray-400 mb-2">修正建议</h4>
                              <div className="space-y-2">
                                {groupCorrections.map(c => (
                                  <div key={c.id} className="border border-gray-100 rounded-lg p-2">
                                    <div className="text-xs text-gray-500">来源：{c.sourceText}</div>
                                    <div className="text-sm text-gray-900">建议：{c.candidateValue}</div>
                                    {c.status === 'PENDING_CONFIRMATION' && (
                                      <div className="flex gap-2 mt-1">
                                        <button
                                          onClick={() => handleConfirmCorrection(c)}
                                          disabled={confirmingCorrectionId === c.id}
                                          className="text-xs px-2 py-0.5 bg-green-50 text-green-600 rounded hover:bg-green-100 transition-colors"
                                        >
                                          {confirmingCorrectionId === c.id ? '确认中...' : '确认'}
                                        </button>
                                        <button
                                          onClick={() => setRejectingCorrectionId(c.id)}
                                          className="text-xs px-2 py-0.5 bg-red-50 text-red-600 rounded hover:bg-red-100 transition-colors"
                                        >
                                          驳回
                                        </button>
                                      </div>
                                    )}
                                    {rejectingCorrectionId === c.id && (
                                      <div className="mt-2 flex gap-2">
                                        <input
                                          type="text"
                                          value={correctionRejectReason}
                                          onChange={e => setCorrectionRejectReason(e.target.value)}
                                          placeholder="驳回原因"
                                          className="flex-1 h-7 px-2 bg-gray-50 border border-gray-200 rounded text-xs"
                                        />
                                        <button
                                          onClick={() => handleRejectCorrection(c)}
                                          className="px-2 py-0.5 bg-red-600 text-white text-xs rounded"
                                        >
                                          提交
                                        </button>
                                      </div>
                                    )}
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              ))}
              {pagedGroups.length === 0 && (
                <div className="text-center py-8 text-gray-400 text-sm">
                  {groups.length === 0 ? '暂无采集组' : '当前筛选条件下没有采集组'}
                </div>
              )}
            </div>

            {/* Pagination */}
            {filteredGroups.length > groupPageSize && (
	              <div className="flex flex-col gap-2 border-t border-gray-200 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
                <span className="text-xs text-gray-400">共 {filteredGroups.length} 条</span>
	                <div className="flex flex-wrap items-center gap-2">
                  <button
                    onClick={() => setGroupPage(p => Math.max(1, p - 1))}
                    disabled={groupPage <= 1}
                    className="text-xs px-2 py-1 border border-gray-200 rounded hover:border-gray-400 transition-colors disabled:opacity-30"
                  >
                    上一页
                  </button>
                  <span className="text-xs text-gray-500">{groupPage} / {totalGroupPages}</span>
                  <button
                    onClick={() => setGroupPage(p => Math.min(totalGroupPages, p + 1))}
                    disabled={groupPage >= totalGroupPages}
                    className="text-xs px-2 py-1 border border-gray-200 rounded hover:border-gray-400 transition-colors disabled:opacity-30"
                  >
                    下一页
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Libraries */}
	      <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-2">
        <div className="bg-white rounded-lg border border-gray-200 p-4">
	          <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
	            <div className="min-w-0">
              <h3 className="text-sm font-semibold text-gray-900">本地用例库预览</h3>
              <div className="text-xs text-gray-400 mt-1">仅预览最近 5 条项目级草稿；完整整理请进入统一本地用例库。</div>
            </div>
	            <div className="flex flex-wrap items-center gap-3 sm:justify-end">
              <Link to={`/projects/${projectId}/local-cases`} className="text-xs text-gray-500 hover:text-gray-900 transition-colors">进入完整列表</Link>
              <button onClick={loadLocalCases} className="text-xs text-gray-500 hover:text-gray-900 transition-colors">刷新</button>
            </div>
          </div>
          {recentLocalCases.length === 0 ? (
            <div className="text-center py-6 text-gray-400 text-sm">暂无本地草稿</div>
          ) : (
            <div className="space-y-2">
              {recentLocalCases.map(c => (
	                <div key={c.id} className="flex flex-col gap-2 border border-gray-100 rounded-lg p-3 sm:flex-row sm:items-center sm:justify-between">
	                  <div className="min-w-0">
	                    <div className="break-words text-sm font-medium text-gray-900">{c.caseTitle}</div>
	                    <div className="mt-1 flex min-w-0 flex-wrap items-center gap-2 text-xs text-gray-500">
                      <span className={`inline-flex items-center px-2 py-0.5 rounded font-medium ${localCaseSourceClass(c.sourceType)}`}>
                        {localCaseSourceLabel(c.sourceType)}
                      </span>
                      <span className="truncate">{c.moduleName || c.caseType || '未分类'}</span>
                      {c.sessionId ? <span>会话 #{c.sessionId}</span> : null}
                    </div>
                  </div>
	                  <StatusBadge status={c.caseStatus} label={c.caseStatus} />
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="bg-white rounded-lg border border-gray-200 p-4">
	          <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
	            <div className="min-w-0">
              <h3 className="text-sm font-semibold text-gray-900">正式用例库预览</h3>
              <div className="text-xs text-gray-400 mt-1">仅预览最近 5 条项目级正式用例，完整内容请进入正式库查看。</div>
            </div>
	            <div className="flex flex-wrap items-center gap-3 sm:justify-end">
              <Link to={`/projects/${projectId}/formal-cases`} className="text-xs text-gray-500 hover:text-gray-900 transition-colors">进入完整列表</Link>
              <button onClick={loadFormalCases} className="text-xs text-gray-500 hover:text-gray-900 transition-colors">刷新</button>
            </div>
          </div>
          {formalCases.length === 0 ? (
            <div className="text-center py-6 text-gray-400 text-sm">暂无正式用例</div>
          ) : (
            <div className="space-y-2">
              {recentFormalCases.map(c => (
	                <div key={c.id} className="flex flex-col gap-2 border border-gray-100 rounded-lg p-3 sm:flex-row sm:items-center sm:justify-between">
	                  <div className="min-w-0">
	                    <div className="break-words text-sm font-medium text-gray-900">{c.caseTitle}</div>
	                    <div className="break-words text-xs text-gray-500">{c.caseNo} · 模块：{c.moduleName || '-'} · {c.priority}</div>
                  </div>
                  <StatusBadge status={c.caseStatus} label={c.caseStatus} />
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Profile Form Drawer */}
      {showProfileForm && (
        <ProfileForm
          editing={editingProfile}
          onClose={() => setShowProfileForm(false)}
          onSave={() => { loadProfiles(); }}
          projectId={projectId}
          showToast={showToast}
        />
      )}

      {/* Naming Dialog */}
      {namingDialog.visible && (
        <Modal
          title="命名轨迹"
          onClose={() => setNamingDialog({ visible: false, sessionId: 0, value: '' })}
          footer={
            <>
              <button
                onClick={() => setNamingDialog({ visible: false, sessionId: 0, value: '' })}
                className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 transition-colors"
              >
                跳过
              </button>
              <button
                onClick={confirmNaming}
                className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 transition-colors"
              >
                保存
              </button>
            </>
          }
        >
          <input
            type="text"
            value={namingDialog.value}
            onChange={e => setNamingDialog({ ...namingDialog, value: e.target.value })}
            className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:bg-white focus:border-gray-400 outline-none transition-all"
            placeholder="输入轨迹名称"
            autoFocus
          />
        </Modal>
      )}

      {/* Summary Edit Dialog */}
      {editingSummary && (
        <Modal
          title="编辑摘要"
          onClose={closeEditSummary}
          footer={
            <>
              <button
                onClick={closeEditSummary}
                className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 transition-colors"
              >
                取消
              </button>
              <button
                onClick={handleSaveSummaryEdit}
                disabled={savingSummaryEdit}
                className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 transition-colors disabled:opacity-50"
              >
                {savingSummaryEdit ? '保存中...' : '保存'}
              </button>
            </>
          }
        >
          <div className="space-y-3 max-h-[70vh] overflow-y-auto pr-1">
            {[
              { key: 'overview', label: '轨迹概述', rows: 1 },
              { key: 'businessSummary', label: '业务流程摘要（JSON 数组字符串或普通文本）', rows: 3 },
              { key: 'keyStepsJson', label: '关键步骤（JSON 数组字符串）', rows: 3 },
              { key: 'keyApiJson', label: '关键接口（JSON 数组字符串）', rows: 3 },
              { key: 'exceptionSummary', label: '异常摘要', rows: 2 },
              { key: 'caseGenerationSuggestionJson', label: '用例生成建议（JSON 对象字符串）', rows: 3 },
              { key: 'pendingConfirmationJson', label: '待确认信息（JSON 数组字符串）', rows: 2 },
            ].map(field => (
              <div key={field.key}>
                <label className="block text-xs text-gray-500 mb-1">{field.label}</label>
                {field.rows > 1 ? (
                  <textarea
                    value={(summaryEditForm as any)[field.key]}
                    onChange={e => setSummaryEditForm({ ...summaryEditForm, [field.key]: e.target.value })}
                    rows={field.rows}
                    className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:bg-white focus:border-gray-400 outline-none resize-none"
                  />
                ) : (
                  <input
                    type="text"
                    value={(summaryEditForm as any)[field.key]}
                    onChange={e => setSummaryEditForm({ ...summaryEditForm, [field.key]: e.target.value })}
                    className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:bg-white focus:border-gray-400 outline-none"
                  />
                )}
              </div>
            ))}
            <div>
              <label className="block text-xs text-gray-500 mb-1">有效性标签</label>
              <select
                value={summaryEditForm.validityLabel}
                onChange={e => setSummaryEditForm({ ...summaryEditForm, validityLabel: e.target.value })}
                className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:bg-white focus:border-gray-400 outline-none"
              >
                {['STANDARD', 'PERSONAL_ONLY', 'BUG_REPRO', 'DEMO', 'DIRTY_DATA', 'TO_CONFIRM'].map(v => (
                  <option key={v} value={v}>{v}</option>
                ))}
              </select>
            </div>
          </div>
        </Modal>
      )}

      {pendingDeleteGroup && (
        <Modal
          title="删除采集组"
          onClose={() => !deletingGroup && setPendingDeleteGroup(null)}
          footer={
            <>
              <button
                onClick={() => setPendingDeleteGroup(null)}
                disabled={deletingGroup}
                className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 transition-colors disabled:opacity-50"
              >
                取消
              </button>
              <button
                onClick={() => handleDeleteGroup(pendingDeleteGroup)}
                disabled={deletingGroup}
                className="px-4 py-2 bg-red-600 text-white text-sm font-semibold rounded-lg hover:bg-red-700 transition-colors disabled:opacity-50"
              >
                {deletingGroup ? '删除中...' : '确认删除'}
              </button>
            </>
          }
        >
          <div className="space-y-3">
            <p className="text-sm text-gray-700">
              你将删除采集组 <span className="font-semibold text-gray-900">{pendingDeleteGroup.groupName}</span>。
              删除后，这组轨迹及其关联的会话展开入口会从当前列表中移除。
            </p>
            <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-xs text-red-700">
              {pendingDeleteGroup.status === 'RECORDING'
                ? '当前采集组仍显示为采集中。系统会先尝试停止仍在运行的会话，再执行删除。'
                : '该采集组当前已停止，确认后会直接执行删除。'}
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
