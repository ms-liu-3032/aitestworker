import type { LogEvidence } from './log-assertions.js'
import { mkdir, readFile, readdir, stat, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

export type LocalReportInput = {
  projectId?: number
  assetId?: number
  caseTitle?: string
  caseNo: string
  versionNo: number
  sessionId: number
  result: string
  durationMs: number
  stepCount: number
  assertionCount: number
  failureStepIndex?: number
  failureReason?: string
  storageStatus: 'RECORDED' | 'EVIDENCE_PENDING' | 'RUN_WRITE_PENDING' | 'LOCAL_ONLY' | 'LOCAL_CAPTURE_INCOMPLETE'
  logEvidence?: LogEvidence[]
  startedAt: number
}

export type LocalApiReportInput = {
  projectId: number
  assetId: number
  caseNo: string
  versionNo: number
  requestKey: string
  result: string
  durationMs: number
  failureStepIndex?: number
  failureReason?: string
  storageStatus: 'RECORDED' | 'EVIDENCE_PENDING' | 'RUN_WRITE_PENDING'
  evidenceId?: number
  logEvidence?: LogEvidence[]
  startedAt: number
  steps: readonly { method: string; path: string; statusCode?: number; durationMs: number; error?: string }[]
  assertions: readonly { stepIndex: number; target: string; path?: string; operator: string; passed: boolean }[]
}

function escapeHtml(value: unknown): string {
  return String(value ?? '').replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[char]!)
}

function logSummary(input: { logEvidence?: LogEvidence[] }): string {
  return input.logEvidence?.length ? `<h2>日志断言</h2><pre>${escapeHtml(JSON.stringify(input.logEvidence, null, 2))}</pre>` : ''
}

function localTime(value: number): string {
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}

function storageLabel(status: LocalReportInput['storageStatus'] | LocalApiReportInput['storageStatus']): string {
  return ({ RECORDED: '已回写', EVIDENCE_PENDING: '证据待上传', RUN_WRITE_PENDING: '结果待回写',
    LOCAL_ONLY: '仅保存在本机', LOCAL_CAPTURE_INCOMPLETE: '本机采集未完成' })[status]
}

function summaryCard(label: string, value: unknown, detail: string): string {
  return `<div class="card"><span class="card-label">${escapeHtml(label)}</span><strong class="card-value">${escapeHtml(value)}</strong><span class="card-detail">${escapeHtml(detail)}</span></div>`
}

// All report styling is embedded: the HTML stays portable and works offline in DSH.
const reportStyle = `<style>
:root{color-scheme:light;--ink:#172033;--muted:#647187;--line:#e4e9f0;--green:#137b56;--red:#b83d50}
*{box-sizing:border-box}body{font:15px/1.55 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;max-width:1180px;margin:0 auto;padding:30px 24px 55px;color:var(--ink);background:#f3f5fa}
h1{margin:0;padding:34px 36px 12px;border-radius:22px 22px 0 0;background:linear-gradient(115deg,#172450,#303f85 65%,#5864a5);color:white;font-size:clamp(24px,3vw,32px);line-height:1.3;box-shadow:0 12px 34px #1c286622}
h1+p{margin:0 0 20px;padding:0 36px 28px;border-radius:0 0 22px 22px;background:linear-gradient(115deg,#172450,#303f85 65%,#5864a5);color:white;box-shadow:0 12px 34px #1c286622}
h1+p strong{display:inline-block;border-radius:100px;padding:6px 12px;margin-top:4px;background:#ffffff28;border:1px solid #ffffff48}
h1+p.pass strong{background:#dff8eb;color:var(--green);border-color:#c9efd9}h1+p.fail strong{background:#ffe6e9;color:var(--red);border-color:#ffd2d9}
h2{font-size:18px;line-height:1.3;margin:26px 0 14px}.summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}
.card{border:1px solid var(--line);border-radius:16px;padding:18px 20px;background:white;box-shadow:0 3px 12px #1f2b4809;min-height:105px;overflow-wrap:anywhere}
.card-label{display:block;color:var(--muted);font-size:12px;font-weight:700;letter-spacing:.03em}.card-value{display:block;margin:6px 0 2px;font-size:21px;font-weight:750;line-height:1.3}.card-detail{display:block;color:var(--muted);font-size:13px}
.pass{color:var(--green)}.fail{color:var(--red)}body>p:not(:first-of-type){background:#fff;border-left:4px solid var(--red);padding:15px 18px;border-radius:9px;overflow-wrap:anywhere}
.gallery{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.gallery details{margin:0;border:1px solid var(--line);border-radius:14px;overflow:hidden;background:white;box-shadow:0 3px 12px #1f2b4809}.gallery summary{cursor:pointer;list-style:none}.gallery summary::-webkit-details-marker{display:none}
.gallery summary img{display:block;width:100%;height:190px;object-fit:contain;background:#edf1f7}.gallery .caption{display:block;padding:10px 13px;color:#43506a;font-size:13px;overflow-wrap:anywhere}.gallery details[open]{grid-column:1/-1}.gallery details[open] summary img{height:auto;max-height:75vh}
table{width:100%;border-collapse:collapse;background:white;border:1px solid var(--line);border-radius:14px;overflow:hidden;box-shadow:0 3px 12px #1f2b4809}th,td{border-bottom:1px solid var(--line);padding:11px 13px;text-align:left;vertical-align:top;overflow-wrap:anywhere}th{background:#f8f9fc;color:var(--muted);font-size:12px}tr:last-child td{border-bottom:0}
pre{max-height:350px;overflow:auto;background:white;border:1px solid var(--line);padding:16px;border-radius:14px;white-space:pre-wrap;overflow-wrap:anywhere}small{display:block;color:var(--muted);line-height:1.6;margin-top:24px}h2 small{display:inline;margin:0 0 0 12px;font-size:12px;font-weight:400}
@media(max-width:900px){.summary{grid-template-columns:repeat(2,minmax(0,1fr))}.gallery{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:600px){body{padding:12px}.summary{gap:8px}.card{padding:13px}.gallery{grid-template-columns:1fr}h1{padding:24px 22px 10px}h1+p{padding:0 22px 22px}table{display:block;overflow-x:auto}}
@media print{body{background:white;max-width:none;padding:0}.card,.gallery details,table{box-shadow:none;break-inside:avoid}h1,h1+p{print-color-adjust:exact}}
</style>`

