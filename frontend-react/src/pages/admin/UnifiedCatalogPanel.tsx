import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { listAllProjects, listAssetCatalog, type CatalogAsset, type Project } from '../../services/api';
import { statusLabel } from '../../utils/displayLabels';

const assetTypes = [
  ['TOM', 'TOM 节点'], ['WIKI_PACK', 'Wiki 包'], ['WIKI_ENTRY', 'Wiki 条目'],
  ['FORMAL_CASE', '正式用例'], ['AUTOMATION', '自动化映射'],
  ['BUSINESS_PACK', '业务包'], ['KNOWLEDGE', '知识片段'],
  ['SKILL', 'Skill'], ['TOOL', 'Tool'], ['TRACE_SUMMARY', '轨迹摘要'],
] as const;

function detailPath(asset: CatalogAsset): string | null {
  const project = `/projects/${asset.projectId}`;
  switch (asset.assetType) {
    case 'TOM': return `/admin/asset-center?tab=tom&modelId=${asset.id}`;
    case 'WIKI_PACK': return `/admin/asset-center?tab=catalog&assetType=wiki&packId=${asset.id}`;
    case 'WIKI_ENTRY': return asset.parentId
      ? `/admin/asset-center?tab=catalog&assetType=wiki&packId=${asset.parentId}&entryId=${asset.id}` : null;
    case 'FORMAL_CASE': return `${project}/formal-cases`;
    case 'AUTOMATION': return `${project}/automation-assets?assetId=${asset.id}`;
    case 'BUSINESS_PACK': return `${project}/business-packs`;
    case 'KNOWLEDGE': return '/admin/asset-center?tab=catalog&assetType=knowledge';
    case 'SKILL': return `${project}/assets`;
    case 'TOOL': return `${project}/assets`;
    case 'TRACE_SUMMARY': return `${project}/trace`;
    default: return null;
  }
}

