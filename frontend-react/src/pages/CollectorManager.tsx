import { useState, useEffect } from 'react';
import { useApp } from '../context/AppContext';
import RichList from '../components/RichList';
import StatusBadge from '../components/StatusBadge';
import Drawer from '../components/Drawer';
import {
  listWorkerDevices, revokeWorkerDevice, createBindCode,
  type WorkerDevice, type BindCodeResponse
} from '../services/api';

function copyText(text: string): Promise<boolean> {
  if (!text) return Promise.resolve(false);
  if (navigator.clipboard?.writeText) {
    return navigator.clipboard.writeText(text).then(() => true).catch(() => false);
  }
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', 'true');
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.select();
  let copied = false;
  try { copied = document.execCommand('copy'); } catch { /* ignore */ }
  document.body.removeChild(textarea);
  return Promise.resolve(copied);
}

export default function CollectorManager() {
  const { showToast } = useApp();
  const [devices, setDevices] = useState<WorkerDevice[]>([]);
  const [loading, setLoading] = useState(true);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [bindCode, setBindCode] = useState<BindCodeResponse | null>(null);
  const [bindCodeLoading, setBindCodeLoading] = useState(false);
  const [bindCopied, setBindCopied] = useState(false);
  const [commandCopied, setCommandCopied] = useState(false);

  useEffect(() => {
    loadDevices();
  }, []);

  const loadDevices = () => {
    setLoading(true);
    listWorkerDevices()
      .then(setDevices)
      .catch(() => showToast('加载采集器列表失败'))
      .finally(() => setLoading(false));
  };

  const handleOpenDrawer = () => {
    setDrawerOpen(true);
    setBindCode(null);
    setBindCopied(false);
    setCommandCopied(false);
  };

  const handleGenerateBindCode = () => {
    setBindCodeLoading(true);
    createBindCode()
      .then(resp => {
        setBindCode(resp);
        setBindCopied(false);
        setCommandCopied(false);
      })
      .catch(() => showToast('生成绑定码失败'))
      .finally(() => setBindCodeLoading(false));
  };

  const handleRevoke = (id: number) => {
    revokeWorkerDevice(id)
      .then(() => {
        setDevices(prev => prev.filter(d => d.id !== id));
        showToast('采集器已解除绑定');
      })
      .catch(() => showToast('操作失败'));
  };

  const handleCopy = async (text: string, kind: 'code' | 'command') => {
    const copied = await copyText(text);
    if (!copied) {
      showToast('复制失败，请手动复制', 'error');
      return;
    }
    if (kind === 'code') {
      setBindCopied(true);
    } else {
      setCommandCopied(true);
    }
    showToast('已复制');
  };

  const bindCommand = bindCode ? `./scripts/local-worker.sh ${bindCode.code}` : '';
  const onlineCount = devices.filter(dev => dev.bindStatus === 'BOUND').length;

  const columns = [
    { key: 'name', label: '设备名称', width: '200px' },
    { key: 'platform', label: '平台', width: '120px' },
    { key: 'status', label: '状态', width: '100px' },
    { key: 'version', label: 'Worker 版本', width: '120px' },
    { key: 'heartbeat', label: '最近心跳', width: '180px' },
    { key: 'actions', label: '操作', width: '100px' },
  ];

  return (
    <div className="mx-auto max-w-[1000px] px-4 py-8 animate-fade-in sm:px-6">
      {/* Header */}
      <div className="mb-6 flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold text-gray-900 mb-1">采集器管理</h1>
          <p className="text-sm text-gray-500">管理已绑定的 Worker 设备，生成绑定码后可直接复制命令到本地终端执行。</p>
        </div>
        <div className="flex flex-wrap items-center gap-3 lg:justify-end">
          <div className="rounded-full border border-gray-200 bg-white px-3 py-1.5 text-xs text-gray-500">
            在线 {onlineCount} / 总计 {devices.length}
          </div>
          <button
            onClick={loadDevices}
            className="min-h-10 shrink-0 rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-gray-300 hover:bg-white"
          >
            刷新列表
          </button>
          <button
            onClick={handleOpenDrawer}
            className="min-h-10 shrink-0 rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-slate-800"
          >
            添加采集器
          </button>
        </div>
      </div>

      {/* List */}
      <RichList
        items={devices}
        columns={columns}
        loading={loading}
        renderRow={(dev: WorkerDevice) => (
          <>
            <div style={{ width: '200px' }}>
              <div className="text-sm font-medium text-gray-900">{dev.deviceName}</div>
              <div className="text-xs font-mono text-gray-500">{dev.arch}</div>
            </div>
            <div className="text-sm text-gray-600" style={{ width: '120px' }}>{dev.platform}</div>
            <div style={{ width: '100px' }}>
              <StatusBadge
                status={dev.bindStatus === 'BOUND' ? 'online' : 'offline'}
                label={dev.bindStatus === 'BOUND' ? '在线' : '离线'}
              />
            </div>
            <div className="text-xs text-gray-500 font-mono" style={{ width: '120px' }}>{dev.workerVersion}</div>
            <div className="text-xs text-gray-500 font-mono" style={{ width: '180px' }}>
              {dev.lastSeenAt ? new Date(dev.lastSeenAt).toLocaleString('zh-CN') : '-'}
            </div>
            <div className="flex items-center gap-2 opacity-40 hover:opacity-100 transition-opacity" style={{ width: '100px', flex: 1 }}>
              <button
                onClick={() => handleRevoke(dev.id)}
                className="text-xs text-red-500 hover:text-red-700 transition-colors"
              >
                解除绑定
              </button>
            </div>
          </>
        )}
      />

      {devices.length === 0 && !loading && (
        <div className="text-center py-16 text-gray-400 text-sm">
          暂无已绑定的采集器设备
        </div>
      )}

      {/* Add Drawer */}
      <Drawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        title="添加采集器"
      >
        <div className="space-y-6">
          <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
            <h3 className="text-sm font-semibold text-blue-900 mb-2">绑定流程</h3>
            <ol className="text-xs text-blue-700 space-y-1 list-decimal list-inside">
              <li>点击下方按钮生成绑定码</li>
              <li>复制绑定命令，到项目根目录终端执行</li>
              <li>保持该终端窗口开启，采集器会持续在线</li>
            </ol>
          </div>

          {!bindCode ? (
            <button
              onClick={handleGenerateBindCode}
              disabled={bindCodeLoading}
              className="w-full bg-slate-900 text-white px-4 py-3 rounded-lg text-sm font-semibold hover:bg-slate-800 transition-colors disabled:opacity-50"
            >
              {bindCodeLoading ? '生成中...' : '生成绑定码'}
            </button>
          ) : (
            <div className="space-y-4">
              <div>
                <label className="block text-[11px] uppercase tracking-wider text-gray-400 font-semibold mb-2">绑定码</label>
                <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
                  <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <span className="break-all text-2xl font-mono font-bold tracking-wider text-gray-900">{bindCode.code}</span>
                    <button
                      onClick={() => handleCopy(bindCode.code, 'code')}
                      className="shrink-0 rounded-lg border border-gray-200 px-3 py-1.5 text-xs font-medium text-gray-600 hover:border-gray-900 hover:text-gray-900 transition-colors"
                    >
                      {bindCopied ? '已复制' : '复制绑定码'}
                    </button>
                  </div>
                </div>
              </div>
              <div>
                <label className="block text-[11px] uppercase tracking-wider text-gray-400 font-semibold mb-2">推荐命令</label>
                <div className="rounded-lg border border-gray-200 bg-gray-950 px-4 py-3">
                  <code className="block break-all text-xs text-green-300">{bindCommand}</code>
                </div>
                <div className="mt-2 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                  <p className="text-xs text-gray-500">
                    在项目根目录另开终端执行这条命令，绑定成功后会自动启动本地采集器。
                  </p>
                  <button
                    onClick={() => handleCopy(bindCommand, 'command')}
                    className="shrink-0 rounded-lg border border-gray-200 px-3 py-1.5 text-xs font-medium text-gray-600 hover:border-gray-900 hover:text-gray-900 transition-colors"
                  >
                    {commandCopied ? '已复制' : '复制命令'}
                  </button>
                </div>
              </div>
              <div className="text-xs text-gray-500 text-center">
                有效期至：{new Date(bindCode.expiresAt).toLocaleString('zh-CN')}
              </div>
              <button
                onClick={handleGenerateBindCode}
                disabled={bindCodeLoading}
                className="w-full text-sm text-gray-500 hover:text-gray-700 transition-colors"
              >
                重新生成
              </button>
            </div>
          )}
        </div>
      </Drawer>
    </div>
  );
}
