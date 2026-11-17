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
  startedAt: number
  steps: readonly { method: string; path: string; statusCode?: number; durationMs: number; error?: string }[]
  assertions: readonly { stepIndex: number; target: string; path?: string; operator: string; passed: boolean }[]
}

function escapeHtml(value: unknown): string {
  return String(value ?? '').replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[char]!)
}

function localTime(value: number): string {
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}

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
    figures.push(`<figure><img src="data:${mime};base64,${data.toString('base64')}" alt="测试截图"><figcaption>${escapeHtml(item.name)}</figcaption></figure>`)
  }
  const verdict = input.result === 'PASS' ? '通过' : '未通过'
  const failure = input.failureReason ? `<p><strong>失败原因：</strong>${escapeHtml(input.failureReason.slice(0, 1000))}</p>` : ''
  const failedStep = input.failureStepIndex === undefined ? '无' : String(input.failureStepIndex + 1)
  const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>${input.storageStatus.startsWith('LOCAL_') ? '本地' : 'AITestHub'} 测试报告 ${escapeHtml(input.caseNo)}</title>
<style>body{font:15px/1.6 system-ui,sans-serif;max-width:1100px;margin:32px auto;padding:0 20px;color:#172033}h1,h2{line-height:1.3}.summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px}.card{border:1px solid #d9e0e8;border-radius:9px;padding:12px;background:#f7f9fc}.pass{color:#147d49}.fail{color:#bf3636}figure{margin:20px 0;border:1px solid #d9e0e8;border-radius:9px;overflow:hidden}img{display:block;width:100%;height:auto}figcaption{padding:7px 12px;background:#f7f9fc;color:#566}small{color:#566}</style></head><body>
<h1>${input.storageStatus.startsWith('LOCAL_') ? '本地 UI 自动化测试报告' : 'AITestHub UI 自动化测试报告'}</h1><p class="${input.result === 'PASS' ? 'pass' : 'fail'}"><strong>${verdict}</strong> · ${escapeHtml(input.result)}</p>
<div class="summary"><div class="card">${input.projectId === undefined ? '本地用例' : `项目 ${input.projectId}<br>资产 ${input.assetId}`}<br>${escapeHtml(input.caseTitle || input.caseNo)}</div><div class="card">版本 v${input.versionNo}<br>步骤 ${input.stepCount} · 断言 ${input.assertionCount}<br>失败步骤 ${failedStep}</div><div class="card">开始 ${localTime(input.startedAt)}<br>耗时 ${(input.durationMs / 1000).toFixed(2)} 秒<br>采集会话 ${input.sessionId}</div><div class="card">${input.storageStatus.startsWith('LOCAL_') ? '保存状态' : '回写状态'} ${escapeHtml(input.storageStatus)}<br>原始截图 ${images.length} 张<br>报告内截图 ${figures.length} 张</div></div>
${failure}<h2>运行截图</h2>${figures.join('\n') || '<p>本次采集没有可嵌入的截图；请检查本地采集目录。</p>'}
<small>${input.storageStatus.startsWith('LOCAL_') ? '报告和 Playwright 原始证据仅保存在本机，未写入 Hub。' : '报告保存在本机；截图来自本次 Playwright 采集。运行证据和 Hub 回写状态以实际接口结果为准。'}</small></body></html>`
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
  const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>AITestHub 接口自动化测试报告 ${escapeHtml(input.caseNo)}</title>
<style>body{font:15px/1.6 system-ui,sans-serif;max-width:1100px;margin:32px auto;padding:0 20px;color:#172033}h1,h2{line-height:1.3}.summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px}.card{border:1px solid #d9e0e8;border-radius:9px;padding:12px;background:#f7f9fc}.pass{color:#147d49}.fail{color:#bf3636}table{width:100%;border-collapse:collapse}th,td{border:1px solid #d9e0e8;padding:8px;text-align:left;vertical-align:top;overflow-wrap:anywhere}th{background:#f7f9fc}small{color:#566}</style></head><body>
<h1>AITestHub 接口自动化测试报告</h1><p class="${input.result === 'PASS' ? 'pass' : 'fail'}"><strong>${input.result === 'PASS' ? '通过' : '未通过'}</strong> · ${escapeHtml(input.result)}</p>
<div class="summary"><div class="card">项目 ${input.projectId}<br>资产 ${input.assetId}<br>用例 ${escapeHtml(input.caseNo)}</div><div class="card">版本 v${input.versionNo}<br>步骤 ${input.steps.length} · 断言 ${input.assertions.length}<br>失败步骤 ${input.failureStepIndex === undefined ? '无' : input.failureStepIndex + 1}</div><div class="card">开始 ${localTime(input.startedAt)}<br>耗时 ${(input.durationMs / 1000).toFixed(2)} 秒</div><div class="card">回写状态 ${escapeHtml(input.storageStatus)}<br>接口证据 ${input.evidenceId === undefined ? '待回写' : `#${input.evidenceId}`}<br>浏览器截图 不适用</div></div>
${input.failureReason ? `<p><strong>失败原因：</strong>${escapeHtml(input.failureReason.slice(0, 1000))}</p>` : ''}
<h2>请求步骤</h2><table><thead><tr><th>步骤</th><th>方法</th><th>路径模板</th><th>状态码</th><th>耗时</th><th>错误</th></tr></thead><tbody>${stepRows}</tbody></table>
<h2>断言结果</h2><table><thead><tr><th>步骤</th><th>目标</th><th>字段路径</th><th>判断</th><th>结果</th></tr></thead><tbody>${assertionRows || '<tr><td colspan="5">无已执行断言</td></tr>'}</tbody></table>
<small>报告仅保存在本机，不包含认证值、请求体或响应正文。接口运行没有浏览器截图；Hub 回写状态以实际接口结果为准。</small></body></html>`
  const path = join(reportDir, `${input.requestKey}.html`)
  await writeFile(path, html, { encoding: 'utf8', mode: 0o600 })
  return path
}
