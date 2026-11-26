import { useEffect, useState } from 'react';
import { listAssetInventory, type AssetInventoryRow } from '../../services/api';

export default function AssetInventoryPanel({ projectId }: { projectId?: number }) {
  const [open, setOpen] = useState(false);
  const [rows, setRows] = useState<AssetInventoryRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setLoading(true);
    setError('');
    const load = async () => {
      try {
        const result = await listAssetInventory(projectId, controller.signal);
        if (!controller.signal.aborted) setRows(result);
      } catch (cause) {
        if (!controller.signal.aborted) {
          setRows([]);
          setError(cause instanceof Error ? cause.message : '资产盘点加载失败');
        }
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    };
    void load();
    return () => controller.abort();
  }, [open, projectId, revision]);

  return (
    <section className="mb-4 rounded-xl border border-gray-200 bg-white p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-gray-900">全链路资产盘点</h2>
          <p className="mt-1 text-xs text-gray-500">按类型、范围、状态和来源统计现有记录；同一资产的版本另计。</p>
        </div>
        <div className="flex gap-2">
          {open && <button type="button" onClick={() => setRevision(value => value + 1)}
            className="rounded-lg border border-gray-200 px-3 py-2 text-xs text-gray-700">刷新盘点</button>}
          <button type="button" onClick={() => setOpen(value => !value)}
            aria-expanded={open} className="rounded-lg border border-gray-200 px-3 py-2 text-xs text-gray-700">
            {open ? '收起盘点' : '查看盘点'}
          </button>
        </div>
      </div>
      {open && <div className="mt-4">
        {loading ? <p className="text-sm text-gray-500">正在盘点…</p> : error ?
          <p role="alert" className="text-sm text-red-700">{error}</p> : rows.length === 0 ?
            <p className="text-sm text-gray-500">当前范围没有资产记录。</p> :
            <div className="max-h-80 overflow-auto">
              <table className="w-full min-w-[620px] text-left text-xs">
                <thead className="sticky top-0 bg-gray-50 text-gray-600"><tr>
                  <th className="px-3 py-2">资产类型</th><th className="px-3 py-2">范围</th>
                  <th className="px-3 py-2">状态</th><th className="px-3 py-2">来源</th>
                  <th className="px-3 py-2 text-right">记录数</th>
                </tr></thead>
                <tbody>{rows.map((row, index) => <tr key={`${row.assetType}-${row.scope}-${row.status}-${row.sourceType}-${index}`} className="border-t border-gray-100">
                  <td className="px-3 py-2">{row.assetType}</td><td className="px-3 py-2">{row.scope}</td>
                  <td className="px-3 py-2">{row.status}</td><td className="px-3 py-2">{row.sourceType || '—'}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{row.count}</td>
                </tr>)}</tbody>
              </table>
            </div>}
      </div>}
    </section>
  );
}
