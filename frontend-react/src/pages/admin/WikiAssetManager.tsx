import { useEffect, useMemo, useState } from 'react';
import { useApp } from '../../context/AppContext';
import {
  createWikiEntry,
  createWikiPack,
  listAdminWikiPacks,
  listProjects,
  listWikiEntries,
  reviewWikiEntry,
  reviewWikiPack,
  updateWikiPackStatus,
  type Project,
  type WikiEntry,
  type WikiPack,
} from '../../services/api';
import { statusLabel, wikiEntryTypeLabel, wikiScopeLabel, wikiScopeOptions } from '../../utils/displayLabels';

const badgeTone: Record<string, string> = {
  PROJECT: 'bg-sky-50 text-sky-700', REUSABLE: 'bg-amber-50 text-amber-700', SYSTEM: 'bg-violet-50 text-violet-700',
  ACTIVE: 'bg-green-50 text-green-700', APPROVED: 'bg-green-50 text-green-700',
  PENDING: 'bg-amber-50 text-amber-700', REJECTED: 'bg-red-50 text-red-700',
  DRAFT: 'bg-gray-100 text-gray-600', INACTIVE: 'bg-gray-100 text-gray-600',
};

function asArray<T>(value: unknown): T[] { return Array.isArray(value) ? value : []; }

