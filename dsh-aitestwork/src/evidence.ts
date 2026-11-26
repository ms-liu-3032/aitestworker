import { createHash } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { dirname, extname, join } from 'node:path'
import type { Binding } from './client.js'
import { callUserApi, projectPath } from './client.js'

type Evidence = { id: number; kind: string; fileName: string; sizeBytes: number; sha256: string }

function safePath(item: Evidence): string {
  if (!Number.isSafeInteger(item.id) || item.id < 1) throw new Error('证据 ID 无效')
  if (item.kind === 'FRAME') {
    if (!/^\d{6}\.jpg$/.test(item.fileName)) throw new Error('录屏帧文件名无效')
    return `frames/${item.fileName}`
  }
  if (item.kind === 'MANIFEST') return `manifest-${item.id}.json`
  if (item.kind === 'TRACE') return `trace-${item.id}.zip`
  if (item.kind === 'VIDEO') return `video-${item.id}.webm`
  if (item.kind === 'SCREENSHOT') {
    const extension = extname(item.fileName).toLowerCase()
    if (!['.png', '.jpg', '.jpeg'].includes(extension)) throw new Error('截图扩展名无效')
    return `screenshots/${item.id}${extension}`
  }
  throw new Error('未知证据类型')
}

function viewer(paths: string[]): string {
  const frames = JSON.stringify(paths).replaceAll('<', '\\u003c')
  return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>AITestWork 采集证据</title>
<style>body{font:16px system-ui;margin:2rem;max-width:70rem}img{display:block;max-width:100%;max-height:75vh;border:1px solid #ddd;margin:1rem 0}button,input{font:inherit}input{width:70%}</style>
<h1>AITestWork 录屏帧</h1><p id="count"></p><img id="frame" alt="录屏帧"><button id="play">播放 / 暂停</button> <input id="seek" type="range" min="0" value="0"><p>原始文件及校验值见同目录 index.json。</p>
<script>const frames=${frames};const image=document.getElementById('frame');const seek=document.getElementById('seek');const count=document.getElementById('count');let timer;
seek.max=Math.max(0,frames.length-1);function show(i){if(!frames.length){count.textContent='没有可回放的录屏帧';image.hidden=true;return}seek.value=String(i);image.src=frames[i];count.textContent=(i+1)+' / '+frames.length}show(0);
seek.oninput=()=>show(Number(seek.value));document.getElementById('play').onclick=()=>{if(timer){clearInterval(timer);timer=undefined;return}if(!frames.length)return;timer=setInterval(()=>show((Number(seek.value)+1)%frames.length),200)};</script></html>`
}

export async function downloadCaptureEvidence(binding: Binding, projectId: number, sessionId: number,
                                              root = join(homedir(), '.dsh', 'aitestwork-evidence')) {
  if (!Number.isSafeInteger(projectId) || projectId < 1 || !Number.isSafeInteger(sessionId) || sessionId < 1) {
    throw new Error('项目 ID 和会话 ID 必须是正整数')
  }
  const base = `/api/aitestwork/dsh/user/projects/${projectId}/captures/${sessionId}`
  const items = await callUserApi<Evidence[]>(binding, `${base}/artifacts`)
  const dir = join(root, String(projectId), String(sessionId))
  await mkdir(dir, { recursive: true, mode: 0o700 })
  const downloaded: Array<Evidence & { path: string }> = []
  for (const item of items) {
    const relative = safePath(item)
    if (!Number.isSafeInteger(item.sizeBytes) || item.sizeBytes < 1 || item.sizeBytes > 50 * 1024 * 1024
        || !/^[a-f0-9]{64}$/i.test(item.sha256)) throw new Error('证据元数据无效')
    const response = await fetch(`${binding.serverUrl}${base}/artifacts/${item.id}`, {
      headers: { Authorization: `Bearer ${binding.userToken}` },
      signal: AbortSignal.timeout(120_000),
    })
    if (!response.ok) throw new Error(`证据 ${item.id} 下载失败（HTTP ${response.status}）`)
    const bytes = Buffer.from(await response.arrayBuffer())
    if (bytes.length !== item.sizeBytes || createHash('sha256').update(bytes).digest('hex') !== item.sha256.toLowerCase()) {
      throw new Error(`证据 ${item.id} 校验失败`)
    }
    const path = join(dir, relative)
    await mkdir(dirname(path), { recursive: true, mode: 0o700 })
    await writeFile(path, bytes, { mode: 0o600 })
    downloaded.push({ ...item, path: relative })
  }
  const frames = downloaded.filter(item => item.kind === 'FRAME').map(item => item.path).sort()
  await writeFile(join(dir, 'index.json'), JSON.stringify({ projectId, sessionId, evidence: downloaded }, null, 2), { mode: 0o600 })
  await writeFile(join(dir, 'viewer.html'), viewer(frames), { mode: 0o600 })
  return { directory: dir, viewer: join(dir, 'viewer.html'), count: downloaded.length, frames: frames.length }
}
