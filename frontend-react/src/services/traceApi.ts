import { api } from './api'

export interface WorkerHealth {
  status: string
  version: string
  protocolVersion: string
  platform: string
  arch: string
  browserReady: boolean
  playwrightReady: boolean
  bound: boolean
  serverConnected: boolean
  browserAlive?: boolean
  browser: { type: string; displayName: string } | null
  activeSessions?: number[]
}

export interface BrowserProfile {
  id: number
  projectId: number
  userId: number
  profileName: string
  targetHost?: string
  accountLabel?: string
  roleLabel?: string
  username?: string
  passwordCipher?: string
  storageStatePath?: string
  status: string
  lastUsedAt?: string
  createdAt: string
  updatedAt: string
}

export interface BrowserTraceGroup {
  id: number
  projectId: number
  userId: number
  profileId?: number
  groupName: string
  description?: string
  status: string
  startedAt?: string
  stoppedAt?: string
  timezone?: string
  createdAt: string
  updatedAt: string
}

export interface BrowserTraceSession {
  id: number
  traceGroupId: number
  projectId: number
  userId: number
  profileId: number
  sessionName?: string
  browserType?: string
  videoPath?: string
  traceFilePath?: string
  status: string
  recordingStartedAtUtc?: string
  recordingStartedAtLocal?: string
  recordingStoppedAtUtc?: string
  recordingStoppedAtLocal?: string
  screencastPath?: string | null
  screencastStartedAtUtc?: string | null
  screencastStoppedAtUtc?: string | null
  screencastDurationMs?: number | null
  createdAt: string
  updatedAt: string
}

export interface BrowserTraceEvent {
  id: number
  traceGroupId: number
  traceSessionId: number
  profileId: number
  eventType: string
  pageUrl?: string
  pageTitle?: string
  elementText?: string
  elementRole?: string
  selector?: string
  valueSummary?: string
  normalizedLocator?: string
  sectionTitle?: string
  dialogTitle?: string
  objectLabel?: string
  happenedAtUtc: string
  happenedAtLocal: string
  relativeMs: number
  createdAt: string
}

export interface BrowserTraceNetwork {
  id: number
  traceGroupId: number
  traceSessionId: number
  profileId: number
  url: string
  method?: string
  statusCode?: number
  durationMs?: number
  failed: number
  errorMessage?: string
  requestSummary?: string
  responseSummary?: string
  relativeMs?: number
  createdAt: string
}

export interface BrowserIssueClip {
  id: number
  traceGroupId: number
  traceSessionId?: number
  clipScope: string
  title?: string
  description?: string
  clipStartAtUtc: string
  clipStartAtLocal: string
  clipEndAtUtc: string
  clipEndAtLocal: string
  clipStartRelativeMs: number
  clipEndRelativeMs: number
  screencastPath?: string | null
  screencastClipStartMs?: number | null
  screencastClipEndMs?: number | null
  status: string
  createdAt: string
}

export interface TraceSummary {
  id: number
  traceGroupId: number
  traceSessionId?: number
  issueClipId?: number
  summaryScope: string
  overview?: string
  businessSummary?: string
  keyStepsJson?: string
  keyApiJson?: string
  exceptionSummary?: string
  caseGenerationSuggestionJson?: string
  pendingConfirmationJson?: string
  status: string
  validityLabel: string
  createdBy: number
  createdAt: string
  updatedAt: string
  confirmedBy?: number
  confirmedAt?: string
  rejectedBy?: number
  rejectedAt?: string
  rejectedReason?: string
  confidenceLabel: string
  rawInputSnapshot?: string
  llmOutputSnapshot?: string
  modelConfigId?: number
  promptSnapshotId?: number
  contextManifestId?: number
  llmInvocationLogId?: number
}