export default function UnifiedCatalogPanel() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState('');
  const [type, setType] = useState('');
  const [status, setStatus] = useState('');
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [items, setItems] = useState<CatalogAsset[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [projectError, setProjectError] = useState('');
  const [revision, setRevision] = useState(0);
  const requestSequence = useRef(0);
  const projectNames = useMemo(() => new Map(projects.map(project => [project.id, project.projectName])), [projects]);

  useEffect(() => {
    let alive = true;
    listAllProjects().then(result => { if (alive) setProjects(result); })
      .catch(cause => { if (alive) setProjectError(cause instanceof Error ? cause.message : '项目列表加载失败'); });
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    const request = ++requestSequence.current;
    setLoading(true);
    setError('');
    listAssetCatalog({ projectId: projectId ? Number(projectId) : undefined, type, status, keyword, page, size: 50 })
      .then(result => {
        if (request !== requestSequence.current) return;
        setItems(result.items);
        setTotal(result.total);
      })
      .catch(cause => {
        if (request !== requestSequence.current) return;
        setItems([]);
        setTotal(0);
        setError(cause instanceof Error ? cause.message : '资产目录加载失败');
      })
      .finally(() => { if (request === requestSequence.current) setLoading(false); });
    return () => { requestSequence.current++; };
  }, [projectId, type, status, keyword, page, revision]);

  const updateFilter = (setter: (value: string) => void, value: string) => {
    setter(value);
    setPage(1);
  };

  return <section className="mb-6 space-y-4">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div><h2 className="text-lg font-semibold text-gray-900">统一资产目录</h2>
        <p className="mt-1 text-xs text-gray-500">这里显示同一资产的来源、适用范围和审核状态；修改与审核仍进入资产所属页面。</p></div>
      <button type="button" onClick={() => setRevision(value => value + 1)} className="rounded-lg border px-3 py-2 text-sm">刷新目录</button>
    </div>
    <div className="grid gap-3 rounded-xl border bg-white p-4 sm:grid-cols-2 xl:grid-cols-5">
      <select aria-label="目录项目" value={projectId} onChange={event => updateFilter(setProjectId, event.target.value)} className="rounded-lg border p-2 text-sm">
        <option value="">全部项目</option>{projects.map(project => <option key={project.id} value={project.id}>{project.projectName}</option>)}
      </select>
      <select aria-label="目录类型" value={type} onChange={event => updateFilter(setType, event.target.value)} className="rounded-lg border p-2 text-sm">
        <option value="">全部类型</option>{assetTypes.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select>
      <select aria-label="目录状态" value={status} onChange={event => updateFilter(setStatus, event.target.value)} className="rounded-lg border p-2 text-sm">
        <option value="">全部状态</option><option value="PENDING">待审核</option><option value="APPROVED">已通过</option>
        <option value="REJECTED">已驳回</option><option value="ACTIVE">已生效</option>
        <option value="DRAFT">草稿</option><option value="DEPRECATED">已弃用</option>
      </select>
      <input aria-label="目录关键词" value={keywordInput} onChange={event => setKeywordInput(event.target.value)}
        onKeyDown={event => { if (event.key === 'Enter') updateFilter(setKeyword, keywordInput.trim()); }}
        placeholder="按资产名称搜索" className="rounded-lg border p-2 text-sm" />
      <button type="button" onClick={() => updateFilter(setKeyword, keywordInput.trim())} className="rounded-lg bg-slate-900 px-3 py-2 text-sm text-white">搜索</button>
    </div>
    {projectError && <p role="alert" className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">项目筛选不可用：{projectError}</p>}
    {error && <p role="alert" className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">{error}</p>}
    {loading ? <p className="rounded-xl border bg-white p-6 text-sm text-gray-500">正在加载资产目录…</p> :
      <div className="overflow-x-auto rounded-xl border bg-white"><table className="w-full min-w-[880px] text-left text-sm">
        <thead className="bg-gray-50 text-gray-600"><tr><th className="p-3">资产</th><th className="p-3">项目</th><th className="p-3">范围</th><th className="p-3">状态</th><th className="p-3">来源</th><th className="p-3">操作</th></tr></thead>
        <tbody>{items.map(asset => <tr key={`${asset.assetType}:${asset.id}`} className="border-t align-top">
          <td className="p-3"><span className="mr-2 rounded bg-gray-100 px-1.5 py-0.5 text-xs">{assetTypes.find(([value]) => value === asset.assetType)?.[1] || asset.assetType}</span>
            <span className="font-medium">{asset.title || `#${asset.id}`}</span><span className="ml-2 text-xs text-gray-400">#{asset.id}</span></td>
          <td className="p-3">{projectNames.get(asset.projectId) || `#${asset.projectId}`}</td>
          <td className="p-3">{asset.scope || '—'}</td>
          <td className="p-3">{asset.lifecycleStatus ? statusLabel(asset.lifecycleStatus) : '—'}{asset.reviewStatus && <span className="ml-2 text-xs text-gray-500">审核：{statusLabel(asset.reviewStatus)}</span>}</td>
          <td className="p-3 text-xs">{asset.sourceType || '未标注'}{asset.sourceId ? ` #${asset.sourceId}` : ''}</td>
          <td className="p-3">{detailPath(asset) ? <Link to={detailPath(asset)!} className="text-blue-700 hover:underline">查看处理</Link> : '—'}</td>
        </tr>)}</tbody>
      </table>{items.length === 0 && <p className="p-6 text-center text-sm text-gray-500">当前筛选没有资产。</p>}</div>}
    <div className="flex items-center justify-between text-xs text-gray-600"><span>共 {total} 条 · 第 {page} 页</span>
      <div className="flex gap-2"><button disabled={page <= 1 || loading} onClick={() => setPage(value => value - 1)} className="rounded border px-3 py-1 disabled:opacity-40">上一页</button>
        <button disabled={page * 50 >= total || loading} onClick={() => setPage(value => value + 1)} className="rounded border px-3 py-1 disabled:opacity-40">下一页</button></div>
    </div>
  </section>;
}