/** A standalone local HTML report. Screenshots are embedded so moving the file does not break them. */
export async function writeLocalAutomationReport(captureRoot: string, input: LocalReportInput): Promise<string> {
  const sessionDir = join(captureRoot, 'traces', String(input.sessionId))
  const screenshotDir = join(sessionDir, 'screenshots')
  await mkdir(sessionDir, { recursive: true, mode: 0o700 })
  const files = await readdir(screenshotDir).catch(() => [])
  const images = (await Promise.all(files.filter(name => /\.(png|jpe?g)$/i.test(name)).map(async name => {
    const path = join(screenshotDir, name)
    const info = await stat(path).catch(() => null)
    return info?.isFile() ? { name, path, size: info.size, time: info.mtimeMs } : null
  }))).filter((item): item is { name: string; path: string; size: number; time: number } => item !== null)
    .sort((a, b) => a.time - b.time || a.name.localeCompare(b.name))
  const samples = images.length <= 12 ? images : Array.from({ length: 12 }, (_item, index) =>
    images[Math.round(index * (images.length - 1) / 11)])
  let embeddedBytes = 0
  const figures: string[] = []
  for (const item of samples) {
    if (item.size > 5_000_000 || embeddedBytes + item.size > 25_000_000) continue
    const data = await readFile(item.path)
    embeddedBytes += data.length
    const mime = item.name.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg'
    const src = `data:${mime};base64,${data.toString('base64')}`
    figures.push(`<details><summary><img src="${src}" alt="测试截图 ${escapeHtml(item.name)}"><span class="caption">${escapeHtml(item.name)} · 点击展开大图</span></summary></details>`)
  }
  const verdict = input.result === 'PASS' ? '单条用例通过（整轮规则覆盖另行判定）' : '单条用例未通过'
  const failure = input.failureReason ? `<p><strong>失败原因：</strong>${escapeHtml(input.failureReason.slice(0, 1000))}</p>` : ''
  const failedStep = input.failureStepIndex === undefined ? '无' : String(input.failureStepIndex + 1)
  const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${input.storageStatus.startsWith('LOCAL_') ? '本地' : 'AITestWork'} 测试报告 ${escapeHtml(input.caseNo)}</title>${reportStyle}</head><body>
<h1>${input.storageStatus.startsWith('LOCAL_') ? '本地 UI 自动化测试报告' : 'AITestWork UI 自动化测试报告'}</h1><p class="${input.result === 'PASS' ? 'pass' : 'fail'}"><strong>${verdict}</strong> · ${escapeHtml(input.result)}</p>
<div class="summary">${summaryCard('测试用例', input.caseTitle || input.caseNo, input.projectId === undefined ? `本地用例 · v${input.versionNo}` : `项目 ${input.projectId} · 资产 ${input.assetId} · v${input.versionNo}`)}${summaryCard('执行耗时', `${(input.durationMs / 1000).toFixed(2)} 秒`, `${input.stepCount} 个步骤 · ${input.assertionCount} 个断言 · 失败步骤 ${failedStep}`)}${summaryCard('开始时间', localTime(input.startedAt), `采集会话 ${input.sessionId}`)}${summaryCard(input.storageStatus.startsWith('LOCAL_') ? '保存状态' : '回写状态', storageLabel(input.storageStatus), `原始截图 ${images.length} 张 · 展示 ${figures.length} 张`)}</div>
${failure}<h2>运行截图 <small>按采集时间排序 · ${figures.length}/${images.length} 张</small></h2>${figures.length ? `<div class="gallery">${figures.join('\n')}</div>` : '<p>本次采集没有可嵌入的截图；请检查本地采集目录。</p>'}
<small>${input.storageStatus.startsWith('LOCAL_') ? '报告和 Playwright 原始证据仅保存在本机，未写入 AITestWork。' : '报告保存在本机；截图来自本次 Playwright 采集。运行证据和 AITestWork 回写状态以实际接口结果为准。'}</small>${logSummary(input)}</body></html>`
  const path = join(sessionDir, 'report.html')
  await writeFile(path, html, { encoding: 'utf8', mode: 0o600 })
  return path
}

/** API runs have no browser screenshots; their local report contains only redacted evidence summaries. */
export async function writeLocalApiAutomationReport(captureRoot: string, input: LocalApiReportInput): Promise<string> {
  if (!/^[0-9a-f-]{36}$/i.test(input.requestKey)) throw new Error('报告运行编号无效')
  const reportDir = join(captureRoot, 'reports', 'api', String(input.projectId), String(input.assetId))
  await mkdir(reportDir, { recursive: true, mode: 0o700 })
  const stepRows = input.steps.map((step, index) => `<tr><td>${index + 1}</td><td>${escapeHtml(step.method)}</td><td>${escapeHtml(step.path)}</td><td>${step.statusCode ?? '—'}</td><td>${step.durationMs} ms</td><td>${escapeHtml(step.error || '—')}</td></tr>`).join('\n')
  const assertionRows = input.assertions.map(check => `<tr><td>${check.stepIndex + 1}</td><td>${escapeHtml(check.target)}</td><td>${escapeHtml(check.path || '—')}</td><td>${escapeHtml(check.operator)}</td><td class="${check.passed ? 'pass' : 'fail'}">${check.passed ? '通过' : '未通过'}</td></tr>`).join('\n')
  const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>AITestWork 接口自动化测试报告 ${escapeHtml(input.caseNo)}</title>${reportStyle}</head><body>
<h1>AITestWork 接口自动化测试报告</h1><p class="${input.result === 'PASS' ? 'pass' : 'fail'}"><strong>${input.result === 'PASS' ? '通过' : '未通过'}</strong> · ${escapeHtml(input.result)}</p>
<div class="summary">${summaryCard('测试用例', input.caseNo, `项目 ${input.projectId} · 资产 ${input.assetId} · v${input.versionNo}`)}${summaryCard('执行耗时', `${(input.durationMs / 1000).toFixed(2)} 秒`, `${input.steps.length} 个请求步骤 · ${input.assertions.length} 个断言`)}${summaryCard('开始时间', localTime(input.startedAt), input.failureStepIndex === undefined ? '无失败步骤' : `失败步骤 ${input.failureStepIndex + 1}`)}${summaryCard('回写状态', storageLabel(input.storageStatus), `接口证据 ${input.evidenceId === undefined ? '待回写' : `#${input.evidenceId}`} · 无浏览器截图`)}</div>
${input.failureReason ? `<p><strong>失败原因：</strong>${escapeHtml(input.failureReason.slice(0, 1000))}</p>` : ''}
<h2>请求步骤</h2><table><thead><tr><th>步骤</th><th>方法</th><th>路径模板</th><th>状态码</th><th>耗时</th><th>错误</th></tr></thead><tbody>${stepRows}</tbody></table>
<h2>断言结果</h2><table><thead><tr><th>步骤</th><th>目标</th><th>字段路径</th><th>判断</th><th>结果</th></tr></thead><tbody>${assertionRows || '<tr><td colspan="5">无已执行断言</td></tr>'}</tbody></table>
<small>报告仅保存在本机，不包含认证值、请求体或响应正文。接口运行没有浏览器截图；AITestWork 回写状态以实际接口结果为准。</small>${logSummary(input)}</body></html>`
  const path = join(reportDir, `${input.requestKey}.html`)
  await writeFile(path, html, { encoding: 'utf8', mode: 0o600 })
  return path
}
