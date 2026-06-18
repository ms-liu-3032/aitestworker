import { useEffect, useMemo, useRef, useState } from 'react';
import { getToken } from '../../services/api';

interface ScreencastFrame {
  index: number;
  relativeMs: number;
  filename: string;
}

interface ScreencastManifest {
  sessionId: number;
  format: string;
  durationMs: number | null;
  frameCount: number;
  frames: ScreencastFrame[];
}

interface ScreencastPlayerProps {
  sessionId: number;
  clipStartMs?: number | null;
  clipEndMs?: number | null;
  autoplay?: boolean;
}

const LOCAL_TOKEN_KEY = 'aitest_worker_local_token';

function fmtMs(ms: number): string {
  if (!ms || ms < 0) return '0:00';
  const total = Math.floor(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

function getLocalToken(): string {
  return localStorage.getItem(LOCAL_TOKEN_KEY) || '';
}

export default function ScreencastPlayer({ sessionId, clipStartMs, clipEndMs, autoplay = true }: ScreencastPlayerProps) {
  const [manifest, setManifest] = useState<ScreencastManifest | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [currentMs, setCurrentMs] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [sourceMode, setSourceMode] = useState<'worker' | 'backend'>('backend');
  const rafRef = useRef<number | null>(null);
  const lastTickRef = useRef<number>(0);
  const localToken = getLocalToken();

  const totalDurationMs = useMemo(() => {
    if (!manifest) return 0;
    if (manifest.durationMs && manifest.durationMs > 0) return manifest.durationMs;
    const frames = manifest.frames;
    return frames.length > 0 ? frames[frames.length - 1].relativeMs : 0;
  }, [manifest]);

  const currentFrame = useMemo<ScreencastFrame | null>(() => {
    if (!manifest || manifest.frames.length === 0) return null;
    const frames = manifest.frames;
    let lo = 0;
    let hi = frames.length - 1;
    let best = 0;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (frames[mid].relativeMs <= currentMs) {
        best = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return frames[best];
  }, [manifest, currentMs]);

  const frameUrl = (filename: string): string => {
    if (sourceMode === 'worker') {
      const localToken = encodeURIComponent(getLocalToken());
      return `http://127.0.0.1:17321/sessions/${sessionId}/screencast/frame/${filename}?localToken=${localToken}`;
    }
    return `/api/trace/sessions/${sessionId}/screencast/frame/${filename}`;
  };

  const pause = () => {
    setPlaying(false);
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    }
    lastTickRef.current = 0;
  };

  const tick = (ts: number) => {
    if (!playing) return;
    if (lastTickRef.current === 0) lastTickRef.current = ts;
    const delta = ts - lastTickRef.current;
    lastTickRef.current = ts;
    const nextMs = Math.min(currentMs + delta, totalDurationMs);
    setCurrentMs(nextMs);
    const end = clipEndMs ?? totalDurationMs;
    if (nextMs >= end) {
      pause();
      return;
    }
    rafRef.current = requestAnimationFrame(tick);
  };

  const play = () => {
    if (!manifest || manifest.frameCount === 0) return;
    setPlaying(true);
    lastTickRef.current = 0;
    rafRef.current = requestAnimationFrame(tick);
  };

  const toggle = () => {
    if (playing) pause();
    else play();
  };

  const handleSeek = (v: number) => {
    setCurrentMs(Math.max(0, Math.min(v, totalDurationMs)));
  };

  const loadManifestFromBestSource = async (): Promise<ScreencastManifest> => {
    const workerToken = getLocalToken();
    const workerHeaders = workerToken ? { 'X-Local-Token': workerToken } : undefined;
    let workerError = '';
    if (workerHeaders) {
      try {
        const res = await fetch(`http://127.0.0.1:17321/sessions/${sessionId}/screencast/manifest`, { headers: workerHeaders });
        if (res.ok) {
          setSourceMode('worker');
          return (await res.json()) as ScreencastManifest;
        }
        workerError = await res.text().catch(() => '');
      } catch (e) {
        workerError = (e as Error)?.message || String(e);
      }
    }

    const token = getToken();
    const res = await fetch(`/api/trace/sessions/${sessionId}/screencast/manifest`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!res.ok) {
      const backendError = await res.text().catch(() => '');
      const detail = workerError
        ? `本地采集器与后端都未能提供录屏：本地=${workerError}；后端=${backendError || `HTTP ${res.status}`}`
        : (backendError || `HTTP ${res.status}`);
      throw new Error(detail);
    }
    setSourceMode('backend');
    return (await res.json()) as ScreencastManifest;
  };

  const loadManifest = async () => {
    setLoading(true);
    setLoadError(null);
    setManifest(null);
    try {
      const data = await loadManifestFromBestSource();
      setManifest(data);
      const initialMs = clipStartMs != null && clipStartMs >= 0
        ? Math.min(clipStartMs, data.durationMs || data.frames[data.frames.length - 1]?.relativeMs || 0)
        : 0;
      setCurrentMs(initialMs);
      if (autoplay !== false) {
        // Use a small timeout to allow state to settle before playing
        setTimeout(() => play(), 0);
      }
    } catch (e) {
      setLoadError('加载录屏失败：' + ((e as Error)?.message || String(e)));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadManifest();
    return () => pause();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  useEffect(() => {
    if (clipStartMs != null && clipStartMs >= 0 && manifest) {
      setCurrentMs(Math.min(clipStartMs, totalDurationMs));
      if (autoplay !== false) play();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clipStartMs]);

  if (loadError) {
    return (
      <div className="rounded-lg bg-amber-50 border border-amber-200 p-4 text-xs text-amber-800">
        <div className="font-medium">加载录屏失败</div>
        <div className="mt-1 break-words text-amber-700">{loadError.replace(/^加载录屏失败：/, '')}</div>
        <div className="mt-3 space-y-1 text-[11px] text-amber-700">
          {!localToken && <div>· 当前没有本地令牌，页面会优先回退到后端录屏接口。</div>}
          <div>· 如果这条会话刚停止，先点一次重试，等待录屏索引和帧文件写入完成。</div>
          <div>· 如果是远端后端环境，请确认录屏文件已上传或本地采集器仍可访问。</div>
        </div>
        <button
          onClick={loadManifest}
          className="mt-3 px-3 py-1.5 bg-white border border-amber-200 rounded text-[11px] font-medium text-amber-800 hover:border-amber-300 transition-colors"
        >
          重试加载录屏
        </button>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="rounded-lg bg-gray-50 p-4 text-center text-xs text-gray-500">
        加载录屏帧序列…
      </div>
    );
  }

  if (manifest && manifest.frameCount === 0) {
    return (
      <div className="rounded-lg bg-gray-50 p-4 text-xs text-gray-500">
        <div className="font-medium text-gray-700">本次会话未采集到主录屏帧</div>
        <div className="mt-1">可能是 session 时长过短，或 CDP screencast 在启动阶段失败。</div>
        <div className="mt-2 space-y-1 text-[11px] text-gray-500">
          <div>· 如果只是短暂点开又关闭浏览器，通常不会留下完整录屏。</div>
          <div>· 建议重新录一条稍长一点的操作，再回到这里查看录屏。</div>
        </div>
      </div>
    );
  }

  if (!manifest || !currentFrame) {
    return (
      <div className="rounded-lg bg-gray-50 p-4 text-xs text-gray-500">
        <div className="font-medium text-gray-700">暂无录屏数据</div>
        <div className="mt-1">当前会话还没有可播放的录屏帧。</div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2 mt-2 rounded-lg bg-gray-50 p-3">
      <img
        src={frameUrl(currentFrame.filename)}
        alt={`session ${sessionId} frame ${currentFrame.index}`}
        className="max-w-full rounded-md bg-black object-contain"
        loading="lazy"
      />
      <div className="flex items-center gap-3">
        <button
          onClick={toggle}
          className="text-xs px-2 py-1 rounded border border-gray-200 bg-white hover:border-gray-400 transition-colors"
        >
          {playing ? '暂停' : '播放'}
        </button>
        <input
          type="range"
          min={0}
          max={totalDurationMs}
          step={50}
          value={currentMs}
          onChange={(e) => handleSeek(Number(e.target.value))}
          className="flex-1 h-1 bg-gray-200 rounded-lg appearance-none cursor-pointer accent-slate-900"
        />
        <span className="text-[11px] text-gray-500 whitespace-nowrap">
          {fmtMs(currentMs)} / {fmtMs(totalDurationMs)}
        </span>
      </div>
    </div>
  );
}
