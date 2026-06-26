import { useState, useEffect, useCallback } from 'react';
import { useApp } from '../../context/AppContext';
import {
  getLoopStatus, setLoopStatus,
  listLoopEvents, listLoopClusters,
  approveLoopCluster, rejectLoopCluster, consumeLoopCandidates,
  type LoopEvent, type LoopCluster
} from '../../services/api';

const statusColors: Record<string, string> = {
  PENDING: 'bg-yellow-50 text-yellow-700',
  APPROVED: 'bg-green-50 text-green-700',
  REJECTED: 'bg-red-50 text-red-700',
  CONSUMED: 'bg-blue-50 text-blue-700',
};

export default function LoopConfig() {
  const { showToast } = useApp();
  const [enabled, setEnabled] = useState(false);
  const [events, setEvents] = useState<LoopEvent[]>([]);
  const [clusters, setClusters] = useState<LoopCluster[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedProjectId, setSelectedProjectId] = useState<number>(1);

  const loadData = useCallback(() => {
    setLoading(true);
    Promise.all([
      getLoopStatus().catch(() => false),
      listLoopEvents(selectedProjectId).catch(() => []),
      listLoopClusters(selectedProjectId).catch(() => []),
    ]).then(([s, e, c]) => {
      setEnabled(s);
      setEvents(e);
      setClusters(c);
    }).finally(() => setLoading(false));
  }, [selectedProjectId]);

  useEffect(() => { loadData(); }, [loadData]);

  const handleToggle = async () => {
    try {
      await setLoopStatus(!enabled);
      setEnabled(!enabled);
      showToast(enabled ? '学习回灌已关闭' : '学习回灌已开启');
    } catch (e: any) { showToast(e.message || '操作失败', 'error'); }
  };

  const handleApprove = async (clusterId: number) => {
    try {
      await approveLoopCluster(clusterId);
      showToast('已批准');
      loadData();
    } catch (e: any) { showToast(e.message || '操作失败', 'error'); }
  };

  const handleReject = async (clusterId: number) => {
    try {
      await rejectLoopCluster(clusterId);
      showToast('已驳回');
      loadData();
    } catch (e: any) { showToast(e.message || '操作失败', 'error'); }
  };

  const handleConsume = async () => {
    try {
      const result = await consumeLoopCandidates(selectedProjectId);
      showToast(`生成 ${result.candidatesGenerated} 个候选资产`);
      loadData();
    } catch (e: any) { showToast(e.message || '操作失败', 'error'); }
  };

  if (loading) return <div className="p-6 text-gray-400 text-sm">加载中...</div>;

  return (
    <div className="p-4 sm:p-6 space-y-4 animate-fade-in">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-lg font-bold text-gray-900">学习回灌</h1>
          <p className="text-xs text-gray-500 mt-0.5">Loop 引擎默认关闭，由后台管理员统一配置</p>
        </div>
        <div className="flex items-center gap-2">
          <label className="text-xs text-gray-500">项目 ID</label>
          <input type="number" value={selectedProjectId} min={1}
            onChange={e => setSelectedProjectId(Number(e.target.value))}
            className="w-20 h-8 px-2 bg-gray-50 border border-gray-200 rounded-lg text-sm" />
          <button onClick={handleToggle}
            className={`px-4 py-2 rounded-lg text-sm font-semibold transition-colors ${enabled ? 'bg-red-50 text-red-700 hover:bg-red-100' : 'bg-slate-900 text-white hover:bg-slate-800'}`}>
            {enabled ? '关闭回灌' : '开启回灌'}
          </button>
          <button onClick={handleConsume}
            className="px-4 py-2 rounded-lg text-sm font-semibold bg-purple-600 text-white hover:bg-purple-700 transition-colors">
            消费候选
          </button>
        </div>
      </div>

      <div>
        <h2 className="text-sm font-semibold text-gray-700 mb-2">学习事件 ({events.length})</h2>
        {events.length === 0 ? (
          <div className="text-center text-gray-400 text-sm py-6 bg-white rounded-lg border border-gray-200">暂无学习事件</div>
        ) : (
          <div className="space-y-2">
            {events.slice(0, 20).map(event => (
              <div key={event.id} className="p-3 bg-white rounded-lg border border-gray-200">
                <div className="flex items-center gap-2 mb-1">
                  <span className="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 text-gray-600">{event.eventType}</span>
                  <span className={`text-[10px] px-1.5 py-0.5 rounded ${statusColors[event.status] || 'bg-gray-100 text-gray-600'}`}>
                    {event.status}
                  </span>
                  {event.suggestedAssetType && (
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-purple-50 text-purple-700">{event.suggestedAssetType}</span>
                  )}
                </div>
                {event.normalizedIssue && <div className="text-xs text-gray-700 mt-1">{event.normalizedIssue}</div>}
              </div>
            ))}
          </div>
        )}
      </div>

      <div>
        <h2 className="text-sm font-semibold text-gray-700 mb-2">问题聚类 ({clusters.length})</h2>
        {clusters.length === 0 ? (
          <div className="text-center text-gray-400 text-sm py-6 bg-white rounded-lg border border-gray-200">暂无聚类</div>
        ) : (
          <div className="space-y-2">
            {clusters.map(cluster => (
              <div key={cluster.id} className="p-3 bg-white rounded-lg border border-gray-200">
                <div className="flex items-center gap-2 mb-1">
                  <span className="text-sm font-medium text-gray-900">{cluster.theme || '未命名聚类'}</span>
                  <span className="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 text-gray-600">{cluster.eventCount} 条事件</span>
                  <span className={`text-[10px] px-1.5 py-0.5 rounded ${statusColors[cluster.status] || 'bg-gray-100 text-gray-600'}`}>
                    {cluster.status}
                  </span>
                </div>
                {cluster.suggestedAction && <div className="text-xs text-gray-500 mt-1">{cluster.suggestedAction}</div>}
                {cluster.status === 'PENDING' && (
                  <div className="flex gap-2 mt-2">
                    <button onClick={() => handleApprove(cluster.id)} className="text-xs text-green-600 hover:text-green-700 font-medium">批准</button>
                    <button onClick={() => handleReject(cluster.id)} className="text-xs text-red-500 hover:text-red-600 font-medium">驳回</button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
