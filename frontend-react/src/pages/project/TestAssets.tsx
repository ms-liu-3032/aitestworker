import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import {
  listFormalCases, listLocalCases, listProjectSummaries,
  listProjectSkills, listProjectTools, updateProjectSkill, updateProjectTool,
  listWikiPacks, listWikiEntries,
  type FormalCase, type LocalCaseDraft, type ProjectSkillAsset, type ProjectToolAsset,
  type WikiEntry, type WikiPack,
} from '../../services/api';
import { statusLabel } from '../../utils/displayLabels';

type TabKey = 'summaries' | 'drafts' | 'formal' | 'wiki' | 'skills' | 'tools';
type SummaryAsset = { id: number; traceGroupId?: number; summaryScope?: string; overview?: string; status?: string; validityLabel?: string; confidenceLabel?: string; createdAt?: string };
type WikiAssetPack = WikiPack & { entries: WikiEntry[] };
type Viewer = { title: string; content: string; source?: string };
type Editor = { type: 'skill' | 'tool'; id: number; title: string; secondary?: string; content: string };

const tabs: Array<{ key: TabKey; label: string }> = [
  { key: 'summaries', label: '轨迹摘要' }, { key: 'drafts', label: '草稿用例' },
  { key: 'formal', label: '正式用例' }, { key: 'wiki', label: '项目 Wiki' },
  { key: 'skills', label: 'Skill 模板' }, { key: 'tools', label: 'Tool 模板' },
];

const fmtDate = (value?: string | null) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-';

function StatCard({ title, value, subtitle }: { title: string; value: string; subtitle: string }) {
  return <div className="rounded-xl border border-gray-200 bg-white p-4"><div className="mb-1 text-[11px] font-semibold uppercase tracking-wider text-gray-400">{title}</div><div className="text-lg font-bold text-gray-900">{value}</div><div className="mt-1 text-xs text-gray-500">{subtitle}</div></div>;
}