export interface TraceCorrectionSuggestion {
  id: number
  traceGroupId: number
  traceSessionId?: number
  issueClipId?: number
  traceEventId?: number
  summaryId?: number
  correctionType: string
  sourceText: string
  candidateValue: string
  confirmedValue?: string
  candidateReason: string
  confidenceLabel: string
  status: string
  stepNo?: number
  correctionScope?: string
  operationType?: string
  candidateStepText?: string
  confirmedStepText?: string
  relatedStepNo?: number
  createdAt: string
}

export interface CleanStepView {
  stepNo: number
  actor: string
  actionType: string
  description: string
  pageName?: string
  pageUrl?: string
  relativeMs?: number
}

export interface TraceGeneratedCase {
  id: number
  projectId: number
  traceGroupId?: number
  traceSessionId?: number
  issueClipId?: number
  caseType: string
  caseTitle: string
  moduleName?: string
  precondition?: string
  steps: string
  expectedResult: string
  priority: string
  caseScope: string
  caseStatus: string
}

export interface TestSkillTemplate {
  id: number
  skillName: string
  applicableScene?: string
  flowSteps: string
  status: string
}

export interface TestToolTemplate {
  id: number
  toolName: string
  operationSteps: string
  status: string
}

export interface FormalCase {
  id: number
  caseNo: string
  caseTitle: string
  moduleName?: string
  priority?: string
  caseStatus: string
}

export interface TraceGroupDetail {
  group: BrowserTraceGroup
  sessions: BrowserTraceSession[]
  events: BrowserTraceEvent[]
  networks: BrowserTraceNetwork[]
  issueClips: BrowserIssueClip[]
}

export interface WorkerStartResult {
  sessionId: number
  status: string
  url: string
}

export interface WorkerStopResult {
  sessionId: number
  status: string
  events: Array<{
    eventType: string
    pageUrl: string
    pageTitle: string
    elementText: string
    elementRole: string
    selector: string
    valueSummary: string
    screenshotPath?: string | null
    normalizedLocator?: string
    sectionTitle?: string
    dialogTitle?: string
    objectLabel?: string
    happenedAtUtc: string
    happenedAtLocal: string
    timezone: string
    relativeMs: number
  }>
  networks: Array<{
    url: string
    method: string
    statusCode?: number | null
    durationMs?: number | null
    failed: boolean
    errorMessage?: string | null
    requestSummary?: string | null
    responseSummary?: string | null
    requestStartedAtUtc: string
    requestStartedAtLocal: string
    responseEndedAtUtc?: string | null
    responseEndedAtLocal?: string | null
    timezone: string
    relativeMs: number
  }>
  videoPath?: string | null
  traceFilePath?: string | null
  screencastPath?: string | null
  screencastStartedAtUtc?: string | null
  screencastStoppedAtUtc?: string | null
  screencastDurationMs?: number | null
}

export async function checkWorkerHealth(): Promise<WorkerHealth | null> {
  try {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 2000)
    const res = await fetch('http://127.0.0.1:17321/health', { signal: controller.signal })
    clearTimeout(timeout)
    if (!res.ok) return null
    return await res.json() as WorkerHealth
  } catch {
    return null
  }
}

