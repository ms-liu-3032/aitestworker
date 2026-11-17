import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import RichList from '../../components/RichList';
import Drawer from '../../components/Drawer';
import {
  listTomCandidates, listActiveTomModels, confirmTomCandidate, rejectTomCandidate,
  upgradeTomCandidate, importManual, buildTestScope, listEnabledModelConfigs,
  extractTomFromFormalCases, listTomRelationCandidates, confirmTomRelationCandidate, rejectTomRelationCandidate, proposeManualTomRelation,
  listTomAliasCandidates, confirmTomAliasCandidate, rejectTomAliasCandidate,
  listTomImpactCandidates, confirmTomImpactCandidate, rejectTomImpactCandidate,
  importHistoricalCases, uploadHistoricalCases, listHistoricalCaseImports,
  type TestObjectModel, type ModelConfigRecord, type TomRelationCandidate, type TomAliasCandidate, type TomImpactCandidate,
  type HistoricalCaseImportBatch
} from '../../services/api';
import { statusLabel as uiStatusLabel } from '../../utils/displayLabels';
import BulkReviewPicker from '../../components/BulkReviewPicker';

export default function MiniTom() {
  const { projectId } = useParams<{ projectId: string }>();
  const { showToast } = useApp();
  const [candidates, setCandidates] = useState<TestObjectModel[]>([]);
  const [activeModels, setActiveModels] = useState<TestObjectModel[]>([]);
  const [relationCandidates, setRelationCandidates] = useState<TomRelationCandidate[]>([]);
  const [manualRelation, setManualRelation] = useState({ fromModelId: '', relationType: 'MODULE_HAS_PAGE', toModelId: '', evidenceText: '' });
  const [proposingRelation, setProposingRelation] = useState(false);
  const [aliasCandidates, setAliasCandidates] = useState<TomAliasCandidate[]>([]);
  const [impactCandidates, setImpactCandidates] = useState<TomImpactCandidate[]>([]);
  const [models, setModels] = useState<ModelConfigRecord[]>([]);
  const [selectedModel, setSelectedModel] = useState<number | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<'candidates' | 'relations' | 'aliases' | 'impacts' | 'active'>('candidates');
  const [importDrawerOpen, setImportDrawerOpen] = useState(false);
  const [scopeDrawerOpen, setScopeDrawerOpen] = useState(false);
  const [importForm, setImportForm] = useState({ docTitle: '', businessDomain: '', markdownContent: '' });
  const [scopeForm, setScopeForm] = useState({ requirementText: '' });
  const [scopeResult, setScopeResult] = useState<any>(null);
  const [importing, setImporting] = useState(false);
  const [building, setBuilding] = useState(false);
  const [upgradingIds, setUpgradingIds] = useState<Set<number>>(new Set());
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [batchUpgrading, setBatchUpgrading] = useState(false);
  const [upgradedIds, setUpgradedIds] = useState<Set<number>>(new Set());
  const [extractingHistoricalCases, setExtractingHistoricalCases] = useState(false);
  const [historicalImportOpen, setHistoricalImportOpen] = useState(false);
  const [historicalImporting, setHistoricalImporting] = useState(false);
  const [historicalFile, setHistoricalFile] = useState<File | null>(null);
  const [historicalBatches, setHistoricalBatches] = useState<HistoricalCaseImportBatch[]>([]);
  const [historicalForm, setHistoricalForm] = useState({ batchName: '', businessDomain: '', content: '' });

  useEffect(() => {
    if (!projectId) return;
    const pid = Number(projectId);
    setLoading(true);
    Promise.all([
      listTomCandidates(pid).catch(() => []),
      listActiveTomModels(pid).catch(() => []),
      listTomRelationCandidates(pid).catch(() => []),
      listTomAliasCandidates(pid).catch(() => []),
      listTomImpactCandidates(pid).catch(() => []),
      listEnabledModelConfigs().catch(() => []),
      listHistoricalCaseImports(pid).catch(() => []),
    ]).then(([c, a, relationItems, aliasItems, impactItems, m, importedBatches]) => {
      setCandidates(c);
      setActiveModels(a);
      setRelationCandidates(relationItems);
      setAliasCandidates(aliasItems);
      setImpactCandidates(impactItems);
      setModels(m);
      setHistoricalBatches(importedBatches);
      setSelectedModel(prev => prev ?? m[0]?.id);
      const already = new Set<number>();
      a.forEach(t => { if (t.upgradedAt) already.add(t.id); });
      setUpgradedIds(already);
    }).finally(() => setLoading(false));
  }, [projectId]);

  const refreshTomAssets = async () => {
    if (!projectId) return;
    const pid = Number(projectId);
    const [candidateItems, activeItems, relationItems, aliasItems, impactItems] = await Promise.all([
      listTomCandidates(pid).catch(() => []),
      listActiveTomModels(pid).catch(() => []),
      listTomRelationCandidates(pid).catch(() => []),
      listTomAliasCandidates(pid).catch(() => []),
      listTomImpactCandidates(pid).catch(() => []),
    ]);
    setCandidates(candidateItems);
    setActiveModels(activeItems);
    setRelationCandidates(relationItems);
    setAliasCandidates(aliasItems);
    setImpactCandidates(impactItems);
  };

  const handleExtractHistoricalCases = async () => {
    if (!projectId) return;
    setExtractingHistoricalCases(true);
    try {
      const result = await extractTomFromFormalCases(Number(projectId));
      await refreshTomAssets();
      setTab('candidates');
      showToast(`已生成 ${result.candidates} 个对象候选、${result.relations} 条关系候选；确认后才会进入 TOM`);
    } catch (e: any) {
      showToast(e?.message || '历史正式用例提取失败', 'error');
    } finally {
      setExtractingHistoricalCases(false);
    }
  };

  const parseHistoricalCases = (content: string) => {
    try {
      const parsed = JSON.parse(content);
      if (Array.isArray(parsed)) return parsed;
    } catch { /* Plain-text parsing below. */ }
    const blocks = content.split(/\n\s*(?=(?:#{1,4}\s*)?(?:用例\s*[:：]|\d+[.、]|\[?TC[-_]?\d+))/).filter(Boolean);
    return blocks.map((block, index) => {
      const value = (labels: string[]) => {
        const line = block.split('\n').find(item => labels.some(label => item.trim().toLowerCase().startsWith(label.toLowerCase())));
        return line?.replace(/^[^:：]+[:：]\s*/, '').trim() || '';
      };
      const title = value(['用例标题', '标题', '用例']) || block.split('\n').find(line => line.trim())?.replace(/^#{1,4}\s*/, '').replace(/^\d+[.、]\s*/, '').trim() || `历史用例 ${index + 1}`;
      return { title, module: value(['模块', '模块名称']), precondition: value(['前置条件']), steps: value(['步骤', '操作步骤']), expectedResult: value(['预期结果', '期望结果']), priority: value(['优先级']), caseType: value(['用例类型']), designMethod: value(['设计方法']) };
    }).filter(item => item.title);
  };

  const handleHistoricalImport = async () => {
    if (!projectId) return;
    setHistoricalImporting(true);
    try {
      const result = historicalFile
        ? await uploadHistoricalCases(Number(projectId), historicalFile, historicalForm.batchName, historicalForm.businessDomain)
        : await importHistoricalCases({ projectId: Number(projectId), batchName: historicalForm.batchName, businessDomain: historicalForm.businessDomain, cases: parseHistoricalCases(historicalForm.content) });
      await refreshTomAssets();
      setHistoricalBatches(await listHistoricalCaseImports(Number(projectId)));
      setHistoricalImportOpen(false);
      setHistoricalFile(null);
      setHistoricalForm({ batchName: '', businessDomain: '', content: '' });
      setTab('candidates');
      showToast(`已导入 ${result.imported} 条历史用例并写入正式用例库（来源：手动导入）；其中 ${result.eligible} 条已生成 ${result.extraction.candidates} 个待审核 TOM 候选`);
    } catch (e: any) {
      showToast(e?.message || '历史用例导入失败', 'error');
    } finally {
      setHistoricalImporting(false);
    }
  };

  const historicalSourceLabel = (sourceType?: string, sourceRefId?: number | null) =>
    sourceType === 'IMPORTED_HISTORICAL_CASE' ? `历史导入用例 #${sourceRefId ?? '-'}` : `正式用例 #${sourceRefId ?? '-'}`;

  const handleConfirmRelation = async (id: number) => {
    if (!projectId) return;
    try {
      await confirmTomRelationCandidate(Number(projectId), id);
      setRelationCandidates(prev => prev.filter(item => item.id !== id));
      showToast('关系已确认并生效');
    } catch (e: any) {
      showToast(e?.message || '请先确认关系两端的 TOM 对象', 'error');
    }
  };

  const handleProposeRelation = async () => {
    if (!projectId) return;
    setProposingRelation(true);
    try {
      await proposeManualTomRelation(Number(projectId), {
        fromModelId: Number(manualRelation.fromModelId), relationType: manualRelation.relationType,
        toModelId: Number(manualRelation.toModelId), evidenceText: manualRelation.evidenceText,
      });
      setManualRelation({ fromModelId: '', relationType: 'MODULE_HAS_PAGE', toModelId: '', evidenceText: '' });
      await refreshTomAssets();
      showToast('关系已提交审核，确认后才会用于测试设计');
    } catch (e: any) {
      showToast(e?.message || '提交关系失败', 'error');
    } finally {
      setProposingRelation(false);
    }
  };

  const handleRejectRelation = async (id: number) => {
    if (!projectId) return;
    try {
      await rejectTomRelationCandidate(Number(projectId), id);
      setRelationCandidates(prev => prev.filter(item => item.id !== id));
      showToast('关系已驳回');
    } catch (e: any) {
      showToast(e?.message || '操作失败', 'error');
    }
  };

  const handleReviewAlias = async (id: number, confirm: boolean) => {
    if (!projectId) return;
    try {
      if (confirm) await confirmTomAliasCandidate(Number(projectId), id);
      else await rejectTomAliasCandidate(Number(projectId), id);
      setAliasCandidates(prev => prev.filter(item => item.id !== id));
      showToast(confirm ? '别名已确认并生效' : '别名已驳回');
    } catch (e: any) {
      showToast(e?.message || '操作失败', 'error');
    }
  };

  const handleReviewImpact = async (id: number, confirm: boolean) => {
    if (!projectId) return;
    try {
      if (confirm) await confirmTomImpactCandidate(Number(projectId), id);
      else await rejectTomImpactCandidate(Number(projectId), id);
      setImpactCandidates(prev => prev.filter(item => item.id !== id));
      showToast(confirm ? '影响链已确认并生效' : '影响链已驳回');
    } catch (e: any) {
      showToast(e?.message || '操作失败', 'error');
    }
  };

  const handleConfirm = async (id: number) => {
    try {
      await confirmTomCandidate(id);
      setCandidates(prev => prev.filter(c => c.id !== id));
      showToast('已确认');
    } catch {
      showToast('操作失败', 'error');
    }
  };

  const handleReject = async (id: number) => {
    try {
      await rejectTomCandidate(id);
      setCandidates(prev => prev.filter(c => c.id !== id));
      showToast('已驳回');
    } catch {
      showToast('操作失败', 'error');
    }
  };

  const handleUpgrade = async (id: number) => {
    setUpgradingIds(prev => new Set(prev).add(id));
    try {
      await upgradeTomCandidate(id);
      setUpgradedIds(prev => new Set(prev).add(id));
      setSelectedIds(prev => { const n = new Set(prev); n.delete(id); return n; });
      showToast('已提交为系统级 TOM，待管理员审核');
    } catch (e: any) {
      showToast(e?.message || '升级失败', 'error');
    } finally {
      setUpgradingIds(prev => { const n = new Set(prev); n.delete(id); return n; });
    }
  };

  const handleBatchUpgrade = async () => {
    const ids = [...selectedIds].filter(id => !upgradedIds.has(id));
    if (ids.length === 0) return;
    setBatchUpgrading(true);
    let ok = 0, fail = 0;
    for (const id of ids) {
      try {
        await upgradeTomCandidate(id);
        setUpgradedIds(prev => new Set(prev).add(id));
        ok++;
      } catch {
        fail++;
      }
    }
    setSelectedIds(new Set());
    setBatchUpgrading(false);
    if (ok > 0) showToast(`已提交 ${ok} 个 TOM 为系统级${fail > 0 ? `，${fail} 个失败` : ''}`);
    else showToast('提交失败', 'error');
  };

  const handleImport = async () => {
    if (!projectId || !importForm.docTitle || !importForm.markdownContent) return;
    if (!selectedModel) {
      showToast('请选择 LLM 模型', 'error');
      return;
    }
    setImporting(true);
    try {
      await importManual({
        projectId: Number(projectId),
        docTitle: importForm.docTitle,
        businessDomain: importForm.businessDomain,
        markdownContent: importForm.markdownContent,
        modelConfigId: selectedModel,
      });
      showToast('导入任务已启动');
      setImportDrawerOpen(false);
      setImportForm({ docTitle: '', businessDomain: '', markdownContent: '' });
      listTomCandidates(Number(projectId)).then(setCandidates).catch(() => {});
    } catch (e: any) {
      showToast(e?.message || '导入失败', 'error');
    } finally {
      setImporting(false);
    }
  };

  const handleBuildScope = async () => {
    if (!projectId || !scopeForm.requirementText) return;
    if (!selectedModel) {
      showToast('请选择 LLM 模型', 'error');
      return;
    }
    setBuilding(true);
    try {
      const result = await buildTestScope({
        projectId: Number(projectId),
        requirementText: scopeForm.requirementText,
        modelConfigId: selectedModel,
      });
      setScopeResult(result);
    } catch (e: any) {
      showToast(e?.message || '构建失败', 'error');
    } finally {
      setBuilding(false);
    }
  };

  const toggleSelect = (id: number) => {
    setSelectedIds(prev => {
      const n = new Set(prev);
      if (n.has(id)) n.delete(id); else n.add(id);
      return n;
    });
  };

  const items = tab === 'candidates' ? candidates : activeModels;

  const upgradableIds = items.filter(t => t.scope === 'PROJECT' && !upgradedIds.has(t.id)).map(t => t.id);
  const allSelected = upgradableIds.length > 0 && upgradableIds.every(id => selectedIds.has(id));

  const toggleSelectAll = () => {
    if (allSelected) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(upgradableIds));
    }
  };

  const columns = tab === 'active' ? [
    { key: 'select', label: '', width: '36px', renderHeader: () => (
      <input type="checkbox" checked={allSelected} onChange={toggleSelectAll} className="h-3.5 w-3.5 rounded border-gray-300 accent-purple-600" />
    ) },
    { key: 'name', label: '名称', width: '160px' },
    { key: 'type', label: '类型', width: '100px' },
    { key: 'scope', label: '级别', width: '80px' },
    { key: 'desc', label: '描述', width: '220px' },
    { key: 'status', label: '状态', width: '80px' },
    { key: 'actions', label: '操作', width: '140px' },
  ] : [
    { key: 'name', label: '名称', width: '180px' },
    { key: 'type', label: '类型', width: '120px' },
    { key: 'desc', label: '描述', width: '280px' },
    { key: 'status', label: '状态', width: '100px' },
    { key: 'actions', label: '操作', width: '140px' },
  ];

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <h1 className="text-xl font-bold text-gray-900 tracking-tight">Mini-TOM</h1>
        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => setImportDrawerOpen(true)}
            className="min-h-9 shrink-0 rounded-lg border border-gray-200 px-3 py-1.5 text-sm text-gray-700 transition-colors hover:bg-gray-50"
          >
            导入使用手册
          </button>
          <button
            onClick={() => setHistoricalImportOpen(true)}
            className="min-h-9 shrink-0 rounded-lg border border-indigo-200 px-3 py-1.5 text-sm font-medium text-indigo-700 transition-colors hover:bg-indigo-50"
          >
            导入历史用例
          </button>
          <button
            onClick={() => setScopeDrawerOpen(true)}
            className="min-h-9 shrink-0 rounded-lg bg-slate-900 px-3 py-1.5 text-sm font-semibold text-white transition-colors hover:bg-slate-800"
          >
            构建测试范围
          </button>
          <button
            onClick={handleExtractHistoricalCases}
            disabled={extractingHistoricalCases}
            title="仅从已提交且未废弃的项目正式用例提取待审核候选，不会直接改变生效 TOM"
            className="min-h-9 shrink-0 rounded-lg border border-blue-200 px-3 py-1.5 text-sm font-medium text-blue-700 transition-colors hover:bg-blue-50 disabled:opacity-50"
          >
            {extractingHistoricalCases ? '提取中...' : '从正式用例提取候选'}
          </button>
        </div>
      </div>

      <div className="mb-4 flex w-fit max-w-full flex-wrap items-center gap-1 rounded-lg bg-gray-100 p-0.5">
        <button
          onClick={() => setTab('candidates')}
          className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all ${
            tab === 'candidates' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
          }`}
        >
          候选 ({candidates.length})
        </button>
        <button
          onClick={() => setTab('relations')}
          className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all ${
            tab === 'relations' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
          }`}
        >
          候选关系 ({relationCandidates.length})
        </button>
        <button
          onClick={() => setTab('aliases')}
          className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all ${
            tab === 'aliases' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
          }`}
        >
          候选别名 ({aliasCandidates.length})
        </button>
        <button
          onClick={() => setTab('impacts')}
          className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all ${
            tab === 'impacts' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
          }`}
        >
          影响链 ({impactCandidates.length})
        </button>
        <button
          onClick={() => setTab('active')}
          className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all ${
            tab === 'active' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
          }`}
        >
          已生效 ({activeModels.length})
        </button>
      </div>

      {tab === 'candidates' && <BulkReviewPicker title="项目 TOM 候选" items={candidates.map(item => ({ id: item.id, name: item.name }))}
        onReview={async (id, approved) => {
          if (approved) await confirmTomCandidate(id); else await rejectTomCandidate(id);
          setCandidates(current => current.filter(item => item.id !== id));
        }} />}
      {tab === 'relations' && <BulkReviewPicker title="TOM 关系" items={relationCandidates.map(item => ({ id: item.id, name: `${item.fromName} → ${item.toName}` }))}
        onReview={async (id, approved) => {
          if (approved) await confirmTomRelationCandidate(Number(projectId), id); else await rejectTomRelationCandidate(Number(projectId), id);
          setRelationCandidates(current => current.filter(item => item.id !== id));
        }} />}
      {tab === 'aliases' && <BulkReviewPicker title="TOM 别名" items={aliasCandidates.map(item => ({ id: item.id, name: `${item.tomName}：${item.aliasName}` }))}
        onReview={async (id, approved) => {
          if (approved) await confirmTomAliasCandidate(Number(projectId), id); else await rejectTomAliasCandidate(Number(projectId), id);
          setAliasCandidates(current => current.filter(item => item.id !== id));
        }} />}
      {tab === 'impacts' && <BulkReviewPicker title="TOM 影响链" items={impactCandidates.map(item => ({ id: item.id, name: `${item.fromName} → ${item.targetRef}` }))}
        onReview={async (id, approved) => {
          if (approved) await confirmTomImpactCandidate(Number(projectId), id); else await rejectTomImpactCandidate(Number(projectId), id);
          setImpactCandidates(current => current.filter(item => item.id !== id));
        }} />}

      {tab === 'active' && selectedIds.size > 0 && (
        <div className="mb-3 flex items-center gap-3 rounded-lg border border-purple-200 bg-purple-50 px-4 py-2">
          <span className="text-sm text-purple-700">已选 {selectedIds.size} 个项目级 TOM</span>
          <button
            onClick={handleBatchUpgrade}
            disabled={batchUpgrading}
            className="rounded-lg bg-purple-600 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-purple-700 disabled:opacity-50"
          >
            {batchUpgrading ? '提交中...' : '批量提交为系统级'}
          </button>
          <button onClick={() => setSelectedIds(new Set())} className="text-xs text-purple-500 hover:text-purple-700">
            取消选择
          </button>
        </div>
      )}

      {tab === 'relations' ? (
        <div className="space-y-4">
          <div className="rounded-lg border border-gray-200 bg-white p-4 space-y-3">
            <div className="text-sm font-semibold text-gray-900">补充 TOM 关系</div>
            <p className="text-xs text-gray-600">选择当前来源项目已确认的对象，填写文档依据。系统级对象请到其来源项目建立关系；提交后仍需人工确认。</p>
            <div className="grid gap-2 md:grid-cols-3">
              <select aria-label="来源对象" value={manualRelation.fromModelId} onChange={e => setManualRelation(prev => ({ ...prev, fromModelId: e.target.value }))} className="rounded border border-gray-300 px-2 py-2 text-sm">
                <option value="">来源对象</option>
                {activeModels.filter(item => item.projectId === Number(projectId)).map(item => <option key={item.id} value={item.id}>{item.modelType} · {item.name} #{item.id}</option>)}
              </select>
              <select aria-label="关系类型" value={manualRelation.relationType} onChange={e => setManualRelation(prev => ({ ...prev, relationType: e.target.value }))} className="rounded border border-gray-300 px-2 py-2 text-sm">
                {['MODULE_HAS_PAGE', 'PAGE_HAS_FIELD', 'ROLE_CAN_ACTION', 'FLOW_HAS_STEP', 'ACTION_CHANGES_STATE', 'ACTION_HAS_ASSERTION', 'MODULE_HAS_ACTION', 'ACTION_HAS_FLOW', 'FLOW_CHANGES_STATE', 'STATE_TRANSITIONS_TO'].map(type => <option key={type} value={type}>{type}</option>)}
              </select>
              <select aria-label="目标对象" value={manualRelation.toModelId} onChange={e => setManualRelation(prev => ({ ...prev, toModelId: e.target.value }))} className="rounded border border-gray-300 px-2 py-2 text-sm">
                <option value="">目标对象</option>
                {activeModels.filter(item => item.projectId === Number(projectId)).map(item => <option key={item.id} value={item.id}>{item.modelType} · {item.name} #{item.id}</option>)}
              </select>
            </div>
            <textarea aria-label="关系依据" value={manualRelation.evidenceText} onChange={e => setManualRelation(prev => ({ ...prev, evidenceText: e.target.value }))} placeholder="填写手册、需求或已核实的业务规则原文（至少 10 字）" rows={2} className="w-full rounded border border-gray-300 px-2 py-2 text-sm" />
            <button onClick={handleProposeRelation} disabled={proposingRelation || !manualRelation.fromModelId || !manualRelation.toModelId || manualRelation.evidenceText.trim().length < 10} className="rounded bg-blue-600 px-3 py-2 text-xs font-semibold text-white disabled:opacity-50">提交关系候选</button>
          </div>
        <RichList
          items={relationCandidates}
          columns={[
            { key: 'from', label: '来源对象', width: '170px' },
            { key: 'relation', label: '关系', width: '160px' },
            { key: 'to', label: '目标对象', width: '170px' },
            { key: 'source', label: '历史依据', width: '150px' },
            { key: 'actions', label: '操作', width: '130px' },
          ]}
          loading={loading}
          emptyText="暂无待审核关系"
          renderRow={(relation: TomRelationCandidate) => (
            <>
              <div className="text-sm font-medium text-gray-900 truncate" style={{ width: '170px' }}>{relation.fromName}</div>
              <div className="text-xs text-gray-600" style={{ width: '160px' }}>{relation.relationType}</div>
              <div className="text-sm text-gray-900 truncate" style={{ width: '170px' }}>{relation.toName}</div>
              <div className="text-xs text-blue-700" style={{ width: '150px' }}>{relation.sourceType === 'MANUAL_SECTION' ? '人工填写依据' : historicalSourceLabel(relation.sourceType, relation.sourceRefId)}</div>
              <div style={{ width: '130px' }} className="flex items-center gap-2">
                <button onClick={() => handleConfirmRelation(relation.id)} className="text-xs text-green-600 hover:text-green-700 font-medium">确认</button>
                <button onClick={() => handleRejectRelation(relation.id)} className="text-xs text-red-500 hover:text-red-600 font-medium">驳回</button>
              </div>
            </>
          )}
        />
        </div>
      ) : tab === 'aliases' ? (
        <RichList
          items={aliasCandidates}
          columns={[
            { key: 'object', label: 'TOM 对象', width: '220px' },
            { key: 'alias', label: '候选别名', width: '220px' },
            { key: 'source', label: '历史依据', width: '160px' },
            { key: 'actions', label: '操作', width: '130px' },
          ]}
          loading={loading}
          emptyText="暂无待审核别名"
          renderRow={(alias: TomAliasCandidate) => (
            <>
              <div className="text-sm font-medium text-gray-900 truncate" style={{ width: '220px' }}>{alias.tomName}</div>
              <div className="text-sm text-gray-700 truncate" style={{ width: '220px' }}>{alias.aliasName}</div>
              <div className="text-xs text-blue-700" style={{ width: '160px' }}>{historicalSourceLabel(alias.sourceType, alias.sourceRefId)}</div>
              <div style={{ width: '130px' }} className="flex items-center gap-2">
                <button onClick={() => handleReviewAlias(alias.id, true)} className="text-xs text-green-600 hover:text-green-700 font-medium">确认</button>
                <button onClick={() => handleReviewAlias(alias.id, false)} className="text-xs text-red-500 hover:text-red-600 font-medium">驳回</button>
              </div>
            </>
          )}
        />
      ) : tab === 'impacts' ? (
        <RichList
          items={impactCandidates}
          columns={[
            { key: 'from', label: '来源对象', width: '200px' },
            { key: 'impact', label: '影响关系', width: '170px' },
            { key: 'target', label: '受影响目标', width: '260px' },
            { key: 'source', label: '历史依据', width: '150px' },
            { key: 'actions', label: '操作', width: '130px' },
          ]}
          loading={loading}
          emptyText="暂无待审核影响链"
          renderRow={(impact: TomImpactCandidate) => (
            <>
              <div className="text-sm font-medium text-gray-900 truncate" style={{ width: '200px' }}>{impact.fromName}</div>
              <div className="text-xs text-gray-600" style={{ width: '170px' }}>{impact.impactType}</div>
              <div className="text-sm text-gray-700 truncate" style={{ width: '260px' }}>{impact.targetKind}: {impact.targetRef}</div>
              <div className="text-xs text-blue-700" style={{ width: '150px' }}>{historicalSourceLabel(impact.sourceType, impact.sourceRefId)}</div>
              <div style={{ width: '130px' }} className="flex items-center gap-2">
                <button onClick={() => handleReviewImpact(impact.id, true)} className="text-xs text-green-600 hover:text-green-700 font-medium">确认</button>
                <button onClick={() => handleReviewImpact(impact.id, false)} className="text-xs text-red-500 hover:text-red-600 font-medium">驳回</button>
              </div>
            </>
          )}
        />
      ) : (
      <RichList
        items={items}
        columns={columns}
        loading={loading}
        emptyText={tab === 'candidates' ? '暂无候选 TOM' : '暂无生效 TOM'}
        renderRow={(tom: TestObjectModel) => tab === 'active' ? (
          <>
            <div style={{ width: '36px' }} className="flex items-center justify-center">
              {tom.scope === 'PROJECT' && !upgradedIds.has(tom.id) && (
                <input
                  type="checkbox"
                  checked={selectedIds.has(tom.id)}
                  onChange={() => toggleSelect(tom.id)}
                  className="h-3.5 w-3.5 rounded border-gray-300 accent-purple-600"
                />
              )}
            </div>
            <div style={{ width: '160px' }}>
              <div className="text-sm font-medium text-gray-900 truncate">{tom.name}</div>
            </div>
            <div className="text-sm text-gray-600" style={{ width: '100px' }}>{tom.modelType}</div>
            <div style={{ width: '80px' }}>
              <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${
                tom.scope === 'SYSTEM' ? 'bg-purple-50 text-purple-700' : 'bg-blue-50 text-blue-700'
              }`}>
                {tom.scope === 'SYSTEM' ? '系统级' : '项目级'}
              </span>
            </div>
            <div className="text-sm text-gray-500 truncate" style={{ width: '220px' }}>{tom.description || '-'}</div>
            <div style={{ width: '80px' }}>
              <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${
                tom.status === 'ACTIVE' ? 'bg-green-50 text-green-700' :
                tom.status === 'CANDIDATE' ? 'bg-yellow-50 text-yellow-700' :
                'bg-gray-100 text-gray-600'
              }`}>
                {uiStatusLabel(tom.status)}
              </span>
            </div>
            <div style={{ width: '140px' }} className="flex items-center gap-2">
              {tom.scope === 'PROJECT' && !upgradedIds.has(tom.id) && (
                <button
                  onClick={() => handleUpgrade(tom.id)}
                  disabled={upgradingIds.has(tom.id)}
                  className="text-xs text-purple-600 hover:text-purple-700 font-medium disabled:opacity-50"
                >
                  {upgradingIds.has(tom.id) ? '提交中...' : '升级为系统级'}
                </button>
              )}
              {upgradedIds.has(tom.id) && (
                <span className="text-xs text-green-600 font-medium">已提交</span>
              )}
              {tom.scope === 'SYSTEM' && (
                <span className="text-xs text-gray-400">-</span>
              )}
            </div>
          </>
        ) : (
          <>
            <div style={{ width: '180px' }}>
              <div className="text-sm font-medium text-gray-900 truncate">{tom.name}</div>
            </div>
            <div className="text-sm text-gray-600" style={{ width: '120px' }}>{tom.modelType}</div>
            <div className="text-sm text-gray-500 truncate" style={{ width: '280px' }}>{tom.description || '-'}</div>
            <div style={{ width: '100px' }}>
              <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${
                tom.status === 'ACTIVE' ? 'bg-green-50 text-green-700' :
                tom.status === 'CANDIDATE' ? 'bg-yellow-50 text-yellow-700' :
                'bg-gray-100 text-gray-600'
              }`}>
                {uiStatusLabel(tom.status)}
              </span>
            </div>
            <div style={{ width: '140px' }} className="flex items-center gap-2">
              <button onClick={() => handleConfirm(tom.id)} className="text-xs text-green-600 hover:text-green-700 font-medium">确认</button>
              <button onClick={() => handleReject(tom.id)} className="text-xs text-red-500 hover:text-red-600 font-medium">驳回</button>
            </div>
          </>
        )}
      />
      )}

      {/* 导入使用手册 Drawer */}
      <Drawer
        open={historicalImportOpen}
        onClose={() => !historicalImporting && setHistoricalImportOpen(false)}
        title="导入历史人工用例"
        footer={<>
          <button onClick={() => setHistoricalImportOpen(false)} disabled={historicalImporting} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700">取消</button>
          <button onClick={handleHistoricalImport} disabled={historicalImporting || (!historicalFile && !historicalForm.content.trim())} className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 disabled:opacity-50">
            {historicalImporting ? '导入并提取中...' : '导入并生成 TOM 候选'}
          </button>
        </>}
      >
        <div className="space-y-4">
          <p className="text-sm text-gray-600">外部人工用例只会作为历史证据，生成的 TOM、Wiki、关系和影响链均为候选，确认前不会影响现有项目知识。</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <div><label htmlFor="historical-case-batch" className="block text-xs font-medium text-gray-500 mb-1">导入批次名称</label><input id="historical-case-batch" value={historicalForm.batchName} onChange={e => setHistoricalForm({ ...historicalForm, batchName: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm" placeholder="如：2025 年回归用例" /></div>
            <div><label htmlFor="historical-case-domain" className="block text-xs font-medium text-gray-500 mb-1">业务领域</label><input id="historical-case-domain" value={historicalForm.businessDomain} onChange={e => setHistoricalForm({ ...historicalForm, businessDomain: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm" placeholder="如：预约、审批" /></div>
          </div>
          <div>
            <label htmlFor="historical-case-file" className="block text-xs font-medium text-gray-500 mb-1">Excel / CSV 文件</label>
            <input id="historical-case-file" type="file" accept=".xlsx,.xls,.csv" onChange={e => { setHistoricalFile(e.target.files?.[0] ?? null); }} className="block w-full text-sm text-gray-600" />
            <p className="mt-1 text-xs text-gray-400">首行表头支持：用例标题、模块、前置条件、步骤、预期结果、优先级、用例类型、设计方法。</p>
          </div>
          <div className="flex items-center gap-2 text-xs text-gray-400"><span className="h-px flex-1 bg-gray-200" />或粘贴结构化内容<span className="h-px flex-1 bg-gray-200" /></div>
          <div>
            <label htmlFor="historical-case-content" className="block text-xs font-medium text-gray-500 mb-1">JSON 数组或文本用例</label>
            <textarea id="historical-case-content" value={historicalForm.content} onChange={e => setHistoricalForm({ ...historicalForm, content: e.target.value })} rows={10} className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm font-mono resize-y" placeholder={'[{"title":"提交预约","module":"预约","steps":"填写并提交","expectedResult":"预约创建成功"}]\n\n或：\n用例标题：提交预约\n模块：预约\n步骤：填写并提交\n预期结果：预约创建成功'} />
          </div>
          {historicalBatches.length > 0 && <div className="border-t border-gray-100 pt-3"><div className="mb-2 text-xs font-medium text-gray-500">最近导入批次</div>{historicalBatches.slice(0, 3).map(batch => <div key={batch.id} className="py-1 text-xs text-gray-600">{batch.batchName}：{batch.eligibleCount}/{batch.itemCount} 条可提取</div>)}</div>}
        </div>
      </Drawer>

      <Drawer
        open={importDrawerOpen}
        onClose={() => setImportDrawerOpen(false)}
        title="导入使用手册"
        footer={
          <>
            <button onClick={() => setImportDrawerOpen(false)} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700">取消</button>
            <button onClick={handleImport} disabled={importing || !selectedModel || !importForm.docTitle || !importForm.markdownContent} className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 disabled:opacity-50">
              {importing ? '导入中...' : '开始导入'}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <div>
            <label htmlFor="manual-import-model" className="block text-xs font-medium text-gray-500 mb-1">LLM 模型</label>
            <select
              id="manual-import-model"
              value={selectedModel || ''}
              onChange={e => setSelectedModel(Number(e.target.value) || undefined)}
              className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            >
              {models.length === 0 && <option value="">暂无可用模型</option>}
              {models.map(m => (
                <option key={m.id} value={m.id}>{m.configName}（{m.modelName}）</option>
              ))}
            </select>
            <p className="mt-1 text-xs text-gray-400">用于从手册中提取页面、字段、流程和断言候选。</p>
          </div>
          <div>
            <label htmlFor="manual-import-title" className="block text-xs font-medium text-gray-500 mb-1">文档标题</label>
            <input id="manual-import-title" type="text" value={importForm.docTitle} onChange={e => setImportForm({ ...importForm, docTitle: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
          </div>
          <div>
            <label htmlFor="manual-import-domain" className="block text-xs font-medium text-gray-500 mb-1">业务领域</label>
            <input id="manual-import-domain" type="text" value={importForm.businessDomain} onChange={e => setImportForm({ ...importForm, businessDomain: e.target.value })} placeholder="如：审批流、CRM、设备巡检" className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
          </div>
          <div>
            <label htmlFor="manual-import-content" className="block text-xs font-medium text-gray-500 mb-1">Markdown 内容</label>
            <textarea id="manual-import-content" value={importForm.markdownContent} onChange={e => setImportForm({ ...importForm, markdownContent: e.target.value })} rows={10} className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm font-mono focus:border-gray-400 outline-none resize-none" placeholder="粘贴 Markdown 格式的使用手册内容..." />
          </div>
        </div>
      </Drawer>

      {/* 构建测试范围 Drawer */}
      <Drawer
        open={scopeDrawerOpen}
        onClose={() => { setScopeDrawerOpen(false); setScopeResult(null); }}
        title="构建测试范围"
      >
        <div className="space-y-4">
          <div>
            <label htmlFor="scope-build-model" className="block text-xs font-medium text-gray-500 mb-1">LLM 模型</label>
            <select
              id="scope-build-model"
              value={selectedModel || ''}
              onChange={e => setSelectedModel(Number(e.target.value) || undefined)}
              className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            >
              {models.length === 0 && <option value="">暂无可用模型</option>}
              {models.map(m => (
                <option key={m.id} value={m.id}>{m.configName}（{m.modelName}）</option>
              ))}
            </select>
            <p className="mt-1 text-xs text-gray-400">用于结合当前项目 TOM 构建受影响模块、页面和建议断言。</p>
          </div>
          <div>
            <label htmlFor="scope-build-requirement" className="block text-xs font-medium text-gray-500 mb-1">需求描述</label>
            <textarea id="scope-build-requirement" value={scopeForm.requirementText} onChange={e => setScopeForm({ requirementText: e.target.value })} rows={4} className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none resize-none" placeholder="输入需求描述..." />
          </div>
          <button onClick={handleBuildScope} disabled={building || !selectedModel || !scopeForm.requirementText} className="w-full bg-slate-900 text-white px-4 py-2.5 rounded-lg text-sm font-semibold hover:bg-slate-800 disabled:opacity-50">
            {building ? '构建中...' : '构建测试范围'}
          </button>

          {scopeResult && (
            <div className="bg-gray-50 rounded-lg p-4 space-y-3">
              <h3 className="text-sm font-semibold text-gray-900">测试范围结果</h3>
              {scopeResult.affectedModules?.length > 0 && (
                <div>
                  <div className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold mb-1">受影响模块</div>
                  <div className="flex flex-wrap gap-1">
                    {scopeResult.affectedModules.map((m: string, i: number) => (
                      <span key={i} className="text-xs bg-blue-50 text-blue-700 px-2 py-0.5 rounded">{m}</span>
                    ))}
                  </div>
                </div>
              )}
              {scopeResult.affectedPages?.length > 0 && (
                <div>
                  <div className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold mb-1">受影响页面</div>
                  <div className="flex flex-wrap gap-1">
                    {scopeResult.affectedPages.map((p: string, i: number) => (
                      <span key={i} className="text-xs bg-green-50 text-green-700 px-2 py-0.5 rounded">{p}</span>
                    ))}
                  </div>
                </div>
              )}
              {scopeResult.suggestedAssertions?.length > 0 && (
                <div>
                  <div className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold mb-1">建议断言</div>
                  <ul className="text-xs text-gray-600 space-y-1">
                    {scopeResult.suggestedAssertions.map((a: string, i: number) => (
                      <li key={i} className="flex items-start gap-1">
                        <span className="text-gray-400 mt-0.5">•</span>
                        <span>{a}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              <div className="text-xs text-gray-500 font-mono">
                项目 TOM: {scopeResult.projectTomCount} / 系统 TOM: {scopeResult.systemTomCount}
              </div>
            </div>
          )}
        </div>
      </Drawer>
    </div>
  );
}
