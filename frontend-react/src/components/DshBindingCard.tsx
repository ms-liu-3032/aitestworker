import { useEffect, useState } from 'react';
import { api } from '../services/api';

interface BindCode { code: string; expiresAt: string }
interface Connection { id: number; deviceName: string; status: string; lastUsedAt: string | null }

function serviceOrigin(value: string): string | null {
  try {
    const url = new URL(value.trim());
    if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
    if (url.username || url.password || url.search || url.hash || url.pathname !== '/') return null;
    return url.origin;
  } catch {
    return null;
  }
}

export default function DshBindingCard() {
  const [code, setCode] = useState<BindCode | null>(null);
  const [connections, setConnections] = useState<Connection[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [copyMessage, setCopyMessage] = useState('');
  const [backendUrl, setBackendUrl] = useState(() => window.location.origin);
  const normalizedBackendUrl = serviceOrigin(backendUrl);
  const bindCommand = code && normalizedBackendUrl ? `/aitest-bind ${normalizedBackendUrl} ${code.code}` : null;

  async function copyCommand() {
    if (!bindCommand) return;
    try {
      if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable');
      await navigator.clipboard.writeText(bindCommand);
    } catch {
      const field = document.createElement('textarea');
      field.value = bindCommand;
      field.style.position = 'fixed';
      field.style.opacity = '0';
      document.body.appendChild(field);
      field.select();
      let copied = false;
      try {
        copied = document.execCommand?.('copy') ?? false;
      } catch {
        copied = false;
      } finally {
        field.remove();
      }
      if (!copied) {
        setCopyMessage('复制失败，请手动复制下方完整命令。');
        return;
      }
    }
    setCopyMessage('完整绑定命令已复制。');
  }

  async function refresh() {
    try {
      setConnections(await api<Connection[]>('/api/dsh/connections'));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '读取 DSH 连接失败');
    }
  }

  useEffect(() => { void refresh(); }, []);

  async function createCode() {
    setLoading(true);
    setError('');
    setCopyMessage('');
    try {
      setCode(await api<BindCode>('/api/dsh/bind-codes', { method: 'POST' }));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '生成绑定码失败');
    } finally {
      setLoading(false);
    }
  }

  async function revoke(connectionId: number) {
    setError('');
    try {
      await api(`/api/dsh/connections/${connectionId}/revoke`, { method: 'POST' });
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '撤销连接失败');
    }
  }

  return (
    <section className="mt-6 rounded-xl border border-gray-200 bg-white p-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-gray-900">本机 DSH 连接</h2>
          <p className="mt-1 text-sm text-gray-500">登录后生成一次性绑定码，在本机 DSH 中绑定测试资产服务。绑定码 10 分钟有效，只能使用一次。</p>
        </div>
        <button onClick={() => void createCode()} disabled={loading} className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-60">
          {loading ? '生成中…' : '生成绑定码'}
        </button>
      </div>
      {code && (
        <div className="mt-4 rounded-lg border border-blue-200 bg-blue-50 p-4">
          <div className="font-mono text-xl font-semibold tracking-widest text-slate-900">{code.code}</div>
          <p className="mt-2 text-xs text-gray-600">有效期至 {code.expiresAt}</p>
          <label htmlFor="dsh-backend-url" className="mt-3 block text-sm font-medium text-slate-800">AITestHub 后端服务地址</label>
          <input id="dsh-backend-url" type="url" value={backendUrl} onChange={event => { setBackendUrl(event.target.value); setCopyMessage(''); }}
            className="mt-1 w-full rounded border border-blue-200 bg-white px-3 py-2 text-sm text-slate-900"
            placeholder="https://aitest.example.com" />
          <p className="mt-1 text-xs text-gray-600">默认使用当前 Web 地址；如果 API 在独立域名，填写可访问 /api/dsh/bind 的服务根地址。</p>
          {normalizedBackendUrl?.startsWith('http://') && <p role="status" className="mt-2 text-xs text-amber-800">当前使用 HTTP，绑定码和连接凭据会在网络中明文传输，请仅在可信网络使用。</p>}
          {bindCommand
            ? <div className="mt-3 space-y-2">
                <div className="break-all rounded bg-white p-3 font-mono text-xs text-slate-800">{bindCommand}</div>
                <button type="button" onClick={() => void copyCommand()} className="rounded border border-blue-300 bg-white px-3 py-1.5 text-sm text-blue-900">复制完整绑定命令</button>
                {copyMessage && <p role="status" className="text-xs text-slate-700">{copyMessage}</p>}
                <p className="text-xs text-gray-600">绑定码共 12 位；请在 10 分钟内粘贴到本机 DSH。若空白新会话执行指令后没有显示结果，输入 DSH 内置指令 /goal 即可查看之前的结果。</p>
              </div>
            : <p role="alert" className="mt-2 text-xs text-red-700">请输入带 HTTP 或 HTTPS 协议的服务根地址。</p>}
        </div>
      )}
      {error && <p role="alert" className="mt-3 text-sm text-red-700">{error}</p>}
      <div className="mt-4 space-y-2">
        {connections.map(connection => (
          <div key={connection.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-gray-200 px-3 py-2 text-sm">
            <span>{connection.deviceName} · {connection.status} · 最近使用 {connection.lastUsedAt || '尚未使用'}</span>
            {connection.status === 'ACTIVE' && <button onClick={() => void revoke(connection.id)} className="text-red-700 hover:underline">撤销连接</button>}
          </div>
        ))}
      </div>
    </section>
  );
}