export async function workerApi<T>(path: string, body: unknown, localToken: string): Promise<T> {
  const res = await fetch(`http://127.0.0.1:17321${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Local-Token': localToken.trim() },
    body: JSON.stringify(body),
  })
  const data = await res.json() as { success: boolean; data: T; message?: string }
  if (!res.ok || !data.success) throw new Error(data.message || '本地采集器请求失败')
  return data.data
}

export function listProfiles(projectId: number) {
  return api<BrowserProfile[]>(`/api/trace/projects/${projectId}/profiles`)
}

export function listGroups(projectId: number) {
  return api<BrowserTraceGroup[]>(`/api/trace/projects/${projectId}/trace-groups`)
}

export function createGroup(projectId: number, body: { groupName: string; description?: string; profileId?: number }) {
  return api<BrowserTraceGroup>(`/api/trace/projects/${projectId}/trace-groups`, { method: 'POST', body: JSON.stringify(body) })
}

export function startGroup(groupId: number) {
  return api(`/api/trace/groups/${groupId}/start`, { method: 'POST' })
}

export function stopGroup(groupId: number) {
  return api(`/api/trace/groups/${groupId}/stop`, { method: 'POST' })
}

export interface TraceScanResult {
  profileCount: number
  eventCount: number
  scannedPages: string[]
}

export function scanFromTrace(groupId: number) {
  return api<TraceScanResult>(`/api/trace/groups/${groupId}/scan`, { method: 'POST' })
}

export function createSession(groupId: number, body: { profileId: number; sessionName: string; browserType?: string; browserExecutablePath?: string }) {
  return api<BrowserTraceSession>(`/api/trace/groups/${groupId}/sessions`, { method: 'POST', body: JSON.stringify(body) })
}

export function startSession(sessionId: number) {
  return api(`/api/trace/sessions/${sessionId}/start`, { method: 'POST' })
}

export function stopSession(sessionId: number, body: { videoPath?: string | null; traceFilePath?: string | null; screencastPath?: string | null; screencastStartedAtUtc?: string | null; screencastStoppedAtUtc?: string | null; screencastDurationMs?: number | null }) {
  return api(`/api/trace/sessions/${sessionId}/stop`, { method: 'POST', body: JSON.stringify(body) })
}

export function updateSessionName(sessionId: number, sessionName: string) {
  return api(`/api/trace/sessions/${sessionId}`, { method: 'PATCH', body: JSON.stringify({ sessionName }) })
}

export function listGroupSessions(groupId: number) {
  return api<BrowserTraceSession[]>(`/api/trace/groups/${groupId}/sessions`)
}

export function getGroupDetail(groupId: number) {
  return api<TraceGroupDetail>(`/api/trace/groups/${groupId}/detail`)
}

export function getGroupSummaries(groupId: number) {
  return api<TraceSummary[]>(`/api/trace/groups/${groupId}/summaries`)
}

export function getGroupCleanSteps(groupId: number) {
  return api<CleanStepView[]>(`/api/trace/groups/${groupId}/clean-steps`)
}

export function getGroupSkills(groupId: number) {
  return api<TestSkillTemplate[]>(`/api/trace/groups/${groupId}/skills`)
}

export function getGroupTools(groupId: number) {
  return api<TestToolTemplate[]>(`/api/trace/groups/${groupId}/tools`)
}

export function getGroupCorrections(groupId: number) {
  return api<TraceCorrectionSuggestion[]>(`/api/trace/groups/${groupId}/corrections`)
}

export function generateGroupSummary(groupId: number, modelConfigId: number, issueClipId?: number | null) {
  return api(`/api/trace/groups/${groupId}/summaries:generate`, {
    method: 'POST',
    body: JSON.stringify({ modelConfigId, issueClipId: issueClipId ?? null, summaryScope: issueClipId ? 'ISSUE_CLIP' : 'GROUP' }),
  })
}

export function regenerateSummary(summaryId: number, modelConfigId: number) {
  return api(`/api/trace/summaries/${summaryId}:regenerate`, {
    method: 'POST',
    body: JSON.stringify({ modelConfigId }),
  })
}

export function confirmSummary(summaryId: number) {
  return api(`/api/trace/summaries/${summaryId}:confirm`, { method: 'POST', body: JSON.stringify({ validityLabel: 'STANDARD' }) })
}

export function rejectSummary(summaryId: number, reason: string) {
  return api(`/api/trace/summaries/${summaryId}:reject`, { method: 'POST', body: JSON.stringify({ reason }) })
}

export function generateGroupCases(groupId: number, modelConfigId: number, caseType: string, issueClipId?: number | null) {
  return api(`/api/trace/groups/${groupId}/cases:generate`, {
    method: 'POST',
    body: JSON.stringify({ modelConfigId, caseType, issueClipId: issueClipId ?? null }),
  })
}

export function generateGroupSkill(groupId: number, modelConfigId: number) {
  return api(`/api/trace/groups/${groupId}/skills:generate`, { method: 'POST', body: JSON.stringify({ modelConfigId }) })
}

export function generateGroupTool(groupId: number, modelConfigId: number) {
  return api(`/api/trace/groups/${groupId}/tools:generate`, { method: 'POST', body: JSON.stringify({ modelConfigId }) })
}

export function listGeneratedCases(projectId: number) {
  return api<TraceGeneratedCase[]>(`/api/trace/projects/${projectId}/generated-cases`)
}

export function listGeneratedCasesPage(projectId: number, traceGroupId: number, page = 0, size = 20) {
  return api<{ items: TraceGeneratedCase[]; total: number; page: number; size: number }>(
    `/api/trace/projects/${projectId}/generated-cases/page?traceGroupId=${traceGroupId}&page=${page}&size=${size}`,
  )
}

export function listFormalCases(projectId: number) {
  return api<FormalCase[]>(`/api/trace/projects/${projectId}/formal-cases`)
}

export function listFormalCasesPage(projectId: number, page = 0, size = 10) {
  return api<{ items: FormalCase[]; total: number; page: number; size: number }>(
    `/api/trace/projects/${projectId}/formal-cases/page?page=${page}&size=${size}`,
  )
}

export function submitGeneratedCase(caseId: number) {
  return api(`/api/trace/generated-cases/${caseId}/submit`, { method: 'POST' })
}

export function deleteGroup(projectId: number, groupId: number) {
  return api(`/api/trace/projects/${projectId}/trace-groups/${groupId}`, { method: 'DELETE' })
}

export function createBindCode() {
  return api<{ code: string; expiresAt: string }>('/api/trace/devices/bind-codes', { method: 'POST' })
}

export function createProfile(projectId: number, body: { profileName: string; targetHost?: string; accountLabel?: string; roleLabel?: string; username?: string; password?: string }) {
  return api(`/api/trace/projects/${projectId}/profiles`, { method: 'POST', body: JSON.stringify(body) })
}

export function updateProfile(projectId: number, profileId: number, body: { profileName: string; targetHost?: string; accountLabel?: string; roleLabel?: string; username?: string; password?: string }) {
  return api(`/api/trace/projects/${projectId}/profiles/${profileId}`, { method: 'PATCH', body: JSON.stringify(body) })
}

export function profileOperation(projectId: number, profileId: number, operationType: string) {
  return api(`/api/trace/projects/${projectId}/profiles/${profileId}/operations`, { method: 'POST', body: JSON.stringify({ operationType }) })
}

export function getProfileCredentials(projectId: number, profileId: number) {
  return api<{ username: string; password: string }>(`/api/trace/projects/${projectId}/profiles/${profileId}/credentials`)
}

export function saveStepCorrection(groupId: number, body: { stepNo: number; sourceText: string; operationType: 'REWRITE' | 'DROP'; confirmedStepText?: string | null }) {
  return api(`/api/trace/groups/${groupId}/step-corrections`, { method: 'POST', body: JSON.stringify(body) })
}

export function learnPattern(groupId: number) {
  return api(`/api/trace/groups/${groupId}/corrections:learn-pattern`, { method: 'POST' })
}

export function confirmCorrection(correctionId: number, body: { confirmedValue?: string | null; confirmedStepText?: string | null }) {
  return api(`/api/trace/corrections/${correctionId}:confirm`, { method: 'POST', body: JSON.stringify(body) })
}

export function rejectCorrection(correctionId: number, reason: string) {
  return api(`/api/trace/corrections/${correctionId}:reject`, { method: 'POST', body: JSON.stringify({ reason }) })
}

export function batchSaveEvents(sessionId: number, events: unknown[]) {
  return api(`/api/trace/sessions/${sessionId}/events:batch`, { method: 'POST', body: JSON.stringify({ events }) })
}

export function batchSaveNetworks(sessionId: number, networks: unknown[]) {
  return api(`/api/trace/sessions/${sessionId}/network:batch`, { method: 'POST', body: JSON.stringify({ items: networks }) })
}
