import { useEffect, useState } from 'react';
import { listActiveTomModels, listWikiTomLinks, updateWikiTomLinks, type TestObjectModel } from '../services/api';

export default function WikiTomLinksEditor({ entryId, projectId, onChanged }: {
  entryId: number; projectId: number; onChanged: () => void;
}) {
  const [models, setModels] = useState<TestObjectModel[]>([]);
  const [selected, setSelected] = useState<number[]>([]);
  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    Promise.all([listActiveTomModels(projectId), listWikiTomLinks(entryId)])
      .then(([items, links]) => { if (active) { setModels(Array.isArray(items) ? items : []); setSelected(Array.isArray(links) ? links : []); } })
      .catch(() => { if (active) setError('无法读取 TOM 关联'); });
    return () => { active = false; };
  }, [entryId, projectId]);

  const filtered = models.filter(item => `${item.modelType} ${item.name} ${item.businessDomain || ''}`.includes(query.trim())).slice(0, 60);
  const save = async () => {
    setBusy(true);
    setError('');
    try {
      await updateWikiTomLinks(entryId, selected);
      onChanged();
    } catch (reason) { setError(reason instanceof Error ? reason.message : '保存关联失败'); }
    finally { setBusy(false); }
  };
  return <div className="mt-4 rounded-lg border border-slate-200 p-3 text-sm">
    <div className="font-medium text-slate-800">关联 TOM 逻辑骨架</div>
    <p className="mt-1 text-xs text-slate-500">选择此知识解释的角色、流程、动作或状态。修改关联后条目会重新进入审核。</p>
    <input value={query} onChange={event => setQuery(event.target.value)} placeholder="搜索 TOM 名称或类型" className="mt-2 w-full rounded border border-slate-200 px-2 py-1.5" />
    <div className="mt-2 max-h-36 overflow-y-auto space-y-1">
      {filtered.map(item => <label key={item.id} className="flex items-center gap-2 text-xs text-slate-700">
        <input type="checkbox" checked={selected.includes(item.id)} onChange={event => setSelected(current => event.target.checked ? [...current, item.id] : current.filter(id => id !== item.id))} />
        <span>{item.modelType} · {item.name} · {item.scope === 'SYSTEM' ? '系统' : '项目'}</span>
      </label>)}
      {!filtered.length && <div className="text-xs text-slate-400">没有匹配的已启用 TOM</div>}
    </div>
    <div className="mt-2 flex items-center justify-between"><span className="text-xs text-slate-500">已关联 {selected.length} 个</span><button disabled={busy || selected.length > 30} onClick={save} className="rounded bg-slate-900 px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50">保存关联</button></div>
    {error && <p className="mt-2 text-xs text-red-600">{error}</p>}
  </div>;
}
