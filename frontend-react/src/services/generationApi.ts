import { api, uploadFile } from './api'
import type { PageResult } from './api'

export interface ModelConfigOption {
  id: number
  configName: string
  modelName: string
}

export interface GenerationSession {
  id: number
  projectId: number
  sessionTitle: string
  status: string
  currentStage: string | null
  modelConfigId: number | null
  promptTemplateId: number | null
  promptSnapshot: string | null
  useMiniTom: boolean
  tomMode: 'DIRECT' | 'PROJECT_TOM' | 'PROJECT_AND_SYSTEM_TOM'
  latestAnalysisVersion: number
  executionTaskId: number | null
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface GenerationMessage {
  id: number
  sessionId: number
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string | null
  structuredPayload: string | null
  analysisVersion: number
  stage: string | null
  createdAt: string
}

export interface GenerationAnalysis {
  id: number
  sessionId: number
  version: number
  requirementText: string
  analysisResult: string | null
  tomScopeSnapshot: string | null
  clarificationQuestions: string | null
  clarificationAnswers: string | null
  assumptions: string | null
  testPoints: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export interface GenerationAttachment {
  id: number
  sessionId: number
  messageId: number | null
  fileName: string
  fileType: string
  fileSize: number
  storagePath: string | null
  parseStatus: string
  parsedContent: string | null
  parseError: string | null
  visionResult: string | null
  createdBy: number
  createdAt: string
}

export interface ConversationReply {
  newMessages: GenerationMessage[]
  analysis: GenerationAnalysis | null
}

const base = (projectId: number) => `/api/projects/${projectId}/generation/sessions`

export function listSessions(projectId: number, params: { page?: number; size?: number; status?: string; keyword?: string } = {}) {
  const qs = new URLSearchParams()
  if (params.page) qs.set('page', String(params.page))
  if (params.size) qs.set('size', String(params.size))
  if (params.status) qs.set('status', params.status)
  if (params.keyword) qs.set('keyword', params.keyword)
  const query = qs.toString()
  return api<PageResult<GenerationSession>>(`${base(projectId)}${query ? '?' + query : ''}`)
}

export function createSession(projectId: number, body: { sessionTitle: string; modelConfigId?: number; promptTemplateId?: number; useMiniTom?: boolean; tomMode?: 'DIRECT' | 'PROJECT_TOM' | 'PROJECT_AND_SYSTEM_TOM' }) {
  return api<GenerationSession>(base(projectId), { method: 'POST', body: JSON.stringify(body) })
}

export function getSession(projectId: number, sessionId: number) {
  return api<GenerationSession>(`${base(projectId)}/${sessionId}`)
}

export function updateSession(projectId: number, sessionId: number, body: Record<string, unknown>) {
  return api<void>(`${base(projectId)}/${sessionId}`, { method: 'PATCH', body: JSON.stringify(body) })
}

export function archiveSession(projectId: number, sessionId: number) {
  return api<void>(`${base(projectId)}/${sessionId}`, { method: 'DELETE' })
}

export function listMessages(projectId: number, sessionId: number) {
  return api<GenerationMessage[]>(`${base(projectId)}/${sessionId}/messages`)
}

export function sendMessage(projectId: number, sessionId: number, content: string) {
  return api<ConversationReply>(`${base(projectId)}/${sessionId}/messages`, { method: 'POST', body: JSON.stringify({ content }) })
}

export function uploadAttachment(projectId: number, sessionId: number, file: File) {
  return uploadFile<GenerationAttachment>(`${base(projectId)}/${sessionId}/attachments`, file)
}

export function listAttachments(projectId: number, sessionId: number) {
  return api<GenerationAttachment[]>(`${base(projectId)}/${sessionId}/attachments`)
}

export function triggerAnalysis(projectId: number, sessionId: number) {
  return api<GenerationAnalysis>(`${base(projectId)}/${sessionId}/analyze`, { method: 'POST' })
}

export function getLatestAnalysis(projectId: number, sessionId: number) {
  return api<GenerationAnalysis>(`${base(projectId)}/${sessionId}/analysis`)
}

export function getAnalysisVersion(projectId: number, sessionId: number, version: number) {
  return api<GenerationAnalysis>(`${base(projectId)}/${sessionId}/analysis/${version}`)
}

export function answerClarification(projectId: number, sessionId: number, questionIndex: number, answer: string) {
  return api<void>(`${base(projectId)}/${sessionId}/clarify/${questionIndex}/answer`, { method: 'POST', body: JSON.stringify({ answer }) })
}

export function skipClarification(projectId: number, sessionId: number) {
  return api<string[]>(`${base(projectId)}/${sessionId}/clarify:skip`, { method: 'POST' })
}

export function confirmAndGenerate(projectId: number, sessionId: number) {
  return api<GenerationAnalysis>(`${base(projectId)}/${sessionId}/generate`, { method: 'POST' })
}

export function skipConfirmAndGenerate(projectId: number, sessionId: number) {
  return api<GenerationAnalysis>(`${base(projectId)}/${sessionId}/generate:skip-confirm`, { method: 'POST' })
}

export function listDrafts(projectId: number, sessionId: number) {
  return api<Record<string, unknown>[]>(`${base(projectId)}/${sessionId}/drafts`)
}
