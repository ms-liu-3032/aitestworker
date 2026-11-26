import { mkdirSync, statSync, writeFileSync, renameSync } from 'node:fs'
import { homedir } from 'node:os'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { spawnSync } from 'node:child_process'

type ReportRecord = { path: string; result: string; kind: string; at: string }
const records: ReportRecord[] = []
let openedAt = 0

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]!)
}

/** Opens one local viewer tab, then updates it for later runs. No test browser is kept alive. */
export function displayReport(run: { reportPath?: string; result?: string; reportError?: string },
    kind: string, root = join(homedir(), '.dsh', 'aitestwork-reports'), open = true):
    { reportViewerPath?: string; reportDisplayError?: string } {
  if (!run.reportPath) return {}
  try {
    const file = resolve(run.reportPath)
    if (!statSync(file, { throwIfNoEntry: false })?.isFile()) throw new Error('本地报告文件不存在')
    mkdirSync(root, { recursive: true, mode: 0o700 })
    records.unshift({ path: file, result: run.result || 'UNKNOWN', kind, at: new Date().toLocaleString('zh-CN') })
    if (records.length > 100) records.length = 100
    const rows = records.map(item => `<li><span class="status ${item.result === 'PASS' ? 'pass' : 'fail'}">${escapeHtml(item.result)}</span><span>${escapeHtml(item.kind)} · ${escapeHtml(item.at)}</span><a href="${escapeHtml(pathToFileURL(item.path).href)}">打开完整报告</a><small>${escapeHtml(item.path)}</small></li>`).join('')
    const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="5"><title>AITestWork 自动化测试报告</title><style>body{font:15px/1.5 system-ui,sans-serif;max-width:980px;margin:32px auto;padding:0 20px;color:#172033;background:#f4f6fb}h1{font-size:26px}p{color:#647187}ol{padding:0;list-style:none}li{background:white;border:1px solid #e3e8f0;border-radius:14px;padding:16px 18px;margin:12px 0;display:flex;gap:12px;align-items:center;flex-wrap:wrap;box-shadow:0 3px 12px #1f2b4809}a{margin-left:auto;color:#334fbe;font-weight:700}.status{border-radius:20px;padding:3px 10px;font-weight:700}.pass{background:#def6e8;color:#137b56}.fail{background:#ffe6e9;color:#b83d50}small{width:100%;color:#697487;overflow-wrap:anywhere}</style></head><body><h1>自动化测试报告</h1><p>最新运行在最上方。此页每 5 秒更新；单击“打开完整报告”查看 UI 截图或接口断言。</p><ol>${rows}</ol></body></html>`
    const index = join(root, 'index.html')
    const pending = `${index}.pending`
    writeFileSync(pending, html, { encoding: 'utf8', mode: 0o600 })
    renameSync(pending, index)
    if (open && Date.now() - openedAt > 3_600_000) {
      const command = process.platform === 'darwin' ? 'open' : process.platform === 'linux' ? 'xdg-open' : null
      if (command) {
        const launched = spawnSync(command, [index], { timeout: 3000, stdio: 'ignore' })
        if (launched.status !== 0) return { reportViewerPath: index, reportDisplayError: '无法自动打开报告页，请手动打开报告路径' }
        openedAt = Date.now()
      }
    }
    return { reportViewerPath: index }
  } catch (error) {
    return { reportDisplayError: error instanceof Error ? error.message : String(error) }
  }
}
