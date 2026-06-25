import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useParams } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import Modal from '../../components/Modal';
import AffectedCasesModal from '../../components/AffectedCasesModal';
import {
  listGenerationSessions, createGenerationSession, updateGenerationSession, archiveGenerationSession, listGenerationMessages, sendGenerationMessage, generateIncremental,
  listGenerationDrafts, deprecateLocalCase, getLatestGenerationAnalysis, listGenerationAnalyses, listEnabledModelConfigs, listEnabledPromptTemplates,
  type GenerationSession, type GenerationMessage, type ModelConfigRecord, type CaseDraft, type PromptTemplateRecord, type RequirementAnalysis
} from '../../services/api';

function safeJsonParse<T>(value: unknown): T | null {
  if (value == null) return null;
  if (typeof value === 'object') return value as T;
  try {
    return JSON.parse(String(value)) as T;
  } catch {
    return null;
  }
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

function asStringArray(value: unknown) {
  if (!Array.isArray(value)) return [];
  return value.map(item => String(item || '').trim()).filter(Boolean);
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
            <div className="break-words font-medium text-amber-900">{q.question}</div>
            {q.reason && <div className="mt-1 break-words text-amber-700">原因：{q.reason}</div>}
            {q.impact && <div className="mt-0.5 break-words text-amber-700">影响：{q.impact}</div>}
          </div>
        ))}
      </div>
    </div>
  );
}