export default function TestAssets() {
  const { projectId } = useParams<{ projectId: string }>();
  const { showToast } = useApp();
  const pid = Number(projectId);
  const [tab, setTab] = useState<TabKey>('summaries');
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadErrors, setLoadErrors] = useState<string[]>([]);
  const loadSequence = useRef(0);
  const [localCases, setLocalCases] = useState<LocalCaseDraft[]>([]);
  const [formalCases, setFormalCases] = useState<FormalCase[]>([]);
  const [summaries, setSummaries] = useState<SummaryAsset[]>([]);
  const [wikiPacks, setWikiPacks] = useState<WikiAssetPack[]>([]);
  const [skills, setSkills] = useState<ProjectSkillAsset[]>([]);
  const [tools, setTools] = useState<ProjectToolAsset[]>([]);
  const [viewing, setViewing] = useState<Viewer | null>(null);
  const [editing, setEditing] = useState<Editor | null>(null);

  const loadAssets = async () => {
    const sequence = ++loadSequence.current;
    if (!Number.isInteger(pid) || pid <= 0) {
      setLoadErrors(['项目编号无效']);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const results = await Promise.allSettled([
        listLocalCases(pid), listFormalCases(pid), listProjectSummaries(pid),
        listProjectSkills(pid), listProjectTools(pid), listWikiPacks(pid),
      ]);
      if (sequence !== loadSequence.current) return;
      const errors: string[] = [];
      const readList = <T,>(index: number, label: string): T[] => {
        const result = results[index];
        if (result.status === 'fulfilled' && Array.isArray(result.value)) return result.value as T[];
        errors.push(`${label}加载失败`);
        return [];
      };
      const drafts = readList<LocalCaseDraft>(0, '草稿用例');
      const formal = readList<FormalCase>(1, '正式用例');
      const summary = readList<SummaryAsset>(2, '轨迹摘要');
      const skill = readList<ProjectSkillAsset>(3, 'Skill 模板');
      const tool = readList<ProjectToolAsset>(4, 'Tool 模板');
      const projectPacks = readList<WikiPack>(5, '项目 Wiki');
      const packsWithEntries: WikiAssetPack[] = [];
      for (let offset = 0; offset < projectPacks.length; offset += 4) {
        const batch = projectPacks.slice(offset, offset + 4);
        const entryResults = await Promise.allSettled(batch.map(pack => listWikiEntries(pack.id)));
        if (sequence !== loadSequence.current) return;
        batch.forEach((pack, index) => {
          const result = entryResults[index];
          if (result.status === 'fulfilled' && Array.isArray(result.value)) {
            packsWithEntries.push({ ...pack, entries: result.value });
          } else {
            errors.push(`知识包 #${pack.id} 条目加载失败`);
            packsWithEntries.push({ ...pack, entries: [] });
          }
        });
      }
      setLocalCases(drafts);
      setFormalCases(formal);
      setSummaries(summary);
      setSkills(skill);
      setTools(tool);
      setWikiPacks(packsWithEntries);
      setLoadErrors(errors);
    } catch (error: any) {
      if (sequence === loadSequence.current) {
        setLoadErrors([error.message || '加载测试资产失败']);
      }
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  };

  useEffect(() => {
    void loadAssets();
    return () => { loadSequence.current += 1; };
  }, [pid]);

  const stats = useMemo(() => ({ summaries: summaries.length, drafts: localCases.length, formal: formalCases.length, wiki: wikiPacks.length, skills: skills.length, tools: tools.length }), [summaries, localCases, formalCases, wikiPacks, skills, tools]);
  const activeItems = useMemo((): any[] => ({ summaries, drafts: localCases, formal: formalCases, wiki: wikiPacks, skills, tools })[tab] || [], [tab, summaries, localCases, formalCases, wikiPacks, skills, tools]);
  const items = useMemo(() => {
    const term = keyword.trim().toLowerCase();
    if (!term) return activeItems;
    return activeItems.filter((item: any) => [item.caseTitle, item.caseNo, item.moduleName, item.overview, item.summaryScope, item.name, item.skillName, item.toolName, item.applicableScene, item.operationSteps, item.status, item.entries?.map((entry: WikiEntry) => `${entry.title} ${entry.content}`).join(' ')].some(value => value && String(value).toLowerCase().includes(term)));
  }, [activeItems, keyword]);

  const saveEdit = async () => {
    if (!editing || !pid || !editing.title.trim() || !editing.content.trim()) return;
    try {
      if (editing.type === 'skill') {
        const updated = await updateProjectSkill(pid, editing.id, { skillName: editing.title.trim(), applicableScene: editing.secondary || '', flowSteps: editing.content.trim() });
        setSkills(current => current.map(item => item.id === updated.id ? updated : item));
        showToast('Skill 模板已保存，已回到待审核状态');
      } else {
        const updated = await updateProjectTool(pid, editing.id, { toolName: editing.title.trim(), operationSteps: editing.content.trim() });
        setTools(current => current.map(item => item.id === updated.id ? updated : item));
        showToast('Tool 模板已保存，已回到待审核状态');
      }
      setEditing(null);
    } catch (error: any) { showToast(error.message || '保存资产失败', 'error'); }
  };

  const emptyText: Record<TabKey, string> = {
    summaries: '暂无轨迹摘要，先去测试执行轨迹里生成或确认摘要。', drafts: '暂无草稿用例，先去用例生成页或轨迹页生成草稿。', formal: '暂无正式用例，确认有效的草稿后会沉淀到这里。',
    wiki: '暂无项目 Wiki。已确认的需求、测试点、上传资料和轨迹摘要会沉淀为项目级候选；Skill 和 Tool 不属于 Wiki。', skills: '暂无 Skill 模板，确认过的稳定操作链路更适合沉淀到这里。', tools: '暂无 Tool 模板，工具型操作流程沉淀后会出现在这里。',
  };

  return <div className="animate-fade-in p-4 sm:p-6">
    <div className="mb-5 rounded-xl border border-gray-200 bg-white p-4">
      <div className="text-sm font-semibold text-gray-900">资产从哪里来、到哪里去</div>
      <div className="mt-1 text-xs text-gray-500">TOM、Wiki、用例和自动化映射在各自页面维护；这里统一查看与跳转。Skill/Tool 模板仍在本页维护。</div>
      <div className="mt-3 flex flex-wrap items-center gap-2 text-xs"><span className="font-medium text-gray-600">设计依据</span>
        <Link to={`/projects/${pid}/mini-tom`} className="rounded bg-slate-50 px-2 py-1 text-slate-700">业务骨架 TOM</Link>
        <span className="text-gray-400">+</span>
        <Link to={`/projects/${pid}/wiki`} className="rounded bg-slate-50 px-2 py-1 text-slate-700">业务知识 Wiki</Link>
        <span className="text-gray-400">+</span>
        <Link to={`/projects/${pid}/formal-cases`} className="rounded bg-slate-50 px-2 py-1 text-slate-700">既有正式用例</Link>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-2 text-xs"><span className="font-medium text-gray-600">产出链路</span>
        <Link to={`/projects/${pid}/generation`} className="rounded bg-slate-50 px-2 py-1 text-slate-700">测试点</Link>
        <span className="text-gray-400">→</span>
        <Link to={`/projects/${pid}/local-cases`} className="rounded bg-slate-50 px-2 py-1 text-slate-700">草稿用例</Link>
        <span className="text-gray-400">→</span>
        <Link to={`/projects/${pid}/formal-cases`} className="rounded bg-slate-50 px-2 py-1 text-slate-700">正式用例</Link>
        <span className="text-gray-400">→</span>
        <Link to={`/projects/${pid}/automation-assets`} className="rounded bg-slate-50 px-2 py-1 text-slate-700">自动化映射</Link>
        <span className="text-gray-400">→</span>
        <Link to={`/projects/${pid}/trace`} className="rounded bg-slate-50 px-2 py-1 text-slate-700">运行证据</Link>
      </div>
      <div className="mt-3 border-t border-gray-100 pt-3 text-xs text-gray-600">
        <span className="font-medium">待处理入口：</span>
        <Link to={`/projects/${pid}/mini-tom`} className="ml-2 text-sky-700">TOM 候选与关系审核</Link>
        <Link to={`/projects/${pid}/wiki`} className="ml-3 text-sky-700">Wiki 包与条目审核</Link>
        <Link to={`/projects/${pid}/local-cases`} className="ml-3 text-sky-700">草稿确认与提交</Link>
        <Link to={`/projects/${pid}/automation-assets`} className="ml-3 text-sky-700">自动化版本审核</Link>
      </div>
    </div>
    <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between"><div><h1 className="text-xl font-bold tracking-tight text-gray-900">资产总览</h1><p className="mt-1 text-sm text-gray-500">集中查看项目资产；TOM、Wiki、用例和自动化映射在各自页面维护与审核。</p></div><div className="flex gap-3"><Link to={`/projects/${pid}/trace`} className="text-sm text-gray-500 hover:text-gray-900">返回轨迹页</Link><button onClick={loadAssets} className="rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50">刷新资产</button></div></div>
    {loadErrors.length > 0 && <div role="alert" className="mb-4 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">{loadErrors.join('；')}。下方数字仅代表成功加载的数据，不能作为资产总数。<button onClick={loadAssets} className="ml-2 font-semibold underline">重试</button></div>}
    <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-6"><StatCard title="轨迹摘要" value={`${stats.summaries}`} subtitle="已沉淀摘要" /><StatCard title="草稿用例" value={`${stats.drafts}`} subtitle="待整理草稿" /><StatCard title="正式用例" value={`${stats.formal}`} subtitle="已提交正式库" /><StatCard title="项目 Wiki" value={`${stats.wiki}`} subtitle="待审核知识包" /><StatCard title="Skill 模板" value={`${stats.skills}`} subtitle="可复用操作模板" /><StatCard title="Tool 模板" value={`${stats.tools}`} subtitle="可复用工具模板" /></div>
    <div className="mb-4 flex w-fit max-w-full flex-wrap gap-1 rounded-lg bg-gray-100 p-0.5">{tabs.map(item => <button key={item.key} onClick={() => setTab(item.key)} className={`rounded-md px-3 py-1.5 text-xs font-medium ${tab === item.key ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'}`}>{item.label} <span className="ml-1 text-[10px] text-gray-400">{stats[item.key]}</span></button>)}</div>
    <div className="mb-4 rounded-xl border border-gray-200 bg-white p-4"><div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between"><div><div className="text-sm font-semibold text-gray-900">{tabs.find(item => item.key === tab)?.label}</div><div className="mt-1 text-xs text-gray-500">当前展示 {items.length} / {stats[tab]} 条，按关键词定位资产。</div></div><input value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="搜索名称、内容、模块、状态..." className="h-10 w-full rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm outline-none md:w-80" /></div></div>
    {loading ? <div className="space-y-2">{[1, 2, 3].map(i => <div key={i} className="h-16 w-full animate-pulse rounded bg-gray-100" />)}</div> : items.length === 0 ? <div className="rounded-xl border border-gray-200 bg-white p-12 text-center text-sm text-gray-400">{emptyText[tab]}</div> : <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
      {tab === 'summaries' && items.map((item: SummaryAsset) => <div key={item.id} className="border-b border-gray-100 px-5 py-4 last:border-0"><div className="flex justify-between gap-3"><div><div className="text-sm font-medium text-gray-900">{item.overview || `摘要 #${item.id}`}</div><div className="mt-1 text-xs text-gray-500">{item.summaryScope && `范围：${item.summaryScope} `}{item.validityLabel && `有效性：${item.validityLabel} `}{item.confidenceLabel && `置信度：${item.confidenceLabel}`}</div></div><Link to={`/projects/${pid}/trace`} className="shrink-0 text-xs text-sky-700">去轨迹页查看</Link></div></div>)}
      {tab === 'drafts' && items.map((item: LocalCaseDraft) => <div key={item.id} className="border-b border-gray-100 px-5 py-4 last:border-0"><div className="flex flex-col gap-3 sm:flex-row sm:justify-between"><div><div className="text-sm font-medium text-gray-900">{item.caseTitle}</div><div className="mt-1 text-xs text-gray-500">{item.moduleName && `模块：${item.moduleName} `}{item.priority && `优先级：${item.priority} `}{item.caseType && `类型：${item.caseType}`}</div></div><div className="flex shrink-0 items-center gap-3"><span className="text-xs text-gray-500">{statusLabel(item.caseStatus || 'DRAFT')}</span><Link to={`/projects/${pid}/local-cases`} className="text-xs text-sky-700">去本地库整理与提交</Link></div></div></div>)}
      {tab === 'formal' && items.map((item: FormalCase) => <div key={item.id} className="border-b border-gray-100 px-5 py-4 last:border-0"><div className="flex justify-between gap-3"><div><div className="text-sm font-medium text-gray-900">{item.caseTitle}</div><div className="mt-1 text-xs text-gray-500">{item.caseNo} {item.moduleName && `· ${item.moduleName}`} {item.priority && `· ${item.priority}`}</div></div><Link to={`/projects/${pid}/formal-cases`} className="shrink-0 text-xs text-slate-600">去正式库</Link></div></div>)}
      {tab === 'wiki' && items.map((pack: WikiAssetPack) => <div key={pack.id} className="border-b border-gray-100 px-5 py-4 last:border-0"><div className="flex flex-col gap-3 sm:flex-row sm:justify-between"><div><div className="text-sm font-medium text-gray-900">{pack.name}</div><div className="mt-1 text-xs text-gray-500">项目级知识包 · {statusLabel(pack.status)} · {pack.reviewStatus === 'APPROVED' ? '已审核' : pack.reviewStatus === 'REJECTED' ? '已驳回' : '待审核'} · {pack.entries.length} 条内容</div>{pack.description && <div className="mt-1 text-xs text-gray-500">{pack.description}</div>}</div><div className="flex shrink-0 gap-3 text-xs"><button onClick={() => setViewing({ title: pack.name, content: pack.description || '暂无知识包说明' })} className="font-medium text-slate-700">查看包详情</button><Link to={`/projects/${pid}/wiki`} className="font-medium text-sky-700">进入知识库管理</Link></div></div><div className="mt-3 space-y-2">{pack.entries.map(entry => <div key={entry.id} className="rounded-lg border border-gray-100 bg-gray-50 px-3 py-2"><div className="flex flex-col gap-2 sm:flex-row sm:justify-between"><div className="min-w-0"><div className="text-sm font-medium text-gray-800">{entry.title}</div><div className="mt-1 line-clamp-2 text-xs text-gray-500">{entry.content}</div><div className="mt-1 text-[11px] text-gray-400">{entry.entryType} · {entry.reviewStatus === 'APPROVED' ? '已审核' : entry.reviewStatus === 'REJECTED' ? '已驳回' : '待审核'}</div></div><div className="flex shrink-0 gap-3 text-xs"><button onClick={() => setViewing({ title: entry.title, content: entry.content, source: entry.sourceRefsJson || undefined })} className="font-medium text-slate-700">查看详情</button><Link to={`/projects/${pid}/wiki`} className="font-medium text-sky-700">去知识库维护</Link></div></div></div>)}{pack.entries.length === 0 && <div className="text-xs text-gray-400">该知识包暂无条目。</div>}</div></div>)}
      {tab === 'skills' && items.map((item: ProjectSkillAsset) => <div key={item.id} className="border-b border-gray-100 px-5 py-4 last:border-0"><div className="flex flex-col gap-3 sm:flex-row sm:justify-between"><div><div className="text-sm font-medium text-gray-900">{item.skillName}</div><div className="mt-1 line-clamp-2 text-xs text-gray-500">{item.applicableScene || '暂无适用场景'}</div></div><div className="flex shrink-0 items-center gap-3 text-xs"><span className="text-gray-500">{statusLabel(item.status || 'DRAFT')}</span><button onClick={() => setViewing({ title: item.skillName, content: `适用场景：${item.applicableScene || '-'}\n\n操作步骤：\n${item.flowSteps || '-'}` })} className="font-medium text-slate-700">查看详情</button><button onClick={() => setEditing({ type: 'skill', id: item.id, title: item.skillName, secondary: item.applicableScene || '', content: item.flowSteps || '' })} className="font-medium text-blue-700">编辑</button><span className="font-mono text-gray-400">{fmtDate(item.updatedAt)}</span></div></div></div>)}
      {tab === 'tools' && items.map((item: ProjectToolAsset) => <div key={item.id} className="border-b border-gray-100 px-5 py-4 last:border-0"><div className="flex flex-col gap-3 sm:flex-row sm:justify-between"><div><div className="text-sm font-medium text-gray-900">{item.toolName}</div><div className="mt-1 line-clamp-2 text-xs text-gray-500">{item.operationSteps || '暂无操作说明'}</div></div><div className="flex shrink-0 items-center gap-3 text-xs"><span className="text-gray-500">{statusLabel(item.status || 'DRAFT')}</span><button onClick={() => setViewing({ title: item.toolName, content: item.operationSteps || '暂无操作说明' })} className="font-medium text-slate-700">查看详情</button><button onClick={() => setEditing({ type: 'tool', id: item.id, title: item.toolName, content: item.operationSteps || '' })} className="font-medium text-blue-700">编辑</button><span className="font-mono text-gray-400">{fmtDate(item.updatedAt)}</span></div></div></div>)}
    </div>}
    {viewing && <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" onClick={() => setViewing(null)}><div className="max-h-[85vh] w-full max-w-2xl overflow-auto rounded-lg bg-white p-5 shadow-xl" onClick={event => event.stopPropagation()}><div className="flex justify-between gap-4"><h2 className="text-base font-semibold text-gray-900">{viewing.title}</h2><button onClick={() => setViewing(null)} className="text-sm text-gray-500">关闭</button></div><pre className="mt-4 whitespace-pre-wrap break-words rounded-lg bg-gray-50 p-3 text-sm text-gray-700">{viewing.content}</pre>{viewing.source && <pre className="mt-3 max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-gray-50 p-3 text-xs text-gray-500">来源：{viewing.source}</pre>}</div></div>}
    {editing && <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" onClick={() => setEditing(null)}><div className="max-h-[90vh] w-full max-w-2xl overflow-auto rounded-lg bg-white p-5 shadow-xl" onClick={event => event.stopPropagation()}><h2 className="text-base font-semibold text-gray-900">编辑{editing.type === 'skill' ? 'Skill 模板' : 'Tool 模板'}</h2><label className="mt-4 block text-xs font-medium text-gray-600">名称</label><input value={editing.title} onChange={event => setEditing({ ...editing, title: event.target.value })} className="mt-1 h-10 w-full rounded-lg border border-gray-200 px-3 text-sm" />{editing.type === 'skill' && <><label className="mt-3 block text-xs font-medium text-gray-600">适用场景</label><input value={editing.secondary || ''} onChange={event => setEditing({ ...editing, secondary: event.target.value })} className="mt-1 h-10 w-full rounded-lg border border-gray-200 px-3 text-sm" /></>}<label className="mt-3 block text-xs font-medium text-gray-600">{editing.type === 'skill' ? '操作步骤' : editing.type === 'tool' ? '操作说明' : '内容'}</label><textarea value={editing.content} onChange={event => setEditing({ ...editing, content: event.target.value })} className="mt-1 min-h-56 w-full rounded-lg border border-gray-200 p-3 text-sm" /><div className="mt-2 text-xs text-amber-700">保存后将回到待审核状态，审核通过并激活后才会参与后续召回。</div><div className="mt-4 flex justify-end gap-3"><button onClick={() => setEditing(null)} className="px-3 py-2 text-sm text-gray-600">取消</button><button onClick={saveEdit} className="rounded-lg bg-slate-900 px-3 py-2 text-sm font-medium text-white">保存并重新审核</button></div></div></div>}
  </div>;
}
