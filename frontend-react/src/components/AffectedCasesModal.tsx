import { useState, useEffect } from 'react';
import Modal from './Modal';
import { type RequirementAnalysis } from '../services/api';

interface AffectedCase {
  title: string;
  reason: string;
  confidence: number;
}

interface AffectedCasesModalProps {
  open: boolean;
  analysis: RequirementAnalysis | null;
  onClose: () => void;
  onConfirm: (selectedTitles: string[]) => void;
  generating?: boolean;
}

function parseAffectedCases(raw: string | null): AffectedCase[] {
  if (!raw) return [];
  try {
    const arr = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (Array.isArray(arr)) return arr;
  } catch {}
  return [];
}

export default function AffectedCasesModal({ open, analysis, onClose, onConfirm, generating }: AffectedCasesModalProps) {
  const cases = parseAffectedCases(analysis?.affectedCases ?? null);
  const changeScope = analysis?.changeScope || 'MINOR';
  const [selected, setSelected] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (open && cases.length > 0) {
      setSelected(new Set(cases.filter(c => c.confidence >= 0.5).map(c => c.title)));
    }
  }, [open, analysis?.id, cases.length]);

  const toggle = (title: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(title)) next.delete(title); else next.add(title);
      return next;
    });
  };

  const toggleAll = () => {
    if (selected.size === cases.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(cases.map(c => c.title)));
    }
  };

  if (!open) return null;

  return (
    <Modal onClose={onClose} title="分析完成 — 请确认受影响的用例"
      footer={
        <>
          <button onClick={onClose} disabled={generating} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 disabled:opacity-50">取消</button>
          <button
            onClick={() => onConfirm([...selected])}
            disabled={generating || selected.size === 0}
            className="px-4 py-2 bg-purple-600 text-white text-sm font-semibold rounded-lg hover:bg-purple-700 disabled:opacity-50"
          >
            {generating ? '生成中...' : `确认更新 ${selected.size} 个用例`}
          </button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="flex items-center gap-3 text-sm">
          <span className="text-gray-600">变更摘要：基于分析结果更新</span>
          <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${
            changeScope === 'MINOR' ? 'bg-blue-50 text-blue-700' : 'bg-amber-50 text-amber-700'
          }`}>{changeScope === 'MINOR' ? '小幅变更' : '重大变更'}</span>
        </div>

        <div className="border border-gray-200 rounded-lg divide-y divide-gray-100">
          {cases.length === 0 ? (
            <div className="px-4 py-6 text-center text-sm text-gray-400">
              无受影响的用例，无需更新
            </div>
          ) : (
            <>
              <div className="px-4 py-2 flex items-center gap-3 bg-gray-50">
                <input type="checkbox" checked={selected.size === cases.length && cases.length > 0}
                  onChange={toggleAll} className="h-3.5 w-3.5 rounded border-gray-300 accent-purple-600" />
                <span className="text-xs text-gray-500">全选 ({selected.size}/{cases.length})</span>
              </div>
              {cases.map(c => (
                <label key={c.title} className="px-4 py-3 flex items-start gap-3 cursor-pointer hover:bg-gray-50 transition-colors">
                  <input type="checkbox" checked={selected.has(c.title)} onChange={() => toggle(c.title)}
                    className="mt-0.5 h-3.5 w-3.5 rounded border-gray-300 accent-purple-600" />
                  <div className="min-w-0 flex-1">
                    <div className="text-sm font-medium text-gray-900 truncate">{c.title}</div>
                    <div className="text-xs text-gray-500 mt-0.5">{c.reason}</div>
                  </div>
                  <span className="text-[10px] text-gray-400 shrink-0">{Math.round(c.confidence * 100)}%</span>
                </label>
              ))}
            </>
          )}
        </div>
      </div>
    </Modal>
  );
}