export default function WikiAssetManager() {
  const { showToast } = useApp();
  const [packs, setPacks] = useState<WikiPack[]>([]);
  const [entries, setEntries] = useState<WikiEntry[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedPack, setSelectedPack] = useState<WikiPack | null>(null);
  const [loading, setLoading] = useState(true);
  const [projectId, setProjectId] = useState('');
  const [scope, setScope] = useState('');
  const [reviewStatus, setReviewStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [newPack, setNewPack] = useState({ projectId: '', scope: 'REUSABLE', name: '', description: '' });
  const [newEntry, setNewEntry] = useState({ entryType: 'RULE', title: '', content: '' });

  const projectNames = useMemo(() => new Map(projects.map(project => [project.id, project.projectName])), [projects]);

  useEffect(() => {
    listProjects({ page: 1, size: 200 })
      .then(result => setProjects(asArray<Project>((result as any)?.items)))
      .catch(() => setProjects([]));
  }, []);

  const loadPacks = async () => {
    setLoading(true);
    try {
      const result = await listAdminWikiPacks({
        projectId: projectId ? Number(projectId) : undefined,
        scope: scope || undefined,
        reviewStatus: reviewStatus || undefined,
      });
      const next = asArray<WikiPack>(result);
      setPacks(next);
      if (selectedPack && !next.some(pack => pack.id === selectedPack.id)) {
        setSelectedPack(null);
        setEntries([]);
      }
    } catch (error: any) {
      showToast(error.message || '加载 Wiki 资产失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void loadPacks(); }, [projectId, scope, reviewStatus]);

  const selectPack = async (pack: WikiPack) => {
    setSelectedPack(pack);
    setEntries([]);
    try { setEntries(asArray<WikiEntry>(await listWikiEntries(pack.id))); }
    catch (error: any) { showToast(error.message || '加载知识条目失败', 'error'); }
  };

  const replacePack = (updated: WikiPack) => {
    setPacks(current => current.map(pack => pack.id === updated.id ? updated : pack));
    setSelectedPack(current => current?.id === updated.id ? updated : current);
  };

  const createPack = async () => {
    if (!newPack.projectId || !newPack.name.trim()) return showToast('请选择归属项目并填写知识包名称', 'error');
    try {
      const created = await createWikiPack(Number(newPack.projectId), newPack.scope, newPack.name.trim(), newPack.description.trim());
      setShowCreate(false);
      setNewPack({ projectId: '', scope: 'REUSABLE', name: '', description: '' });
      setPacks(current => [created, ...current]);
      await selectPack(created);
      showToast('Wiki 知识包已创建');
    } catch (error: any) { showToast(error.message || '创建失败', 'error'); }
  };

  const createEntry = async () => {
    if (!selectedPack || !newEntry.title.trim() || !newEntry.content.trim()) return;
    try {
      const created = await createWikiEntry(selectedPack.id, newEntry.entryType, newEntry.title.trim(), newEntry.content.trim());
      setEntries(current => [created, ...current]);
      setNewEntry({ entryType: 'RULE', title: '', content: '' });
      showToast('知识条目已创建，等待审核');
    } catch (error: any) { showToast(error.message || '创建条目失败', 'error'); }
  };

  const changePackReviewStatus = async (reviewStatus: 'APPROVED' | 'REJECTED') => {
    if (!selectedPack) return;
    try {
      replacePack(await reviewWikiPack(selectedPack.id, reviewStatus));
      showToast(reviewStatus === 'APPROVED' ? '知识包审核通过' : '知识包已驳回');
    } catch (error: any) {
      showToast(error.message || '知识包审核失败', 'error');
    }
  };

  const changePackStatus = async (status: 'ACTIVE' | 'INACTIVE') => {
    if (!selectedPack) return;
    try {
      replacePack(await updateWikiPackStatus(selectedPack.id, status));
      showToast(status === 'ACTIVE' ? '知识包已激活' : '知识包已停用');
    } catch (error: any) {
      showToast(error.message || '知识包状态更新失败', 'error');
    }
  };

  const changeEntryReviewStatus = async (entry: WikiEntry, reviewStatus: 'APPROVED' | 'REJECTED') => {
    try {
      const updated = await reviewWikiEntry(entry.id, reviewStatus);
      setEntries(current => current.map(item => item.id === entry.id ? updated : item));
      showToast(reviewStatus === 'APPROVED' ? '知识条目审核通过' : '知识条目已驳回');
    } catch (error: any) {
      showToast(error.message || '知识条目审核失败', 'error');
    }
  };

  const filteredPacks = useMemo(() => {
    const term = keyword.trim().toLowerCase();
    if (!term) return packs;
    return packs.filter(pack => [pack.name, pack.description, projectNames.get(pack.projectId), wikiScopeLabel(pack.scope)]
      .some(value => value && String(value).toLowerCase().includes(term)));
  }, [packs, keyword, projectNames]);

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-sky-100 bg-sky-50 px-4 py-3 text-xs text-sky-900">
        项目级知识用于单个项目；跨项目复用级可供多个项目参考；系统级是审核后的全局规则。三层知识均需完成条目审核、知识包审核并激活后才参与生成链路。
      </div>

      <div className="grid grid-cols-1 gap-3 rounded-xl border border-gray-200 bg-white p-4 md:grid-cols-4">
        <select value={projectId} onChange={event => setProjectId(event.target.value)} className="h-10 rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm">
          <option value="">全部项目</option>
          {projects.map(project => <option key={project.id} value={project.id}>{project.projectName}</option>)}
        </select>
        <select value={scope} onChange={event => setScope(event.target.value)} className="h-10 rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm">
          <option value="">全部 Wiki 层级</option>
          {wikiScopeOptions.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
        <select value={reviewStatus} onChange={event => setReviewStatus(event.target.value)} className="h-10 rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm">
          <option value="">全部审核状态</option><option value="PENDING">待审核</option><option value="APPROVED">审核通过</option><option value="REJECTED">已驳回</option>
        </select>
        <input value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="搜索知识包或项目" className="h-10 rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm" />
      </div>

      <div className="flex items-center justify-between">
        <div className="text-xs text-gray-500">共 {filteredPacks.length} 个知识包</div>
        <button onClick={() => setShowCreate(current => !current)} className="rounded-lg bg-slate-900 px-3 py-2 text-xs font-semibold text-white">新建 Wiki 知识包</button>
      </div>

      {showCreate && (
        <div className="grid grid-cols-1 gap-3 rounded-xl border border-gray-200 bg-white p-4 md:grid-cols-2">
          <select value={newPack.projectId} onChange={event => setNewPack(current => ({ ...current, projectId: event.target.value }))} className="h-10 rounded-lg border border-gray-200 px-3 text-sm">
            <option value="">选择归属项目</option>{projects.map(project => <option key={project.id} value={project.id}>{project.projectName}</option>)}
          </select>
          <select value={newPack.scope} onChange={event => setNewPack(current => ({ ...current, scope: event.target.value }))} className="h-10 rounded-lg border border-gray-200 px-3 text-sm">
            {wikiScopeOptions.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
          <input value={newPack.name} onChange={event => setNewPack(current => ({ ...current, name: event.target.value }))} placeholder="知识包名称" className="h-10 rounded-lg border border-gray-200 px-3 text-sm" />
          <input value={newPack.description} onChange={event => setNewPack(current => ({ ...current, description: event.target.value }))} placeholder="用途说明" className="h-10 rounded-lg border border-gray-200 px-3 text-sm" />
          <div className="flex gap-2 md:col-span-2"><button onClick={createPack} className="rounded-lg bg-slate-900 px-3 py-2 text-xs font-semibold text-white">创建</button><button onClick={() => setShowCreate(false)} className="px-3 py-2 text-xs text-gray-500">取消</button></div>
        </div>
      )}

      {loading ? <div className="py-10 text-center text-sm text-gray-400">正在加载...</div> : (
        <div className="grid min-h-[420px] grid-cols-1 gap-4 xl:grid-cols-[minmax(320px,0.9fr)_minmax(420px,1.4fr)]">
          <div className="space-y-2">
            {filteredPacks.map(pack => (
              <button key={pack.id} onClick={() => void selectPack(pack)} className={`w-full rounded-lg border p-3 text-left ${selectedPack?.id === pack.id ? 'border-slate-400 bg-slate-50' : 'border-gray-200 bg-white'}`}>
                <div className="flex flex-wrap gap-1.5">
                  {[pack.scope, pack.status, pack.reviewStatus].map((value, index) => <span key={`${value}-${index}`} className={`rounded px-1.5 py-0.5 text-[10px] ${badgeTone[value] || 'bg-gray-100 text-gray-600'}`}>{index === 0 ? wikiScopeLabel(value) : statusLabel(value)}</span>)}
                </div>
                <div className="mt-2 text-sm font-semibold text-gray-900">{pack.name}</div>
                <div className="mt-1 text-xs text-gray-500">归属项目：{projectNames.get(pack.projectId) || `项目 #${pack.projectId}`}</div>
              </button>
            ))}
            {!filteredPacks.length && <div className="rounded-lg border border-dashed border-gray-200 py-12 text-center text-sm text-gray-400">暂无匹配知识包</div>}
          </div>

          <div className="rounded-xl border border-gray-200 bg-white p-4">
            {!selectedPack ? <div className="py-20 text-center text-sm text-gray-400">选择左侧知识包查看和审核条目</div> : <>
              <div className="flex flex-wrap items-start justify-between gap-3 border-b border-gray-100 pb-3">
                <div><div className="font-semibold text-gray-900">{selectedPack.name}</div><div className="mt-1 text-xs text-gray-500">{wikiScopeLabel(selectedPack.scope)} · {statusLabel(selectedPack.status)} · {statusLabel(selectedPack.reviewStatus)}</div></div>
                <div className="flex flex-wrap gap-2">
                  {selectedPack.reviewStatus === 'PENDING' && <><button onClick={() => void changePackReviewStatus('APPROVED')} className="text-xs font-medium text-green-700">审核通过</button><button onClick={() => void changePackReviewStatus('REJECTED')} className="text-xs font-medium text-red-600">驳回</button></>}
                  {selectedPack.reviewStatus === 'APPROVED' && selectedPack.status !== 'ACTIVE' && <button onClick={() => void changePackStatus('ACTIVE')} className="rounded bg-slate-900 px-2.5 py-1.5 text-xs font-semibold text-white">激活</button>}
                  {selectedPack.status === 'ACTIVE' && <button onClick={() => void changePackStatus('INACTIVE')} className="text-xs font-medium text-amber-700">停用</button>}
                </div>
              </div>

              <div className="my-3 grid grid-cols-1 gap-2 md:grid-cols-[140px_1fr]">
                <select value={newEntry.entryType} onChange={event => setNewEntry(current => ({ ...current, entryType: event.target.value }))} className="h-9 rounded-lg border border-gray-200 px-2 text-xs"><option value="RULE">业务规则</option><option value="DECISION">设计决策</option><option value="IMPLEMENTATION">实现说明</option><option value="EXPERIENCE">测试经验</option></select>
                <input value={newEntry.title} onChange={event => setNewEntry(current => ({ ...current, title: event.target.value }))} placeholder="新增条目标题" className="h-9 rounded-lg border border-gray-200 px-3 text-xs" />
                <textarea value={newEntry.content} onChange={event => setNewEntry(current => ({ ...current, content: event.target.value }))} placeholder="知识内容" rows={3} className="rounded-lg border border-gray-200 px-3 py-2 text-xs md:col-span-2" />
                <button onClick={createEntry} className="w-fit rounded bg-gray-900 px-3 py-1.5 text-xs font-semibold text-white md:col-span-2">新增待审核条目</button>
              </div>

              <div className="max-h-[460px] space-y-2 overflow-y-auto pr-1">
                {entries.map(entry => <div key={entry.id} className="rounded-lg border border-gray-100 bg-gray-50 p-3">
                  <div className="flex flex-wrap items-center gap-2"><span className="text-xs font-semibold text-gray-900">{entry.title}</span><span className="rounded bg-white px-1.5 py-0.5 text-[10px] text-gray-600">{wikiEntryTypeLabel(entry.entryType)}</span><span className={`rounded px-1.5 py-0.5 text-[10px] ${badgeTone[entry.reviewStatus] || 'bg-gray-100 text-gray-600'}`}>{statusLabel(entry.reviewStatus)}</span><span className="rounded bg-white px-1.5 py-0.5 text-[10px] text-gray-600">{statusLabel(entry.effectiveStatus)}</span></div>
                  <div className="mt-2 whitespace-pre-wrap text-xs text-gray-600">{entry.content}</div>
                  {entry.sourceRefsJson && <details className="mt-2 text-xs text-gray-500"><summary className="cursor-pointer">查看来源追溯</summary><pre className="mt-1 max-h-32 overflow-auto whitespace-pre-wrap rounded bg-white p-2">{entry.sourceRefsJson}</pre></details>}
                  {entry.reviewStatus === 'PENDING' && <div className="mt-2 flex gap-3"><button onClick={() => void changeEntryReviewStatus(entry, 'APPROVED')} className="text-xs font-medium text-green-700">通过</button><button onClick={() => void changeEntryReviewStatus(entry, 'REJECTED')} className="text-xs font-medium text-red-600">驳回</button></div>}
                </div>)}
                {!entries.length && <div className="py-10 text-center text-xs text-gray-400">暂无知识条目</div>}
              </div>
            </>}
          </div>
        </div>
      )}
    </div>
  );
}
