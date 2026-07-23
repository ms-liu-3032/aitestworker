import { useEffect, useMemo, useState } from 'react';
import { useApp } from '../../context/AppContext';
import {
  exportLlmInvocationReport,
  exportSecurityEventReport,
  getLlmInvocationChain,
  getLlmInvocationSnapshot,
  listLlmInvocationLogs,
  listSecurityEventLogs,
  type LlmInvocationChain,
  type LlmInvocationLog,
  type LlmInvocationSnapshot,
  type SecurityEventLog,
} from '../../services/api';
import { displayLabel, errorCodeLabel, statusLabel } from '../../utils/displayLabels';

type ActiveTab = 'llm' | 'security';

const llmStatusColors: Record<string, string> = {
  SUCCESS: 'bg-green-50 text-green-700',
  FAILED: 'bg-red-50 text-red-700',
  ATTEMPT_OK: 'bg-green-50 text-green-700',
  ATTEMPT_RETRY: 'bg-yellow-50 text-yellow-700',
  ATTEMPT_FAILED: 'bg-red-50 text-red-700',
};

const severityColors: Record<string, string> = {
  INFO: 'bg-blue-50 text-blue-700',
  WARN: 'bg-yellow-50 text-yellow-700',
  WARNING: 'bg-yellow-50 text-yellow-700',
  HIGH: 'bg-red-50 text-red-700',
  ERROR: 'bg-red-50 text-red-700',
};

