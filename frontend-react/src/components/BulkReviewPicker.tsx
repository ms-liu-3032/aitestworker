import { useEffect, useState } from 'react';
import { useApp } from '../context/AppContext';

export type ReviewItem = { id: number; name: string };

type Props = {
  title: string;
  items: ReviewItem[];
  onReview: (id: number, approved: boolean, reason?: string) => Promise<void>;
  onComplete?: () => Promise<void>;
  rejectReasonRequired?: boolean;
  allowReject?: boolean;
};

/** Selection stays local to the visible queue; each review uses the page's existing protected endpoint. */
export default function BulkReviewPicker({ title, items, onReview, onComplete, rejectReasonRequired = false, allowReject = true }: Props) {
  const { showToast } = useApp();
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [running, setRunning] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [reason, setReason] = useState('');
  const visibleIds = items.map(item => item.id).join(',');
  useEffect(() => {
    const ids = new Set(items.map(item => item.id));
    setSelected(previous => {
      const retained = [...previous].filter(id => ids.has(id));
      return retained.length === previous.size ? previous : new Set(retained);
    });
  }, [visibleIds]);

  const review = async (approved: boolean) => {
    const chosen = items.filter(item => selected.has(item.id));
    if (!chosen.length || running) return;
    if (!approved && rejectReasonRequired && !reason.trim()) { showToast('请填写批量驳回原因', 'error'); return; }
    if (!window.confirm(`确定${approved ? '通过' : '驳回'}选中的 ${chosen.length} 条${title}吗？`)) return;
    setRunning(true);
    const failed: number[] = [];
    for (const item of chosen) {
      try { await onReview(item.id, approved, approved ? undefined : reason.trim() || undefined); }
      catch { failed.push(item.id); }
    }
    setSelected(new Set(failed));
    if (onComplete) {
      try { await onComplete(); }
      catch { showToast('审核已提交，但刷新列表失败，请手动刷新', 'error'); }
    }
    setRunning(false);
    showToast(`批量审核完成：成功 ${chosen.length - failed.length} 条，失败 ${failed.length} 条${failed.length ? '；失败项已保留选择' : ''}`, failed.length ? 'error' : undefined);
  };

  if (!items.length) return null;
  return (
    <details open={expanded} onToggle={event => setExpanded(event.currentTarget.open)} className="rounded-lg border border-gray-200 bg-white p-3 text-xs">
      <summary className="cursor-pointer font-medium text-gray-800">批量审核{title} · 待审核 {items.length} 条</summary>
      {expanded && <>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button type="button" onClick={() => setSelected(new Set(items.map(item => item.id)))} disabled={running} className="rounded border px-2 py-1">全选当前列表</button>
        <button type="button" onClick={() => setSelected(new Set())} disabled={running} className="rounded border px-2 py-1">清空选择</button>
        <span className="text-gray-500">已选 {selected.size} 条</span>
        <button type="button" onClick={() => void review(true)} disabled={running || !selected.size} className="rounded bg-green-600 px-2 py-1 font-medium text-white disabled:opacity-50">批量通过</button>
        {allowReject && <button type="button" onClick={() => void review(false)} disabled={running || !selected.size} className="rounded bg-red-600 px-2 py-1 font-medium text-white disabled:opacity-50">批量驳回</button>}
      </div>
      {rejectReasonRequired && <input aria-label="批量驳回原因" value={reason} onChange={event => setReason(event.target.value)} disabled={running}
        placeholder="批量驳回原因（必填）" className="mt-2 w-full rounded border border-gray-200 p-2" />}
      <div className="mt-2 max-h-40 overflow-y-auto rounded border border-gray-100 p-2">
        {items.map(item => <label key={item.id} className="flex cursor-pointer items-center gap-2 py-1 text-gray-700">
          <input type="checkbox" checked={selected.has(item.id)} disabled={running} onChange={() => setSelected(previous => {
            const next = new Set(previous); if (next.has(item.id)) next.delete(item.id); else next.add(item.id); return next;
          })} />
          <span className="truncate" title={item.name}>{item.name}</span>
        </label>)}
      </div>
      </>}
    </details>
  );
}
