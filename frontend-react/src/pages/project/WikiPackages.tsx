import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import {
  listWikiPacks, createWikiPack,
  listWikiEntries, createWikiEntry, reviewWikiEntry,
  type WikiPack, type WikiEntry
} from '../../services/api';

const scopeColors: Record<string, string> = {
  PROJECT: 'bg-blue-50 text-blue-700',
  REUSABLE: 'bg-amber-50 text-amber-700',
  SYSTEM: 'bg-purple-50 text-purple-700',
};

const statusColors: Record<string, string> = {
  DRAFT: 'bg-gray-100 text-gray-600',
  ACTIVE: 'bg-green-50 text-green-700',
  INACTIVE: 'bg-yellow-50 text-yellow-700',
  ARCHIVED: 'bg-gray-100 text-gray-400',
};

function asArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? value : [];
}

export default function WikiPackages() {
  const { projectId } = useParams<{ projectId: string }>();
  const { showToast } = useApp();
  const [packs, setPacks] = useState<WikiPack[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedPack, setSelectedPack] = useState<WikiPack | null>(null);
  const [entries, setEntries] = useState<WikiEntry[]>([]);
  const [showCreatePack, setShowCreatePack] = useState(false);
  const [showCreateEntry, setShowCreateEntry] = useState(false);
  const [newPackName, setNewPackName] = useState('');
  const [newPackScope, setNewPackScope] = useState('PROJECT');
  const [newEntryTitle, setNewEntryTitle] = useState('');
  const [newEntryContent, setNewEntryContent] = useState('');
  const [newEntryType, setNewEntryType] = useState('RULE');

  useEffect(() => {
    if (!projectId) return;
    setLoading(true);
    listWikiPacks(Number(projectId))
      .then(result => setPacks(asArray<WikiPack>(result)))
      .catch(() => setPacks([]))
      .finally(() => setLoading(false));
  }, [projectId]);

  const handleCreatePack = async () => {
    if (!projectId || !newPackName.trim()) return;
    try {
      const pack = await createWikiPack(Number(projectId), newPackScope, newPackName.trim(), '');
      setPacks(prev => [pack, ...prev]);
      setShowCreatePack(false);
      setNewPackName('');
      showToast('知识包创建成功');
    } catch (e: any) { showToast(e.message || '创建失败', 'error'); }
  };

  const handleSelectPack = async (pack: WikiPack) => {
    setSelectedPack(pack);
    setEntries([]);
    try {
      const items = await listWikiEntries(pack.id);
      setEntries(asArray<WikiEntry>(items));
    } catch { setEntries([]); }
  };

  const handleCreateEntry = async () => {
    if (!selectedPack || !newEntryTitle.trim() || !newEntryContent.trim()) return;
    try {
      const entry = await createWikiEntry(selectedPack.id, newEntryType, newEntryTitle.trim(), newEntryContent.trim());
      setEntries(prev => [entry, ...prev]);
      setShowCreateEntry(false);
      setNewEntryTitle('');
      setNewEntryContent('');
      showToast('知识条目创建成功');
    } catch (e: any) { showToast(e.message || '创建失败', 'error'); }
  };

  const handleReviewEntry = async (entryId: number, status: string) => {
    try {
      const updated = await reviewWikiEntry(entryId, status);
      setEntries(prev => prev.map(e => e.id === entryId ? updated : e));
      showToast(status === 'APPROVED' ? '已通过' : '已驳回');
    } catch (e: any) { showToast(e.message || '操作失败', 'error'); }
  };

  if (loading) return <div className="p-6 text-gray-400 text-sm">加载中...</div>;

  return (
    <div className="p-4 sm:p-6 space-y-4 animate-fade-in">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-bold text-gray-900">知识库</h1>
        <button onClick={() => setShowCreatePack(true)} className="bg-slate-900 text-white px-3 py-1.5 rounded-lg text-xs font-semibold hover:bg-slate-800">
          新建知识包
        </button>
      </div>

      {/* Pack 列表 */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
        {packs.map(pack => (
          <div key={pack.id} onClick={() => handleSelectPack(pack)}
            className={`p-4 rounded-xl border cursor-pointer transition-all ${selectedPack?.id === pack.id ? 'border-purple-300 bg-purple-50/50' : 'border-gray-200 hover:border-gray-300'}`}>
            <div className="flex items-center gap-2 mb-2">
              <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${scopeColors[pack.scope] || 'bg-gray-100 text-gray-600'}`}>
                {pack.scope}
              </span>
              <span className={`text-[10px] px-1.5 py-0.5 rounded ${statusColors[pack.status] || 'bg-gray-100 text-gray-600'}`}>
                {pack.status}
              </span>
            </div>
            <div className="text-sm font-semibold text-gray-900 truncate">{pack.name}</div>
            {pack.description && <div className="text-xs text-gray-500 mt-1 truncate">{pack.description}</div>}
          </div>
        ))}
        {packs.length === 0 && <div className="col-span-full text-center text-gray-400 text-sm py-8">暂无知识包</div>}
      </div>

      {/* Entry 列表 */}
      {selectedPack && (
        <div className="mt-4">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-700">{selectedPack.name} — 条目 ({entries.length})</h2>
            <button onClick={() => setShowCreateEntry(true)} className="text-xs text-purple-600 hover:text-purple-700 font-medium">+ 新建条目</button>
          </div>
          <div className="space-y-2">
            {entries.map(entry => (
              <div key={entry.id} className="p-3 bg-white rounded-lg border border-gray-200">
                <div className="flex items-center gap-2 mb-1">
                  <span className="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 text-gray-600">{entry.entryType}</span>
                  <span className={`text-[10px] px-1.5 py-0.5 rounded ${entry.reviewStatus === 'APPROVED' ? 'bg-green-50 text-green-700' : entry.reviewStatus === 'REJECTED' ? 'bg-red-50 text-red-700' : 'bg-yellow-50 text-yellow-700'}`}>
                    {entry.reviewStatus}
                  </span>
                </div>
                <div className="text-sm font-medium text-gray-900">{entry.title}</div>
                <div className="text-xs text-gray-500 mt-1 line-clamp-2">{entry.content}</div>
                {entry.reviewStatus === 'PENDING' && (
                  <div className="flex gap-2 mt-2">
                    <button onClick={() => handleReviewEntry(entry.id, 'APPROVED')} className="text-xs text-green-600 hover:text-green-700 font-medium">通过</button>
                    <button onClick={() => handleReviewEntry(entry.id, 'REJECTED')} className="text-xs text-red-500 hover:text-red-600 font-medium">驳回</button>
                  </div>
                )}
              </div>
            ))}
            {entries.length === 0 && <div className="text-center text-gray-400 text-sm py-6">暂无条目</div>}
          </div>
        </div>
      )}

      {/* 创建 Pack 弹窗 */}
      {showCreatePack && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/20 backdrop-blur-sm" onClick={() => setShowCreatePack(false)}>
          <div className="bg-white rounded-xl shadow-xl p-6 w-full max-w-md" onClick={e => e.stopPropagation()}>
            <h3 className="text-sm font-semibold text-gray-900 mb-3">新建知识包</h3>
            <div className="space-y-3">
              <div>
                <label className="text-xs text-gray-500 mb-1 block">名称</label>
                <input value={newPackName} onChange={e => setNewPackName(e.target.value)} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm" placeholder="知识包名称" />
              </div>
              <div>
                <label className="text-xs text-gray-500 mb-1 block">范围</label>
                <select value={newPackScope} onChange={e => setNewPackScope(e.target.value)} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm">
                  <option value="PROJECT">项目级</option>
                  <option value="REUSABLE">复用参考级</option>
                </select>
              </div>
              <div className="flex justify-end gap-2">
                <button onClick={() => setShowCreatePack(false)} className="px-3 py-1.5 text-sm text-gray-500">取消</button>
                <button onClick={handleCreatePack} className="px-3 py-1.5 bg-slate-900 text-white text-sm rounded-lg font-semibold hover:bg-slate-800">创建</button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 创建 Entry 弹窗 */}
      {showCreateEntry && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/20 backdrop-blur-sm" onClick={() => setShowCreateEntry(false)}>
          <div className="bg-white rounded-xl shadow-xl p-6 w-full max-w-lg" onClick={e => e.stopPropagation()}>
            <h3 className="text-sm font-semibold text-gray-900 mb-3">新建知识条目</h3>
            <div className="space-y-3">
              <div>
                <label className="text-xs text-gray-500 mb-1 block">类型</label>
                <select value={newEntryType} onChange={e => setNewEntryType(e.target.value)} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm">
                  <option value="RULE">规则</option>
                  <option value="DECISION">决策</option>
                  <option value="HISTORY">历史</option>
                  <option value="IMPLEMENTATION">实现说明</option>
                  <option value="EXCEPTION">例外</option>
                  <option value="FAQ">FAQ</option>
                </select>
              </div>
              <div>
                <label className="text-xs text-gray-500 mb-1 block">标题</label>
                <input value={newEntryTitle} onChange={e => setNewEntryTitle(e.target.value)} className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm" placeholder="条目标题" />
              </div>
              <div>
                <label className="text-xs text-gray-500 mb-1 block">内容</label>
                <textarea value={newEntryContent} onChange={e => setNewEntryContent(e.target.value)} rows={4} className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm resize-none" placeholder="知识内容..." />
              </div>
              <div className="flex justify-end gap-2">
                <button onClick={() => setShowCreateEntry(false)} className="px-3 py-1.5 text-sm text-gray-500">取消</button>
                <button onClick={handleCreateEntry} className="px-3 py-1.5 bg-slate-900 text-white text-sm rounded-lg font-semibold hover:bg-slate-800">创建</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
