import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import RichList from '../../components/RichList';
import Drawer from '../../components/Drawer';
import {
  listTomCandidates, listActiveTomModels, confirmTomCandidate, rejectTomCandidate,
  upgradeTomCandidate, importManual, buildTestScope, type TestObjectModel
} from '../../services/api';

export default function MiniTom() {
  const { projectId } = useParams<{ projectId: string }>();
  const { showToast } = useApp();
  const [candidates, setCandidates] = useState<TestObjectModel[]>([]);
  const [activeModels, setActiveModels] = useState<TestObjectModel[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<'candidates' | 'active'>('candidates');
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

  useEffect(() => {
    if (!projectId) return;
    const pid = Number(projectId);
    setLoading(true);
    Promise.all([
      listTomCandidates(pid).catch(() => []),
      listActiveTomModels(pid).catch(() => []),
    ]).then(([c, a]) => {
      setCandidates(c);
      setActiveModels(a);
      const already = new Set<number>();
      a.forEach(t => { if (t.upgradedAt) already.add(t.id); });
      setUpgradedIds(already);
    }).finally(() => setLoading(false));
  }, [projectId]);

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
    setImporting(true);
    try {
      await importManual({
        projectId: Number(projectId),
        docTitle: importForm.docTitle,
        businessDomain: importForm.businessDomain,
        markdownContent: importForm.markdownContent,
      });
      showToast('导入任务已启动');
      setImportDrawerOpen(false);
      setImportForm({ docTitle: '', businessDomain: '', markdownContent: '' });
      listTomCandidates(Number(projectId)).then(setCandidates).catch(() => {});
    } catch {
      showToast('导入失败', 'error');
    } finally {
      setImporting(false);
    }
  };

  const handleBuildScope = async () => {
    if (!projectId || !scopeForm.requirementText) return;
    setBuilding(true);
    try {
      const result = await buildTestScope({
        projectId: Number(projectId),
        requirementText: scopeForm.requirementText,
      });
      setScopeResult(result);
    } catch {
      showToast('构建失败', 'error');
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
            onClick={() => setScopeDrawerOpen(true)}
            className="min-h-9 shrink-0 rounded-lg bg-slate-900 px-3 py-1.5 text-sm font-semibold text-white transition-colors hover:bg-slate-800"
          >
            构建测试范围
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
          onClick={() => setTab('active')}
          className={`px-3 py-1.5 text-xs font-medium rounded-md transition-all ${
            tab === 'active' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
          }`}
        >
          已生效 ({activeModels.length})
        </button>
      </div>

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
                {tom.status === 'ACTIVE' ? '生效' : tom.status === 'CANDIDATE' ? '候选' : tom.status}
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
                {tom.status === 'ACTIVE' ? '生效' : tom.status === 'CANDIDATE' ? '候选' : tom.status}
              </span>
            </div>
            <div style={{ width: '140px' }} className="flex items-center gap-2">
              <button onClick={() => handleConfirm(tom.id)} className="text-xs text-green-600 hover:text-green-700 font-medium">确认</button>
              <button onClick={() => handleReject(tom.id)} className="text-xs text-red-500 hover:text-red-600 font-medium">驳回</button>
            </div>
          </>
        )}
      />

      {/* 导入使用手册 Drawer */}
      <Drawer
        open={importDrawerOpen}
        onClose={() => setImportDrawerOpen(false)}
        title="导入使用手册"
        footer={
          <>
            <button onClick={() => setImportDrawerOpen(false)} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700">取消</button>
            <button onClick={handleImport} disabled={importing || !importForm.docTitle || !importForm.markdownContent} className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 disabled:opacity-50">
              {importing ? '导入中...' : '开始导入'}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">文档标题</label>
            <input type="text" value={importForm.docTitle} onChange={e => setImportForm({ ...importForm, docTitle: e.target.value })} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">业务领域</label>
            <input type="text" value={importForm.businessDomain} onChange={e => setImportForm({ ...importForm, businessDomain: e.target.value })} placeholder="如：审批流、CRM、设备巡检" className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">Markdown 内容</label>
            <textarea value={importForm.markdownContent} onChange={e => setImportForm({ ...importForm, markdownContent: e.target.value })} rows={10} className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm font-mono focus:border-gray-400 outline-none resize-none" placeholder="粘贴 Markdown 格式的使用手册内容..." />
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
            <label className="block text-xs font-medium text-gray-500 mb-1">需求描述</label>
            <textarea value={scopeForm.requirementText} onChange={e => setScopeForm({ requirementText: e.target.value })} rows={4} className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none resize-none" placeholder="输入需求描述..." />
          </div>
          <button onClick={handleBuildScope} disabled={building || !scopeForm.requirementText} className="w-full bg-slate-900 text-white px-4 py-2.5 rounded-lg text-sm font-semibold hover:bg-slate-800 disabled:opacity-50">
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