export function AnalysisMessageContent({ analysis }: { analysis: RequirementAnalysis }) {
  const result = safeJsonParse<{
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
          <div className="mt-1 leading-relaxed">{result.requirement_understanding}</div>
        </div>
      )}
      {affected.some(([, items]) => items && items.length > 0) && (
        <div>
          <div className="font-semibold text-gray-900">【影响范围】</div>
          <div className="mt-1 space-y-0.5">
            {affected.map(([label, items]) => (
              items && items.length > 0 ? (
                <div key={label} className="break-words">- {label}：{items.join('、')}</div>
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
                <div className="break-words font-medium">- {q.question}</div>
                {q.reason && <div className="ml-3 text-gray-500">原因：{q.reason}</div>}
                {q.impact && <div className="ml-3 text-gray-500">影响：{q.impact}</div>}
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
              <div key={idx} className="break-words">- {item}</div>
            ))}
          </div>
        </div>
      )}
      {testPoints.length > 0 && (
        <div>
          <div className="font-semibold text-gray-900">【测试点分析】</div>
          <div className="mt-1 space-y-0.5">
            {testPoints.map((tp, idx) => (
              <div key={idx} className="break-words">
                {idx + 1}. {tp.title || `测试点 ${idx + 1}`}{tp.description ? ` - ${tp.description}` : ''}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

export function AnalysisPanel({ analyses, latestAnalysis }: { analyses: RequirementAnalysis[]; latestAnalysis: RequirementAnalysis }) {
  const [expandedVersions, setExpandedVersions] = useState<Set<number>>(new Set([latestAnalysis.version]));

  const toggleVersion = (version: number) => {
    setExpandedVersions(prev => {
      const next = new Set(prev);
      if (next.has(version)) next.delete(version);
      else next.add(version);
      return next;
    });
  };

  const renderAnalysis = (a: RequirementAnalysis) => {
    const result = safeJsonParse<{
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
      evidence_summary?: {
        evidence_count?: number;
        confidence_label?: string;
        tom_node_refs?: string[];
        page_refs?: string[];
        business_pack_refs?: string[];
        trace_refs?: string[];
        source_basis?: string[];
        unsupported_items?: string[];
      };
      clarification_questions?: { question?: string; reason?: string; impact?: string }[];
      assumptions?: { assumption?: string; reason?: string }[];
      test_points?: {
        title?: string;
        description?: string;
        point_type?: string;
        priority_hint?: string;
        test_dimension?: string;
      }[];
    }>(a.analysisResult);

    const evidenceSummary = result?.evidence_summary;
    const testPoints = result?.test_points ?? safeJsonParse<any[]>(a.testPoints) ?? [];
    const questions = result?.clarification_questions
      ?? safeJsonParse<any[]>(a.clarificationQuestions)
      ?? [];
    const assumptions = result?.assumptions ?? safeJsonParse<any[]>(a.assumptions) ?? [];

    return (
      <div className="space-y-4">
        {result?.requirement_understanding && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">需求理解</div>
            <div className="break-words text-xs leading-relaxed text-gray-800">{result.requirement_understanding}</div>
          </div>
        )}
        {result?.business_domain && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">业务领域</div>
            <div className="break-words text-xs text-gray-800">{result.business_domain}</div>
          </div>
        )}
        {result?.requirement_type && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">需求类型</div>
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-indigo-50 text-indigo-700">{result.requirement_type}</span>
          </div>
        )}
        {result?.input_sources && result.input_sources.length > 0 && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">输入来源</div>
            <div className="flex flex-wrap gap-1">
              {result.input_sources.map((src, idx) => (
                <span key={idx} className="text-[10px] px-1.5 py-0.5 rounded bg-teal-50 text-teal-700">{src}</span>
              ))}
            </div>
            {result.input_source_notes && (
              <div className="mt-1 text-xs text-gray-500">{result.input_source_notes}</div>
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
                  <div className="break-words font-medium">{q.question}</div>
                  {q.reason && <div className="ml-3 text-gray-500">原因：{q.reason}</div>}
                  {q.impact && <div className="ml-3 text-gray-500">影响：{q.impact}</div>}
                </div>
              ))}
            </div>
          </div>
        )}
        {result?.risk_scenarios && result.risk_scenarios.length > 0 && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">风险场景</div>
            <div className="space-y-0.5">{result.risk_scenarios.map((s, idx) => (
              <div key={idx} className="text-xs text-gray-700 break-words">- {s}</div>
            ))}</div>
          </div>
        )}
        {result?.boundary_conditions && result.boundary_conditions.length > 0 && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">边界条件</div>
            <div className="space-y-0.5">{result.boundary_conditions.map((c, idx) => (
              <div key={idx} className="text-xs text-gray-700 break-words">- {c}</div>
            ))}</div>
          </div>
        )}
        <ClarificationQuestions questions={questions} />
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
                    {evidenceSummary.confidence_label}
                  </span>
                )}
              </div>
            </div>
            <div className="mt-2 grid grid-cols-1 gap-2">
              <EvidenceChips title="TOM" items={evidenceSummary.tom_node_refs} />
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
                    <span key={idx} className="break-words text-[10px] px-1.5 py-0.5 rounded bg-gray-100 text-gray-700">{item}</span>
                  ))}</div>
                </div>
              );
            })}
          </div>
        )}
        {testPoints.length > 0 && (
          <div>
            <div className="text-[11px] font-medium text-gray-500 mb-1">测试点 ({testPoints.length})</div>
            <div className="space-y-2">
              {testPoints.map((tp, idx) => (
                <div key={idx} className="text-xs text-gray-700 rounded-lg border border-gray-100 p-2">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="break-words font-medium">{tp.title || `测试点 ${idx + 1}`}</span>
                    {tp.point_type && (
                      <span className="text-[9px] px-1 py-0.5 rounded bg-violet-50 text-violet-600">{tp.point_type}</span>
                    )}
                    {tp.priority_hint && (
                      <span className={`text-[9px] px-1 py-0.5 rounded ${
                        tp.priority_hint === 'RISK' ? 'bg-red-50 text-red-600' :
                        tp.priority_hint === 'EXTENDED' ? 'bg-blue-50 text-blue-600' :
                        'bg-green-50 text-green-600'
                      }`}>{tp.priority_hint}</span>
                    )}
                  </div>
                  {tp.description && (
                    <div className="mt-0.5 break-words text-gray-500 line-clamp-2">{tp.description}</div>
                  )}
                  <div className="mt-1 flex flex-wrap items-center gap-2 text-[10px] text-gray-400">
                    {tp.test_dimension && <span>维度：{tp.test_dimension}</span>}
                    {tp.related_module && <span>模块：{tp.related_module}</span>}
                    {tp.coverage_status && <span>覆盖：{qualityStatusLabel(tp.coverage_status)}</span>}
                    {tp.confidence !== undefined && <span>置信度：{tp.confidence}%</span>}
                  </div>
                  {(tp.source_basis?.length || tp.source_refs || tp.unsupported_items?.length) && (
                    <div className="mt-2 space-y-1">
                      <EvidenceChips title="依据" items={tp.source_basis} />
                      <EvidenceChips title="TOM" items={tp.source_refs?.tom_node_refs} />
                      <EvidenceChips title="页面" items={tp.source_refs?.page_refs} />
                      <EvidenceChips title="业务包" items={tp.source_refs?.business_pack_refs} />
                      <EvidenceChips title="待确认" items={tp.unsupported_items} />
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
                <div key={idx} className="break-words text-xs text-gray-600">• {a.assumption}</div>
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
        const isExpanded = expandedVersions.has(a.version);
        return (
          <div key={a.id} className="border border-gray-200 rounded-lg overflow-hidden">
            <button
              onClick={() => toggleVersion(a.version)}
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
                  {a.status === 'CONFIRMED' ? '已确认' : a.status === 'SKIPPED' ? '已跳过' : '待确认'}
                </span>
              </div>
              <span className={`text-[10px] text-gray-400 transition-transform ${isExpanded ? 'rotate-90' : ''}`}>▶</span>
            </button>
            {isExpanded && (
              <div className="px-3 py-3 border-t border-gray-100">
                {renderAnalysis(a)}
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
  const [analysis, setAnalysis] = useState<RequirementAnalysis | null>(null);
  const [analyses, setAnalyses] = useState<RequirementAnalysis[]>([]);
  const [analysisLoading, setAnalysisLoading] = useState(false);
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
  const messagesEndRef = useRef<HTMLDivElement>(null);

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

  useEffect(() => {
    if (!projectId || !activeSession) return;
    setAnalysisLoading(true);
    Promise.all([
      listGenerationMessages(Number(projectId), activeSession.id).catch(() => []),
      listGenerationDrafts(Number(projectId), activeSession.id).catch(() => []),
      getLatestGenerationAnalysis(Number(projectId), activeSession.id).catch(() => null),
      listGenerationAnalyses(Number(projectId), activeSession.id).catch(() => []),
    ]).then(([msgs, d, a, allAnalyses]) => {
      setMessages(msgs);
      setDrafts(d);
      setAnalysis(a);
      setAnalyses(allAnalyses);
    }).finally(() => {
      setAnalysisLoading(false);
    });
  }, [projectId, activeSession]);

  useEffect(() => {
    if (!activeSession) return;
    setSessionModelDraft(activeSession.modelConfigId ?? '');
    setSessionPromptDraft(activeSession.promptTemplateId ?? '');
  }, [activeSession?.id, activeSession?.modelConfigId, activeSession?.promptTemplateId]);

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
        body: '当前会话后续的分析与生成会按新的模型、提示模板和 Mini‑TOM 开关继续执行，适合在同一项目里快速对比不同策略。',
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
    if (!input.trim() || !projectId || !activeSession || sending) return;
    const content = input.trim();
    const optimisticMessage = buildOptimisticUserMessage(activeSession.id, content);
    setInput('');
    setMessages(prev => mergeSentMessages(prev, optimisticMessage, []));
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
        // Auto-route: check affected cases
        if (nextAnalysis.affectedCases && nextAnalysis.affectedCases !== '[]' && nextAnalysis.affectedCases !== 'null') {
          try {
            const parsed = JSON.parse(nextAnalysis.affectedCases);
            if (Array.isArray(parsed) && parsed.length > 0) {
              const totalDrafts = drafts.filter(d => d.status !== 'DEPRECATED').length;
              const affectedRatio = totalDrafts > 0 ? parsed.length / totalDrafts : 1;
              const isMinor = nextAnalysis.changeScope === 'MINOR';
              if (isMinor && affectedRatio < 0.5) {
                // MINOR + 受影响 < 50% → 弹窗让用户确认增量生成
                setAffectedModalOpen(true);
              } else {
                // MAJOR 或受影响 >= 50% → 提示建议全量生成
                showToast(`变更范围${isMinor ? '较大' : '为重大变更'}（影响 ${parsed.length}/${totalDrafts} 个用例），建议全量重新生成。可输入「重新生成」执行全量生成。`, 'info');
              }
            }
          } catch {}
        }
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
      setMessages(prev => prev.filter(item => item.id !== optimisticMessage.id));
      setInput(content);
      showToast(error.message || '发送失败', 'error');
    } finally {
      setSending(false);
      setAnalysisLoading(false);
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
      const updatedDrafts = await generateIncremental(Number(projectId), activeSession.id, selectedDraftIds);
      setDrafts(updatedDrafts);
      setAffectedModalOpen(false);
      showToast(`已更新 ${updatedDrafts.length} 个用例`);
    } catch (error: any) {
      showToast(error.message || '增量生成失败', 'error');
    } finally {
      setIncrementalGenerating(false);
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
        body: '该草稿已标记为弃用，可从本地用例库中继续管理其他草稿。',
      });
      showToast('草稿已舍弃');
    } catch (error: any) {
      showToast(error.message || '舍弃失败', 'error');
    } finally {
      setDiscardingId(null);
      setPendingDiscardDraft(null);
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
              <option key={status} value={status}>{status}</option>
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
                  {s.status}
                </span>
              </div>
              <div className="text-[11px] text-gray-400 mt-1 truncate">
                阶段：{s.currentStage || '待分析'}
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
              {sending && (
                <div className="flex justify-start">
                  <div className="max-w-[92%] rounded-xl border border-sky-100 bg-white px-4 py-3 text-sm text-gray-900 shadow-sm sm:max-w-[80%]">
                    <div className="flex items-center gap-2">
                      <span className="h-2 w-2 animate-pulse rounded-full bg-sky-500" />
                      <span className="font-medium">正在思考</span>
                    </div>
                    <div className="mt-1 text-xs leading-relaxed text-gray-500">
                      正在分析需求、整理测试点并生成草稿用例，完成后会自动更新右侧分析结果和草稿箱。
                    </div>
                  </div>
                </div>
              )}
              <div ref={messagesEndRef} />
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
                disabled={sending}
                className="flex-1 resize-none rounded-lg border border-gray-200 bg-gray-50 px-4 py-3 text-sm outline-none transition-colors focus:border-gray-400 disabled:opacity-50"
                style={{ minHeight: '80px', maxHeight: '160px' }}
              />
              <button
                onClick={handleSend}
                disabled={sending || !input.trim()}
                className="shrink-0 rounded-lg bg-slate-900 px-4 py-3 text-sm font-semibold text-white transition-colors hover:bg-slate-800 disabled:opacity-50"
              >
                {sending ? '思考中...' : '发送'}
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
              {analysisLoading && <span className="text-[11px] text-gray-400">{sending ? '思考中...' : '加载中...'}</span>}
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
              <AnalysisPanel analyses={analyses} latestAnalysis={analysis} />
            )}
          </div>

          {/* 草稿箱 */}
          <div className="px-4 py-3 border-b border-gray-100 bg-gray-50/50">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-gray-900">草稿箱</h3>
              <span className="text-[11px] font-mono text-gray-400">{sending ? '生成中...' : `${drafts.filter(d => d.status !== 'DEPRECATED').length} 条`}</span>
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
                            {d.status === 'DRAFT' ? '草稿' : d.status === 'CONFIRMED' ? '已确认' : d.status === 'DEPRECATED' ? '已舍弃' : d.status}
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
                          <button
                            onClick={() => setPendingDiscardDraft(d)}
                            className="text-[11px] text-red-500 hover:text-red-700 transition-colors px-1.5 py-0.5 rounded hover:bg-red-50"
                          >
                            舍弃
                          </button>
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
                <button
                  onClick={() => {
                    setPendingDiscardDraft(selectedDraft);
                    setSelectedDraft(null);
                  }}
                  className="px-4 py-2 bg-red-600 text-white text-sm font-semibold rounded-lg hover:bg-red-700 transition-colors"
                >
                  舍弃
                </button>
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
                  {selectedDraft.status === 'DRAFT' ? '草稿' : selectedDraft.status === 'CONFIRMED' ? '已确认' : selectedDraft.status === 'DEPRECATED' ? '已舍弃' : selectedDraft.status}
                </span>
              </div>
              <div>
                <div className="text-[11px] text-gray-500 mb-0.5">类型</div>
                <div className="text-[10px] text-gray-700">{selectedDraft.caseType || '-'}</div>
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
                            {selectedEvidenceSummary.confidence_label}
                          </span>
                        )}
                      </div>
                      <EvidenceChips title="TOM" items={selectedEvidenceSummary.tom_node_refs} />
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
                          来源测试点：{selectedGeneratorRefs.sourceTestPoint}
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
                            <div className="break-words font-medium">{tp.title || `测试点 ${idx + 1}`}</div>
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
              舍弃后该草稿会标记为“已舍弃”，不再参与后续提交到正式库。
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
