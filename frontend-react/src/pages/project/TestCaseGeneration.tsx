import { useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react';
import { Link } from 'react-router-dom';
import { useParams } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import Modal from '../../components/Modal';
import AffectedCasesModal from '../../components/AffectedCasesModal';
import {
  listGenerationSessions, createGenerationSession, updateGenerationSession, archiveGenerationSession, listGenerationMessages, sendGenerationMessage,
  listGenerationDrafts, deprecateLocalCase, duplicateLocalCase, getLatestGenerationAnalysis, listGenerationAnalyses, listEnabledModelConfigs, listEnabledPromptTemplates,
  startSessionCaseGenerationTask, startSessionRequirementAnalysisTask, startSessionIncrementalGenerationTask, getAsyncGenerationTask, retryAsyncGenerationTask, cancelAsyncGenerationTask,
  confirmGenerationRequirementScope, confirmGenerationTestPointScope,
  type GenerationSession, type GenerationMessage, type ModelConfigRecord, type CaseDraft, type PromptTemplateRecord, type RequirementAnalysis,
  type AsyncGenerationTask, type AsyncGenerationTaskStage, type RequirementScopeDecision, type TestPointScopeDecision
} from '../../services/api';
import { statusLabel as uiStatusLabel } from '../../utils/displayLabels';
import {
  listAttachments as listGenerationAttachments,
  uploadAttachment as uploadGenerationAttachment,
  type GenerationAttachment,
} from '../../services/generationApi';

function safeJsonParse<T>(value: unknown): T | null {
  if (value == null) return null;
  if (typeof value === 'object') return value as T;
  try {
    return JSON.parse(String(value)) as T;
  } catch {
    return null;
  }
}

function displayText(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) return value.map(displayText).filter(Boolean).join('、');
  if (typeof value === 'object') {
    const obj = value as Record<string, unknown>;
    const preferred = [
      'question', 'title', 'description', 'reason', 'impact', 'condition', 'source_basis',
      'assumption', 'module', 'page', 'field', 'flow', 'role', 'name', 'content', 'value',
    ];
    const parts = preferred
      .filter(key => obj[key] != null)
      .map(key => `${labelForObjectKey(key)}：${displayText(obj[key])}`)
      .filter(Boolean);
    if (parts.length > 0) return parts.join('；');
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

function displayList(value: unknown): string[] {
  if (value == null) return [];
  if (Array.isArray(value)) return value.map(displayText).map(item => item.trim()).filter(Boolean);
  const text = displayText(value).trim();
  return text ? [text] : [];
}

const TEST_POINT_LABELS: Record<string, string> = {
  MAIN_FLOW: '主流程',
  BRANCH: '条件分支',
  BOUNDARY: '边界条件',
  EXCEPTION: '异常场景',
  STATE: '状态流转',
  DATA: '数据一致性',
  AUTH: '权限',
  CONCURRENCY: '并发',
  IDEMPOTENT: '幂等',
  FUNCTIONAL: '功能层',
  BOUNDARY_SUPPLEMENT: '边界补充层',
  CORE: '核心',
  EXTENDED: '扩展',
  RISK: '风险',
  POSITIVE: '正向场景', NEGATIVE: '反向场景',
  COMBINATION: '组合场景', RECOVERY: '恢复与一致性',
  VALID_FLOW: '有效主流程', EXPECTED_BUSINESS_RESULT: '业务结果',
  CONDITION_TRUE: '条件成立', CONDITION_FALSE: '条件不成立', KEY_COMBINATIONS: '关键条件组合',
  VALID_EQUIVALENCE_CLASS: '有效等价类', INVALID_EQUIVALENCE_CLASS: '无效等价类',
  AT_BOUNDARY: '边界值', JUST_INSIDE_BOUNDARY: '边界内值', JUST_OUTSIDE_BOUNDARY: '边界外值',
  FAILURE_TRIGGER: '失败触发', USER_FEEDBACK: '错误反馈', NO_UNEXPECTED_SIDE_EFFECT: '无异常副作用',
  VALID_TRANSITION: '合法状态流转', INVALID_TRANSITION: '非法状态流转', RECOVERY_OR_ROLLBACK: '恢复或回滚',
  WRITE_RESULT: '写入结果', READ_BACK_CONSISTENCY: '回读一致性', FAILURE_CONSISTENCY: '失败一致性',
  AUTHORIZED: '有权限', UNAUTHORIZED: '无权限', DATA_SCOPE_ISOLATION: '数据范围隔离',
  CONCURRENT_CONFLICT: '并发冲突', CONSISTENT_FINAL_RESULT: '最终结果一致',
  NO_DUPLICATE_SIDE_EFFECT: '无重复副作用', FIRST_REQUEST: '首次请求', REPEATED_REQUEST: '重复请求',
};

const SCOPE_RECOMMENDATION_LABELS: Record<string, string> = {
  IN_SCOPE: '生成',
  REFERENCE_ONLY: '仅参考',
  OUT_OF_SCOPE: '排除',
  NEEDS_CONFIRMATION: '人工判断',
};

function testPointLabel(value: unknown): string {
  const raw = displayText(value).trim();
  return TEST_POINT_LABELS[raw.toUpperCase()] || raw;
}

function scenarioTypeLabel(value: unknown): string {
  const raw = displayText(value).trim().toUpperCase();
  return ({
    POSITIVE: '正向场景', NEGATIVE: '反向场景', BOUNDARY: '边界场景',
    COMBINATION: '组合场景', STATE: '状态场景', RECOVERY: '恢复与一致性',
  } as Record<string, string>)[raw] || raw;
}

function formatRuleConfidence(value: unknown): string | null {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return null;
  const percent = numeric >= 0 && numeric <= 1 ? numeric * 100 : numeric;
  return `${Math.round(percent * 10) / 10}%`;
}

function labelForObjectKey(key: string) {
  const labels: Record<string, string> = {
    question: '问题',
    title: '标题',
    description: '描述',
    reason: '原因',
    impact: '影响',
    condition: '条件',
    source_basis: '依据',
    assumption: '假设',
    module: '模块',
    page: '页面',
    field: '字段',
    flow: '流程',
    role: '角色',
    name: '名称',
    content: '内容',
    value: '值',
  };
  return labels[key] || key;
}

function parseAnalysisResult<T extends Record<string, any>>(value: unknown): T | null {
  const parsed = safeJsonParse<Record<string, any>>(value);
  if (!parsed) return null;
  if (parsed.analysis && typeof parsed.analysis === 'object' && !Array.isArray(parsed.analysis)) {
    return { ...parsed.analysis, ...parsed } as T;
  }
  return parsed as T;
}

function normalizeMessageRole(role?: string | null) {
  return (role || '').trim().toUpperCase();
}

function isUserRole(role?: string | null) {
  return normalizeMessageRole(role) === 'USER';
}

function messageStageLabel(stage?: string | null) {
  const normalized = (stage || '').trim().toUpperCase();
  const labels: Record<string, string> = {
    USER_INPUT: '用户输入',
    SYSTEM_ASK_TOM_MODE: '选择分析方式',
    SYSTEM_ANALYSIS_START: '需求分析中',
    REQUIREMENT_ANALYSIS_RESULT: '需求分析结果',
    CLARIFICATION_QUESTION: '等待确认',
    SYSTEM_CASE_GENERATING: '用例生成中',
    CASE_RESULT: '用例生成结果',
    OPERATION_HINT: '操作提示',
    ERROR: '异常提示',
  };
  return labels[normalized] || stage || '';
}

function isRequirementAnalysisStage(stage?: string | null) {
  return (stage || '').trim().toUpperCase() === 'REQUIREMENT_ANALYSIS_RESULT';
}

type CoverageDimensionValue = number | {
  count?: number;
  items?: string[];
};

type CoverageMatrixRow = {
  module?: string;
  main_flow?: CoverageDimensionValue;
  branch?: CoverageDimensionValue;
  boundary?: CoverageDimensionValue;
  exception?: CoverageDimensionValue;
  state?: CoverageDimensionValue;
  data?: CoverageDimensionValue;
  auth?: CoverageDimensionValue;
  concurrency?: CoverageDimensionValue;
  idempotent?: CoverageDimensionValue;
  total?: number;
};

type CoverageDimensionKey = Exclude<keyof CoverageMatrixRow, 'module' | 'total'>;

const COVERAGE_DIMENSIONS: Array<[CoverageDimensionKey, string]> = [
  ['main_flow', '主流程'],
  ['branch', '分支'],
  ['boundary', '边界'],
  ['exception', '异常'],
  ['state', '状态'],
  ['data', '数据'],
  ['auth', '权限'],
  ['concurrency', '并发'],
  ['idempotent', '幂等'],
];

function coverageValue(row: CoverageMatrixRow, key: CoverageDimensionKey | 'total') {
  const value = row[key];
  if (typeof value === 'number') return value;
  if (value && typeof value === 'object' && typeof value.count === 'number') return value.count;
  return 0;
}

function coverageItems(row: CoverageMatrixRow, key: CoverageDimensionKey) {
  const value = row[key];
  if (value && typeof value === 'object' && Array.isArray(value.items)) {
    return value.items.map(displayText).map(item => item.trim()).filter(Boolean);
  }
  return [];
}

function CoverageMatrix({ rows, compact = false }: { rows?: CoverageMatrixRow[]; compact?: boolean }) {
  if (!rows || rows.length === 0) return null;
  const total = rows.reduce((sum, row) => sum + coverageValue(row, 'total'), 0);
  const modules = rows.length;

  return (
    <details className={compact ? 'rounded-lg border border-violet-100 bg-violet-50/60 p-2' : 'rounded-lg border border-gray-200 bg-gray-50/70 p-2'}>
      <summary className="cursor-pointer select-none list-none">
        <div className="flex items-center justify-between gap-2">
          <span className={compact ? 'text-[11px] font-semibold text-violet-800' : 'font-semibold text-gray-900'}>
            {compact ? '覆盖矩阵' : '【覆盖矩阵】'}
          </span>
          <span className="shrink-0 rounded bg-white px-2 py-0.5 text-[11px] text-gray-500">
            {modules} 模块 / 合计 {total}
          </span>
        </div>
      </summary>
      <div className="mt-2 space-y-2">
        {rows.map((row, idx) => {
          const rowTotal = coverageValue(row, 'total') || COVERAGE_DIMENSIONS.reduce((sum, [key]) => sum + coverageValue(row, key), 0);
          const emptyDimensions = COVERAGE_DIMENSIONS
            .filter(([key]) => coverageValue(row, key) === 0)
            .map(([, label]) => label);
          return (
            <div key={`${row.module || 'module'}-${idx}`} className="rounded bg-white/80 p-2 text-xs text-gray-700">
              <div className="mb-1 flex items-center justify-between gap-2">
                <span className="break-words font-medium text-gray-900">{row.module || '未命名模块'}</span>
                <span className="shrink-0 text-[11px] text-gray-500">合计 {rowTotal}</span>
              </div>
              <div className="grid grid-cols-3 gap-1 sm:grid-cols-4">
                {COVERAGE_DIMENSIONS.map(([key, label]) => (
                  <div key={key} className="rounded border border-gray-100 bg-gray-50 px-1.5 py-1">
                    <span className="text-gray-500">{label}</span>
                    <span className="ml-1 font-semibold text-gray-900">{coverageValue(row, key)}</span>
                  </div>
                ))}
              </div>
              <div className="mt-2 space-y-1.5">
                {COVERAGE_DIMENSIONS.map(([key, label]) => {
                  const items = coverageItems(row, key);
                  if (items.length === 0) return null;
                  return (
                    <div key={`${key}-items`} className="rounded border border-gray-100 bg-white px-2 py-1.5">
                      <div className="mb-1 text-[11px] font-medium text-gray-600">{label}明细</div>
                      <ul className="space-y-0.5">
                        {items.map((item, itemIdx) => (
                          <li key={itemIdx} className="break-words text-[11px] leading-relaxed text-gray-700">
                            - {item}
                          </li>
                        ))}
                      </ul>
                    </div>
                  );
                })}
              </div>
              {emptyDimensions.length > 0 && (
                <div className="mt-1.5 break-words text-[11px] text-amber-700">
                  未覆盖或待确认：{emptyDimensions.join('、')}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </details>
  );
}

function buildOptimisticUserMessage(sessionId: number, content: string): GenerationMessage {
  return {
    id: -Date.now(),
    sessionId,
    role: 'USER',
    content,
    structuredPayload: null,
    analysisVersion: 0,
    stage: 'USER_INPUT',
    createdAt: new Date().toISOString(),
  };
}

function mergeSentMessages(
  current: GenerationMessage[],
  optimisticMessage: GenerationMessage,
  incomingMessages: GenerationMessage[],
) {
  const withoutOptimistic = current.filter(item => item.id !== optimisticMessage.id);
  const hasPersistedUser = incomingMessages.some(item => (
    isUserRole(item.role) && (item.content || '').trim() === optimisticMessage.content.trim()
  ));
  const messagesToAppend = hasPersistedUser ? incomingMessages : [optimisticMessage, ...incomingMessages];
  const seenPersistedIds = new Set(withoutOptimistic.filter(item => item.id > 0).map(item => item.id));

  return messagesToAppend.reduce<GenerationMessage[]>((acc, item) => {
    if (item.id > 0 && seenPersistedIds.has(item.id)) {
      return acc;
    }
    if (item.id > 0) seenPersistedIds.add(item.id);
    acc.push(item);
    return acc;
  }, [...withoutOptimistic]);
}

function normalizedCommand(content: string) {
  return content.trim().replace(/[\s,.，。、!?！？:：;；]/g, '');
}

function isCaseGenerationIntent(content: string) {
  const normalized = normalizedCommand(content);
  return [
    '生成用例', '生成测试用例', '开始生成用例', '重新生成', '重新生成用例', '全量重新生成',
    '直接生成', '直接生成用例', '跳过确认', '跳过确认生成用例', '跳过确认直接生成用例',
    '按当前内容生成', '不用确认直接生成', '生成吧', '请生成', '输出用例', '输出吧',
  ].includes(normalized);
}

function isReanalysisIntent(content: string) {
  const normalized = normalizedCommand(content);
  return ['需求分析', '重新分析', '再分析', '更新分析', '重新生成需求分析', '重新做需求分析', '重新分析需求', '重新整理分析'].includes(normalized);
}

function isConfirmIntent(content: string) {
  const normalized = normalizedCommand(content).toLowerCase();
  return ['确认', '确认分析', '确认无误', '没问题', '没有问题', 'ok', 'okay', '好的', '可以', '对', '行', '嗯'].includes(normalized);
}

function asyncTaskRunning(task?: AsyncGenerationTask | null) {
  return task?.status === 'PENDING' || task?.status === 'RUNNING';
}

function asyncTaskFinished(task?: AsyncGenerationTask | null) {
  return task?.status === 'SUCCEEDED'
    || task?.status === 'FAILED'
    || task?.status === 'TIMEOUT'
    || task?.status === 'CANCELED';
}


function isRequirementAnalysisTask(task?: AsyncGenerationTask | null) {
  return task?.taskType === 'REQUIREMENT_ANALYSIS'
    || task?.taskType === 'REQUIREMENT_SCOPE_CONTINUATION'
    || task?.taskType === 'TEST_POINT_SCOPE_CONTINUATION';
}

function isRequirementScopeContinuationTask(task?: AsyncGenerationTask | null) {
  return task?.taskType === 'REQUIREMENT_SCOPE_CONTINUATION';
}

function isTestPointScopeContinuationTask(task?: AsyncGenerationTask | null) {
  return task?.taskType === 'TEST_POINT_SCOPE_CONTINUATION';
}

function isIncrementalCaseTask(task?: AsyncGenerationTask | null) {
  return task?.taskType === 'INCREMENTAL_CASE_GENERATION';
}

function shouldUseAsyncAnalysis(session: GenerationSession | null | undefined, content: string) {
  // An explicit generation command must never be downgraded to a TOM choice merely because
  // a recovered/stale session is still parked at ASK_TOM_MODE.
  if (isCaseGenerationIntent(content)) return false;
  const stage = (session?.currentStage || '').trim().toUpperCase();
  if (stage === 'ASK_TOM_MODE') return true;
  if (isReanalysisIntent(content)) return true;
  if ((stage === 'WAITING_REQUIREMENT_SCOPE' || stage === 'WAITING_USER_CONFIRMATION' || stage === 'ANALYSIS_READY')
      && !isCaseGenerationIntent(content) && !isConfirmIntent(content)) {
    return true;
  }
  return false;
}

function requiresScopeConfirmation(session: GenerationSession | null | undefined,
                                   analysis: RequirementAnalysis | null | undefined) {
  const status = (analysis?.status || '').trim().toUpperCase();
  if (['NEED_SCOPE_CONFIRMATION', 'SCOPE_CONFIRMED',
    'NEED_TEST_POINT_SCOPE_CONFIRMATION', 'TEST_POINT_SCOPE_CONFIRMED', 'NEED_CONFIRMATION'].includes(status)) {
    return true;
  }
  const stage = (session?.currentStage || '').trim().toUpperCase();
  return stage === 'WAITING_REQUIREMENT_SCOPE'
    || (stage === 'WAITING_USER_CONFIRMATION' && status !== 'CONFIRMED' && status !== 'GENERATED');
}

function asyncTaskTitle(task?: AsyncGenerationTask | null) {
  if (isTestPointScopeContinuationTask(task)) return '用例编排任务';
  if (isRequirementScopeContinuationTask(task)) return '测试点生成任务';
  if (isRequirementAnalysisTask(task)) return '异步需求分析任务';
  if (isIncrementalCaseTask(task)) return '异步增量更新任务';
  return '异步生成任务';
}

function asyncTaskRunningLabel(task?: AsyncGenerationTask | null) {
  if (isTestPointScopeContinuationTask(task)) return '按确认测试点编排用例';
  if (isRequirementScopeContinuationTask(task)) return '按确认需求范围生成测试点';
  if (isRequirementAnalysisTask(task)) return '需求分析中';
  if (isIncrementalCaseTask(task)) return '增量更新中';
  return '用例生成中';
}

function asyncTaskRunningDescription(task?: AsyncGenerationTask | null) {
  if (isTestPointScopeContinuationTask(task)) {
    return '正在把已确认测试点编排为节点用例和跨主题完整流程，不会重新分析需求或恢复已排除测试点。';
  }
  if (isRequirementScopeContinuationTask(task)) {
    const current = currentAsyncStage(task);
    return current
      ? `当前阶段：${current.label}。只处理人工确认的本期需求项，参考项不会独立生成测试点。`
      : '正在根据已确认的需求范围生成覆盖矩阵、测试点和用例编排。';
  }
  if (isRequirementAnalysisTask(task)) {
    const current = currentAsyncStage(task);
    if (current) {
      return `当前阶段：${current.label}。完成需求范围识别后会等待人工确认，不会自动展开测试点。`;
    }
    return '需求分析任务正在后台分阶段执行，完成后会自动刷新分析结果和待澄清问题。';
  }
  if (isIncrementalCaseTask(task)) {
    return '增量更新任务正在后台执行，完成后会自动刷新右侧草稿箱。';
  }
  return '生成任务正在后台执行，完成后会自动刷新右侧草稿箱。';
}

function currentAsyncStage(task?: AsyncGenerationTask | null) {
  const stages = task?.stages || [];
  return stages.find(stage => stage.status === 'RUNNING')
    || stages.find(stage => stage.status === 'PENDING')
    || stages[stages.length - 1];
}

function stageTone(status?: string | null) {
  switch ((status || '').toUpperCase()) {
    case 'SUCCEEDED':
      return 'border-green-200 bg-green-50 text-green-700';
    case 'RUNNING':
      return 'border-blue-200 bg-blue-50 text-blue-700';
    case 'FAILED':
    case 'TIMEOUT':
      return 'border-red-200 bg-red-50 text-red-700';
    case 'CANCELED':
      return 'border-gray-200 bg-gray-50 text-gray-500';
    default:
      return 'border-gray-200 bg-white text-gray-500';
  }
}

function stageDotTone(status?: string | null) {
  switch ((status || '').toUpperCase()) {
    case 'SUCCEEDED':
      return 'bg-green-500';
    case 'RUNNING':
      return 'bg-blue-500 animate-pulse';
    case 'FAILED':
    case 'TIMEOUT':
      return 'bg-red-500';
    default:
      return 'bg-gray-300';
  }
}

function AsyncTaskStageCard({ stage }: { stage: AsyncGenerationTaskStage }) {
  return (
    <div className={`min-w-0 rounded-lg border px-3 py-2 text-xs ${stageTone(stage.status)}`}>
      <div className="flex items-center gap-2">
        <span className={`h-2 w-2 shrink-0 rounded-full ${stageDotTone(stage.status)}`} />
        <span className="truncate font-medium">{stage.label}</span>
      </div>
      <div className="mt-1 text-[10px] opacity-80">{uiStatusLabel(stage.status)}</div>
      {(stage.status === 'FAILED' || stage.status === 'TIMEOUT') && (
        <AsyncErrorDetails message={stage.errorMessage || stage.errorCode || '该阶段执行失败'} compact />
      )}
    </div>
  );
}

function AsyncErrorDetails({ message, compact = false }: { message: string; compact?: boolean }) {
  const threshold = compact ? 120 : 220;
  if (message.length <= threshold) {
    return <div className="mt-1 break-words text-[11px] leading-relaxed">{message}</div>;
  }
  return (
    <details className="mt-1 min-w-0 text-[11px] leading-relaxed">
      <summary className="cursor-pointer break-words marker:text-red-500">
        {message.slice(0, threshold)}… <span className="font-medium">展开完整错误</span>
      </summary>
      <div className="mt-2 max-h-40 overflow-y-auto whitespace-pre-wrap break-words rounded border border-red-200 bg-white/60 p-2">
        {message}
      </div>
    </details>
  );
}

function asyncTaskButtonLabel(task?: AsyncGenerationTask | null) {
  if (isRequirementAnalysisTask(task)) return '分析中...';
  if (isIncrementalCaseTask(task)) return '更新中...';
  return '生成中...';
}

function asyncTaskErrorMessage(task?: AsyncGenerationTask | null) {
  if (!task) return '';
  const code = task.errorCode || task.status;
  const tips: Record<string, string> = {
    TIMEOUT: '模型响应超时，请稍后重试，或减少输入内容后再生成。',
    RATE_LIMITED: '模型服务限流或额度受限，请稍后重试。',
    AUTH_ERROR: '模型配置鉴权失败，请检查 API Key、权限或余额。',
    CONTEXT_TOO_LONG: '输入上下文过长，请减少需求内容或缩小生成范围。',
    REASONING_EXHAUSTED: '模型只完成了推理，未输出最终结果。系统会按更小节点重试；持续出现时请调整模型推理强度或输出额度。',
    OUTPUT_PARSE_ERROR: '模型返回格式不符合要求，请重试或调整模型。',
    INVALID_REQUEST: '模型请求参数无效，请检查模型配置。',
    PROVIDER_ERROR: '模型服务或中转网关异常，请稍后重试。',
    UNKNOWN_ERROR: '生成失败，请查看后台日志或稍后重试。',
  };
  return task.errorMessage || tips[code] || tips.UNKNOWN_ERROR;
}

function asStringArray(value: unknown) {
  return displayList(value);
}

function versionLabel(version: number, subVersion?: number): string {
  return subVersion ? `${version}.${subVersion}` : `${version}`;
}

function parseDraftSourceRefs(draft?: CaseDraft | null) {
  return safeJsonParse<any>(draft?.sourceRefsJson || null);
}

function qualityStatusLabel(status?: string | null) {
  switch ((status || '').toUpperCase()) {
    case 'PASS':
      return '证据通过';
    case 'PARTIAL':
      return '部分覆盖';
    case 'LOW_EVIDENCE':
      return '低证据';
    default:
      return status || '未校验';
  }
}

function qualityStatusTone(status?: string | null) {
  switch ((status || '').toUpperCase()) {
    case 'PASS':
      return 'bg-green-50 text-green-700';
    case 'PARTIAL':
      return 'bg-amber-50 text-amber-700';
    case 'LOW_EVIDENCE':
      return 'bg-red-50 text-red-700';
    default:
      return 'bg-gray-100 text-gray-600';
  }
}

function EvidenceChips({ title, items }: { title: string; items: unknown }) {
  const values = asStringArray(items);
  if (values.length === 0) return null;
  return (
    <div>
      <div className="text-[11px] font-medium text-gray-500 mb-1">{title}</div>
      <div className="flex flex-wrap gap-1">
        {values.slice(0, 8).map((item, idx) => (
          <span key={`${item}-${idx}`} className="break-words rounded bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-700">
            {item}
          </span>
        ))}
      </div>
    </div>
  );
}

function ClarificationQuestions({
  questions,
  title = '需要澄清',
}: {
  questions: { question?: string; reason?: string; impact?: string }[];
  title?: string;
}) {
  if (questions.length === 0) return null;
  return (
    <div>
      <div className="text-[11px] font-medium text-amber-700 mb-1">{title} ({questions.length})</div>
      <div className="space-y-2">
        {questions.map((q, idx) => (
          <div key={idx} className="text-xs rounded-lg border border-amber-200 bg-amber-50 p-2">
            <div className="break-words font-medium text-amber-900">{displayText(q.question || q)}</div>
            {q.reason && <div className="mt-1 break-words text-amber-700">原因：{displayText(q.reason)}</div>}
            {q.impact && <div className="mt-0.5 break-words text-amber-700">影响：{displayText(q.impact)}</div>}
          </div>
        ))}
      </div>
    </div>
  );
}

export function AnalysisMessageContent({ analysis }: { analysis: RequirementAnalysis }) {
  const result = parseAnalysisResult<{
    requirement_understanding?: string;
    affected_modules?: string[];
    affected_pages?: string[];
    affected_fields?: string[];
    affected_flows?: string[];
    affected_roles?: string[];
    conflicts?: string[];
    uncertain_items?: string[];
    clarification_questions?: { question?: string; reason?: string; impact?: string }[];
    review_risk_questions?: { question?: string; reason?: string; impact?: string }[];
    risk_scenarios?: string[];
    boundary_conditions?: string[];
    coverage_matrix?: CoverageMatrixRow[];
    case_plan?: { id?: string; title?: string; case_strategy?: string; source_test_point_refs?: string[]; precondition_test_point_refs?: string[]; case_designs?: { id?: string; title?: string; scenario?: string; scenario_type?: string; design_method?: string; design_methods?: string[]; coverage_requirements?: string[] }[] }[];
  }>(analysis.analysisResult);
  const questions = result?.clarification_questions
    ?? safeJsonParse<any[]>(analysis.clarificationQuestions)
    ?? [];
  const testPoints = safeJsonParse<any[]>(analysis.testPoints) ?? [];
  const affected: Array<[string, string[] | undefined]> = [
    ['模块', result?.affected_modules],
    ['页面', result?.affected_pages],
    ['字段', result?.affected_fields],
    ['流程', result?.affected_flows],
    ['角色', result?.affected_roles],
  ];

  return (
    <div className="space-y-3">
      <div className="font-semibold text-gray-900">【需求分析结果 v{versionLabel(analysis.version, analysis.subVersion)}】</div>
      {result?.requirement_understanding && (
        <div>
          <div className="font-semibold text-gray-900">【需求理解】</div>
          <div className="mt-1 leading-relaxed">{displayText(result.requirement_understanding)}</div>
        </div>
      )}
      {affected.some(([, items]) => items && items.length > 0) && (
        <div>
          <div className="font-semibold text-gray-900">【影响范围】</div>
          <div className="mt-1 space-y-0.5">
            {affected.map(([label, items]) => (
              items && items.length > 0 ? (
                <div key={label} className="break-words">- {label}：{displayList(items).join('、')}</div>
              ) : null
            ))}
          </div>
        </div>
      )}
      {/* 评审前需确认问题 */}
      {result?.review_risk_questions && result.review_risk_questions.length > 0 && (
        <div className="rounded-lg border border-amber-200 bg-amber-50/70 p-2">
          <div className="font-semibold text-[11px] text-amber-700 mb-1">【评审前需确认问题】</div>
          <div className="space-y-1.5">
            {result.review_risk_questions.map((q, idx) => (
              <div key={idx} className="text-xs text-gray-700">
                <div className="break-words font-medium">- {displayText(q.question || q)}</div>
                {q.reason && <div className="ml-3 text-gray-500">原因：{displayText(q.reason)}</div>}
                {q.impact && <div className="ml-3 text-gray-500">影响：{displayText(q.impact)}</div>}
              </div>
            ))}
          </div>
        </div>
      )}
      <ClarificationQuestions questions={questions} title="需要澄清" />
      {result?.uncertain_items && result.uncertain_items.length > 0 && (
        <div>
          <div className="font-semibold text-gray-900">【分析不确定项】</div>
          <div className="mt-1 space-y-0.5">
            {result.uncertain_items.map((item, idx) => (
              <div key={idx} className="break-words">- {displayText(item)}</div>
            ))}
          </div>
        </div>
      )}
      <CoverageMatrix rows={result?.coverage_matrix} />
      {testPoints.length > 0 && (
        <div>
          <div className="font-semibold text-gray-900">【测试点分析】</div>
          <div className="mt-1 space-y-0.5">
            {testPoints.map((tp, idx) => (
              <div key={idx} className="break-words">
                {idx + 1}. {displayText(tp.title) || `测试点 ${idx + 1}`}{tp.description ? ` - ${displayText(tp.description)}` : ''}
                {(tp.skill_layer || tp.design_method) && (
                  <span className="ml-1 text-[11px] text-gray-500">
                    [{[displayText(tp.skill_layer), displayText(tp.design_method)].filter(Boolean).join(' / ')}]
                  </span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
      {result?.case_plan && result.case_plan.length > 0 && (
        <div>
          <div className="font-semibold text-gray-900">【用例编排计划】</div>
          <div className="mt-1 space-y-1">
            {result.case_plan.map((plan, idx) => (
              <div key={plan.id || idx} className="break-words text-xs text-gray-700">
                {plan.id || `CP${idx + 1}`} · {displayText(plan.title) || '用例生成节点'}
                <span className="ml-1 text-[11px] text-gray-500">[{displayText(plan.case_strategy)}]</span>
                {plan.case_designs && <span className="ml-1 text-[11px] text-gray-500">· {plan.case_designs.length} 个设计项</span>}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

type TestPointDisposition = TestPointScopeDecision['disposition'];

function defaultTestPointDisposition(point: any): TestPointDisposition {
  if (point?.scope_decision_source === 'USER'
      && ['GENERATE', 'REFERENCE_ONLY', 'EXCLUDED'].includes(point?.generation_scope)) {
    return point.generation_scope;
  }
  if (point?.generation_scope === 'EXCLUDED' || point?.scope_recommendation === 'OUT_OF_SCOPE') return 'EXCLUDED';
  return 'GENERATE';
}

export function AnalysisPanel({
  analyses,
  latestAnalysis,
  onConfirmRequirementScope,
  onConfirmTestPointScope,
  savingRequirementScope = false,
  savingTestPointScope = false,
}: {
  analyses: RequirementAnalysis[];
  latestAnalysis: RequirementAnalysis;
  onConfirmRequirementScope?: (version: number, decisions: RequirementScopeDecision[]) => Promise<void> | void;
  onConfirmTestPointScope?: (version: number, decisions: TestPointScopeDecision[]) => Promise<void> | void;
  savingRequirementScope?: boolean;
  savingTestPointScope?: boolean;
}) {
  const [expandedVersions, setExpandedVersions] = useState<Set<number>>(new Set([latestAnalysis.id]));
  const [scopeDrafts, setScopeDrafts] = useState<Record<number, Record<string, TestPointDisposition>>>({});
  const [requirementScopeDrafts, setRequirementScopeDrafts] = useState<Record<number, Record<string, TestPointDisposition>>>({});
  const [scopeReasonDrafts, setScopeReasonDrafts] = useState<Record<number, Record<string, string>>>({});
  const [requirementScopeReasonDrafts, setRequirementScopeReasonDrafts] = useState<Record<number, Record<string, string>>>({});
  const [scopeKeywords, setScopeKeywords] = useState<Record<number, string>>({});
  const [scopePages, setScopePages] = useState<Record<number, number>>({});

  useEffect(() => {
    const points = safeJsonParse<any[]>(latestAnalysis.testPoints) ?? [];
    const next: Record<string, TestPointDisposition> = {};
    points.forEach((point, index) => {
      next[String(point.id || `TP${index + 1}`)] = defaultTestPointDisposition(point);
    });
    setScopeDrafts(current => ({ ...current, [latestAnalysis.id]: next }));
  }, [latestAnalysis.id, latestAnalysis.version, latestAnalysis.updatedAt, latestAnalysis.testPoints]);

  useEffect(() => {
    const result = parseAnalysisResult<{ requirement_atoms?: any[] }>(latestAnalysis.analysisResult);
    const atoms = result?.requirement_atoms || [];
    const next: Record<string, TestPointDisposition> = {};
    atoms.forEach((atom, index) => {
      next[String(atom.id || `R${index + 1}`)] = defaultTestPointDisposition(atom);
    });
    setRequirementScopeDrafts(current => ({ ...current, [latestAnalysis.id]: next }));
  }, [latestAnalysis.id, latestAnalysis.version, latestAnalysis.updatedAt, latestAnalysis.analysisResult]);

  const toggleVersion = (version: number) => {
    setExpandedVersions(prev => {
      const next = new Set(prev);
      if (next.has(version)) next.delete(version);
      else next.add(version);
      return next;
    });
  };

  const renderAnalysis = (a: RequirementAnalysis, isLatest: boolean) => {
    const result = parseAnalysisResult<{
      requirement_understanding?: string;
      business_domain?: string;
      requirement_type?: string;
      input_sources?: string[];
      input_source_notes?: string;
      affected_modules?: string[];
      affected_pages?: string[];
      affected_fields?: string[];
      affected_flows?: string[];
      affected_roles?: string[];
      conflicts?: string[];
      uncertain_items?: string[];
      out_of_scope?: string[];
      review_risk_questions?: { question?: string; reason?: string; impact?: string }[];
      risk_scenarios?: string[];
      boundary_conditions?: string[];
      coverage_matrix?: CoverageMatrixRow[];
      skill_self_check?: {
        three_layer_complete?: boolean;
        redundancy_checked?: boolean;
        method_routing_checked?: boolean;
        p0_review_checked?: boolean;
        notes?: string[];
      };
      evidence_summary?: {
        evidence_count?: number;
        confidence_label?: string;
        tom_node_refs?: string[];
        wiki_refs?: string[];
        page_refs?: string[];
        business_pack_refs?: string[];
        trace_refs?: string[];
        source_basis?: string[];
        unsupported_items?: string[];
      };
      clarification_questions?: { question?: string; reason?: string; impact?: string }[];
      assumptions?: { assumption?: string; reason?: string }[];
      requirement_atoms?: {
        id?: string;
        category?: string;
        title?: string;
        requirement?: string;
        source_basis?: string | string[];
        needs_clarification?: boolean;
        scope_recommendation?: string;
        scope_reason?: string;
        generation_scope?: TestPointDisposition;
      }[];
      requirement_scope_review?: {
        status?: string;
        generate_count?: number;
        reference_count?: number;
        excluded_count?: number;
      };
      test_points?: {
        title?: string;
        description?: string;
        point_type?: string;
        skill_layer?: string;
        design_method?: string;
        priority_hint?: string;
        test_dimension?: string;
      }[];
      case_plan?: {
        id?: string;
        title?: string;
        case_strategy?: string;
        source_test_point_refs?: string[];
        precondition_test_point_refs?: string[];
        case_designs?: { id?: string; title?: string; scenario?: string; scenario_type?: string; design_method?: string; design_methods?: string[]; coverage_requirements?: string[] }[];
      }[];
      test_point_scope_review?: {
        status?: string;
        generate_count?: number;
        reference_count?: number;
        excluded_count?: number;
      };
    }>(a.analysisResult);

    const evidenceSummary = result?.evidence_summary;
    const testPoints = result?.test_points ?? safeJsonParse<any[]>(a.testPoints) ?? [];
    const questions = result?.clarification_questions
      ?? safeJsonParse<any[]>(a.clarificationQuestions)
      ?? [];
    const assumptions = result?.assumptions ?? safeJsonParse<any[]>(a.assumptions) ?? [];
    const requirementAtoms = result?.requirement_atoms ?? [];
    const requirementScopeReview = result?.requirement_scope_review;
    const scopeReview = result?.test_point_scope_review;
    const testPointScopeAvailable = !['NEED_SCOPE_CONFIRMATION', 'SCOPE_CONFIRMED'].includes(a.status);
    const canEditRequirementScope = isLatest && a.status === 'NEED_SCOPE_CONFIRMATION'
      && Boolean(onConfirmRequirementScope);
    const canEditScope = isLatest
      && ['NEED_TEST_POINT_SCOPE_CONFIRMATION', 'NEED_CONFIRMATION'].includes(a.status)
      && Boolean(onConfirmTestPointScope);
    const requirementVersionDraft = requirementScopeDrafts[a.id] || {};
    const versionScopeDraft = scopeDrafts[a.id] || {};
    const requirementReasonDraft = requirementScopeReasonDrafts[a.id] || {};
    const scopeReasonDraft = scopeReasonDrafts[a.id] || {};
    const requirementScopeCounts = requirementAtoms.reduce((counts, atom, index) => {
      const disposition = requirementVersionDraft[String(atom.id || `R${index + 1}`)] || defaultTestPointDisposition(atom);
      counts[disposition] += 1;
      return counts;
    }, { GENERATE: 0, REFERENCE_ONLY: 0, EXCLUDED: 0 } as Record<TestPointDisposition, number>);
    const scopeCounts = testPoints.reduce((counts, point, index) => {
      const disposition = versionScopeDraft[String(point.id || `TP${index + 1}`)] || defaultTestPointDisposition(point);
      counts[disposition] += 1;
      return counts;
    }, { GENERATE: 0, REFERENCE_ONLY: 0, EXCLUDED: 0 } as Record<TestPointDisposition, number>);
    const scopeKeyword = (scopeKeywords[a.id] || '').trim().toLowerCase();
    const indexedTestPoints = testPoints.map((point, index) => ({
      point,
      index,
      id: String(point.id || `TP${index + 1}`),
    }));
    const filteredTestPoints = scopeKeyword
      ? indexedTestPoints.filter(({ point }) => [
          point.title, point.description, point.related_module, point.related_page,
          point.related_flow, point.point_type, point.test_dimension,
        ].some(value => String(value || '').toLowerCase().includes(scopeKeyword)))
      : indexedTestPoints;
    const scopePageSize = 30;
    const scopePageCount = Math.max(1, Math.ceil(filteredTestPoints.length / scopePageSize));
    const scopePage = Math.min(scopePages[a.id] || 1, scopePageCount);
    const visibleTestPoints = filteredTestPoints.slice((scopePage - 1) * scopePageSize, scopePage * scopePageSize);

    const setAllScope = (disposition: TestPointDisposition) => {
      const next: Record<string, TestPointDisposition> = {};
      testPoints.forEach((point, index) => { next[String(point.id || `TP${index + 1}`)] = disposition; });
      setScopeDrafts(current => ({ ...current, [a.id]: next }));
    };

    const setFilteredScope = (disposition: TestPointDisposition) => {
      setScopeDrafts(current => {
        const next = { ...(current[a.id] || {}) };
        filteredTestPoints.forEach(({ id }) => { next[id] = disposition; });
        return { ...current, [a.id]: next };
      });
    };

    const setRecommendedRequirementScope = () => {
      const next: Record<string, TestPointDisposition> = {};
      requirementAtoms.forEach((atom, index) => {
        next[String(atom.id || `R${index + 1}`)] = defaultTestPointDisposition(atom);
      });
      setRequirementScopeDrafts(current => ({ ...current, [a.id]: next }));
    };

    const confirmRequirementScope = async () => {
      if (!onConfirmRequirementScope) return;
      const decisions = requirementAtoms.map((atom, index) => {
        const requirementAtomId = String(atom.id || `R${index + 1}`);
        return {
          requirementAtomId,
          disposition: requirementVersionDraft[requirementAtomId] || defaultTestPointDisposition(atom),
          reason: requirementReasonDraft[requirementAtomId] || '',
        } as RequirementScopeDecision;
      });
      await onConfirmRequirementScope(a.version, decisions);
    };

    const confirmScope = async () => {
      if (!onConfirmTestPointScope) return;
      const decisions = testPoints.map((point, index) => {
        const testPointId = String(point.id || `TP${index + 1}`);
        return {
          testPointId,
          disposition: versionScopeDraft[testPointId] || defaultTestPointDisposition(point),
          reason: scopeReasonDraft[testPointId] || '',
        } as TestPointScopeDecision;
      });
      await onConfirmTestPointScope(a.version, decisions);
    };

    return (
      <div className="space-y-4">
        {result?.requirement_understanding && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">需求理解</div>
            <div className="break-words text-xs leading-relaxed text-gray-800">{displayText(result.requirement_understanding)}</div>
          </div>
        )}
        {result?.business_domain && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">业务领域</div>
            <div className="break-words text-xs text-gray-800">{displayText(result.business_domain)}</div>
          </div>
        )}
        {result?.requirement_type && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">需求类型</div>
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-indigo-50 text-indigo-700">{displayText(result.requirement_type)}</span>
          </div>
        )}
        {result?.input_sources && result.input_sources.length > 0 && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">输入来源</div>
            <div className="flex flex-wrap gap-1">
              {result.input_sources.map((src, idx) => (
                <span key={idx} className="text-[10px] px-1.5 py-0.5 rounded bg-teal-50 text-teal-700">{displayText(src)}</span>
              ))}
            </div>
            {result.input_source_notes && (
              <div className="mt-1 text-xs text-gray-500">{displayText(result.input_source_notes)}</div>
            )}
          </div>
        )}
        <ClarificationQuestions questions={questions} />
        {requirementAtoms.length > 0 && requirementScopeReview && (
          <div className="rounded-lg border border-blue-200 bg-blue-50/70 p-2">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div>
                <div className="text-[11px] font-semibold text-blue-900">本期需求范围</div>
                <div className="mt-0.5 text-[10px] leading-4 text-blue-700">
                  先确认变更范围，再生成覆盖矩阵和测试点。背景可保留为参考，明确非本期内容可排除。
                </div>
              </div>
              <span className={`rounded px-1.5 py-0.5 text-[10px] ${
                requirementScopeReview.status === 'CONFIRMED'
                  ? 'bg-green-100 text-green-700' : 'bg-amber-100 text-amber-700'
              }`}>
                {requirementScopeReview.status === 'CONFIRMED' ? '范围已确认' : '等待人工确认'}
              </span>
            </div>
            <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[10px]">
              <span className="rounded bg-white px-1.5 py-0.5 text-green-700">本期 {requirementScopeCounts.GENERATE}</span>
              <span className="rounded bg-white px-1.5 py-0.5 text-sky-700">参考 {requirementScopeCounts.REFERENCE_ONLY}</span>
              <span className="rounded bg-white px-1.5 py-0.5 text-gray-600">排除 {requirementScopeCounts.EXCLUDED}</span>
              {canEditRequirementScope && (
                <button type="button" onClick={setRecommendedRequirementScope}
                  className="rounded border border-blue-200 bg-white px-1.5 py-0.5 text-blue-700 hover:border-blue-300">
                  恢复默认范围
                </button>
              )}
            </div>
            <div className="mt-2 max-h-80 space-y-1.5 overflow-y-auto pr-1">
              {requirementAtoms.map((atom, index) => {
                const atomId = String(atom.id || `R${index + 1}`);
                const value = requirementVersionDraft[atomId] || defaultTestPointDisposition(atom);
                return (
                  <div key={atomId} className="rounded border border-blue-100 bg-white p-2">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                      <div className="min-w-0 flex-1">
                        <div className="break-words text-xs font-medium text-gray-800">
                          {atomId} · {displayText(atom.title) || displayText(atom.requirement) || '未命名需求项'}
                        </div>
                        {atom.requirement && atom.title && (
                          <div className="mt-0.5 break-words text-[10px] leading-4 text-gray-500">{displayText(atom.requirement)}</div>
                        )}
                      </div>
                      {canEditRequirementScope ? (
                        <select aria-label={`需求范围-${atomId}`} value={value}
                          onChange={event => setRequirementScopeDrafts(current => ({
                            ...current,
                            [a.id]: { ...(current[a.id] || {}), [atomId]: event.target.value as TestPointDisposition },
                          }))}
                          className="h-7 shrink-0 rounded border border-gray-200 bg-white px-2 text-[10px] text-gray-700 outline-none focus:border-blue-300">
                          <option value="GENERATE">本期生成</option>
                          <option value="REFERENCE_ONLY">仅作参考</option>
                          <option value="EXCLUDED">排除</option>
                        </select>
                      ) : (
                        <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-600">
                          {value === 'GENERATE' ? '本期生成' : value === 'REFERENCE_ONLY' ? '仅作参考' : '已排除'}
                        </span>
                      )}
                    </div>
                    <div className="mt-1 flex flex-wrap gap-2 text-[10px] text-gray-400">
                      {atom.category && <span>类型：{displayText(atom.category)}</span>}
                      {atom.scope_recommendation && <span>AI 建议：{SCOPE_RECOMMENDATION_LABELS[atom.scope_recommendation] || displayText(atom.scope_recommendation)}</span>}
                      {atom.needs_clarification && <span className="text-amber-600">需要澄清</span>}
                    </div>
                    {atom.scope_reason && <div className="mt-1 break-words text-[10px] text-gray-500">依据：{displayText(atom.scope_reason)}</div>}
                    {canEditRequirementScope && value !== defaultTestPointDisposition(atom) && (
                      <input
                        value={requirementReasonDraft[atomId] || ''}
                        onChange={event => setRequirementScopeReasonDrafts(current => ({
                          ...current,
                          [a.id]: { ...(current[a.id] || {}), [atomId]: event.target.value },
                        }))}
                        placeholder="人工调整原因（可选）"
                        className="mt-1 h-7 w-full rounded border border-gray-200 px-2 text-[10px] outline-none focus:border-blue-300"
                      />
                    )}
                  </div>
                );
              })}
            </div>
            {canEditRequirementScope && (
              <button type="button" onClick={() => void confirmRequirementScope()}
                disabled={savingRequirementScope || requirementScopeCounts.GENERATE === 0}
                className="mt-2 w-full rounded-lg bg-blue-700 px-3 py-2 text-xs font-semibold text-white hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-50">
                {savingRequirementScope ? '正在提交并创建测试点任务...' : requirementScopeCounts.GENERATE === 0
                  ? '至少保留一个本期需求项' : '确认范围并生成测试点'}
              </button>
            )}
          </div>
        )}
        {/* 评审前需确认问题 */}
        {result?.review_risk_questions && result.review_risk_questions.length > 0 && (
          <div className="rounded-lg border border-amber-200 bg-amber-50/70 p-2">
            <div className="text-[11px] font-semibold text-amber-700 mb-1">评审前需确认问题</div>
            <div className="space-y-1.5">
              {result.review_risk_questions.map((q, idx) => (
                <div key={idx} className="text-xs text-gray-700">
                  <div className="break-words font-medium">{displayText(q.question || q)}</div>
                  {q.reason && <div className="ml-3 text-gray-500">原因：{displayText(q.reason)}</div>}
                  {q.impact && <div className="ml-3 text-gray-500">影响：{displayText(q.impact)}</div>}
                </div>
              ))}
            </div>
          </div>
        )}
        {result?.risk_scenarios && result.risk_scenarios.length > 0 && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">风险场景</div>
            <div className="space-y-0.5">{result.risk_scenarios.map((s, idx) => (
              <div key={idx} className="text-xs text-gray-700 break-words">- {displayText(s)}</div>
            ))}</div>
          </div>
        )}
        {result?.boundary_conditions && result.boundary_conditions.length > 0 && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">边界条件</div>
            <div className="space-y-0.5">{result.boundary_conditions.map((c, idx) => (
              <div key={idx} className="text-xs text-gray-700 break-words">- {displayText(c)}</div>
            ))}</div>
          </div>
        )}
        <CoverageMatrix rows={result?.coverage_matrix} compact />
        {result?.skill_self_check && (
          <div className="rounded-lg border border-gray-100 bg-gray-50 p-2">
            <div className="text-[11px] font-semibold text-gray-700 mb-1">Skill 自检</div>
            <div className="flex flex-wrap gap-1 text-[10px]">
              <span className={`px-1.5 py-0.5 rounded ${result.skill_self_check.three_layer_complete ? 'bg-green-50 text-green-700' : 'bg-amber-50 text-amber-700'}`}>三层递进</span>
              <span className={`px-1.5 py-0.5 rounded ${result.skill_self_check.redundancy_checked ? 'bg-green-50 text-green-700' : 'bg-amber-50 text-amber-700'}`}>防冗余</span>
              <span className={`px-1.5 py-0.5 rounded ${result.skill_self_check.method_routing_checked ? 'bg-green-50 text-green-700' : 'bg-amber-50 text-amber-700'}`}>方法调度</span>
              <span className={`px-1.5 py-0.5 rounded ${result.skill_self_check.p0_review_checked ? 'bg-green-50 text-green-700' : 'bg-amber-50 text-amber-700'}`}>P0复核</span>
            </div>
            {result.skill_self_check.notes && result.skill_self_check.notes.length > 0 && (
              <div className="mt-1 space-y-0.5">
                {result.skill_self_check.notes.map((note, idx) => (
                  <div key={idx} className="break-words text-[10px] text-gray-500">- {displayText(note)}</div>
                ))}
              </div>
            )}
          </div>
        )}
        {evidenceSummary && (
          <div className="rounded-lg border border-sky-100 bg-sky-50/70 p-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="text-[11px] font-semibold text-sky-900">证据命中</div>
              <div className="flex flex-wrap items-center gap-1">
                <span className="rounded bg-white px-1.5 py-0.5 text-[10px] text-sky-700">
                  {evidenceSummary.evidence_count ?? 0} 条
                </span>
                {evidenceSummary.confidence_label && (
                  <span className="rounded bg-white px-1.5 py-0.5 text-[10px] text-sky-700">
                    {displayText(evidenceSummary.confidence_label)}
                  </span>
                )}
              </div>
            </div>
            <div className="mt-2 grid grid-cols-1 gap-2">
              <EvidenceChips title="TOM" items={evidenceSummary.tom_node_refs} />
              <EvidenceChips title="LLM Wiki" items={evidenceSummary.wiki_refs} />
              <EvidenceChips title="页面" items={evidenceSummary.page_refs} />
              <EvidenceChips title="业务包" items={evidenceSummary.business_pack_refs} />
              <EvidenceChips title="轨迹/摘要" items={evidenceSummary.trace_refs} />
              <EvidenceChips title="待确认" items={evidenceSummary.unsupported_items} />
            </div>
          </div>
        )}
        {result && (
          <div className="grid grid-cols-1 gap-2">
            {(['affected_modules', 'affected_pages', 'affected_fields', 'affected_flows', 'affected_roles'] as const).map(key => {
              const items = result[key];
              if (!items || items.length === 0) return null;
              const labels: Record<string, string> = {
                affected_modules: '受影响模块', affected_pages: '受影响页面',
                affected_fields: '受影响字段', affected_flows: '受影响流程', affected_roles: '受影响角色',
              };
              return (
                <div key={key}>
                  <div className="text-[11px] font-medium text-gray-500 mb-1">{labels[key]}</div>
                  <div className="flex flex-wrap gap-1">{items.map((item, idx) => (
                    <span key={idx} className="break-words text-[10px] px-1.5 py-0.5 rounded bg-gray-100 text-gray-700">{displayText(item)}</span>
                  ))}</div>
                </div>
              );
            })}
          </div>
        )}
        {testPointScopeAvailable && testPoints.length > 0 && (
          <div>
            <div className="mb-2 rounded-lg border border-indigo-100 bg-indigo-50/60 p-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <div className="text-[11px] font-semibold text-indigo-900">测试点生成范围</div>
                  <div className="mt-0.5 text-[10px] leading-4 text-indigo-700">
                    AI 只提供范围建议。确认后，仅“生成用例”的测试点会进入用例编排；参考和排除项仍保留在分析中。
                  </div>
                </div>
                <span className={`rounded px-1.5 py-0.5 text-[10px] ${
                  scopeReview?.status === 'CONFIRMED' ? 'bg-green-100 text-green-700' : 'bg-amber-100 text-amber-700'
                }`}>
                  {scopeReview?.status === 'CONFIRMED' ? '范围已确认' : '待人工确认'}
                </span>
              </div>
              <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[10px]">
                <span className="rounded bg-white px-1.5 py-0.5 text-green-700">生成 {scopeCounts.GENERATE}</span>
                <span className="rounded bg-white px-1.5 py-0.5 text-sky-700">仅参考 {scopeCounts.REFERENCE_ONLY}</span>
                <span className="rounded bg-white px-1.5 py-0.5 text-gray-600">排除 {scopeCounts.EXCLUDED}</span>
                {canEditScope && (
                  <>
                    <button type="button" onClick={() => setAllScope('GENERATE')} className="rounded border border-gray-200 bg-white px-1.5 py-0.5 text-gray-600 hover:border-gray-300">全部生成</button>
                    <button type="button" onClick={() => setAllScope('REFERENCE_ONLY')} className="rounded border border-gray-200 bg-white px-1.5 py-0.5 text-gray-600 hover:border-gray-300">全部仅参考</button>
                    <button type="button" onClick={() => setAllScope('EXCLUDED')} className="rounded border border-gray-200 bg-white px-1.5 py-0.5 text-gray-600 hover:border-gray-300">全部排除</button>
                  </>
                )}
              </div>
            </div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">测试点 ({testPoints.length})</div>
            <div className="mb-2 text-[10px] leading-4 text-gray-400">
              生成链路：需求原子 → 覆盖矩阵
              {displayList(evidenceSummary?.tom_node_refs).length > 0
                ? `（本轮已向覆盖矩阵提供 ${displayList(evidenceSummary?.tom_node_refs).length} 个 TOM 节点作为上下文）`
                : '（本轮未命中 TOM 节点）'}
              → 结构化测试点
            </div>
            <div className="mb-2 flex flex-wrap items-center gap-2">
              <input
                value={scopeKeywords[a.id] || ''}
                onChange={event => {
                  setScopeKeywords(current => ({ ...current, [a.id]: event.target.value }));
                  setScopePages(current => ({ ...current, [a.id]: 1 }));
                }}
                placeholder="搜索测试点、模块、维度"
                className="h-8 min-w-0 flex-1 rounded border border-gray-200 px-2 text-[11px] outline-none focus:border-indigo-300"
              />
              {canEditScope && scopeKeyword && (
                <>
                  <button type="button" onClick={() => setFilteredScope('GENERATE')} className="h-8 rounded border border-gray-200 px-2 text-[10px] text-gray-600">筛选项生成</button>
                  <button type="button" onClick={() => setFilteredScope('REFERENCE_ONLY')} className="h-8 rounded border border-gray-200 px-2 text-[10px] text-gray-600">筛选项参考</button>
                  <button type="button" onClick={() => setFilteredScope('EXCLUDED')} className="h-8 rounded border border-red-200 px-2 text-[10px] text-red-600">筛选项排除</button>
                </>
              )}
            </div>
            <div className="space-y-2">
              {visibleTestPoints.map(({ point: tp, index: idx, id: testPointId }) => (
                <div key={testPointId} className="text-xs text-gray-700 rounded-lg border border-gray-100 p-2">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="break-words font-medium">{displayText(tp.title) || `测试点 ${idx + 1}`}</span>
                    {tp.point_type && (
                      <span className="text-[9px] px-1 py-0.5 rounded bg-violet-50 text-violet-600">{testPointLabel(tp.point_type)}</span>
                    )}
                    {tp.skill_layer && (
                      <span className="text-[9px] px-1 py-0.5 rounded bg-slate-50 text-slate-600">{testPointLabel(tp.skill_layer)}</span>
                    )}
                    {tp.design_method && (
                      <span className="text-[9px] px-1 py-0.5 rounded bg-cyan-50 text-cyan-700">{displayText(tp.design_method)}</span>
                    )}
                    {tp.priority_hint && (
                      <span className={`text-[9px] px-1 py-0.5 rounded ${
                        tp.priority_hint === 'RISK' ? 'bg-red-50 text-red-600' :
                        tp.priority_hint === 'EXTENDED' ? 'bg-blue-50 text-blue-600' :
                        'bg-green-50 text-green-600'
                      }`}>{testPointLabel(tp.priority_hint)}</span>
                    )}
                  </div>
                  {tp.description && (
                    <div className="mt-0.5 break-words text-gray-500 line-clamp-2">{displayText(tp.description)}</div>
                  )}
                  <div className="mt-2 flex flex-wrap items-center gap-2">
                    {canEditScope ? (
                      <select
                        aria-label={`测试点范围-${displayText(tp.title) || idx + 1}`}
                        value={versionScopeDraft[testPointId] || defaultTestPointDisposition(tp)}
                        onChange={event => {
                          setScopeDrafts(current => ({
                            ...current,
                            [a.id]: { ...(current[a.id] || {}), [testPointId]: event.target.value as TestPointDisposition },
                          }));
                        }}
                        className="h-7 rounded border border-gray-200 bg-white px-2 text-[10px] text-gray-700 outline-none focus:border-indigo-300"
                      >
                        <option value="GENERATE">生成用例</option>
                        <option value="REFERENCE_ONLY">仅作参考</option>
                        <option value="EXCLUDED">排除测试点</option>
                      </select>
                    ) : (
                      <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-600">
                        {defaultTestPointDisposition(tp) === 'GENERATE' ? '生成用例' : defaultTestPointDisposition(tp) === 'REFERENCE_ONLY' ? '仅作参考' : '已排除'}
                      </span>
                    )}
                    {tp.scope_recommendation && (
                      <span className="text-[10px] text-gray-400">
                        AI 建议：{SCOPE_RECOMMENDATION_LABELS[tp.scope_recommendation] || displayText(tp.scope_recommendation)}
                      </span>
                    )}
                    {tp.scope_reason && <span className="break-words text-[10px] text-gray-400">依据：{displayText(tp.scope_reason)}</span>}
                  </div>
                  {canEditScope
                    && (versionScopeDraft[testPointId] || defaultTestPointDisposition(tp)) !== defaultTestPointDisposition(tp)
                    && (
                      <input
                        value={scopeReasonDraft[testPointId] || ''}
                        onChange={event => {
                          setScopeReasonDrafts(current => ({
                            ...current,
                            [a.id]: { ...(current[a.id] || {}), [testPointId]: event.target.value },
                          }));
                        }}
                        placeholder="人工调整原因（可选）"
                        className="mt-1 h-7 w-full rounded border border-gray-200 px-2 text-[10px] outline-none focus:border-indigo-300"
                      />
                    )}
                  <div className="mt-1 flex flex-wrap items-center gap-2 text-[10px] text-gray-400">
                    {tp.test_dimension && (
                      <span title="测试维度表示该测试点从主流程、分支、边界、异常、状态、数据、权限、并发或幂等哪个视角进行验证">
                        测试维度：{testPointLabel(tp.test_dimension)}
                      </span>
                    )}
                    {tp.related_module && <span>模块：{displayText(tp.related_module)}</span>}
                    {tp.coverage_status && <span>覆盖：{qualityStatusLabel(tp.coverage_status)}</span>}
                    {tp.confidence !== undefined && formatRuleConfidence(tp.confidence) && (
                      <span title="该数值由平台根据是否需要澄清等规则评估，不是模型给出的精确概率">
                        规则置信：{formatRuleConfidence(tp.confidence)}
                      </span>
                    )}
                  </div>
                  {(displayList(tp.source_basis).length || tp.source_refs || displayList(tp.unsupported_items).length) && (
                    <div className="mt-2 space-y-1">
                      <EvidenceChips title="依据" items={tp.source_basis} />
                      <EvidenceChips title="TOM" items={tp.source_refs?.tom_node_refs} />
                      <EvidenceChips title="LLM Wiki" items={tp.source_refs?.wiki_refs} />
                      <EvidenceChips title="页面" items={tp.source_refs?.page_refs} />
                      <EvidenceChips title="业务包" items={tp.source_refs?.business_pack_refs} />
                      <EvidenceChips title="待确认" items={tp.unsupported_items} />
                    </div>
                  )}
                </div>
              ))}
            </div>
            {filteredTestPoints.length === 0 && (
              <div className="rounded border border-dashed border-gray-200 py-6 text-center text-[11px] text-gray-400">没有匹配的测试点</div>
            )}
            {scopePageCount > 1 && (
              <div className="mt-2 flex items-center justify-between text-[10px] text-gray-500">
                <span>第 {scopePage} / {scopePageCount} 页 · 筛选后 {filteredTestPoints.length} 条</span>
                <div className="flex gap-1">
                  <button type="button" disabled={scopePage <= 1}
                    onClick={() => setScopePages(current => ({ ...current, [a.id]: Math.max(1, scopePage - 1) }))}
                    className="rounded border border-gray-200 px-2 py-1 disabled:opacity-40">上一页</button>
                  <button type="button" disabled={scopePage >= scopePageCount}
                    onClick={() => setScopePages(current => ({ ...current, [a.id]: Math.min(scopePageCount, scopePage + 1) }))}
                    className="rounded border border-gray-200 px-2 py-1 disabled:opacity-40">下一页</button>
                </div>
              </div>
            )}
            {canEditScope && (
              <button
                type="button"
                onClick={() => void confirmScope()}
                disabled={savingTestPointScope || scopeCounts.GENERATE === 0}
                className="mt-3 w-full rounded-lg bg-slate-900 px-3 py-2 text-xs font-semibold text-white hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {savingTestPointScope ? '保存范围中...' : scopeCounts.GENERATE === 0 ? '至少保留一个生成项' : '确认测试点范围'}
              </button>
            )}
          </div>
        )}
        {result?.case_plan && result.case_plan.length > 0 && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">用例编排计划 ({result.case_plan.length})</div>
            <div className="space-y-1.5">
              {result.case_plan.map((plan, idx) => (
                <div key={plan.id || idx} className="rounded-lg border border-gray-100 bg-gray-50 p-2 text-xs text-gray-700">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="font-medium">{displayText(plan.id) || `CP${idx + 1}`} · {displayText(plan.title) || '用例生成节点'}</span>
                    {plan.case_strategy && <span className="rounded bg-indigo-50 px-1 py-0.5 text-[9px] text-indigo-700">{displayText(plan.case_strategy)}</span>}
                  </div>
                  <div className="mt-1 break-words text-[10px] text-gray-500">
                    测试点：{displayList(plan.source_test_point_refs).join('、') || '未引用'}
                    {displayList(plan.precondition_test_point_refs).length > 0 && ` · 前置：${displayList(plan.precondition_test_point_refs).join('、')}`}
                  </div>
                  {plan.case_designs && plan.case_designs.length > 0 && (
                    <div className="mt-1.5 space-y-0.5 border-t border-gray-200 pt-1.5 text-[10px] text-gray-600">
                      {plan.case_designs.map((design, designIndex) => (
                        <div key={design.id || designIndex} className="break-words">
                          {displayText(design.id) || `CD${designIndex + 1}`} · {displayText(design.title) || displayText(design.scenario) || '具体用例设计'}
                          {design.scenario_type && <span className="ml-1 text-sky-600">[{scenarioTypeLabel(design.scenario_type)}]</span>}
                          {(design.design_methods?.length || design.design_method) && (
                            <span className="ml-1 text-gray-400">[{(design.design_methods || [design.design_method]).filter(Boolean).map(item => displayText(item)).join(' + ')}]</span>
                          )}
                          {design.coverage_requirements && design.coverage_requirements.length > 0 && (
                            <div className="mt-1 text-[10px] leading-4 text-gray-500">
                              覆盖义务：{design.coverage_requirements.map(item => testPointLabel(item)).join('、')}
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}
        {assumptions.length > 0 && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">假设 ({assumptions.length})</div>
            <div className="space-y-1">
              {assumptions.map((a, idx) => (
                <div key={idx} className="break-words text-xs text-gray-600">• {displayText(a.assumption || a)}</div>
              ))}
            </div>
          </div>
        )}
      </div>
    );
  };

  if (analyses.length === 0) {
    return (
      <div className="text-sm text-gray-400 text-center py-4">
        {latestAnalysis ? '暂无历史分析' : '发送需求后自动生成'}
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {(() => {
        const latestA = analyses.length > 0 ? analyses[0] : null;
        if (latestA && latestA.subVersion >= 3) {
          return (
            <div className="rounded-lg bg-amber-50 border border-amber-200 px-3 py-2 text-[11px] text-amber-700">
              已进行 {latestA.subVersion} 次增量分析，建议输入「重新分析」执行全量分析以确保准确性。
            </div>
          );
        }
        return null;
      })()}
      {analyses.map((a, idx) => {
        const isLatest = idx === 0;
        const isExpanded = expandedVersions.has(a.id);
        return (
          <div key={a.id} className="border border-gray-200 rounded-lg overflow-hidden">
            <button
              onClick={() => toggleVersion(a.id)}
              className="flex w-full items-start justify-between gap-2 bg-gray-50 px-3 py-2 transition-colors hover:bg-gray-100"
            >
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-[11px] font-medium text-gray-700">版本 {versionLabel(a.version, a.subVersion)}</span>
                {isLatest && <span className="text-[10px] px-1.5 py-0.5 rounded bg-blue-100 text-blue-700">最新</span>}
                <span className={`text-[10px] px-1.5 py-0.5 rounded ${
                  a.status === 'CONFIRMED' ? 'bg-green-100 text-green-700' :
                  a.status === 'SKIPPED' ? 'bg-gray-100 text-gray-500' :
                  'bg-yellow-100 text-yellow-700'
                }`}>
                  {a.status === 'CONFIRMED' ? '已确认' : a.status === 'SKIPPED' ? '已跳过'
                    : a.status === 'NEED_SCOPE_CONFIRMATION' ? '待范围确认'
                    : a.status === 'SCOPE_CONFIRMED' ? '范围处理中'
                    : a.status === 'NEED_TEST_POINT_SCOPE_CONFIRMATION' ? '待测试点确认'
                    : a.status === 'TEST_POINT_SCOPE_CONFIRMED' ? '编排处理中'
                    : '待确认'}
                </span>
              </div>
              <span className={`text-[10px] text-gray-400 transition-transform ${isExpanded ? 'rotate-90' : ''}`}>▶</span>
            </button>
            {isExpanded && (
              <div className="px-3 py-3 border-t border-gray-100">
                {renderAnalysis(a, isLatest)}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

export default function TestCaseGeneration() {
  const { projectId } = useParams<{ projectId: string }>();
  const { showToast } = useApp();
  const [sessions, setSessions] = useState<GenerationSession[]>([]);
  const [activeSession, setActiveSession] = useState<GenerationSession | null>(null);
  const [messages, setMessages] = useState<GenerationMessage[]>([]);
  const [drafts, setDrafts] = useState<CaseDraft[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [models, setModels] = useState<ModelConfigRecord[]>([]);
  const [promptTemplates, setPromptTemplates] = useState<PromptTemplateRecord[]>([]);
  const [selectedModel, setSelectedModel] = useState<number | undefined>();
  const [selectedPromptTemplate, setSelectedPromptTemplate] = useState<number | undefined>();
  const [sessionModelDraft, setSessionModelDraft] = useState<number | ''>('');
  const [sessionPromptDraft, setSessionPromptDraft] = useState<number | ''>('');
  const [savingSessionConfig, setSavingSessionConfig] = useState(false);
  const [selectedDraft, setSelectedDraft] = useState<CaseDraft | null>(null);
  const [pendingDiscardDraft, setPendingDiscardDraft] = useState<CaseDraft | null>(null);
  const [discardingId, setDiscardingId] = useState<number | null>(null);
  const [duplicatingDraftId, setDuplicatingDraftId] = useState<number | null>(null);
  const [analysis, setAnalysis] = useState<RequirementAnalysis | null>(null);
  const [analyses, setAnalyses] = useState<RequirementAnalysis[]>([]);
  const [analysisLoading, setAnalysisLoading] = useState(false);
  const [savingRequirementScope, setSavingRequirementScope] = useState(false);
  const [savingTestPointScope, setSavingTestPointScope] = useState(false);
  const [affectedModalOpen, setAffectedModalOpen] = useState(false);
  const [incrementalGenerating, setIncrementalGenerating] = useState(false);
  const [renameDraft, setRenameDraft] = useState('');
  const [showRenameModal, setShowRenameModal] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [pendingArchiveSession, setPendingArchiveSession] = useState<GenerationSession | null>(null);
  const [archiving, setArchiving] = useState(false);
  const [actionNotice, setActionNotice] = useState<{ title: string; body: string } | null>(null);
  const [sessionKeyword, setSessionKeyword] = useState('');
  const [sessionStatusFilter, setSessionStatusFilter] = useState('');
  const [refreshingSessions, setRefreshingSessions] = useState(false);
  const [attachments, setAttachments] = useState<GenerationAttachment[]>([]);
  const [uploadingAttachment, setUploadingAttachment] = useState(false);
  const [activeAsyncTask, setActiveAsyncTask] = useState<AsyncGenerationTask | null>(null);
  const [restoringAsyncTask, setRestoringAsyncTask] = useState(false);
  const [asyncTaskBusy, setAsyncTaskBusy] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const attachmentInputRef = useRef<HTMLInputElement>(null);

  const completedAsyncStages = useMemo(
    () => (activeAsyncTask?.stages || []).filter(stage => stage.status === 'SUCCEEDED'),
    [activeAsyncTask?.stages],
  );
  const attentionAsyncStages = useMemo(
    () => (activeAsyncTask?.stages || []).filter(stage => stage.status !== 'SUCCEEDED'),
    [activeAsyncTask?.stages],
  );

  const loadSessions = async (silent = false) => {
    if (!projectId) return;
    if (silent) {
      setRefreshingSessions(true);
    } else {
      setLoading(true);
    }
    try {
      const [res, m, prompts] = await Promise.all([
        listGenerationSessions(Number(projectId)).catch(() => ({ items: [] })),
        listEnabledModelConfigs().catch(() => []),
        listEnabledPromptTemplates().catch(() => []),
      ]);
      const nextSessions = res.items || [];
      setSessions(nextSessions);
      setModels(m);
      setPromptTemplates(prompts);
      if (m.length > 0) setSelectedModel(prev => prev ?? m[0].id);
      if (prompts.length > 0) setSelectedPromptTemplate(prev => prev ?? prompts[0].id);
      setActiveSession(current => {
        if (current) {
          return nextSessions.find(item => item.id === current.id) || nextSessions[0] || null;
        }
        return nextSessions[0] || null;
      });
    } finally {
      setLoading(false);
      setRefreshingSessions(false);
    }
  };

  useEffect(() => {
    void loadSessions();
  }, [projectId]);

  const maybeOpenAffectedCasesModal = (nextAnalysis: RequirementAnalysis | null | undefined, nextDrafts: CaseDraft[]) => {
    if (!nextAnalysis?.affectedCases || nextAnalysis.affectedCases === '[]' || nextAnalysis.affectedCases === 'null') return;
    try {
      const parsed = JSON.parse(nextAnalysis.affectedCases);
      if (Array.isArray(parsed) && parsed.length > 0) {
        const totalDrafts = nextDrafts.filter(d => d.status !== 'DEPRECATED').length;
        const affectedRatio = totalDrafts > 0 ? parsed.length / totalDrafts : 1;
        const isMinor = nextAnalysis.changeScope === 'MINOR';
        if (isMinor && affectedRatio < 0.5) {
          setAffectedModalOpen(true);
        } else {
          showToast(`变更范围${isMinor ? '较大' : '为重大变更'}（影响 ${parsed.length}/${totalDrafts} 个用例），建议全量重新生成。可输入「重新生成」执行全量生成。`, 'info');
        }
      }
    } catch {}
  };

  const refreshSessionData = async (sessionId: number, options?: { showLoading?: boolean }) => {
    if (!projectId) return null;
    if (options?.showLoading) setAnalysisLoading(true);
    try {
      const [msgs, d, a, allAnalyses, atts] = await Promise.all([
        listGenerationMessages(Number(projectId), sessionId).catch(() => []),
        listGenerationDrafts(Number(projectId), sessionId).catch(() => []),
        getLatestGenerationAnalysis(Number(projectId), sessionId).catch(() => null),
        listGenerationAnalyses(Number(projectId), sessionId).catch(() => []),
        listGenerationAttachments(Number(projectId), sessionId).catch(() => []),
      ]);
      setMessages(msgs);
      setDrafts(d);
      setAnalysis(a);
      setAnalyses(allAnalyses);
      setAttachments(atts);
      return { msgs, d, a, allAnalyses, atts };
    } finally {
      if (options?.showLoading) setAnalysisLoading(false);
    }
  };

  const refreshSessionDataLater = (sessionId: number, delays: number[] = [1500, 4000, 8000]) => {
    delays.forEach(delay => {
      window.setTimeout(() => {
        void refreshSessionData(sessionId);
        void loadSessions(true);
      }, delay);
    });
  };

  useEffect(() => {
    if (!projectId || !activeSession) return;
    let disposed = false;
    // A send lock belongs to the previous task/session snapshot. The restored task
    // below becomes the single source of truth while the current session is loaded.
    setSending(false);
    setAnalysisLoading(false);
    setActiveAsyncTask(null);
    void refreshSessionData(activeSession.id, { showLoading: true });
    if (!activeSession.executionTaskId) return () => {
      disposed = true;
    };
    setRestoringAsyncTask(true);
    void getAsyncGenerationTask(Number(projectId), activeSession.executionTaskId)
      .then(task => {
        if (!disposed) {
          setActiveAsyncTask(task);
          if (asyncTaskFinished(task)) {
            setSending(false);
            setAnalysisLoading(false);
          }
        }
      })
      .catch(error => {
        if (!disposed) {
          // A stale executionTaskId must not permanently lock the message composer.
          console.warn('Failed to restore async generation task', error);
          setActiveAsyncTask(null);
        }
      })
      .finally(() => {
        if (!disposed) setRestoringAsyncTask(false);
      });
    return () => {
      disposed = true;
    };
  }, [projectId, activeSession?.id, activeSession?.executionTaskId]);

  useEffect(() => {
    if (!asyncTaskFinished(activeAsyncTask)) return;
    // A terminal snapshot may arrive through polling, task restoration, or a
    // session-list refresh. All three paths must release the composer lock.
    setSending(false);
    setAnalysisLoading(false);
  }, [activeAsyncTask?.taskId, activeAsyncTask?.status]);

  useEffect(() => {
    if (!activeSession) return;
    setSessionModelDraft(activeSession.modelConfigId ?? '');
    setSessionPromptDraft(activeSession.promptTemplateId ?? '');
  }, [activeSession?.id, activeSession?.modelConfigId, activeSession?.promptTemplateId]);

  useEffect(() => {
    if (!projectId || !activeSession || !activeAsyncTask || !asyncTaskRunning(activeAsyncTask)) return;
    let stopped = false;
    let timer: number | null = null;
    let queryErrorShown = false;
    const poll = async () => {
      try {
        const task = await getAsyncGenerationTask(Number(projectId), activeAsyncTask.taskId);
        if (stopped) return;
        queryErrorShown = false;
        setActiveAsyncTask(current => {
          if (current?.taskId === task.taskId
              && !asyncTaskRunning(current)
              && asyncTaskRunning(task)) {
            return current;
          }
          return task;
        });
        const interimRefresh = isRequirementAnalysisTask(task)
          ? await refreshSessionData(activeSession.id).catch(() => null)
          : null;
        if (task.status === 'SUCCEEDED') {
          setSending(false);
          setAnalysisLoading(false);
          const refreshed = interimRefresh ?? await refreshSessionData(activeSession.id);
          void loadSessions(true);
          if (isRequirementAnalysisTask(task)) {
            if (refreshed?.a) {
              maybeOpenAffectedCasesModal(refreshed.a, refreshed.d);
            } else {
              refreshSessionDataLater(activeSession.id, [1200, 3000, 6000]);
            }
            if (isTestPointScopeContinuationTask(task)) {
              setActionNotice({
                title: '用例编排完成',
                body: '已严格按确认后的测试点生成节点用例与完整流程编排。检查后输入“生成用例”才会创建草稿。',
              });
              showToast('用例编排完成');
            } else if (isRequirementScopeContinuationTask(task)) {
              setActionNotice({
                title: '测试点生成完成',
                body: '覆盖矩阵和测试点已按确认后的需求范围生成。请审核哪些测试点生成用例、仅参考或排除。',
              });
              showToast('测试点生成完成');
            } else {
              setActionNotice({
                title: '需求范围识别完成',
                body: '需求已拆为可审核范围项。请先确认本期生成、仅参考和排除内容，再进入覆盖矩阵与测试点生成。',
              });
              showToast('需求范围识别完成');
            }
          } else if (isIncrementalCaseTask(task)) {
            setActionNotice({
              title: '增量更新完成',
              body: '受影响草稿已更新，右侧草稿箱已刷新。',
            });
            showToast('增量更新完成');
          } else {
            setActionNotice({
              title: '用例生成完成',
              body: `已生成 ${task.draftCount} 条草稿，右侧草稿箱可直接查看详情或舍弃。`,
            });
            showToast('用例生成完成');
          }
        } else if (task.status === 'FAILED' || task.status === 'TIMEOUT' || task.status === 'CANCELED') {
          setSending(false);
          setAnalysisLoading(false);
          await refreshSessionData(activeSession.id);
          void loadSessions(true);
          if (task.status === 'CANCELED') {
            showToast(isRequirementAnalysisTask(task) ? '分析任务已取消' : '生成任务已取消');
          } else {
            showToast(asyncTaskErrorMessage(task), 'error');
          }
        } else if (!stopped) {
          timer = window.setTimeout(() => void poll(), 2000);
        }
      } catch (error: any) {
        if (!stopped) {
          if (!queryErrorShown) {
            queryErrorShown = true;
            showToast(error.message || '查询生成任务失败，正在自动重试', 'error');
          }
          timer = window.setTimeout(() => void poll(), 3000);
        }
      }
    };
    void poll();
    return () => {
      stopped = true;
      if (timer !== null) window.clearTimeout(timer);
    };
  }, [projectId, activeSession?.id, activeAsyncTask?.taskId, activeAsyncTask?.status]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, sending]);

  const filteredSessions = useMemo(() => {
    const keyword = sessionKeyword.trim().toLowerCase();
    return sessions.filter(session => {
      const statusOk = !sessionStatusFilter || session.status === sessionStatusFilter;
      if (!statusOk) return false;
      if (!keyword) return true;
      return [
        session.sessionTitle || '',
        session.status || '',
        session.currentStage || '',
      ].some(value => value.toLowerCase().includes(keyword));
    });
  }, [sessions, sessionKeyword, sessionStatusFilter]);

  const draftCount = drafts.filter(d => d.status !== 'DEPRECATED').length;
  const confirmedDraftCount = drafts.filter(item => item.status === 'CONFIRMED').length;
  const sessionCount = sessions.length;
  const activeSessionStage = activeSession?.currentStage || '待分析';
  const sessionStatusOptions = useMemo(
    () => Array.from(new Set(sessions.map(item => item.status).filter(Boolean))),
    [sessions]
  );

  const handleNewSession = async () => {
    if (!projectId) return;
    try {
      const session = await createGenerationSession(Number(projectId), {
        sessionTitle: '新会话',
        modelConfigId: selectedModel,
        promptTemplateId: selectedPromptTemplate,
      });
      setSessions(prev => [session, ...prev]);
      setActiveSession(session);
      setMessages([]);
      setDrafts([]);
      setActionNotice({
        title: '已创建新会话',
        body: '现在可以直接输入需求，系统会结合当前项目上下文开始生成草稿用例。',
      });
    } catch {
      showToast('创建会话失败', 'error');
    }
  };

  const handleSaveSessionConfig = async () => {
    if (!projectId || !activeSession) return;
    setSavingSessionConfig(true);
    try {
      await updateGenerationSession(Number(projectId), activeSession.id, {
        modelConfigId: sessionModelDraft === '' ? null : Number(sessionModelDraft),
        promptTemplateId: sessionPromptDraft === '' ? null : Number(sessionPromptDraft),
      });
      setSessions(prev => prev.map(item => (
        item.id === activeSession.id
          ? {
              ...item,
              modelConfigId: sessionModelDraft === '' ? null : Number(sessionModelDraft),
              promptTemplateId: sessionPromptDraft === '' ? null : Number(sessionPromptDraft),
            }
          : item
      )));
      setActiveSession(prev => prev ? {
        ...prev,
        modelConfigId: sessionModelDraft === '' ? null : Number(sessionModelDraft),
        promptTemplateId: sessionPromptDraft === '' ? null : Number(sessionPromptDraft),
      } : prev);
      setActionNotice({
        title: '会话配置已更新',
        body: '当前会话后续的分析与生成会按新的模型、提示模板和已选择的 TOM 使用模式继续执行。',
      });
      showToast('会话配置已更新');
    } catch (error: any) {
      showToast(error.message || '更新会话配置失败', 'error');
    } finally {
      setSavingSessionConfig(false);
    }
  };

  const handleRenameSession = async () => {
    if (!projectId || !activeSession || !renameDraft.trim()) return;
    setRenaming(true);
    try {
      const nextTitle = renameDraft.trim();
      await updateGenerationSession(Number(projectId), activeSession.id, { sessionTitle: nextTitle });
      setSessions(prev => prev.map(item => item.id === activeSession.id ? { ...item, sessionTitle: nextTitle } : item));
      setActiveSession(prev => prev ? { ...prev, sessionTitle: nextTitle } : prev);
      setShowRenameModal(false);
      setRenameDraft('');
      setActionNotice({
        title: '会话名称已更新',
        body: '新的会话标题已经生效，后续在项目里检索和回看这轮生成记录会更容易定位。',
      });
      showToast('会话名称已更新');
    } catch (error: any) {
      showToast(error.message || '更新会话失败', 'error');
    } finally {
      setRenaming(false);
    }
  };

  const handleArchiveSession = async () => {
    if (!projectId || !pendingArchiveSession) return;
    setArchiving(true);
    try {
      await archiveGenerationSession(Number(projectId), pendingArchiveSession.id);
      setSessions(prev => prev.filter(item => item.id !== pendingArchiveSession.id));
      setActiveSession(prev => {
        if (!prev || prev.id !== pendingArchiveSession.id) return prev;
        const remaining = sessions.filter(item => item.id !== pendingArchiveSession.id);
        return remaining[0] || null;
      });
      if (activeSession?.id === pendingArchiveSession.id) {
        setMessages([]);
        setDrafts([]);
      }
      setPendingArchiveSession(null);
      setActionNotice({
        title: '会话已删除',
        body: '该会话已从列表中删除。如果还要继续整理草稿，可以直接去本地用例库查看已经沉淀的结果。',
      });
      showToast('会话已删除');
      await loadSessions(true);
    } catch (error: any) {
      showToast(error.message || '删除会话失败', 'error');
    } finally {
      setArchiving(false);
    }
  };

  const handleSend = async () => {
    if (!input.trim() || !projectId || !activeSession || sending || restoringAsyncTask || asyncTaskRunning(activeAsyncTask)) return;
    const content = input.trim();
    const optimisticMessage = buildOptimisticUserMessage(activeSession.id, content);
    setInput('');
    setMessages(prev => mergeSentMessages(prev, optimisticMessage, []));

    if (shouldUseAsyncAnalysis(activeSession, content)) {
      setSending(true);
      setAnalysisLoading(true);
      setAsyncTaskBusy(true);
      try {
        const task = await startSessionRequirementAnalysisTask(Number(projectId), activeSession.id, content);
        setActiveAsyncTask(task);
        setMessages(prev => mergeSentMessages(prev, optimisticMessage, [{
          id: -Date.now() - 1,
          sessionId: activeSession.id,
          role: 'ASSISTANT',
          content: `已创建需求分析任务 #${task.taskId}，正在后台分析需求。分析期间重复点击不会创建重复模型调用。`,
          structuredPayload: null,
          analysisVersion: analysis?.version ?? null,
          stage: 'SYSTEM_ANALYSIS_START',
          createdAt: new Date().toISOString(),
        }]));
        setActionNotice({
          title: '需求分析中',
          body: `任务 #${task.taskId} 已开始执行。系统会自动刷新需求分析结果，重复点击不会创建重复模型调用。`,
        });
        void refreshSessionData(activeSession.id);
      } catch (error: any) {
        setMessages(prev => prev.filter(item => item.id !== optimisticMessage.id));
        setInput(content);
        setSending(false);
        setAnalysisLoading(false);
        showToast(error.message || '启动需求分析任务失败', 'error');
      } finally {
        setAsyncTaskBusy(false);
      }
      return;
    }

    if (isCaseGenerationIntent(content) && !requiresScopeConfirmation(activeSession, analysis)) {
      setSending(true);
      setAnalysisLoading(true);
      setAsyncTaskBusy(true);
      try {
        const task = await startSessionCaseGenerationTask(Number(projectId), activeSession.id, content);
        setActiveAsyncTask(task);
        setMessages(prev => mergeSentMessages(prev, optimisticMessage, [{
          id: -Date.now() - 1,
          sessionId: activeSession.id,
          role: 'ASSISTANT',
          content: `已创建用例生成任务 #${task.taskId}，正在后台生成草稿。生成期间你可以留在当前页面查看进度。`,
          structuredPayload: null,
          analysisVersion: analysis?.version ?? null,
          stage: 'SYSTEM_CASE_GENERATING',
          createdAt: new Date().toISOString(),
        }]));
        setActionNotice({
          title: '用例生成中',
          body: `任务 #${task.taskId} 已开始执行。系统会自动刷新右侧草稿箱，重复点击不会创建重复模型调用。`,
        });
        void refreshSessionData(activeSession.id);
      } catch (error: any) {
        setMessages(prev => prev.filter(item => item.id !== optimisticMessage.id));
        setInput(content);
        setSending(false);
        setAnalysisLoading(false);
        showToast(error.message || '启动生成任务失败', 'error');
      } finally {
        setAsyncTaskBusy(false);
      }
      return;
    }

    setSending(true);
    setAnalysisLoading(true);
    try {
      const reply = await sendGenerationMessage(Number(projectId), activeSession.id, content);
      const replyMessages = reply.newMessages || [];
      setMessages(prev => mergeSentMessages(prev, optimisticMessage, replyMessages));
      if (reply.analysis) {
        const nextAnalysis = reply.analysis as RequirementAnalysis;
        setAnalysis(nextAnalysis);
        setAnalyses(prev => [nextAnalysis, ...prev.filter(item => item.id !== nextAnalysis.id)]);
        maybeOpenAffectedCasesModal(nextAnalysis, drafts);
      }
      // 刷新草稿与分析结果
      const [nextMessages, nextDrafts, latestAnalysis, allAnalyses] = await Promise.all([
        listGenerationMessages(Number(projectId), activeSession.id).catch(() => []),
        listGenerationDrafts(Number(projectId), activeSession.id).catch(() => []),
        getLatestGenerationAnalysis(Number(projectId), activeSession.id).catch(() => null),
        listGenerationAnalyses(Number(projectId), activeSession.id).catch(() => []),
      ]);
      if (nextMessages.length > 0) {
        const containsSentUser = nextMessages.some(item => (
          isUserRole(item.role) && (item.content || '').trim() === content
        ));
        setMessages(prev => containsSentUser ? nextMessages : mergeSentMessages(prev, optimisticMessage, []));
      }
      setDrafts(nextDrafts);
      if (latestAnalysis) setAnalysis(latestAnalysis);
      if (allAnalyses.length > 0) setAnalyses(allAnalyses);
      const activeDraftCount = nextDrafts.filter((d: any) => d.status !== 'DEPRECATED').length;
      setActionNotice({
        title: activeDraftCount > 0 ? '草稿已更新' : '消息已发送',
        body: activeDraftCount > 0
          ? `当前会话已有 ${activeDraftCount} 条草稿，可继续在右侧预览，或去本地用例库整理。`
          : '系统已收到需求描述，稍后会把分析结果沉淀为草稿用例。',
      });
      void loadSessions(true);
    } catch (error: any) {
      const message = error.message || '发送失败';
      showToast(message, 'error');
      setActionNotice({
        title: '分析请求异常',
        body: '请求已结束但后端可能仍在写入分析结果，系统正在刷新当前会话状态。若模型网关超时，请更换模型或减少本轮 TOM/需求上下文。',
      });
      await refreshSessionData(activeSession.id);
      await loadSessions(true);
      refreshSessionDataLater(activeSession.id);
    } finally {
      setSending(false);
      setAnalysisLoading(false);
    }
  };

  const handlePickAttachment = () => {
    if (!activeSession || uploadingAttachment) return;
    attachmentInputRef.current?.click();
  };

  const handleAttachmentChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !projectId || !activeSession) return;
    setUploadingAttachment(true);
    try {
      const uploaded = await uploadGenerationAttachment(Number(projectId), activeSession.id, file);
      setAttachments(prev => [...prev, uploaded]);
      showToast(`已上传 ${file.name}`);
      setActionNotice({
        title: '附件已上传',
        body: '该文件/图片会参与当前会话的后续需求分析。解析完成后，系统会自动把文本或图片理解结果并入分析上下文。',
      });
    } catch (error: any) {
      showToast(error.message || '上传失败', 'error');
    } finally {
      setUploadingAttachment(false);
    }
  };

  const handleRetryAsyncTask = async () => {
    if (!projectId || !activeAsyncTask) return;
    setAsyncTaskBusy(true);
    setSending(true);
    setAnalysisLoading(true);
    try {
      const task = await retryAsyncGenerationTask(Number(projectId), activeAsyncTask.taskId);
      setActiveAsyncTask(task);
      setActionNotice({
        title: '正在从失败节点继续',
        body: `任务 #${task.taskId} 会复用已完成的分析节点，只重新执行失败或未完成节点；完成后会自动刷新分析结果和草稿箱。`,
      });
    } catch (error: any) {
      setSending(false);
      setAnalysisLoading(false);
      showToast(error.message || '重试失败', 'error');
    } finally {
      setAsyncTaskBusy(false);
    }
  };

  const handleCancelAsyncTask = async () => {
    if (!projectId || !activeAsyncTask) return;
    setAsyncTaskBusy(true);
    try {
      const task = await cancelAsyncGenerationTask(Number(projectId), activeAsyncTask.taskId);
      setActiveAsyncTask(task);
      setSending(false);
      setAnalysisLoading(false);
      setActionNotice({
        title: '生成任务已取消',
        body: '系统会忽略该任务后续结果，不会把取消后的输出写入当前会话草稿箱。',
      });
    } catch (error: any) {
      showToast(error.message || '取消失败', 'error');
    } finally {
      setAsyncTaskBusy(false);
    }
  };

  const attachmentStatusLabel = (status?: string | null) => {
    switch ((status || '').toUpperCase()) {
      case 'PARSED':
        return '已解析';
      case 'PARSING':
        return '解析中';
      case 'FAILED':
        return '解析失败';
      default:
        return status || '待处理';
    }
  };

  const attachmentStatusTone = (status?: string | null) => {
    switch ((status || '').toUpperCase()) {
      case 'PARSED':
        return 'bg-green-50 text-green-700';
      case 'PARSING':
        return 'bg-blue-50 text-blue-700';
      case 'FAILED':
        return 'bg-red-50 text-red-700';
      default:
        return 'bg-gray-100 text-gray-600';
    }
  };

  const handleIncrementalGenerate = async (selectedTitles: string[]) => {
    if (!projectId || !activeSession || selectedTitles.length === 0) return;
    // Match selected titles to draft IDs
    const selectedDraftIds = drafts
      .filter(d => d.status !== 'DEPRECATED' && selectedTitles.includes(d.caseTitle))
      .map(d => d.id);
    if (selectedDraftIds.length === 0) {
      setAffectedModalOpen(false);
      return;
    }
    setIncrementalGenerating(true);
    try {
      const task = await startSessionIncrementalGenerationTask(Number(projectId), activeSession.id, selectedDraftIds);
      setActiveAsyncTask(task);
      setAffectedModalOpen(false);
      setSending(true);
      setAnalysisLoading(true);
      setActionNotice({
        title: '增量更新中',
        body: `任务 #${task.taskId} 已开始执行。系统会自动刷新右侧草稿箱，重复点击不会创建重复模型调用。`,
      });
      showToast('已启动增量更新任务');
    } catch (error: any) {
      showToast(error.message || '增量生成失败', 'error');
    } finally {
      setIncrementalGenerating(false);
    }
  };

  const handleConfirmTestPointScope = async (version: number, decisions: TestPointScopeDecision[]) => {
    if (!projectId || !activeSession) return;
    setSavingTestPointScope(true);
    try {
      setAnalysisLoading(true);
      const task = await confirmGenerationTestPointScope(Number(projectId), activeSession.id, version, decisions);
      setActiveAsyncTask(task);
      setActionNotice({
        title: '测试点范围已确认',
        body: `任务 #${task.taskId} 只会使用标记为“生成用例”的测试点编排节点用例和完整流程；仅参考与排除项保留审计记录但不会进入模型输入。`,
      });
      showToast('测试点范围已确认，正在生成用例编排');
      await refreshSessionData(activeSession.id);
    } catch (error: any) {
      setAnalysisLoading(false);
      showToast(error.message || '保存测试点范围失败', 'error');
      throw error;
    } finally {
      setSavingTestPointScope(false);
    }
  };

  const handleConfirmRequirementScope = async (version: number, decisions: RequirementScopeDecision[]) => {
    if (!projectId || !activeSession) return;
    setSavingRequirementScope(true);
    setAnalysisLoading(true);
    try {
      const task = await confirmGenerationRequirementScope(Number(projectId), activeSession.id, version, decisions);
      setActiveAsyncTask(task);
      setActionNotice({
        title: '需求范围已确认',
        body: `任务 #${task.taskId} 将严格使用已确认的本期需求项生成覆盖矩阵和测试点，不会重新理解或扩写已排除内容。`,
      });
      showToast('需求范围已确认，正在生成测试点');
      await refreshSessionData(activeSession.id);
    } catch (error: any) {
      setAnalysisLoading(false);
      showToast(error.message || '确认需求范围失败', 'error');
      throw error;
    } finally {
      setSavingRequirementScope(false);
    }
  };

  const handleDiscardDraft = async () => {
    if (!projectId || !pendingDiscardDraft) return;
    setDiscardingId(pendingDiscardDraft.id);
    try {
      await deprecateLocalCase(Number(projectId), pendingDiscardDraft.id);
      setDrafts(prev => prev.filter(item => item.id !== pendingDiscardDraft.id));
      if (selectedDraft?.id === pendingDiscardDraft.id) setSelectedDraft(null);
      setActionNotice({
        title: '草稿已舍弃',
        body: '该草稿已从当前会话和本地用例库中删除，不会再参与后续提交。',
      });
      showToast('草稿已舍弃');
    } catch (error: any) {
      showToast(error.message || '舍弃失败', 'error');
    } finally {
      setDiscardingId(null);
      setPendingDiscardDraft(null);
    }
  };

  const handleDuplicateDraft = async (draft: CaseDraft) => {
    if (!projectId || !activeSession) return;
    setDuplicatingDraftId(draft.id);
    try {
      await duplicateLocalCase(Number(projectId), draft.id);
      await refreshSessionData(activeSession.id);
      setActionNotice({
        title: '草稿已复制',
        body: '已创建一条独立草稿。你可以在当前草稿箱或本地用例库继续编辑后再提交。',
      });
      showToast('已复制为新的草稿');
    } catch (error: any) {
      showToast(error.message || '复制草稿失败', 'error');
    } finally {
      setDuplicatingDraftId(null);
    }
  };

  const statusTone = (status: string) => {
    if (status === 'COMPLETED' || status === 'DONE') return 'bg-green-50 text-green-700';
    if (status === 'RUNNING' || status === 'PROCESSING') return 'bg-blue-50 text-blue-700';
    if (status === 'FAILED' || status === 'ERROR') return 'bg-red-50 text-red-700';
    return 'bg-gray-100 text-gray-600';
  };

  const selectedDraftRefs = parseDraftSourceRefs(selectedDraft);
  const selectedEvidenceSummary = selectedDraftRefs?.evidenceSummary;
  const selectedQualityGate = selectedDraftRefs?.qualityGate;
  const selectedSourceTestPoints = Array.isArray(selectedDraftRefs?.sourceTestPoints)
    ? selectedDraftRefs.sourceTestPoints
    : [];
  const selectedGeneratorRefs = selectedDraftRefs?.generatorRefs || selectedDraftRefs || {};

  return (
    <div className="flex min-h-[calc(100vh-56px)] min-w-0 flex-col overflow-hidden animate-fade-in xl:h-[calc(100vh-56px)] xl:flex-row">
      {/* 左侧会话列表 */}
      <div className="flex max-h-[420px] w-full shrink-0 flex-col border-b border-gray-200 bg-white xl:max-h-none xl:w-72 xl:border-b-0 xl:border-r">
        <div className="p-3 border-b border-gray-100 space-y-3">
          <div className="grid grid-cols-2 gap-2">
            <div className="rounded-lg border border-gray-200 bg-gray-50 px-3 py-2">
              <div className="text-[11px] text-gray-500">会话数</div>
              <div className="text-sm font-semibold text-gray-900 mt-1">{sessionCount}</div>
            </div>
            <div className="rounded-lg border border-gray-200 bg-gray-50 px-3 py-2">
              <div className="text-[11px] text-gray-500">当前草稿</div>
              <div className="text-sm font-semibold text-gray-900 mt-1">{draftCount}</div>
            </div>
          </div>
          <select
            value={selectedModel || ''}
            onChange={e => setSelectedModel(Number(e.target.value) || undefined)}
            className="w-full h-8 px-2 bg-gray-50 border border-gray-200 rounded-lg text-xs focus:border-gray-400 outline-none"
          >
            {models.length === 0 && <option value="">暂无可用模型</option>}
            {models.map(m => (
              <option key={m.id} value={m.id}>{m.configName}</option>
            ))}
          </select>
          <select
            value={selectedPromptTemplate || ''}
            onChange={e => setSelectedPromptTemplate(Number(e.target.value) || undefined)}
            className="w-full h-8 px-2 bg-gray-50 border border-gray-200 rounded-lg text-xs focus:border-gray-400 outline-none"
          >
            {promptTemplates.length === 0 && <option value="">暂无可用提示模板</option>}
            {promptTemplates.map(template => (
              <option key={template.id} value={template.id}>{template.promptName}</option>
            ))}
          </select>
          <button
            onClick={handleNewSession}
            className="w-full bg-slate-900 text-white px-3 py-2 rounded-lg text-sm font-semibold hover:bg-slate-800 transition-colors"
          >
            + 新会话
          </button>
          <input
            type="text"
            value={sessionKeyword}
            onChange={e => setSessionKeyword(e.target.value)}
            placeholder="搜索会话标题、状态、阶段"
            className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-xs focus:border-gray-400 outline-none"
          />
          <select
            value={sessionStatusFilter}
            onChange={e => setSessionStatusFilter(e.target.value)}
            className="w-full h-8 px-2 bg-gray-50 border border-gray-200 rounded-lg text-xs focus:border-gray-400 outline-none"
          >
            <option value="">全部状态</option>
            {sessionStatusOptions.map(status => (
              <option key={status} value={status}>{uiStatusLabel(status)}</option>
            ))}
          </select>
          <div className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-gray-400">
            <span>当前展示 {filteredSessions.length} 条</span>
            <div className="flex flex-wrap items-center gap-2">
              <button onClick={() => void loadSessions(true)} className="text-sky-600 hover:text-sky-800 transition-colors">
                {refreshingSessions ? '刷新中...' : '刷新'}
              </button>
              <Link to={projectId ? `/projects/${projectId}/local-cases` : '#'} className="text-sky-600 hover:text-sky-800 transition-colors">
                打开本地用例库
              </Link>
            </div>
          </div>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {filteredSessions.map(s => (
            <button
              key={s.id}
              onClick={() => setActiveSession(s)}
              className={`group w-full text-left px-3 py-2.5 border-b border-gray-100 transition-colors ${
                activeSession?.id === s.id ? 'bg-gray-100' : 'hover:bg-gray-50'
              }`}
            >
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <div className="text-sm font-medium text-gray-900 truncate">{s.sessionTitle || '未命名会话'}</div>
                  <div className="text-[11px] text-gray-500 mt-0.5">{new Date(s.createdAt).toLocaleDateString('zh-CN')}</div>
                </div>
                <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded flex-shrink-0 ${statusTone(s.status)}`}>
                  {uiStatusLabel(s.status)}
                </span>
              </div>
              <div className="text-[11px] text-gray-400 mt-1 truncate">
                阶段：{messageStageLabel(s.currentStage) || '待分析'}
              </div>
              <div className="mt-2 flex items-center gap-3 opacity-0 group-hover:opacity-100 transition-opacity">
                <span
                  onClick={(e) => {
                    e.stopPropagation();
                    setRenameDraft(s.sessionTitle || '未命名会话');
                    setActiveSession(s);
                    setShowRenameModal(true);
                  }}
                  className="text-[11px] text-sky-600 hover:text-sky-800 transition-colors"
                >
                  改名
                </span>
                <span
                  onClick={(e) => {
                    e.stopPropagation();
                    setPendingArchiveSession(s);
                  }}
                  className="text-[11px] text-red-500 hover:text-red-700 transition-colors px-1.5 py-0.5 rounded hover:bg-red-50"
                >
                  删除
                </span>
              </div>
            </button>
          ))}
          {sessions.length === 0 && !loading && (
            <div className="px-3 py-8 text-center text-gray-400 text-sm">暂无会话</div>
          )}
          {sessions.length > 0 && filteredSessions.length === 0 && !loading && (
            <div className="px-3 py-8 text-center text-gray-400 text-sm">当前筛选条件下没有会话</div>
          )}
        </div>
      </div>

      {/* 中间对话区 */}
      <div className="flex min-h-[560px] min-w-0 flex-1 flex-col bg-gray-50 xl:min-h-0">
        {activeSession ? (
          <>
            <div className="flex flex-col gap-3 border-b border-gray-200 bg-white px-5 py-3 sm:flex-row sm:items-start sm:justify-between">
              <div className="min-w-0 flex-1">
                <div className="text-sm font-semibold text-gray-900 truncate">{activeSession.sessionTitle || '未命名会话'}</div>
                <div className="text-[11px] text-gray-500 mt-0.5">
                  阶段：{activeSessionStage} · 已确认草稿 {confirmedDraftCount} / {draftCount}
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-2 sm:justify-end">
                <button
                  onClick={() => {
                    setRenameDraft(activeSession.sessionTitle || '未命名会话');
                    setShowRenameModal(true);
                  }}
                  className="text-xs text-sky-600 hover:text-sky-800 transition-colors"
                >
                  改名
                </button>
                <button
                  onClick={() => setPendingArchiveSession(activeSession)}
                  className="text-xs text-red-500 hover:text-red-700 transition-colors px-2 py-1 rounded hover:bg-red-50"
                >
                  删除会话
                </button>
                <Link to={projectId ? `/projects/${projectId}/overview` : '#'} className="text-xs text-gray-500 hover:text-gray-800 transition-colors">
                  返回概览
                </Link>
                <span className="shrink-0 text-[11px] text-gray-500 font-mono">#{activeSession.id}</span>
              </div>
            </div>
            <div className="px-5 py-3 bg-white border-b border-gray-100">
              <div className="grid grid-cols-1 lg:grid-cols-[1fr_1fr_auto] gap-3 items-end">
                <div>
                  <label className="block text-[11px] font-medium text-gray-500 mb-1">会话模型</label>
                  <select
                    value={sessionModelDraft}
                    onChange={e => setSessionModelDraft(e.target.value ? Number(e.target.value) : '')}
                    className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-xs focus:border-gray-400 outline-none"
                  >
                    <option value="">不指定</option>
                    {models.map(model => (
                      <option key={model.id} value={model.id}>{model.configName}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-[11px] font-medium text-gray-500 mb-1">提示模板</label>
                  <select
                    value={sessionPromptDraft}
                    onChange={e => setSessionPromptDraft(e.target.value ? Number(e.target.value) : '')}
                    className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-xs focus:border-gray-400 outline-none"
                  >
                    <option value="">默认模板</option>
                    {promptTemplates.map(template => (
                      <option key={template.id} value={template.id}>{template.promptName}</option>
                    ))}
                  </select>
                </div>
                <div className="flex items-center gap-3">
                  <button
                    onClick={handleSaveSessionConfig}
                    disabled={savingSessionConfig}
                    className="min-h-9 shrink-0 px-3 py-2 bg-slate-900 text-white text-xs font-semibold rounded-lg hover:bg-slate-800 transition-colors disabled:opacity-50"
                  >
                    {savingSessionConfig ? '保存中...' : '保存配置'}
                  </button>
                </div>
              </div>
            </div>
            {actionNotice && (
              <div className="mx-5 mt-5 rounded-xl border border-sky-200 bg-sky-50 px-4 py-3">
                <div className="text-sm font-semibold text-sky-900">{actionNotice.title}</div>
                <div className="text-xs text-sky-800 mt-1">{actionNotice.body}</div>
                <div className="mt-3 flex flex-wrap items-center gap-3 text-xs">
                  <Link to={projectId ? `/projects/${projectId}/local-cases` : '#'} className="text-sky-700 hover:text-sky-900 transition-colors">
                    去本地用例库
                  </Link>
                  <Link to={projectId ? `/projects/${projectId}/overview` : '#'} className="text-sky-700 hover:text-sky-900 transition-colors">
                    返回项目概览
                  </Link>
                  <button onClick={() => setActionNotice(null)} className="text-sky-700 hover:text-sky-900 transition-colors">
                    收起提示
                  </button>
                </div>
              </div>
            )}
            {activeAsyncTask && (
              <div className={`mx-5 mt-3 max-h-[42vh] shrink-0 overflow-y-auto rounded-xl border px-4 py-3 ${
                activeAsyncTask.status === 'SUCCEEDED'
                  ? 'border-green-200 bg-green-50'
                  : activeAsyncTask.status === 'FAILED' || activeAsyncTask.status === 'TIMEOUT'
                    ? 'border-red-200 bg-red-50'
                    : activeAsyncTask.status === 'CANCELED'
                      ? 'border-gray-200 bg-gray-50'
                      : 'border-amber-200 bg-amber-50'
              }`}>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <div className="text-sm font-semibold text-gray-900">
                      {asyncTaskTitle(activeAsyncTask)} #{activeAsyncTask.taskId}
                    </div>
                    <div className="mt-1 text-xs text-gray-600">
                      状态：{uiStatusLabel(activeAsyncTask.status)}
                      {activeAsyncTask.draftCount > 0 ? ` · 草稿 ${activeAsyncTask.draftCount} 条` : ''}
                    </div>
                    {(activeAsyncTask.status === 'FAILED' || activeAsyncTask.status === 'TIMEOUT') && (
                      <div className="text-red-700">
                        <AsyncErrorDetails message={asyncTaskErrorMessage(activeAsyncTask)} />
                      </div>
                    )}
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    {asyncTaskRunning(activeAsyncTask) && (
                      <button
                        onClick={handleCancelAsyncTask}
                        disabled={asyncTaskBusy}
                        className="rounded-lg border border-gray-200 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                      >
                        取消
                      </button>
                    )}
                    {(activeAsyncTask.status === 'FAILED' || activeAsyncTask.status === 'TIMEOUT') && (
                      <button
                        onClick={handleRetryAsyncTask}
                        disabled={asyncTaskBusy}
                        className="rounded-lg bg-slate-900 px-3 py-1.5 text-xs font-semibold text-white hover:bg-slate-800 disabled:opacity-50"
                      >
                        从失败节点继续
                      </button>
                    )}
                  </div>
                </div>
                {(activeAsyncTask.stages || []).length > 0 && (
                  <div className="mt-3 space-y-2">
                    {attentionAsyncStages.length > 0 && (
                      <div className="grid gap-2 sm:grid-cols-3">
                        {attentionAsyncStages.map(stage => <AsyncTaskStageCard key={stage.code} stage={stage} />)}
                      </div>
                    )}
                    {completedAsyncStages.length > 0 && (
                      <details className="rounded-lg border border-green-200 bg-green-50/70 text-xs text-green-800">
                        <summary className="cursor-pointer select-none px-3 py-2 font-medium marker:text-green-600">
                          已完成 {completedAsyncStages.length} 个节点
                          {attentionAsyncStages.length > 0 ? '，展开查看完成明细' : '，展开查看执行明细'}
                        </summary>
                        <div className="max-h-64 overflow-y-auto border-t border-green-200 p-2">
                          <div className="grid gap-2 sm:grid-cols-3">
                            {completedAsyncStages.map(stage => <AsyncTaskStageCard key={stage.code} stage={stage} />)}
                          </div>
                        </div>
                      </details>
                    )}
                  </div>
                )}
              </div>
            )}
            <div className="flex-1 overflow-y-auto p-5 space-y-3">
              {messages.map(m => {
                const userMessage = isUserRole(m.role);
                const stageLabel = messageStageLabel(m.stage);
                const analysisForMessage = !userMessage && isRequirementAnalysisStage(m.stage) && (m.analysisVersion ?? 0) > 0
                  ? analyses.find(item => item.version === m.analysisVersion) ?? null
                  : null;
                return (
                  <div key={`${m.id}-${m.createdAt}`} className={`flex ${userMessage ? 'justify-end' : 'justify-start'}`}>
                    <div className={`max-w-[92%] rounded-xl px-4 py-3 text-sm sm:max-w-[80%] ${
                      userMessage
                        ? 'bg-slate-900 text-white'
                        : 'bg-white border border-gray-200 text-gray-900'
                    }`}>
                      <div className="break-words whitespace-pre-wrap">
                        {analysisForMessage ? <AnalysisMessageContent analysis={analysisForMessage} /> : (m.content || '')}
                      </div>
                      <div className={`mt-2 flex flex-wrap items-center gap-2 text-[10px] ${
                        userMessage ? 'text-slate-300' : 'text-gray-400'
                      }`}>
                        {stageLabel && !userMessage && (
                          <span className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-500">{stageLabel}</span>
                        )}
                        {m.analysisVersion !== null && m.analysisVersion > 0 && !userMessage && (
                          <span>分析 v{versionLabel(m.analysisVersion ?? 0, analyses.find(item => item.version === m.analysisVersion)?.subVersion)}</span>
                        )}
                        <span>{new Date(m.createdAt).toLocaleTimeString('zh-CN')}</span>
                      </div>
                    </div>
                  </div>
                );
              })}
              {(sending || restoringAsyncTask || asyncTaskRunning(activeAsyncTask)) && (
                <div className="flex justify-start">
                  <div className="max-w-[92%] rounded-xl border border-sky-100 bg-white px-4 py-3 text-sm text-gray-900 shadow-sm sm:max-w-[80%]">
                    <div className="flex items-center gap-2">
                      <span className="h-2 w-2 animate-pulse rounded-full bg-sky-500" />
                      <span className="font-medium">{restoringAsyncTask ? '正在恢复任务状态' : asyncTaskRunning(activeAsyncTask) ? asyncTaskRunningLabel(activeAsyncTask) : '正在思考'}</span>
                    </div>
                    <div className="mt-1 text-xs leading-relaxed text-gray-500">
                      {restoringAsyncTask
                        ? '正在读取该会话已有的后台任务，完成后会自动恢复进度或允许继续发送。'
                        : asyncTaskRunning(activeAsyncTask)
                        ? asyncTaskRunningDescription(activeAsyncTask)
                        : '正在分析需求、整理测试点并生成草稿用例，完成后会自动更新右侧分析结果和草稿箱。'}
                    </div>
                  </div>
                </div>
              )}
              <div ref={messagesEndRef} />
            </div>
            <div className="border-t border-gray-100 bg-white px-4 py-2">
              <div className="flex flex-wrap items-center gap-2">
                <input
                  ref={attachmentInputRef}
                  type="file"
                  className="hidden"
                  accept=".txt,.md,.pdf,.doc,.docx,.png,.jpg,.jpeg,.webp"
                  onChange={handleAttachmentChange}
                />
                <button
                  onClick={handlePickAttachment}
                  disabled={!activeSession || uploadingAttachment}
                  className="rounded-lg border border-gray-200 bg-gray-50 px-3 py-1.5 text-xs font-medium text-gray-700 transition-colors hover:bg-gray-100 disabled:opacity-50"
                >
                  {uploadingAttachment ? '上传中...' : '上传文件/图片'}
                </button>
                <span className="text-[11px] text-gray-400">
                  支持需求文档、说明文本和截图，上传后会自动参与当前会话分析。
                </span>
              </div>
              {attachments.length > 0 && (
                <div className="mt-2 flex flex-wrap gap-2">
                  {attachments.map(att => (
                    <div key={att.id} className="max-w-full rounded-lg border border-gray-200 bg-gray-50 px-2.5 py-1.5 text-[11px] text-gray-700">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="max-w-[240px] truncate font-medium" title={att.fileName}>{att.fileName}</span>
                        <span className={`rounded px-1.5 py-0.5 text-[10px] ${attachmentStatusTone(att.parseStatus)}`}>
                          {attachmentStatusLabel(att.parseStatus)}
                        </span>
                        <span className="text-gray-400">{Math.max(1, Math.round(att.fileSize / 1024))} KB</span>
                      </div>
                      {att.parseError && (
                        <div className="mt-1 break-words text-[10px] text-red-500">{att.parseError}</div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div className="flex items-end gap-2 p-4 bg-white border-t border-gray-200">
              <textarea
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !e.shiftKey) {
                    e.preventDefault();
                    handleSend();
                  }
                }}
                placeholder="输入需求描述... (Ctrl+Enter 发送，Shift+Enter 换行)"
                rows={3}
                disabled={sending || restoringAsyncTask || asyncTaskRunning(activeAsyncTask)}
                className="flex-1 resize-none rounded-lg border border-gray-200 bg-gray-50 px-4 py-3 text-sm outline-none transition-colors focus:border-gray-400 disabled:opacity-50"
                style={{ minHeight: '80px', maxHeight: '160px' }}
              />
              <button
                onClick={handleSend}
                disabled={sending || restoringAsyncTask || asyncTaskRunning(activeAsyncTask) || !input.trim()}
                className="shrink-0 rounded-lg bg-slate-900 px-4 py-3 text-sm font-semibold text-white transition-colors hover:bg-slate-800 disabled:opacity-50"
              >
                {restoringAsyncTask ? '恢复中...' : asyncTaskRunning(activeAsyncTask) ? asyncTaskButtonLabel(activeAsyncTask) : sending ? '思考中...' : '发送'}
              </button>
            </div>
          </>
        ) : (
          <div className="flex-1 flex items-center justify-center text-gray-400 text-sm">
            选择或创建一个会话开始生成用例
          </div>
        )}
      </div>

      {/* 右侧分析 + 草稿面板 */}
      <div className="flex max-h-[620px] w-full shrink-0 flex-col overflow-hidden border-t border-gray-200 bg-white xl:max-h-none xl:w-80 xl:border-l xl:border-t-0">
        <div className="min-h-0 flex-1 overflow-y-auto">
          {/* 需求分析结果 */}
          <div className="px-4 py-3 border-b border-gray-100 bg-sky-50/50">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-gray-900">需求分析结果</h3>
              {analysisLoading && <span className="text-[11px] text-gray-400">{asyncTaskRunning(activeAsyncTask) ? asyncTaskRunningLabel(activeAsyncTask) : sending ? '思考中...' : '加载中...'}</span>}
            </div>
            <div className="text-[11px] text-gray-500 mt-1">
              当前会话最新一轮的结构化分析，可据此确认需求理解是否准确。
            </div>
          </div>
          <div className="px-4 py-3 border-b border-gray-100">
            {sending && (
              <div className="mb-3 rounded-lg border border-sky-100 bg-sky-50 px-3 py-2 text-xs leading-relaxed text-sky-800">
                正在分析本轮输入，完成后会在这里展示需求理解、测试点和待确认事项。
              </div>
            )}
            {!analysis ? (
              <div className={`text-sm text-gray-400 text-center ${sending ? 'py-2' : 'py-4'}`}>
                {activeSession ? '暂无分析结果，发送需求后自动生成' : '选择会话后查看分析'}
              </div>
            ) : (
              <AnalysisPanel
                analyses={analyses}
                latestAnalysis={analysis}
                onConfirmRequirementScope={handleConfirmRequirementScope}
                onConfirmTestPointScope={handleConfirmTestPointScope}
                savingRequirementScope={savingRequirementScope}
                savingTestPointScope={savingTestPointScope}
              />
            )}
          </div>

          {/* 草稿箱 */}
          <div className="px-4 py-3 border-b border-gray-100 bg-gray-50/50">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-gray-900">草稿箱</h3>
              <span className="text-[11px] font-mono text-gray-400">{asyncTaskRunning(activeAsyncTask) ? asyncTaskRunningLabel(activeAsyncTask) : sending ? '生成中...' : `${drafts.filter(d => d.status !== 'DEPRECATED').length} 条`}</span>
            </div>
            <div className="text-[11px] text-gray-500 mt-1">
              当前会话沉淀出来的最近草稿，可直接查看详情或舍弃。
            </div>
          </div>
          <div>
            {drafts.filter(d => d.status !== 'DEPRECATED').length === 0 ? (
              <div className="px-4 py-12 text-center text-gray-400 text-sm">
                {activeSession ? '暂无草稿，发送需求后自动生成' : '选择会话后查看草稿'}
              </div>
            ) : (
              <div className="divide-y divide-gray-100">
                {drafts.filter(d => d.status !== 'DEPRECATED').map(d => {
                  const refs = parseDraftSourceRefs(d);
                  const evidenceSummary = refs?.evidenceSummary;
                  const evidenceCount = evidenceSummary?.evidence_count ?? evidenceSummary?.evidenceCount;
                  return (
                    <div key={d.id} className="px-4 py-3 hover:bg-gray-50 transition-colors">
                      <div className="mb-1 flex items-start justify-between gap-2">
                        <div className="min-w-0 truncate text-sm font-medium text-gray-900">{d.caseTitle}</div>
                        <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded flex-shrink-0 ml-2 ${
                          d.priority === 'P0' ? 'bg-red-50 text-red-600' :
                          d.priority === 'P1' ? 'bg-orange-50 text-orange-600' :
                          d.priority === 'P2' ? 'bg-yellow-50 text-yellow-600' :
                          'bg-gray-100 text-gray-500'
                        }`}>
                          {d.priority}
                        </span>
                      </div>
                      {d.moduleName && (
                        <div className="text-[11px] text-gray-500 mb-1">{d.moduleName}</div>
                      )}
                      {d.steps && (
                        <div className="text-xs text-gray-600 line-clamp-2 mb-1">{d.steps}</div>
                      )}
                      {d.expectedResult && (
                        <div className="text-xs text-gray-500 line-clamp-1">预期：{d.expectedResult}</div>
                      )}
                      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded ${
                            d.status === 'DRAFT' ? 'bg-yellow-50 text-yellow-700' :
                            d.status === 'CONFIRMED' ? 'bg-green-50 text-green-700' :
                            d.status === 'DEPRECATED' ? 'bg-gray-100 text-gray-500' :
                            'bg-gray-100 text-gray-600'
                          }`}>
                            {d.status === 'DEPRECATED' ? '已舍弃' : uiStatusLabel(d.status)}
                          </span>
                          {d.analysisVersion != null && (
                            <span className="text-[10px] font-medium px-1.5 py-0.5 rounded bg-sky-50 text-sky-700">
                              分析 v{d.analysisVersion != null ? versionLabel(d.analysisVersion, analyses.find(item => item.version === d.analysisVersion)?.subVersion) : d.analysisVersion}
                            </span>
                          )}
                          {d.qualityStatus && (
                            <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded ${qualityStatusTone(d.qualityStatus)}`}>
                              {qualityStatusLabel(d.qualityStatus)}
                            </span>
                          )}
                          {evidenceCount !== undefined && (
                            <span className="text-[10px] font-medium px-1.5 py-0.5 rounded bg-indigo-50 text-indigo-700">
                              证据 {evidenceCount}
                            </span>
                          )}
                        </div>
                        <span className="shrink-0 text-[10px] text-gray-400">
                          {new Date(d.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}
                        </span>
                      </div>
                      <div className="mt-2 flex flex-wrap items-center justify-end gap-3">
                        <button
                          onClick={() => setSelectedDraft(d)}
                          className="text-[11px] text-sky-600 hover:text-sky-800 transition-colors"
                        >
                          查看详情
                        </button>
                        {d.status !== 'DEPRECATED' && d.status !== 'SUBMITTED' && (
                          <>
                            <button
                              onClick={() => void handleDuplicateDraft(d)}
                              disabled={duplicatingDraftId === d.id}
                              className="text-[11px] text-violet-600 hover:text-violet-800 transition-colors disabled:opacity-50"
                            >
                              {duplicatingDraftId === d.id ? '复制中...' : '复制'}
                            </button>
                            <button
                              onClick={() => setPendingDiscardDraft(d)}
                              className="text-[11px] text-red-500 hover:text-red-700 transition-colors px-1.5 py-0.5 rounded hover:bg-red-50"
                            >
                              舍弃
                            </button>
                          </>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      </div>

      {showRenameModal && activeSession && (
        <Modal
          title="重命名会话"
          onClose={() => !renaming && setShowRenameModal(false)}
          footer={
            <>
              <button
                onClick={() => setShowRenameModal(false)}
                disabled={renaming}
                className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 transition-colors disabled:opacity-50"
              >
                取消
              </button>
              <button
                onClick={handleRenameSession}
                disabled={renaming || !renameDraft.trim()}
                className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 transition-colors disabled:opacity-50"
              >
                {renaming ? '保存中...' : '保存'}
              </button>
            </>
          }
        >
          <div className="space-y-3">
            <p className="text-sm text-gray-700">给这轮生成会话起一个更容易回看的名字，后续在项目里搜索和对照草稿都会更顺手。</p>
            <input
              type="text"
              value={renameDraft}
              onChange={e => setRenameDraft(e.target.value)}
              placeholder="输入会话名称"
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:bg-white focus:border-gray-400 outline-none transition-all"
              autoFocus
            />
          </div>
        </Modal>
      )}

      {pendingArchiveSession && (
        <Modal
          title="删除生成会话"
          onClose={() => !archiving && setPendingArchiveSession(null)}
          footer={
            <>
              <button
                onClick={() => setPendingArchiveSession(null)}
                disabled={archiving}
                className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 transition-colors disabled:opacity-50"
              >
                取消
              </button>
              <button
                onClick={handleArchiveSession}
                disabled={archiving}
                className="px-4 py-2 bg-red-600 text-white text-sm font-semibold rounded-lg hover:bg-red-700 transition-colors disabled:opacity-50"
              >
                {archiving ? '删除中...' : '确认删除'}
              </button>
            </>
          }
        >
          <div className="space-y-3">
            <p className="text-sm text-gray-700">
              删除后，会话 <span className="font-semibold text-gray-900">{pendingArchiveSession.sessionTitle || '未命名会话'}</span> 会从当前列表中移除，
              但已经生成的草稿仍可在本地用例库继续整理。
            </p>
            <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs text-amber-700">
              如果这轮会话还在用来追踪当前需求，请先确认草稿已经整理完成，再执行归档。
            </div>
          </div>
        </Modal>
      )}

      {selectedDraft && (
        <Modal
          title="草稿详情"
          onClose={() => setSelectedDraft(null)}
          footer={
            <>
              <button
                onClick={() => setSelectedDraft(null)}
                className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 transition-colors"
              >
                关闭
              </button>
              {selectedDraft.status !== 'DEPRECATED' && selectedDraft.status !== 'SUBMITTED' && (
                <>
                  <button
                    onClick={() => void handleDuplicateDraft(selectedDraft)}
                    disabled={duplicatingDraftId === selectedDraft.id}
                    className="px-4 py-2 border border-violet-200 text-violet-700 text-sm font-semibold rounded-lg hover:bg-violet-50 transition-colors disabled:opacity-50"
                  >
                    {duplicatingDraftId === selectedDraft.id ? '复制中...' : '复制草稿'}
                  </button>
                  <button
                    onClick={() => {
                      setPendingDiscardDraft(selectedDraft);
                      setSelectedDraft(null);
                    }}
                    className="px-4 py-2 bg-red-600 text-white text-sm font-semibold rounded-lg hover:bg-red-700 transition-colors"
                  >
                    舍弃
                  </button>
                </>
              )}
            </>
          }
        >
          <div className="space-y-3 text-sm">
            <div>
              <div className="text-[11px] text-gray-500 mb-0.5">用例标题</div>
              <div className="font-medium text-gray-900">{selectedDraft.caseTitle}</div>
            </div>
            {selectedDraft.moduleName && (
              <div>
                <div className="text-[11px] text-gray-500 mb-0.5">所属模块</div>
                <div className="text-gray-800">{selectedDraft.moduleName}</div>
              </div>
            )}
            <div className="flex flex-wrap items-center gap-3">
              <div>
                <div className="text-[11px] text-gray-500 mb-0.5">优先级</div>
                <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded ${
                  selectedDraft.priority === 'P0' ? 'bg-red-50 text-red-600' :
                  selectedDraft.priority === 'P1' ? 'bg-orange-50 text-orange-600' :
                  selectedDraft.priority === 'P2' ? 'bg-yellow-50 text-yellow-600' :
                  'bg-gray-100 text-gray-500'
                }`}>{selectedDraft.priority}</span>
              </div>
              <div>
                <div className="text-[11px] text-gray-500 mb-0.5">状态</div>
                <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded ${
                  selectedDraft.status === 'DRAFT' ? 'bg-yellow-50 text-yellow-700' :
                  selectedDraft.status === 'CONFIRMED' ? 'bg-green-50 text-green-700' :
                  selectedDraft.status === 'DEPRECATED' ? 'bg-gray-100 text-gray-500' :
                  'bg-gray-100 text-gray-600'
                }`}>
                  {selectedDraft.status === 'DEPRECATED' ? '已舍弃' : uiStatusLabel(selectedDraft.status)}
                </span>
              </div>
              <div>
                <div className="text-[11px] text-gray-500 mb-0.5">类型</div>
                <div className="text-[10px] text-gray-700">{selectedDraft.caseType ? testPointLabel(selectedDraft.caseType) : '-'}</div>
              </div>
              {selectedDraft.analysisVersion != null && (
                <div>
                  <div className="text-[11px] text-gray-500 mb-0.5">来源分析</div>
                  <div className="text-[10px] text-sky-700">v{versionLabel(selectedDraft.analysisVersion, analyses.find(item => item.version === selectedDraft.analysisVersion)?.subVersion)}</div>
                </div>
              )}
              {selectedDraft.qualityStatus && (
                <div>
                  <div className="text-[11px] text-gray-500 mb-0.5">证据校验</div>
                  <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded ${qualityStatusTone(selectedDraft.qualityStatus)}`}>
                    {qualityStatusLabel(selectedDraft.qualityStatus)}
                  </span>
                </div>
              )}
            </div>
            {(selectedEvidenceSummary || selectedQualityGate || selectedSourceTestPoints.length > 0) && (
              <div className="rounded-lg border border-sky-100 bg-sky-50/70 p-3">
                <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                  <div className="text-[11px] font-semibold text-sky-900">来源证据链</div>
                  {selectedQualityGate?.status && (
                    <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${qualityStatusTone(selectedQualityGate.status)}`}>
                      {qualityStatusLabel(selectedQualityGate.status)}
                    </span>
                  )}
                </div>
                <div className="space-y-2">
                  {selectedEvidenceSummary && (
                    <>
                      <div className="flex flex-wrap gap-1.5 text-[10px]">
                        <span className="rounded bg-white px-1.5 py-0.5 text-sky-700">
                          证据 {selectedEvidenceSummary.evidence_count ?? selectedEvidenceSummary.evidenceCount ?? 0}
                        </span>
                        {selectedEvidenceSummary.confidence_label && (
                          <span className="rounded bg-white px-1.5 py-0.5 text-sky-700">
                            {displayText(selectedEvidenceSummary.confidence_label)}
                          </span>
                        )}
                      </div>
                      <EvidenceChips title="TOM" items={selectedEvidenceSummary.tom_node_refs} />
                      <EvidenceChips title="LLM Wiki" items={selectedEvidenceSummary.wiki_refs} />
                      <EvidenceChips title="页面" items={selectedEvidenceSummary.page_refs} />
                      <EvidenceChips title="业务包" items={selectedEvidenceSummary.business_pack_refs} />
                      <EvidenceChips title="轨迹/摘要" items={selectedEvidenceSummary.trace_refs} />
                    </>
                  )}
                  {(selectedGeneratorRefs?.sourceTestPoint || selectedGeneratorRefs?.confidence !== undefined || selectedGeneratorRefs?.sourceBasis || selectedGeneratorRefs?.unsupportedItems) && (
                    <div className="rounded bg-white px-2 py-2">
                      <div className="mb-1 text-[11px] font-medium text-gray-600">本条用例依据</div>
                      {selectedGeneratorRefs.sourceTestPoint && (
                        <div className="mb-1 break-words text-xs text-gray-800">
                          来源测试点：{displayText(selectedGeneratorRefs.sourceTestPoint)}
                        </div>
                      )}
                      {selectedGeneratorRefs.confidence !== undefined && (
                        <div className="mb-1 text-[10px] text-gray-500">
                          模型置信度：{Number(selectedGeneratorRefs.confidence).toFixed(2)}
                        </div>
                      )}
                      {(selectedQualityGate?.stepCount !== undefined || selectedQualityGate?.expectedCount !== undefined || selectedGeneratorRefs.stepCount !== undefined || selectedGeneratorRefs.expectedCount !== undefined) && (
                        <div className="mb-1 text-[10px] text-gray-500">
                          步骤/预期：{selectedQualityGate?.stepCount ?? selectedGeneratorRefs.stepCount ?? '-'} / {selectedQualityGate?.expectedCount ?? selectedGeneratorRefs.expectedCount ?? '-'}
                        </div>
                      )}
                      <div className="space-y-1">
                        <EvidenceChips title="生成依据" items={selectedGeneratorRefs.sourceBasis} />
                        <EvidenceChips title="未支持项" items={selectedGeneratorRefs.unsupportedItems} />
                      </div>
                    </div>
                  )}
                  {selectedSourceTestPoints.length > 0 && (
                    <div>
                      <div className="text-[11px] font-medium text-gray-500 mb-1">来源测试点</div>
                      <div className="space-y-1">
                        {selectedSourceTestPoints.slice(0, 6).map((tp: any, idx: number) => (
                          <div key={idx} className="rounded bg-white px-2 py-1 text-xs text-gray-700">
                            <div className="break-words font-medium">{displayText(tp.title) || `测试点 ${idx + 1}`}</div>
                            <div className="mt-0.5 flex flex-wrap gap-1 text-[10px] text-gray-400">
                              {tp.coverage_status && <span>{qualityStatusLabel(tp.coverage_status)}</span>}
                              {tp.needs_confirmation && <span>需确认</span>}
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                  <EvidenceChips title="校验提示" items={selectedQualityGate?.warnings} />
                </div>
              </div>
            )}
            {selectedDraft.precondition && (
              <div>
                <div className="text-[11px] text-gray-500 mb-0.5">前置条件</div>
                <div className="text-gray-800 whitespace-pre-wrap">{selectedDraft.precondition}</div>
              </div>
            )}
            <div>
              <div className="text-[11px] text-gray-500 mb-0.5">测试步骤</div>
              <div className="text-gray-800 whitespace-pre-wrap">{selectedDraft.steps}</div>
            </div>
            <div>
              <div className="text-[11px] text-gray-500 mb-0.5">预期结果</div>
              <div className="text-gray-800 whitespace-pre-wrap">{selectedDraft.expectedResult}</div>
            </div>
          </div>
        </Modal>
      )}

      {pendingDiscardDraft && (
        <Modal
          title="舍弃草稿"
          onClose={() => !discardingId && setPendingDiscardDraft(null)}
          footer={
            <>
              <button
                onClick={() => setPendingDiscardDraft(null)}
                disabled={discardingId !== null}
                className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 transition-colors disabled:opacity-50"
              >
                取消
              </button>
              <button
                onClick={handleDiscardDraft}
                disabled={discardingId !== null}
                className="px-4 py-2 bg-red-600 text-white text-sm font-semibold rounded-lg hover:bg-red-700 transition-colors disabled:opacity-50"
              >
                {discardingId !== null ? '舍弃中...' : '确认舍弃'}
              </button>
            </>
          }
        >
          <div className="space-y-3">
            <p className="text-sm text-gray-700">
              确定要舍弃草稿 <span className="font-semibold text-gray-900">{pendingDiscardDraft.caseTitle}</span> 吗？
            </p>
            <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs text-amber-700">
              舍弃后该草稿会从当前会话和本地用例库中删除，且无法恢复。
            </div>
          </div>
        </Modal>
      )}

      <AffectedCasesModal
        open={affectedModalOpen}
        analysis={analysis}
        onClose={() => setAffectedModalOpen(false)}
        onConfirm={handleIncrementalGenerate}
        generating={incrementalGenerating}
      />
    </div>
  );
}
