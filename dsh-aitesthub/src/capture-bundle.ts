import { createHash, randomUUID } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { copyFile, link, mkdir, mkdtemp, open, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { basename, join } from 'node:path'
import type { StopCaptureResponse } from 'local-browser-worker'
import type { Binding } from './client.js'
import { callApi, projectPath } from './client.js'

const run = promisify(execFile)
const CHUNK_BYTES = 4 * 1024 * 1024
type Summary = { artifactCount: number; sourceBytes: number; eventCount: number;
  networkCount: number; byKind: Record<string, number> }
type Bundle = { requestKey: string; path: string; sizeBytes: number; sha256: string;
  chunkCount: number; nextChunk: number; summary: Summary }

async function digestFile(path: string): Promise<string> {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(path)) hash.update(chunk)
  return hash.digest('hex')
}

async function putFile(source: string, dest: string): Promise<number> {
  await mkdir(join(dest, '..'), { recursive: true, mode: 0o700 })
  try { await link(source, dest) } catch { await copyFile(source, dest) }
  return (await stat(source)).size
}

export async function prepareCaptureBundle(result: StopCaptureResponse, root: string, sessionId: number): Promise<Bundle> {
  await mkdir(root, { recursive: true, mode: 0o700 })
  const metaPath = join(root, `bundle-${sessionId}.json`)
  try {
    const prior = JSON.parse(await readFile(metaPath, 'utf8')) as Bundle
    if ((await stat(prior.path)).size === prior.sizeBytes && await digestFile(prior.path) === prior.sha256) return prior
  } catch { /* build once when no valid archive exists */ }
  const temp = await mkdtemp(join(root, `bundle-build-${sessionId}-`))
  const archive = join(root, `bundle-${sessionId}.tar.gz`)
  const summary: Summary = { artifactCount: 0, sourceBytes: 0, eventCount: result.events.length,
    networkCount: result.networks.length, byKind: {} }
  const seen = new Set<string>()
  const add = async (kind: string, source: string | null | undefined, relative: string) => {
    if (!source || seen.has(source)) return
    seen.add(source)
    summary.sourceBytes += await putFile(source, join(temp, relative))
    summary.artifactCount++
    summary.byKind[kind] = (summary.byKind[kind] || 0) + 1
  }
  try {
    for (const [index, event] of result.events.entries()) if (event.screenshotPath) {
      await add('SCREENSHOT', event.screenshotPath, `screenshots/${String(index).padStart(6, '0')}-${basename(event.screenshotPath)}`)
    }
    await add('SCREENSHOT', result.videoPath, 'screenshots/final.png')
    await add('TRACE', result.traceFilePath, 'trace/trace.zip')
    if (result.screencastPath) {
      await add('MANIFEST', result.screencastPath, 'screencast/manifest.json')
      const manifest = JSON.parse(await readFile(result.screencastPath, 'utf8')) as { frames?: { filename: string }[] }
      for (const frame of manifest.frames || []) {
        if (!/^\d{6}\.jpg$/.test(frame.filename)) throw new Error('录屏帧文件名无效')
        await add('FRAME', join(result.screencastPath, '..', 'frames', frame.filename), `screencast/frames/${frame.filename}`)
      }
    }
    if (!summary.artifactCount || !summary.sourceBytes) throw new Error('没有可打包的原始证据')
    await writeFile(join(temp, 'summary.json'), JSON.stringify({ sessionId, ...summary }, null, 2), { mode: 0o600 })
    await run('tar', ['-czf', archive, '-C', temp, '.'])
    const sizeBytes = (await stat(archive)).size
    const bundle: Bundle = { requestKey: randomUUID(), path: archive, sizeBytes,
      sha256: await digestFile(archive), chunkCount: Math.ceil(sizeBytes / CHUNK_BYTES), nextChunk: 0, summary }
    await writeFile(metaPath, JSON.stringify(bundle), { mode: 0o600 })
    return bundle
  } finally { await rm(temp, { recursive: true, force: true }) }
}

export async function uploadCaptureBundle(binding: Binding, projectId: number, sessionId: number,
                                          result: StopCaptureResponse, root: string) {
  const bundle = await prepareCaptureBundle(result, root, sessionId)
  const base = `${projectPath(projectId)}/captures/${sessionId}/bundle`
  const file = await open(bundle.path, 'r')
  try {
    for (let index = bundle.nextChunk; index < bundle.chunkCount; index++) {
      const bytes = Buffer.alloc(Math.min(CHUNK_BYTES, bundle.sizeBytes - index * CHUNK_BYTES))
      let offset = 0
      while (offset < bytes.length) {
        const { bytesRead } = await file.read(bytes, offset, bytes.length - offset, index * CHUNK_BYTES + offset)
        if (!bytesRead) throw new Error('本地证据压缩包读取不完整')
        offset += bytesRead
      }
      const response = await fetch(`${binding.serverUrl}${base}/chunks/${bundle.requestKey}/${index}`, {
        method: 'POST', headers: { Authorization: `Bearer ${binding.agentToken}`,
          'Content-Type': 'application/octet-stream' }, body: bytes, signal: AbortSignal.timeout(120_000),
      })
      let message = ''
      try { const payload = await response.json() as { success: boolean; message?: string }; message = payload.message || ''
        if (!payload.success) throw new Error(message || '证据分段上传失败') }
      catch (error) { throw new Error(response.status === 413 ? '证据分段上传被网关拒绝（HTTP 413）'
        : `证据分段上传失败（HTTP ${response.status}）：${message || String(error)}`) }
      if (!response.ok) throw new Error(`证据分段上传失败（HTTP ${response.status}）：${message}`)
      bundle.nextChunk = index + 1
      await writeFile(join(root, `bundle-${sessionId}.json`), JSON.stringify(bundle), { mode: 0o600 })
    }
  } finally { await file.close() }
  const saved = await callApi<{ id: number; sha256: string; sizeBytes: number }>(binding, `${base}/commit`, 'POST', { requestKey: bundle.requestKey,
    chunkCount: bundle.chunkCount, sizeBytes: bundle.sizeBytes, sha256: bundle.sha256, summary: bundle.summary })
  return { id: saved.id, sha256: saved.sha256, sizeBytes: saved.sizeBytes, summary: bundle.summary }
}

export async function clearCaptureBundle(root: string, sessionId: number) {
  await rm(join(root, `bundle-${sessionId}.json`), { force: true })
  await rm(join(root, `bundle-${sessionId}.tar.gz`), { force: true })
}