export default function RuntimeDiagnostics() {
  const { showToast } = useApp();
  const [activeTab, setActiveTab] = useState<ActiveTab>('llm');
  const [projectIdInput, setProjectIdInput] = useState('');
  const [taskIdInput, setTaskIdInput] = useState('');
  const [status, setStatus] = useState('');
  const [errorCode, setErrorCode] = useState('');
  const [severity, setSeverity] = useState('');
  const [keyword, setKeyword] = useState('');
  const [limit, setLimit] = useState(50);
  const [llmLogs, setLlmLogs] = useState<LlmInvocationLog[]>([]);
  const [securityEvents, setSecurityEvents] = useState<SecurityEventLog[]>([]);
  const [selectedChain, setSelectedChain] = useState<LlmInvocationChain | null>(null);
  const [chainLoading, setChainLoading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);

  const filters = useMemo(() => {
    const projectId = Number(projectIdInput);
    const taskId = Number(taskIdInput);
    return {
      projectId: Number.isFinite(projectId) && projectId > 0 ? projectId : undefined,
      taskId: Number.isFinite(taskId) && taskId > 0 ? taskId : undefined,
      status: status.trim() || undefined,
      errorCode: errorCode.trim() || undefined,
      severity: severity.trim() || undefined,
      keyword: keyword.trim() || undefined,
      limit,
    };
  }, [projectIdInput, taskIdInput, status, errorCode, severity, keyword, limit]);

  const loadData = async () => {
    setLoading(true);
    try {
      if (activeTab === 'llm') {
        const data = await listLlmInvocationLogs(filters);
        setLlmLogs(data);
      } else {
        const data = await listSecurityEventLogs(filters);
        setSecurityEvents(data);
      }
    } catch (e: any) {
      showToast(e.message || '加载运行诊断日志失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab]);

  const resetFilters = () => {
    setProjectIdInput('');
    setTaskIdInput('');
    setStatus('');
    setErrorCode('');
    setSeverity('');
    setKeyword('');
    setLimit(50);
  };

  const openChain = async (requestId: string) => {
    setChainLoading(true);
    try {
      const chain = await getLlmInvocationChain(requestId);
      setSelectedChain(chain);
    } catch (e: any) {
      showToast(e.message || '加载调用链路失败', 'error');
    } finally {
      setChainLoading(false);
    }
  };

  const exportReport = async () => {
    setExporting(true);
    try {
      const today = new Date().toISOString().slice(0, 10);
      const report = activeTab === 'llm'
        ? await exportLlmInvocationReport(filters)
        : await exportSecurityEventReport(filters);
      downloadText(`${activeTab === 'llm' ? 'llm-diagnostics' : 'security-events'}-${today}.md`, report);
      showToast('运行诊断报告已导出', 'success');
    } catch (e: any) {
      showToast(e.message || '导出运行诊断报告失败', 'error');
    } finally {
      setExporting(false);
    }
  };

  return (
    <div className="p-4 sm:p-6 space-y-4 animate-fade-in">
      <div className="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
        <div>
          <h1 className="text-lg font-bold text-gray-900">运行诊断</h1>
          <p className="text-xs text-gray-500 mt-0.5">查看 LLM 调用和安全事件，输出内容默认截断展示</p>
          {activeTab === 'llm' && (
            <p className="mt-1 text-xs text-blue-700">排查单个异步任务时请填写任务 ID，再查询或导出，避免其他长任务挤出目标调用链。</p>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => void exportReport()}
            disabled={exporting}
            className="h-9 rounded-lg border border-gray-200 bg-white px-3 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-60"
          >
            {exporting ? '导出中' : activeTab === 'llm' ? '导出调用报告' : '导出安全报告'}
          </button>
          <div className="flex rounded-lg border border-gray-200 bg-white p-1 text-sm">
            <button
              onClick={() => setActiveTab('llm')}
              className={`rounded-md px-3 py-1.5 ${activeTab === 'llm' ? 'bg-slate-900 text-white' : 'text-gray-600 hover:bg-gray-50'}`}
            >
              LLM 调用
            </button>
            <button
              onClick={() => setActiveTab('security')}
              className={`rounded-md px-3 py-1.5 ${activeTab === 'security' ? 'bg-slate-900 text-white' : 'text-gray-600 hover:bg-gray-50'}`}
            >
              安全事件
            </button>
          </div>
        </div>
      </div>

      <div className="grid gap-2 rounded-lg border border-gray-200 bg-white p-3 md:grid-cols-2 xl:grid-cols-7">
        <input
          value={projectIdInput}
          onChange={e => setProjectIdInput(e.target.value)}
          placeholder="项目 ID"
          className="h-9 rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm outline-none focus:border-blue-400"
        />
        {activeTab === 'llm' && (
          <input
            value={taskIdInput}
            onChange={e => setTaskIdInput(e.target.value)}
            placeholder="任务 ID"
            className="h-9 rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm outline-none focus:border-blue-400"
          />
        )}
        {activeTab === 'llm' ? (
          <>
            <input
              value={status}
              onChange={e => setStatus(e.target.value)}
              placeholder="状态"
              className="h-9 rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm outline-none focus:border-blue-400"
            />
            <input
              value={errorCode}
              onChange={e => setErrorCode(e.target.value)}
              placeholder="错误码"
              className="h-9 rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm outline-none focus:border-blue-400"
            />
          </>
        ) : (
          <input
            value={severity}
            onChange={e => setSeverity(e.target.value)}
            placeholder="级别"
            className="h-9 rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm outline-none focus:border-blue-400"
          />
        )}
        <input
          value={keyword}
          onChange={e => setKeyword(e.target.value)}
          placeholder="关键字"
          className="h-9 rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm outline-none focus:border-blue-400"
        />
        <select
          value={limit}
          onChange={e => setLimit(Number(e.target.value))}
          className="h-9 rounded-lg border border-gray-200 bg-gray-50 px-3 text-sm outline-none focus:border-blue-400"
        >
          <option value={50}>最近 50 条</option>
          <option value={100}>最近 100 条</option>
          <option value={200}>最近 200 条</option>
          <option value={500}>最近 500 条</option>
          <option value={1000}>最近 1000 条</option>
        </select>
        <div className="flex gap-2">
          <button
            onClick={() => void loadData()}
            disabled={loading}
            className="h-9 flex-1 rounded-lg bg-slate-900 px-3 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-60"
          >
            {loading ? '加载中' : '查询'}
          </button>
          <button
            onClick={resetFilters}
            className="h-9 rounded-lg border border-gray-200 bg-white px-3 text-sm text-gray-600 hover:bg-gray-50"
          >
            重置
          </button>
        </div>
      </div>

      {activeTab === 'llm' ? (
        <LlmLogTable logs={llmLogs} loading={loading} onOpenChain={openChain} chainLoading={chainLoading} />
      ) : (
        <SecurityEventTable events={securityEvents} loading={loading} />
      )}

      {selectedChain && (
        <ChainDrawer chain={selectedChain} onClose={() => setSelectedChain(null)} />
      )}
    </div>
  );
}

function LlmLogTable({
  logs,
  loading,
  onOpenChain,
  chainLoading,
}: {
  logs: LlmInvocationLog[];
  loading: boolean;
  onOpenChain: (requestId: string) => void;
  chainLoading: boolean;
}) {
  if (loading) {
    return <div className="rounded-lg border border-gray-200 bg-white py-10 text-center text-sm text-gray-400">加载中...</div>;
  }
  if (logs.length === 0) {
    return <div className="rounded-lg border border-gray-200 bg-white py-10 text-center text-sm text-gray-400">暂无 LLM 调用日志</div>;
  }
  return (
    <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
      <div className="overflow-x-auto">
        <table className="min-w-[1080px] w-full text-left text-sm">
          <thead className="bg-gray-50 text-xs text-gray-500">
            <tr>
              <th className="px-3 py-2 font-medium">时间</th>
              <th className="px-3 py-2 font-medium">状态</th>
              <th className="px-3 py-2 font-medium">模型</th>
              <th className="px-3 py-2 font-medium">阶段</th>
              <th className="px-3 py-2 font-medium">项目/任务</th>
              <th className="px-3 py-2 font-medium">耗时</th>
              <th className="px-3 py-2 font-medium">错误</th>
              <th className="px-3 py-2 font-medium">输出预览</th>
              <th className="px-3 py-2 font-medium">操作</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {logs.map(log => (
              <tr key={log.id} className="align-top hover:bg-gray-50">
                <td className="px-3 py-2 whitespace-nowrap text-gray-500">{formatTime(log.createdAt)}</td>
                <td className="px-3 py-2">
                  <span className={`rounded px-1.5 py-0.5 text-[11px] ${llmStatusColors[log.status] || 'bg-gray-100 text-gray-600'}`}>
                    {statusLabel(log.status)}
                  </span>
                  {log.retryIndex !== null && <div className="mt-1 text-[11px] text-gray-400">retry #{log.retryIndex}</div>}
                </td>
                <td className="px-3 py-2 min-w-36">
                  <div className="font-medium text-gray-800">{log.modelName || '-'}</div>
                  <div className="text-xs text-gray-400">{log.provider || '-'}</div>
                </td>
                <td className="px-3 py-2 text-gray-600">{displayLabel(log.stage, '-')}</td>
                <td className="px-3 py-2 text-xs text-gray-500">
                  <div>project: {log.projectId ?? '-'}</div>
                  <div>task: {log.taskId ?? '-'}</div>
                  <div className="max-w-40 truncate" title={log.requestId}>req: {log.requestId}</div>
                </td>
                <td className="px-3 py-2 whitespace-nowrap text-gray-600">{log.durationMs ?? '-'} ms</td>
                <td className="px-3 py-2 min-w-48">
                  <div className="text-xs font-medium text-red-600" title={log.errorCode || undefined}>{errorCodeLabel(log.errorCode)}</div>
                  {log.errorMessage && <div className="mt-1 text-xs text-gray-500 line-clamp-3">{log.errorMessage}</div>}
                </td>
                <td className="px-3 py-2 min-w-72">
                  <div className="max-h-24 overflow-auto whitespace-pre-wrap rounded bg-gray-50 p-2 text-xs text-gray-500">
                    {log.rawOutputPreview || '-'}
                  </div>
                </td>
                <td className="px-3 py-2 whitespace-nowrap">
                  <button
                    onClick={() => onOpenChain(log.requestId)}
                    disabled={chainLoading}
                    className="rounded border border-gray-200 bg-white px-2 py-1 text-xs font-medium text-blue-600 hover:bg-blue-50 disabled:opacity-50"
                  >
                    查看链路
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function ChainDrawer({ chain, onClose }: { chain: LlmInvocationChain; onClose: () => void }) {
  const { showToast } = useApp();
  const [snapshot, setSnapshot] = useState<LlmInvocationSnapshot | null>(null);
  const [snapshotLoadingId, setSnapshotLoadingId] = useState<number | null>(null);

  const openSnapshot = async (id: number) => {
    setSnapshotLoadingId(id);
    try {
      const data = await getLlmInvocationSnapshot(id);
      setSnapshot(data);
    } catch (e: any) {
      showToast(e.message || '加载完整快照失败', 'error');
    } finally {
      setSnapshotLoadingId(null);
    }
  };

  return (
    <div className="fixed inset-0 z-40 flex justify-end bg-black/20" role="dialog" aria-modal="true">
      <div className="flex h-full w-full max-w-2xl flex-col bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-gray-200 px-5 py-4">
          <div className="min-w-0">
            <h2 className="text-base font-semibold text-gray-900">LLM 调用链路</h2>
            <p className="mt-1 truncate text-xs text-gray-500">requestId: {chain.rootRequestId}</p>
          </div>
          <button onClick={onClose} className="rounded-lg px-2 py-1 text-sm text-gray-500 hover:bg-gray-100">
            关闭
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-5">
          {chain.entries.length === 0 ? (
            <div className="rounded-lg border border-gray-200 py-10 text-center text-sm text-gray-400">暂无链路日志</div>
          ) : (
            <div className="space-y-3">
              {chain.entries.map((entry, index) => (
                <div key={entry.id} className="rounded-lg border border-gray-200 bg-white p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-600">#{index + 1}</span>
                    <span className={`rounded px-1.5 py-0.5 text-[11px] ${llmStatusColors[entry.status] || 'bg-gray-100 text-gray-600'}`}>
                      {statusLabel(entry.status)}
                    </span>
                    {entry.retryIndex !== null && (
                      <span className="rounded bg-yellow-50 px-1.5 py-0.5 text-[11px] text-yellow-700">retry #{entry.retryIndex}</span>
                    )}
                    <span className="text-xs text-gray-400">{formatTime(entry.createdAt)}</span>
                  </div>

                  <div className="mt-2 grid gap-2 text-xs text-gray-600 sm:grid-cols-2">
                    <div>模型：{entry.provider || '-'} / {entry.modelName || '-'}</div>
                    <div>阶段：{displayLabel(entry.stage, '-')}</div>
                    <div>耗时：{entry.durationMs ?? '-'} ms</div>
                    <div>
                      Token：输入 {entry.tokenInput || 0} / 缓存 {entry.tokenCachedInput || 0} / 输出 {entry.tokenOutput || 0}
                    </div>
                    <div className="sm:col-span-2 break-all">requestId：{entry.requestId}</div>
                  </div>

                  <div className="mt-3 flex justify-end">
                    <button
                      onClick={() => void openSnapshot(entry.id)}
                      disabled={snapshotLoadingId === entry.id}
                      className="rounded border border-gray-200 bg-white px-2 py-1 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                    >
                      {snapshotLoadingId === entry.id ? '加载中' : '查看完整快照'}
                    </button>
                  </div>

                  {(entry.errorCode || entry.errorMessage) && (
                    <div className="mt-3 rounded bg-red-50 p-2 text-xs text-red-700">
                      <div className="font-medium" title={entry.errorCode || undefined}>{errorCodeLabel(entry.errorCode)}</div>
                      {entry.errorMessage && <div className="mt-1 whitespace-pre-wrap">{entry.errorMessage}</div>}
                    </div>
                  )}

                  <div className="mt-3 max-h-48 overflow-auto whitespace-pre-wrap rounded bg-gray-50 p-2 text-xs text-gray-500">
                    {entry.rawOutputPreview || '-'}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {snapshot && (
          <div className="border-t border-gray-200 bg-gray-50 p-4">
            <div className="mb-2 flex items-start justify-between gap-3">
              <div>
                <h3 className="text-sm font-semibold text-gray-900">完整快照</h3>
                <p className="mt-0.5 text-xs text-gray-500">已由后端脱敏，仍请仅用于故障排查</p>
              </div>
              <button onClick={() => setSnapshot(null)} className="rounded px-2 py-1 text-xs text-gray-500 hover:bg-white">
                收起
              </button>
            </div>
            <div className="grid gap-2 text-xs text-gray-600 sm:grid-cols-2">
              <div>日志 ID：{snapshot.id}</div>
              <div>状态：{statusLabel(snapshot.status)}</div>
              <div>模型：{snapshot.provider || '-'} / {snapshot.modelName || '-'}</div>
              <div title={snapshot.errorCode || undefined}>错误类型：{errorCodeLabel(snapshot.errorCode)}</div>
              <div className="sm:col-span-2 break-all">requestId：{snapshot.requestId}</div>
            </div>
            {snapshot.errorMessage && (
              <div className="mt-3 rounded bg-red-50 p-2 text-xs text-red-700 whitespace-pre-wrap">
                {snapshot.errorMessage}
              </div>
            )}
            <div className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap rounded border border-gray-200 bg-white p-3 text-xs text-gray-600">
              {snapshot.rawOutput || '-'}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function SecurityEventTable({ events, loading }: { events: SecurityEventLog[]; loading: boolean }) {
  if (loading) {
    return <div className="rounded-lg border border-gray-200 bg-white py-10 text-center text-sm text-gray-400">加载中...</div>;
  }
  if (events.length === 0) {
    return <div className="rounded-lg border border-gray-200 bg-white py-10 text-center text-sm text-gray-400">暂无安全事件</div>;
  }
  return (
    <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
      <div className="overflow-x-auto">
        <table className="min-w-[880px] w-full text-left text-sm">
          <thead className="bg-gray-50 text-xs text-gray-500">
            <tr>
              <th className="px-3 py-2 font-medium">时间</th>
              <th className="px-3 py-2 font-medium">类型</th>
              <th className="px-3 py-2 font-medium">级别</th>
              <th className="px-3 py-2 font-medium">项目/任务</th>
              <th className="px-3 py-2 font-medium">请求</th>
              <th className="px-3 py-2 font-medium">详情预览</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {events.map(event => (
              <tr key={event.id} className="align-top hover:bg-gray-50">
                <td className="px-3 py-2 whitespace-nowrap text-gray-500">{formatTime(event.createdAt)}</td>
                <td className="px-3 py-2 font-medium text-gray-800">{event.eventType}</td>
                <td className="px-3 py-2">
                  <span className={`rounded px-1.5 py-0.5 text-[11px] ${severityColors[event.severity] || 'bg-gray-100 text-gray-600'}`}>
                    {event.severity}
                  </span>
                </td>
                <td className="px-3 py-2 text-xs text-gray-500">
                  <div>project: {event.projectId ?? '-'}</div>
                  <div>task: {event.taskId ?? '-'}</div>
                </td>
                <td className="px-3 py-2 max-w-48 truncate text-xs text-gray-500" title={event.requestId || ''}>
                  {event.requestId || '-'}
                </td>
                <td className="px-3 py-2 min-w-96">
                  <div className="max-h-28 overflow-auto whitespace-pre-wrap rounded bg-gray-50 p-2 text-xs text-gray-500">
                    {event.detailPreview || '-'}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function formatTime(value: string | null | undefined) {
  if (!value) {
    return '-';
  }
  return value.replace('T', ' ').slice(0, 19);
}

function downloadText(filename: string, content: string) {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
