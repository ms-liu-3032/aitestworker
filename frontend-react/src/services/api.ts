import { requestBlob, requestJson, requestText } from './httpClient'
export { getToken, setToken, clearToken, HttpError } from './httpClient'

export function api<T>(url: string, options: RequestInit = {}): Promise<T> {
  return requestJson<T>(url, options)
}

export function apiText(url: string, options: RequestInit = {}): Promise<string> {
  return requestText(url, options)
}

export function uploadFile<T>(url: string, file: File): Promise<T> {
  const formData = new FormData()
  formData.append('file', file)
  return requestJson<T>(url, { method: 'POST', body: formData })
}

// ===== Common =====
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

// ===== Project API =====
export interface Project {
  id: number
  projectName: string
  description: string
  status: string
  createdBy: number
  createdAt: string
  updatedAt: string
}

export function listProjects(params: { page?: number; size?: number; keyword?: string; status?: string } = {}) {
  const qs = new URLSearchParams()
  if (params.page !== undefined) qs.set('page', String(params.page))
  if (params.size !== undefined) qs.set('size', String(params.size))
  if (params.keyword) qs.set('keyword', params.keyword)
  if (params.status) qs.set('status', params.status)
  const query = qs.toString()
  return api<PageResult<Project>>(`/api/projects/page${query ? '?' + query : ''}`)
}

/** Load every project for filters; a fixed first page silently hides projects after the limit. */
export async function listAllProjects(): Promise<Project[]> {
  const pageSize = 200
  const first = await listProjects({ page: 1, size: pageSize })
  const projects = [...first.items]
  const pages = Math.ceil(first.total / pageSize)
  for (let page = 2; page <= pages; page++) {
    const next = await listProjects({ page, size: pageSize })
    projects.push(...next.items)
  }
  return projects
}

export function getProject(projectId: number) {
  return api<Project>(`/api/projects/${projectId}`)
}

export interface ProjectMember {
  userId: number
  username: string
  displayName: string | null
  projectRole: 'OWNER' | 'MEMBER'
}

export interface ProjectMemberList { canManage: boolean; members: ProjectMember[] }

export function listProjectMembers(projectId: number) {
  return api<ProjectMemberList>(`/api/projects/${projectId}/members`)
}

export function addProjectMember(projectId: number, username: string) {
  return api<ProjectMember>(`/api/projects/${projectId}/members`, {
    method: 'POST', body: JSON.stringify({ username }),
  })
}

export function removeProjectMember(projectId: number, userId: number) {
  return api<void>(`/api/projects/${projectId}/members/${userId}`, { method: 'DELETE' })
}

export function createProject(body: { projectName: string; description?: string }) {
  return api<Project>('/api/projects', { method: 'POST', body: JSON.stringify(body) })
}

export function updateProject(projectId: number, body: { projectName: string; description?: string }) {
  return api<Project>(`/api/projects/${projectId}`, { method: 'PUT', body: JSON.stringify(body) })
}

export function deleteProject(projectId: number) {
  return api<void>(`/api/projects/${projectId}`, { method: 'DELETE' })
}

export function restoreProject(projectId: number) {
  return api<Project>(`/api/projects/${projectId}/restore`, { method: 'POST' })
}

// ===== Auth API =====
export interface User {
  id: number
  username: string
  displayName?: string
  roleCode: string
}

export function login(username: string, password: string) {
  return api<{ token: string; user: User }>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password })
  })
}

export function fetchMe() {
  return api<User>('/api/auth/me')
}

export function initAdmin(username: string, password: string, displayName: string) {
  return api<{ token: string; user: User }>('/api/auth/init-admin', {
    method: 'POST',
    body: JSON.stringify({ username, password, displayName })
  })
}

export function getInitStatus() {
  return api<{ initialized: boolean }>('/api/auth/init-status')
}

// ===== User Admin API =====
export interface UserRecord {
  id: number
  username: string
  displayName: string
  roleCode: string
  status: string
  createdAt: string
  updatedAt: string
}

export function listUsers() {
  return api<UserRecord[]>('/api/admin/users')
}

export function createUser(body: { username: string; password: string; displayName: string; roleCode: string }) {
  return api<UserRecord>('/api/admin/users', { method: 'POST', body: JSON.stringify(body) })
}

// ===== Model Config API =====
export interface ModelConfigRecord {
  id: number
  configName: string
  provider: string
  modelName: string
  endpoint: string
  status: string
  createdBy: number
  createdAt: string
  updatedAt: string
  visionEnabled: boolean | null
}

export function listModelConfigs() {
  return api<ModelConfigRecord[]>('/api/admin/model-configs')
}

export function createModelConfig(body: { configName: string; provider: string; modelName: string; endpoint?: string; apiKey?: string; visionEnabled?: boolean | null }) {
  return api<ModelConfigRecord>('/api/admin/model-configs', { method: 'POST', body: JSON.stringify(body) })
}

export function updateModelConfig(id: number, body: { configName: string; provider: string; modelName: string; endpoint?: string; apiKey?: string; visionEnabled?: boolean | null }) {
  return api<ModelConfigRecord>(`/api/admin/model-configs/${id}`, { method: 'PUT', body: JSON.stringify(body) })
}

export function deleteModelConfig(id: number) {
  return api<void>(`/api/admin/model-configs/${id}`, { method: 'DELETE' })
}

export function listEnabledModelConfigs() {
  return api<ModelConfigRecord[]>('/api/model-configs/enabled')
}

// ===== Prompt Template API =====
export interface PromptTemplateRecord {
  id: number
  promptName: string
  promptType: string
  scope: string
  content: string
  status: string
  version: number
  reviewStatus: string
  contributorUserId: number | null
  contributorUsername: string | null
  deprecatedAt: string | null
  deprecatedBy: number | null
  deprecatedReason: string | null
  createdAt: string
  updatedAt: string
}

export function listPromptTemplates() {
  return api<PromptTemplateRecord[]>('/api/admin/prompts')
}

export function createPromptTemplate(body: { promptName: string; promptType: string; content: string }) {
  return api<PromptTemplateRecord>('/api/admin/prompts', { method: 'POST', body: JSON.stringify(body) })
}

export function createPromptNewVersion(id: number, content: string) {
  return api<PromptTemplateRecord>(`/api/admin/prompts/${id}/new-version`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  })
}

export function reviewPromptTemplate(id: number, approved: boolean, reason?: string) {
  return api<PromptTemplateRecord>(`/api/admin/prompts/${id}/review`, {
    method: 'POST',
    body: JSON.stringify({ approved, reason }),
  })
}

export function listEnabledPromptTemplates() {
  return api<PromptTemplateRecord[]>('/api/prompts/enabled')
}

// ===== Mini-TOM API =====
export interface TestObjectModel {
  id: number
  projectId: number | null
  scope: string
  modelType: string
  name: string
  description: string
  propertiesJson: string | null
  sourceType: string
  sourceRefId: number | null
  sourceContext: string | null
  confidence: number | null
  status: string
  requiresHumanConfirm: boolean
  validityLabel: string | null
  businessDomain: string | null
  priority: number | null
  createdBy?: number | null
  confirmedBy?: number | null
  deprecatedAt?: string | null
  deprecatedBy?: number | null
  deprecatedReason?: string | null
  upgradedBy?: number | null
  upgradedAt?: string | null
  createdAt: string
  updatedAt: string
}

export interface TomRelationCandidate {
  id: number
  projectId: number
  fromModelId: number
  fromName: string
  relationType: string
  toModelId: number
  toName: string
  sourceType: string
  sourceRefId: number | null
  sourceRefsJson: string | null
  status: string
  requiresHumanConfirm: boolean
  confidence: number | null
  createdAt: string
}

export interface TomAliasCandidate {
  id: number
  tomId: number
  tomName: string
  aliasName: string
  sourceType: string
  sourceRefId: number | null
  status: string
  createdAt: string
}

export interface TomImpactCandidate {
  id: number
  fromModelId: number
  fromName: string
  targetKind: string
  targetRef: string
  impactType: string
  sourceRefId: number | null
  sourceType: string
  status: string
  createdAt: string
}

export function listTomCandidates(projectId?: number) {
  const qs = projectId ? `?projectId=${projectId}` : ''
  return api<TestObjectModel[]>(`/api/mini-tom/candidates${qs}`)
}

export function listActiveTomModels(projectId?: number) {
  const qs = projectId ? `?projectId=${projectId}` : ''
  return api<TestObjectModel[]>(`/api/mini-tom/active${qs}`)
}

export function listAdminTomModels(params: {
  projectId?: number
  modelType?: string
  status?: string
  sourceType?: string
  businessDomain?: string
  createdBy?: number
  confirmedBy?: number
} = {}) {
  const qs = new URLSearchParams()
  if (params.projectId !== undefined) qs.set('projectId', String(params.projectId))
  if (params.modelType) qs.set('modelType', params.modelType)
  if (params.status) qs.set('status', params.status)
  if (params.sourceType) qs.set('sourceType', params.sourceType)
  if (params.businessDomain) qs.set('businessDomain', params.businessDomain)
  if (params.createdBy !== undefined) qs.set('createdBy', String(params.createdBy))
  if (params.confirmedBy !== undefined) qs.set('confirmedBy', String(params.confirmedBy))
  const query = qs.toString()
  return api<TestObjectModel[]>(`/api/admin/mini-tom/models${query ? `?${query}` : ''}`)
}

export function getAdminTomModelReferences(id: number) {
  return api<Array<{ taskId: number; taskName: string; status: string; createdAt: string }>>(
    `/api/admin/mini-tom/models/${id}/references`
  )
}

export function confirmAdminTomModel(id: number) {
  return api<TestObjectModel>(`/api/admin/mini-tom/models/${id}/confirm`, { method: 'POST' })
}

export function rejectAdminTomModel(id: number, reason?: string) {
  return api<TestObjectModel>(`/api/admin/mini-tom/models/${id}/reject`, {
    method: 'POST',
    body: reason ? JSON.stringify({ reason }) : JSON.stringify({}),
  })
}

export function deprecateAdminTomModel(id: number, reason?: string) {
  return api<TestObjectModel>(`/api/admin/mini-tom/models/${id}/deprecate`, {
    method: 'POST',
    body: reason ? JSON.stringify({ reason }) : JSON.stringify({}),
  })
}

export function restoreAdminTomModel(id: number) {
  return api<TestObjectModel>(`/api/admin/mini-tom/models/${id}/restore`, {
    method: 'POST',
    body: JSON.stringify({}),
  })
}

export function confirmTomCandidate(id: number) {
  return api<TestObjectModel>(`/api/mini-tom/candidates/${id}/confirm`, { method: 'POST' })
}

export function upgradeTomCandidate(id: number) {
  return api<TestObjectModel>(`/api/mini-tom/candidates/${id}/upgrade`, { method: 'POST' })
}

export function rejectTomCandidate(id: number, reason?: string) {
  return api<TestObjectModel>(`/api/mini-tom/candidates/${id}/reject`, { method: 'POST', body: reason ? JSON.stringify({ reason }) : undefined })
}

export function extractTomFromFormalCases(projectId: number) {
  return api<{ candidates: number; relations: number; unchanged: number; skipped: number }>(
    `/api/mini-tom/extract/from-formal-cases?projectId=${projectId}`, { method: 'POST' }
  )
}

export interface HistoricalCaseImportInput {
  title: string; module?: string; precondition?: string; steps?: string; expectedResult?: string;
  priority?: string; caseType?: string; designMethod?: string;
}

export interface HistoricalCaseImportResult {
  importId: number; imported: number; formalImported: number; eligible: number; skipped: number;
  extraction: { candidates: number; relations: number; unchanged: number; skipped: number };
}

export interface HistoricalCaseImportBatch {
  id: number; projectId: number; batchName: string; businessDomain: string | null;
  sourceFormat: string; itemCount: number; eligibleCount: number; status: string; createdAt: string;
}

export function importHistoricalCases(body: { projectId: number; batchName?: string; businessDomain?: string; cases: HistoricalCaseImportInput[] }) {
  return api<HistoricalCaseImportResult>('/api/mini-tom/historical-case-imports', { method: 'POST', body: JSON.stringify(body) })
}

export async function uploadHistoricalCases(projectId: number, file: File, batchName?: string, businessDomain?: string): Promise<HistoricalCaseImportResult> {
  const formData = new FormData()
  formData.append('projectId', String(projectId))
  formData.append('file', file)
  if (batchName) formData.append('batchName', batchName)
  if (businessDomain) formData.append('businessDomain', businessDomain)
  return requestJson<HistoricalCaseImportResult>('/api/mini-tom/historical-case-imports/upload', {
    method: 'POST', body: formData,
  })
}

export function listHistoricalCaseImports(projectId: number) {
  return api<HistoricalCaseImportBatch[]>(`/api/mini-tom/historical-case-imports?projectId=${projectId}`)
}

export function listTomRelationCandidates(projectId: number) {
  return api<TomRelationCandidate[]>(`/api/mini-tom/relation-candidates?projectId=${projectId}`)
}

export function proposeManualTomRelation(projectId: number, command: {
  fromModelId: number; relationType: string; toModelId: number; evidenceText: string
}) {
  return api<TomRelationCandidate>(`/api/mini-tom/relation-candidates/manual?projectId=${projectId}`, {
    method: 'POST', body: JSON.stringify(command),
  })
}

export function confirmTomRelationCandidate(projectId: number, id: number) {
  return api<TomRelationCandidate>(`/api/mini-tom/relation-candidates/${id}/confirm?projectId=${projectId}`, { method: 'POST' })
}

export function rejectTomRelationCandidate(projectId: number, id: number, reason?: string) {
  return api<TomRelationCandidate>(`/api/mini-tom/relation-candidates/${id}/reject?projectId=${projectId}`, {
    method: 'POST', body: reason ? JSON.stringify({ reason }) : undefined,
  })
}

export function listTomAliasCandidates(projectId: number) {
  return api<TomAliasCandidate[]>(`/api/mini-tom/alias-candidates?projectId=${projectId}`)
}

export function confirmTomAliasCandidate(projectId: number, id: number) {
  return api<void>(`/api/mini-tom/alias-candidates/${id}/confirm?projectId=${projectId}`, { method: 'POST' })
}

export function rejectTomAliasCandidate(projectId: number, id: number, reason?: string) {
  return api<void>(`/api/mini-tom/alias-candidates/${id}/reject?projectId=${projectId}`, {
    method: 'POST', body: reason ? JSON.stringify({ reason }) : undefined,
  })
}

export function listTomImpactCandidates(projectId: number) {
  return api<TomImpactCandidate[]>(`/api/mini-tom/impact-candidates?projectId=${projectId}`)
}

export function confirmTomImpactCandidate(projectId: number, id: number) {
  return api<void>(`/api/mini-tom/impact-candidates/${id}/confirm?projectId=${projectId}`, { method: 'POST' })
}

export function rejectTomImpactCandidate(projectId: number, id: number, reason?: string) {
  return api<void>(`/api/mini-tom/impact-candidates/${id}/reject?projectId=${projectId}`, {
    method: 'POST', body: reason ? JSON.stringify({ reason }) : undefined,
  })
}

export function buildTestScope(body: { projectId: number; requirementText: string; modelConfigId?: number }) {
  return api<any>('/api/mini-tom/build-test-scope', { method: 'POST', body: JSON.stringify(body) })
}

export function importManual(body: { projectId: number; docTitle: string; businessDomain?: string; markdownContent: string; modelConfigId?: number }) {
  return api<any>('/api/mini-tom/import/manual', { method: 'POST', body: JSON.stringify(body) })
}

// ===== Trace Group API =====
export interface TraceGroup {
  id: number
  projectId: number
  userId: number
  profileId: number | null
  groupName: string
  description: string
  status: string
  startedAt: string | null
  stoppedAt: string | null
  timezone: string | null
  createdAt: string
  updatedAt: string
}

export function listTraceGroups(projectId: number) {
  return api<TraceGroup[]>(`/api/trace/projects/${projectId}/trace-groups`)
}

export function getTraceGroup(projectId: number, groupId: number) {
  return api<TraceGroup>(`/api/trace/projects/${projectId}/trace-groups/${groupId}`)
}

export function createTraceGroup(projectId: number, body: { groupName: string; description?: string; profileId?: number }) {
  return api<TraceGroup>(`/api/trace/projects/${projectId}/trace-groups`, { method: 'POST', body: JSON.stringify(body) })
}

export function deleteTraceGroup(projectId: number, groupId: number) {
  return api<void>(`/api/trace/projects/${projectId}/trace-groups/${groupId}`, { method: 'DELETE' })
}

export function startTraceGroup(groupId: number) {
  return api<TraceGroup>(`/api/trace/groups/${groupId}/start`, { method: 'POST' })
}

export function stopTraceGroup(groupId: number) {
  return api<TraceGroup>(`/api/trace/groups/${groupId}/stop`, { method: 'POST' })
}

// ===== Cases API =====
export interface GeneratedCase {
  id: number
  projectId: number
  userId: number
  traceGroupId: number
  traceSessionId: number | null
  issueClipId: number | null
  caseType: string
  caseTitle: string
  moduleName: string | null
  precondition: string | null
  steps: string
  expectedResult: string
  priority: string
  caseScope: string | null
  caseStatus: string
  sourceRefsJson: string | null
  modelConfigId: number | null
  promptSnapshot: string | null
  createdAt: string
  updatedAt: string
}

export interface FormalCase {
  id: number
  projectId: number
  caseNo: string
  caseTitle: string
  moduleName: string | null
  precondition: string | null
  steps: string
  expectedResult: string
  priority: string
  caseType: string
  scenarioType: string
  designMethod: string | null
  caseScope: string | null
  caseStatus: string
  submittedBy: number | null
  submittedAt: string | null
  exportedAt: string | null
  sourceTraceGroupId: number | null
  sourceTraceSessionId: number | null
  sourceIssueClipId: number | null
  importSourceType: string | null
  importSourceRefId: number | null
  createdAt: string
  updatedAt: string
}

export interface CasePage<T> {
  items: T[]
  total: number
  page: number
  size: number
  moduleOptions?: string[]
}

export type CaseLibraryFilters = {
  keyword?: string
  modules?: string[]
  priorities?: string[]
  statuses?: string[]
  sources?: string[]
  scenarioTypes?: string[]
}

function buildCaseLibraryQuery(page: number, size: number, filters: CaseLibraryFilters = {}) {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  if (filters.keyword?.trim()) query.set('keyword', filters.keyword.trim())
  filters.modules?.forEach(value => query.append('modules', value))
  filters.priorities?.forEach(value => query.append('priorities', value))
  filters.statuses?.forEach(value => query.append('statuses', value))
  filters.sources?.forEach(value => query.append('sources', value))
  filters.scenarioTypes?.forEach(value => query.append('scenarioTypes', value))
  return query.toString()
}

export function listGeneratedCases(projectId: number) {
  return api<GeneratedCase[]>(`/api/trace/projects/${projectId}/generated-cases`)
}

export function listFormalCases(projectId: number) {
  return api<FormalCase[]>(`/api/trace/projects/${projectId}/formal-cases`)
}

export function listFormalCasesPage(projectId: number, page = 0, size = 50, filters: CaseLibraryFilters = {}) {
  return api<CasePage<FormalCase>>(`/api/trace/projects/${projectId}/formal-cases/page?${buildCaseLibraryQuery(page, size, filters)}`)
}

export function getFormalCase(projectId: number, caseId: number) {
  return api<FormalCase>(`/api/trace/projects/${projectId}/formal-cases/${caseId}`)
}

export function deleteFormalCase(projectId: number, caseId: number) {
  return api<void>(`/api/trace/projects/${projectId}/formal-cases/${caseId}`, { method: 'DELETE' })
}

export function deleteFormalCases(projectId: number, caseIds: number[]) {
  return api<{ affectedCount: number }>(`/api/trace/projects/${projectId}/formal-cases/batch/delete`, {
    method: 'POST',
    body: JSON.stringify({ caseIds }),
  })
}

async function exportCases(url: string, caseIds?: number[]) {
  return requestBlob(url, {
    method: 'POST',
    body: JSON.stringify(caseIds || []),
  })
}

export function exportFormalCasesToXmind(projectId: number, caseIds?: number[]) {
  return exportCases(`/api/projects/${projectId}/export/formal-cases`, caseIds)
}

export function exportFormalCasesToExcel(projectId: number, caseIds?: number[]) {
  return exportCases(`/api/projects/${projectId}/export/formal-cases/excel`, caseIds)
}

export function exportLocalCasesToXmind(projectId: number, caseIds?: number[]) {
  return exportCases(`/api/projects/${projectId}/export/local-cases`, caseIds)
}

export function exportLocalCasesToExcel(projectId: number, caseIds?: number[]) {
  return exportCases(`/api/projects/${projectId}/export/local-cases/excel`, caseIds)
}

export function submitGeneratedCase(generatedCaseId: number) {
  return api<FormalCase>(`/api/trace/generated-cases/${generatedCaseId}/submit`, { method: 'POST' })
}

// ===== Worker Device API =====
export interface WorkerDevice {
  id: number
  userId: number
  deviceName: string
  platform: string
  arch: string
  workerVersion: string
  protocolVersion: string
  bindStatus: string
  lastSeenAt: string | null
  createdAt: string
  updatedAt: string
}

export function listWorkerDevices() {
  return api<WorkerDevice[]>('/api/trace/devices')
}

export function revokeWorkerDevice(deviceId: number) {
  return api<void>(`/api/trace/devices/${deviceId}/revoke`, { method: 'POST' })
}

export interface BindCodeResponse {
  code: string
  expiresAt: string
}

export function createBindCode() {
  return api<BindCodeResponse>('/api/trace/devices/bind-codes', { method: 'POST' })
}

// ===== Admin Asset API =====
export interface AssetInventoryRow {
  assetType: string
  scope: string
  status: string
  sourceType: string | null
  count: number
}

export function listAssetInventory(projectId?: number, signal?: AbortSignal) {
  const query = projectId === undefined ? '' : `?projectId=${encodeURIComponent(String(projectId))}`
  return api<AssetInventoryRow[]>(`/api/admin/assets/inventory${query}`, { signal })
}

type AdminAssetParams = {
  projectId?: number
  status?: string
}

function buildAssetQuery(params?: AdminAssetParams & { moduleName?: string }) {
  const qs = new URLSearchParams()
  if (params?.projectId) qs.set('projectId', String(params.projectId))
  if (params?.status) qs.set('status', params.status)
  if (params?.moduleName) qs.set('moduleName', params.moduleName)
  const query = qs.toString()
  return query ? `?${query}` : ''
}

export function listAdminFormalCases(params?: AdminAssetParams & { moduleName?: string }) {
  return api<any[]>(`/api/admin/assets/formal-cases${buildAssetQuery(params)}`)
}

export interface CatalogAsset {
  assetType: string
  id: number
  projectId: number
  title: string | null
  scope: string | null
  lifecycleStatus: string | null
  reviewStatus: string | null
  sourceType: string | null
  sourceId: number | null
  parentId: number | null
  createdAt: string | null
}

export function listAssetCatalog(filters: {
  projectId?: number
  type?: string
  status?: string
  keyword?: string
  page?: number
  size?: number
}) {
  const query = new URLSearchParams()
  if (filters.projectId) query.set('projectId', String(filters.projectId))
  if (filters.type) query.set('type', filters.type)
  if (filters.status) query.set('status', filters.status)
  if (filters.keyword) query.set('keyword', filters.keyword)
  query.set('page', String(filters.page || 1))
  query.set('size', String(filters.size || 50))
  return api<PageResult<CatalogAsset>>(`/api/admin/assets/catalog?${query}`)
}

export function listAdminTestPoints(params?: AdminAssetParams) {
  return api<any[]>(`/api/admin/assets/test-points${buildAssetQuery(params)}`)
}

export function listAdminKnowledge(params?: AdminAssetParams) {
  return api<any[]>(`/api/admin/assets/knowledge${buildAssetQuery(params)}`)
}

export function listAdminSummaries(params?: AdminAssetParams) {
  return api<any[]>(`/api/admin/assets/summaries${buildAssetQuery(params)}`)
}

/** Project-facing trace summaries. Unlike the admin asset endpoint, this endpoint is scoped by project access. */
export function listProjectSummaries(projectId: number) {
  return api<any[]>(`/api/trace/projects/${projectId}/summaries`)
}

export function listAdminSkills(params?: AdminAssetParams) {
  return api<any[]>(`/api/admin/assets/skills${buildAssetQuery(params)}`)
}

export function listAdminTools(params?: AdminAssetParams) {
  return api<any[]>(`/api/admin/assets/tools${buildAssetQuery(params)}`)
}

export interface ProjectSkillAsset {
  id: number
  projectId: number
  skillName: string
  applicableScene: string | null
  flowSteps: string | null
  sourceType: string | null
  sourceId: number | null
  status: string
  deprecatedReason: string | null
  createdAt: string
  updatedAt: string
}

export interface ProjectToolAsset {
  id: number
  projectId: number
  toolName: string
  operationSteps: string | null
  sourceType: string | null
  sourceId: number | null
  status: string
  deprecatedReason: string | null
  createdAt: string
  updatedAt: string
}

export function listProjectSkills(projectId: number) {
  return api<ProjectSkillAsset[]>(`/api/trace/projects/${projectId}/skills`)
}

export function updateProjectSkill(projectId: number, skillId: number, payload: Pick<ProjectSkillAsset, 'skillName' | 'applicableScene' | 'flowSteps'>) {
  return api<ProjectSkillAsset>(`/api/trace/projects/${projectId}/skills/${skillId}`, {
    method: 'PATCH', body: JSON.stringify(payload),
  })
}

export function listProjectTools(projectId: number) {
  return api<ProjectToolAsset[]>(`/api/trace/projects/${projectId}/tools`)
}

export function updateProjectTool(projectId: number, toolId: number, payload: Pick<ProjectToolAsset, 'toolName' | 'operationSteps'>) {
  return api<ProjectToolAsset>(`/api/trace/projects/${projectId}/tools/${toolId}`, {
    method: 'PATCH', body: JSON.stringify(payload),
  })
}

export function deprecateAdminAsset(type: string, id: number, reason?: string) {
  return api<void>(`/api/admin/assets/${type}/${id}/deprecate`, {
    method: 'POST',
    body: reason ? JSON.stringify({ reason }) : JSON.stringify({}),
  })
}

export function restoreAdminAsset(type: string, id: number) {
  return api<void>(`/api/admin/assets/${type}/${id}/restore`, {
    method: 'POST',
    body: JSON.stringify({}),
  })
}

// ===== Candidate Review API =====
export function listCandidates(params?: { type?: string; projectId?: number; status?: string }) {
  const qs = new URLSearchParams()
  if (params?.type) qs.set('type', params.type)
  if (params?.projectId) qs.set('projectId', String(params.projectId))
  if (params?.status) qs.set('status', params.status)
  const query = qs.toString()
  return api<any[]>(`/api/admin/candidates${query ? '?' + query : ''}`)
}

export function listCandidatesPage(params: {
  type?: string; projectId?: number; status?: string; keyword?: string; page: number; size?: number
}) {
  const qs = new URLSearchParams()
  if (params.type) qs.set('type', params.type)
  if (params.projectId) qs.set('projectId', String(params.projectId))
  if (params.status) qs.set('status', params.status)
  if (params.keyword) qs.set('keyword', params.keyword)
  qs.set('page', String(params.page))
  qs.set('size', String(params.size || 50))
  return api<PageResult<any>>(`/api/admin/candidates/page?${qs}`)
}

export function confirmCandidate(type: string, id: number) {
  return api<void>(`/api/admin/candidates/${type}/${id}/confirm`, { method: 'POST' })
}

export function rejectCandidate(type: string, id: number, reason?: string) {
  return api<void>(`/api/admin/candidates/${type}/${id}/reject`, { method: 'POST', body: reason ? JSON.stringify({ reason }) : undefined })
}

export function batchConfirmCandidates(items: { type: string; id: number }[]) {
  return api<number>('/api/admin/candidates/batch-confirm', {
    method: 'POST',
    body: JSON.stringify({ items }),
  })
}

export function batchRejectCandidates(items: { type: string; id: number }[], reason?: string) {
  return api<number>('/api/admin/candidates/batch-reject', {
    method: 'POST',
    body: JSON.stringify({ items, reason }),
  })
}

export type AutomationAsset = {
  id: number; projectId: number; caseKind: string; caseId: number; caseNo: string; assetType: 'UI' | 'API';
  status: string; currentVersion: number; latestVersion: number; healCount: number;
  blockedReason?: string | null; createdBy: number;
  sourceCaseVersion: number; caseVersion: number; caseStale: boolean; caseMissing: boolean;
  reviewStatus: string;
  archiveReason?: string | null; archivedBy?: number | null; archivedAt?: string | null;
}

export type AutomationRun = {
  id: number; assetId: number; versionNo: number; result: string; durationMs: number;
  evidenceSessionId?: number | null; apiEvidenceId?: number | null;
  logEvidence?: { ruleId: string; purpose: string; result: string; reason: string; serviceName?: string; instances: unknown[] }[] | null;
  failureStepIndex?: number | null; failureReason?: string | null;
}

export type AutomationApiEvidence = {
  id: number; assetId: number; versionNo: number; result: string;
  evidence: { steps: unknown[]; assertions: unknown[]; targetOrigin?: string };
}

export type AutomationVersion = {
  assetId: number; versionNo: number; mapping: Record<string, unknown>; status: string;
  changeReason: string; revisionKind: string; sourceRunId?: number | null; verifiedRunId?: number | null;
  reviewStatus: string; reviewedBy?: number | null; reviewReason?: string | null;
}

export type UiCoverageVerdict = {
  taskId: number; taskKey: string; targetOrigin: string; inventoryStatus: string;
  result: string; requiredCount: number; coveredCount: number;
  rules: { ruleCode: string; ruleVersion: number; checkKind: string;
    applicability: string; reason?: string | null; status: string }[];
  obligations: { id: number; fieldKey: string; pagePath: string; fieldLabel: string;
    ruleCode: string; sourceBasis: string; applicability: string; positiveRunId?: number | null;
    negativeRunId?: number | null; status: string }[];
}

export type UiExecutionRule = { id: number; projectId: number; ruleCode: string;
  versionNo: number; title: string; checkKind: string; status: string };

export function listPendingUiExecutionRules(projectId: number) {
  return api<UiExecutionRule[]>(`/api/projects/${projectId}/ui-coverage/rules/pending`)
}
export function approveUiExecutionRule(projectId: number, ruleId: number) {
  return api<UiExecutionRule>(`/api/projects/${projectId}/ui-coverage/rules/${ruleId}/approve`, { method: 'POST' })
}

export function listUiCoverageTasks(projectId: number) {
  return api<UiCoverageVerdict[]>(`/api/projects/${projectId}/ui-coverage`)
}
export function confirmUiCoverageTask(projectId: number, taskId: number) {
  return api<UiCoverageVerdict>(`/api/projects/${projectId}/ui-coverage/${taskId}/confirm`, { method: 'POST' })
}

export function listAutomationAssets(projectId: number) {
  return api<AutomationAsset[]>(`/api/projects/${projectId}/automation-assets`)
}
export function getAutomationVersion(projectId: number, assetId: number, versionNo: number) {
  return api<AutomationVersion>(`/api/projects/${projectId}/automation-assets/${assetId}/versions/${versionNo}`)
}
export function listAutomationRuns(projectId: number, assetId: number) {
  return api<AutomationRun[]>(`/api/projects/${projectId}/automation-assets/${assetId}/runs`)
}
export function getAutomationApiEvidence(projectId: number, assetId: number, evidenceId: number) {
  return api<AutomationApiEvidence>(`/api/projects/${projectId}/automation-assets/${assetId}/api-evidence/${evidenceId}`)
}
export function proposeAutomationVersion(projectId: number, assetId: number, input: {
  requestKey: string; expectedVersion: number; changeReason: string; mapping: Record<string, unknown>;
}) {
  return api<AutomationVersion>(`/api/projects/${projectId}/automation-assets/${assetId}/versions`, {
    method: 'POST', body: JSON.stringify(input),
  })
}
export function unblockAutomationAsset(projectId: number, assetId: number) {
  return api<AutomationAsset>(`/api/projects/${projectId}/automation-assets/${assetId}/unblock`, { method: 'POST' })
}
export function publishAutomationAsset(projectId: number, assetId: number) {
  return api<number>(`/api/projects/${projectId}/automation-assets/${assetId}/publish-reusable`, { method: 'POST' })
}
export function approveAutomationVersion(projectId: number, assetId: number, versionNo: number) {
  return api<AutomationVersion>(`/api/projects/${projectId}/automation-assets/${assetId}/versions/${versionNo}/approve`, { method: 'POST' })
}
export function batchApproveAutomationVersions(projectId: number, targets: { assetId: number; versionNo: number }[]) {
  return api<AutomationVersion[]>(`/api/projects/${projectId}/automation-assets/batch-approve`, {
    method: 'POST', body: JSON.stringify({ targets }),
  })
}
export function discardAutomationCandidate(projectId: number, assetId: number, versionNo: number, reason: string) {
  return api<AutomationVersion>(`/api/projects/${projectId}/automation-assets/${assetId}/versions/${versionNo}/discard`, {
    method: 'POST', body: JSON.stringify({ reason }),
  })
}
export function archiveAutomationAsset(projectId: number, assetId: number, reason: string) {
  return api<AutomationAsset>(`/api/projects/${projectId}/automation-assets/${assetId}/archive`, {
    method: 'POST', body: JSON.stringify({ reason }),
  })
}

// ===== Controlled Scan API =====
export function listBuiltinScanSources() {
  return api<any[]>('/api/admin/controlled-scans/sources')
}

export function listScanJobs(projectId?: number) {
  const qs = projectId ? `?projectId=${projectId}` : ''
  return api<any[]>(`/api/admin/controlled-scans/jobs${qs}`)
}

export function listScanProfiles(projectId?: number) {
  const qs = projectId ? `?projectId=${projectId}` : ''
  return api<any[]>(`/api/admin/controlled-scans/profiles${qs}`)
}

export function runScan(body: { projectId: number; modelConfigId?: number; scanMode?: string; sourceKeys?: string[]; urls?: string[] }) {
  return api<any>('/api/admin/controlled-scans/run', { method: 'POST', body: JSON.stringify(body) })
}

// ===== Generation Session API =====
export interface GenerationSession {
  id: number
  projectId: number
  sessionTitle: string
  status: string
  currentStage: string | null
  modelConfigId: number | null
  promptTemplateId: number | null
  useMiniTom: boolean
  tomMode: 'DIRECT' | 'PROJECT_TOM' | 'PROJECT_AND_SYSTEM_TOM'
  generationDepth: 'BASIC' | 'STANDARD' | 'DEEP'
  latestAnalysisVersion: number | null
  executionTaskId: number | null
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface GenerationMessage {
  id: number
  sessionId: number
  role: string
  content: string
  structuredPayload: any
  analysisVersion: number | null
  stage: string | null
  createdAt: string
}

export function listGenerationSessions(projectId: number, params?: { page?: number; size?: number; status?: string; keyword?: string }) {
  const qs = new URLSearchParams()
  if (params?.page) qs.set('page', String(params.page))
  if (params?.size) qs.set('size', String(params.size))
  if (params?.status) qs.set('status', params.status)
  if (params?.keyword) qs.set('keyword', params.keyword)
  const query = qs.toString()
  return api<PageResult<GenerationSession>>(`/api/projects/${projectId}/generation/sessions${query ? '?' + query : ''}`)
}

export function createGenerationSession(projectId: number, body: { sessionTitle?: string; modelConfigId?: number; promptTemplateId?: number; useMiniTom?: boolean; tomMode?: 'DIRECT' | 'PROJECT_TOM' | 'PROJECT_AND_SYSTEM_TOM'; generationDepth?: 'BASIC' | 'STANDARD' | 'DEEP' }) {
  return api<GenerationSession>(`/api/projects/${projectId}/generation/sessions`, { method: 'POST', body: JSON.stringify(body) })
}

export function updateGenerationSession(
  projectId: number,
  sessionId: number,
  body: { sessionTitle?: string; modelConfigId?: number | null; promptTemplateId?: number | null; useMiniTom?: boolean; tomMode?: 'DIRECT' | 'PROJECT_TOM' | 'PROJECT_AND_SYSTEM_TOM'; generationDepth?: 'BASIC' | 'STANDARD' | 'DEEP' }
) {
  return api<void>(`/api/projects/${projectId}/generation/sessions/${sessionId}`, { method: 'PATCH', body: JSON.stringify(body) })
}

export function archiveGenerationSession(projectId: number, sessionId: number) {
  return api<void>(`/api/projects/${projectId}/generation/sessions/${sessionId}`, { method: 'DELETE' })
}

export function listGenerationMessages(projectId: number, sessionId: number) {
  return api<GenerationMessage[]>(`/api/projects/${projectId}/generation/sessions/${sessionId}/messages`)
}

export function sendGenerationMessage(projectId: number, sessionId: number, content: string) {
  return api<{ newMessages: GenerationMessage[]; analysis: any }>(`/api/projects/${projectId}/generation/sessions/${sessionId}/messages`, { method: 'POST', body: JSON.stringify({ content }) })
}

export function generateIncremental(projectId: number, sessionId: number, selectedDraftIds: number[]) {
  return api<CaseDraft[]>(`/api/projects/${projectId}/generation/sessions/${sessionId}/generate-incremental`, { method: 'POST', body: JSON.stringify({ selectedDraftIds }) })
}

export interface CaseDraft {
  id: number
  sessionId: number
  analysisId?: number | null
  analysisVersion?: number | null
  caseTitle: string
  moduleName: string | null
  precondition: string | null
  steps: string
  expectedResult: string
  priority: string
  caseType: string
  status: string
  sourceRefsJson: string | null
  qualityStatus: string | null
  createdAt: string
  updatedAt: string
}

export function listGenerationDrafts(projectId: number, sessionId: number) {
  return api<CaseDraft[]>(`/api/projects/${projectId}/generation/sessions/${sessionId}/drafts`)
}

export type AsyncGenerationTaskType = 'TEST_CASE_GENERATION' | 'TEST_POINT_GENERATION' | 'TRACE_SUMMARY' | 'REQUIREMENT_ANALYSIS' | 'REQUIREMENT_SCOPE_CONTINUATION' | 'TEST_POINT_SCOPE_CONTINUATION' | 'INCREMENTAL_CASE_GENERATION'
export type AsyncGenerationTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMEOUT' | 'CANCELED'

export interface AsyncGenerationTask {
  taskId: number
  taskType: string
  status: AsyncGenerationTaskStatus
  errorCode: string | null
  errorMessage: string | null
  draftCount: number
  createdAt: string
  updatedAt: string
  stages?: AsyncGenerationTaskStage[]
}

export interface AsyncGenerationTaskStage {
  code: string
  label: string
  status: AsyncGenerationTaskStatus | 'PENDING'
  errorCode?: string | null
  errorMessage?: string | null
  updatedAt?: string | null
}

export interface CreateAsyncGenerationTaskRequest {
  taskType: AsyncGenerationTaskType
  taskName?: string
  requirementText?: string
  modelConfigId?: number
  promptSnapshot?: string
  generationMode?: string
  useMiniTom?: boolean
  promptVersion?: number
  traceGroupId?: number
  traceSessionId?: number
  issueClipId?: number
  summaryScope?: string
}

export function createAsyncGenerationTask(projectId: number, body: CreateAsyncGenerationTaskRequest) {
  return api<AsyncGenerationTask>(`/api/projects/${projectId}/generation/tasks`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function getAsyncGenerationTask(projectId: number, taskId: number) {
  return api<AsyncGenerationTask>(`/api/projects/${projectId}/generation/tasks/${taskId}`)
}

export function retryAsyncGenerationTask(projectId: number, taskId: number) {
  return api<AsyncGenerationTask>(`/api/projects/${projectId}/generation/tasks/${taskId}/retry`, {
    method: 'POST',
  })
}

export function cancelAsyncGenerationTask(projectId: number, taskId: number) {
  return api<AsyncGenerationTask>(`/api/projects/${projectId}/generation/tasks/${taskId}/cancel`, {
    method: 'POST',
  })
}

export function startSessionCaseGenerationTask(projectId: number, sessionId: number, content?: string) {
  return api<AsyncGenerationTask>(`/api/projects/${projectId}/generation/sessions/${sessionId}/generate-async`, {
    method: 'POST',
    body: JSON.stringify({ content: content || '生成用例' }),
  })
}

export function startSessionRequirementAnalysisTask(projectId: number, sessionId: number, content?: string) {
  return api<AsyncGenerationTask>(`/api/projects/${projectId}/generation/sessions/${sessionId}/analysis-async`, {
    method: 'POST',
    body: JSON.stringify({ content: content || '使用 TOM' }),
  })
}

export function startSessionIncrementalGenerationTask(projectId: number, sessionId: number, selectedDraftIds: number[]) {
  return api<AsyncGenerationTask>(`/api/projects/${projectId}/generation/sessions/${sessionId}/generate-incremental-async`, {
    method: 'POST',
    body: JSON.stringify({ selectedDraftIds }),
  })
}

export interface AnalysisResult {
  requirement_understanding?: string
  business_domain?: string
  affected_modules?: string[]
  affected_pages?: string[]
  affected_fields?: string[]
  affected_flows?: string[]
  affected_roles?: string[]
  conflicts?: string[]
  uncertain_items?: string[]
  out_of_scope?: string[]
}

export interface TestPoint {
  id?: string
  title?: string
  description?: string
  test_dimension?: string
  related_module?: string
  related_page?: string
  related_flow?: string
  source_basis?: string[]
  confidence?: number
  needs_confirmation?: boolean
  scope_recommendation?: 'IN_SCOPE' | 'REFERENCE_ONLY' | 'OUT_OF_SCOPE' | 'NEEDS_CONFIRMATION'
  scope_reason?: string
  generation_scope?: 'GENERATE' | 'REFERENCE_ONLY' | 'EXCLUDED'
  scope_decision_source?: 'AI_RECOMMENDATION' | 'USER'
}

export interface ClarificationQuestion {
  question?: string
  reason?: string
  impact?: string
}

export interface Assumption {
  assumption?: string
  reason?: string
}

export interface RequirementAnalysis {
  id: number
  sessionId: number
  version: number
  subVersion: number
  requirementText: string | null
  analysisResult: string | AnalysisResult | null
  tomScopeSnapshot: any
  clarificationQuestions: string | null
  clarificationAnswers: any
  assumptions: string | null
  testPoints: string | null
  affectedCases: string | null
  changeScope: string | null
  newCasesNeeded: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export function getLatestGenerationAnalysis(projectId: number, sessionId: number) {
  return api<RequirementAnalysis>(`/api/projects/${projectId}/generation/sessions/${sessionId}/analysis`)
}

export function listGenerationAnalyses(projectId: number, sessionId: number) {
  return api<RequirementAnalysis[]>(`/api/projects/${projectId}/generation/sessions/${sessionId}/analyses`)
}

export interface TestPointScopeDecision {
  testPointId: string
  disposition: 'GENERATE' | 'REFERENCE_ONLY' | 'EXCLUDED'
  reason?: string
  edit?: TestPointContentEdit
}

export interface TestPointContentEdit {
  title?: string
  description?: string
  relatedModule?: string
  relatedPage?: string
  relatedFlow?: string
  pointType?: string
  priorityHint?: string
}

export interface RequirementScopeDecision {
  requirementAtomId: string
  disposition: 'GENERATE' | 'REFERENCE_ONLY' | 'EXCLUDED'
  reason?: string
}

export function confirmGenerationRequirementScope(
  projectId: number,
  sessionId: number,
  version: number,
  decisions: RequirementScopeDecision[],
) {
  return api<AsyncGenerationTask>(`/api/projects/${projectId}/generation/sessions/${sessionId}/analysis/${version}/requirement-scope`, {
    method: 'PUT',
    body: JSON.stringify({ decisions }),
  })
}

export function confirmGenerationTestPointScope(
  projectId: number,
  sessionId: number,
  version: number,
  decisions: TestPointScopeDecision[],
) {
  return api<AsyncGenerationTask>(`/api/projects/${projectId}/generation/sessions/${sessionId}/analysis/${version}/test-point-scope`, {
    method: 'PUT',
    body: JSON.stringify({ decisions }),
  })
}

export interface LocalCaseDraft {
  id: number
  taskId: number
  projectId: number
  caseNo: string
  caseTitle: string
  projectName: string
  moduleName: string | null
  precondition: string | null
  steps: string
  expectedResult: string
  priority: string
  caseType: string | null
  scenarioType: string | null
  designMethod: string | null
  sourceRefsJson: string | null
  caseScope: string | null
  caseStatus: string
  versionNo: number
  createdBy: number
  createdAt: string
  updatedAt: string
  sessionId: number | null
  sourceType: string
}

export function listLocalCases(projectId: number) {
  return api<LocalCaseDraft[]>(`/api/projects/${projectId}/generation/local-cases`)
}

export function listLocalCasesPage(projectId: number, page = 0, size = 50, filters: CaseLibraryFilters = {}) {
  return api<CasePage<LocalCaseDraft>>(`/api/projects/${projectId}/generation/local-cases/page?${buildCaseLibraryQuery(page, size, filters)}`)
}

export function getLocalCase(projectId: number, draftId: number) {
  return api<LocalCaseDraft>(`/api/projects/${projectId}/generation/local-cases/${draftId}`)
}

export function duplicateLocalCase(projectId: number, draftId: number) {
  return api<LocalCaseDraft>(`/api/projects/${projectId}/generation/local-cases/${draftId}/duplicate`, {
    method: 'POST', body: JSON.stringify({}),
  })
}

export function updateLocalCase(
  projectId: number,
  draftId: number,
  body: { caseTitle?: string; moduleName?: string; precondition?: string; steps?: string; expectedResult?: string; priority?: string }
) {
  return api<LocalCaseDraft>(`/api/projects/${projectId}/generation/local-cases/${draftId}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })
}

export function confirmLocalCase(projectId: number, draftId: number) {
  return api<LocalCaseDraft>(`/api/projects/${projectId}/generation/local-cases/${draftId}/confirm`, {
    method: 'POST',
    body: JSON.stringify({}),
  })
}

export function batchConfirmLocalCases(projectId: number, draftIds: number[]) {
  return api<{ affectedCount: number }>(`/api/projects/${projectId}/generation/local-cases/batch/confirm`, {
    method: 'POST',
    body: JSON.stringify({ draftIds }),
  })
}

export function deprecateLocalCase(projectId: number, draftId: number) {
  return api<LocalCaseDraft>(`/api/projects/${projectId}/generation/local-cases/${draftId}/deprecate`, {
    method: 'POST',
    body: JSON.stringify({}),
  })
}

export function batchDeprecateLocalCases(projectId: number, draftIds: number[]) {
  return api<{ affectedCount: number }>(`/api/projects/${projectId}/generation/local-cases/batch/deprecate`, {
    method: 'POST',
    body: JSON.stringify({ draftIds }),
  })
}

export function submitLocalCase(projectId: number, draftId: number) {
  return api<void>(`/api/projects/${projectId}/generation/local-cases/${draftId}/submit`, {
    method: 'POST',
    body: JSON.stringify({}),
  })
}

export function batchSubmitLocalCases(projectId: number, draftIds: number[]) {
  return api<{ affectedCount: number }>(`/api/projects/${projectId}/generation/local-cases/batch/submit`, {
    method: 'POST',
    body: JSON.stringify({ draftIds }),
  })
}

// ===== Project Files API =====
export interface ProjectFileRecord {
  id: number
  projectId: number
  fileName: string
  fileType: string
  storageType: string
  contentHash: string
  createdBy: number
  createdAt: string
}

export function listProjectFiles(projectId: number) {
  return api<ProjectFileRecord[]>(`/api/projects/${projectId}/files`)
}

// ===== Domain Preset API =====
export interface DomainPreset {
  name: string
  description: string
  defaultModel: string
  scanSources: string[]
}

export function listDomainPresets() {
  return api<DomainPreset[]>('/api/domain-presets')
}

export function getDefaultDomainPreset() {
  return api<DomainPreset>('/api/domain-presets/default')
}

// ===== Business Pack API =====
export interface BusinessPack {
  id: number
  projectId: number
  packName: string
  packType: string
  businessDomain: string | null
  version: number
  status: string
  description: string | null
  sourceTypes: string | null
  itemCount: number
  confidenceAvg: number | null
  builtAt: string | null
  activatedAt: string | null
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface BusinessPackItem {
  id: number
  packId: number
  projectId: number
  itemType: string
  itemKey: string
  itemValue: string | null
  confidence: number
  sourceType: string | null
  sourceRefId: number | null
  sourceRefJson: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export function listBusinessPacks(projectId: number, status?: string) {
  const qs = status ? `?status=${status}` : ''
  return api<BusinessPack[]>(`/api/projects/${projectId}/business-packs${qs}`)
}

export function getBusinessPack(projectId: number, packId: number) {
  return api<BusinessPack>(`/api/projects/${projectId}/business-packs/${packId}`)
}

export function listBusinessPackItems(projectId: number, packId: number) {
  return api<BusinessPackItem[]>(`/api/projects/${projectId}/business-packs/${packId}/items`)
}

export function listBusinessPackItemsByProject(projectId: number, itemType?: string) {
  const qs = itemType ? `?itemType=${itemType}` : ''
  return api<BusinessPackItem[]>(`/api/projects/${projectId}/business-packs/items${qs}`)
}

export interface BusinessPackRefreshDiagnostic {
  id: number
  projectId: number
  status: string
  errorMessage: string | null
  tomCount: number
  pageProfileCount: number
  patternCount: number
  summaryCount: number
  generatedPackCount: number
  inferredRelationCount: number
  startedAt: string
  finishedAt: string
  createdAt: string
}

export function listBusinessPackRefreshDiagnostics(projectId: number) {
  return api<BusinessPackRefreshDiagnostic[]>(`/api/projects/${projectId}/business-packs/refresh-diagnostics`)
}

export function generateBusinessPacks(projectId: number) {
  return api<BusinessPack[]>(`/api/projects/${projectId}/business-packs/generate`, { method: 'POST' })
}

export function activateBusinessPack(projectId: number, packId: number) {
  return api<BusinessPack>(`/api/projects/${projectId}/business-packs/${packId}/activate`, { method: 'POST' })
}

export function deactivateBusinessPack(projectId: number, packId: number) {
  return api<BusinessPack>(`/api/projects/${projectId}/business-packs/${packId}/deactivate`, { method: 'POST' })
}

export function archiveBusinessPack(projectId: number, packId: number) {
  return api<BusinessPack>(`/api/projects/${projectId}/business-packs/${packId}/archive`, { method: 'POST' })
}

export function updateBusinessPack(projectId: number, packId: number, data: { packName?: string; description?: string }) {
  return api<BusinessPack>(`/api/projects/${projectId}/business-packs/${packId}`, {
    method: 'PATCH',
    body: JSON.stringify(data)
  })
}

export function deleteBusinessPackItem(projectId: number, packId: number, itemId: number) {
  return api<void>(`/api/projects/${projectId}/business-packs/${packId}/items/${itemId}`, { method: 'DELETE' })
}

// ===== Business Pack Snapshots =====
export interface BusinessPackSnapshot {
  id: number
  packId: number
  projectId: number
  snapshotNo: number
  packName: string
  businessDomain: string | null
  status: string
  itemCount: number
  confidenceAvg: number | null
  snapshotJson: string | null
  changeSummary: string | null
  createdBy: number
  createdAt: string
}

export function listBusinessPackSnapshots(projectId: number, packId: number) {
  return api<BusinessPackSnapshot[]>(`/api/projects/${projectId}/business-packs/${packId}/snapshots`)
}

// ===== Business Pack Relations =====
export interface BusinessPackRelation {
  id: number
  projectId: number
  sourcePackId: number
  targetPackId: number
  relationType: string
  confidence: number
  description: string | null
  sourceType: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export function listBusinessPackRelations(projectId: number, packId: number) {
  return api<BusinessPackRelation[]>(`/api/projects/${projectId}/business-packs/${packId}/relations`)
}

export function createBusinessPackRelation(projectId: number, packId: number, data: { targetPackId: number; relationType: string; description?: string }) {
  return api<BusinessPackRelation>(`/api/projects/${projectId}/business-packs/${packId}/relations`, {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

// ===== Business Pack Bindings =====
export interface RuleBinding {
  id: number
  packId: number
  projectId: number
  ruleType: string
  ruleRef: string
  ruleConfigJson: string | null
  confidence: number
  status: string
  createdAt: string
  updatedAt: string
}

export interface ScanBinding {
  id: number
  packId: number
  projectId: number
  scanProfileId: number
  routePath: string | null
  pageLabel: string | null
  confidence: number
  status: string
  createdAt: string
  updatedAt: string
}

export interface TomBinding {
  id: number
  packId: number
  projectId: number
  tomId: number
  tomName: string | null
  tomType: string | null
  confidence: number
  status: string
  createdAt: string
  updatedAt: string
}

export function listRuleBindings(projectId: number, packId: number) {
  return api<RuleBinding[]>(`/api/projects/${projectId}/business-packs/${packId}/bindings/rules`)
}

export function listScanBindings(projectId: number, packId: number) {
  return api<ScanBinding[]>(`/api/projects/${projectId}/business-packs/${packId}/bindings/scans`)
}

export function listTomBindings(projectId: number, packId: number) {
  return api<TomBinding[]>(`/api/projects/${projectId}/business-packs/${packId}/bindings/toms`)
}

// ===== Business Pack Transitions =====
export function getAvailableTransitions(projectId: number, packId: number) {
  return api<string[]>(`/api/projects/${projectId}/business-packs/${packId}/transitions`)
}

// ===== Business Pack Consumption Logs =====
export interface ConsumptionLog {
  id: number
  packId: number
  projectId: number
  consumerType: string
  consumerRef: string | null
  signalCount: number
  consumedAt: string
}

export function listConsumptionLogs(projectId: number, packId: number) {
  return api<ConsumptionLog[]>(`/api/projects/${projectId}/business-packs/${packId}/consumption`)
}

export function listConsumptionLogsByProject(projectId: number, consumerType?: string) {
  const qs = consumerType ? `?consumerType=${consumerType}` : ''
  return api<ConsumptionLog[]>(`/api/projects/${projectId}/business-packs/consumption${qs}`)
}

// ===== Business Pack Infer Relations =====
export function inferBusinessPackRelations(projectId: number) {
  return api<{ created: number }>(`/api/projects/${projectId}/business-packs/infer-relations`, { method: 'POST' })
}

// ===== Scan Source Config =====
export interface ScanSourceConfig {
  id: number
  projectId: number | null
  sourceKey: string
  sourceLabel: string
  sourceType: string
  sourceUrl: string | null
  sourceFilePath: string | null
  defaultSelected: boolean
  enabled: boolean
  description: string | null
  configJson: string | null
  createdBy: number | null
  createdAt: string | null
  updatedAt: string | null
}

export function listScanSourceConfigs(projectId: number) {
  return api<ScanSourceConfig[]>(`/api/projects/${projectId}/scan-sources`)
}

export function listDefaultScanSources(projectId: number) {
  return api<ScanSourceConfig[]>(`/api/projects/${projectId}/scan-sources/defaults`)
}

export function createScanSourceConfig(projectId: number, data: {
  sourceKey: string; sourceLabel: string; sourceType: string;
  sourceUrl?: string; sourceFilePath?: string; defaultSelected?: boolean;
  enabled?: boolean; description?: string; configJson?: string
}) {
  return api<ScanSourceConfig>(`/api/projects/${projectId}/scan-sources`, {
    method: 'POST', body: JSON.stringify(data)
  })
}

export function updateScanSourceConfig(projectId: number, sourceId: number, data: {
  sourceLabel?: string; sourceType?: string; sourceUrl?: string;
  sourceFilePath?: string; defaultSelected?: boolean; enabled?: boolean;
  description?: string; configJson?: string
}) {
  return api<ScanSourceConfig>(`/api/projects/${projectId}/scan-sources/${sourceId}`, {
    method: 'PATCH', body: JSON.stringify(data)
  })
}

export function deleteScanSourceConfig(projectId: number, sourceId: number) {
  return api<void>(`/api/projects/${projectId}/scan-sources/${sourceId}`, { method: 'DELETE' })
}

export function enableScanSourceConfig(projectId: number, sourceId: number) {
  return api<void>(`/api/projects/${projectId}/scan-sources/${sourceId}/enable`, { method: 'POST' })
}

export function disableScanSourceConfig(projectId: number, sourceId: number) {
  return api<void>(`/api/projects/${projectId}/scan-sources/${sourceId}/disable`, { method: 'POST' })
}

// ===== Trace Rule Pack Config =====
export interface TraceRulePackConfig {
  id: number
  projectId: number | null
  packKey: string
  packName: string
  packType: string
  version: number
  status: string
  priority: number
  configJson: string
  description: string | null
  createdBy: number | null
  createdAt: string | null
  updatedAt: string | null
}

export function listTraceRulePacks(projectId: number) {
  return api<TraceRulePackConfig[]>(`/api/projects/${projectId}/trace-rule-packs`)
}

export function createTraceRulePack(projectId: number, data: {
  packKey: string; packName: string; packType?: string; status?: string;
  priority?: number; configJson: string; description?: string
}) {
  return api<TraceRulePackConfig>(`/api/projects/${projectId}/trace-rule-packs`, {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export function updateTraceRulePack(projectId: number, packId: number, data: {
  packName?: string; packType?: string; status?: string;
  priority?: number; configJson?: string; description?: string
}) {
  return api<TraceRulePackConfig>(`/api/projects/${projectId}/trace-rule-packs/${packId}`, {
    method: 'PATCH',
    body: JSON.stringify(data),
  })
}

export function activateTraceRulePack(projectId: number, packId: number) {
  return api<void>(`/api/projects/${projectId}/trace-rule-packs/${packId}/activate`, { method: 'POST' })
}

export function deactivateTraceRulePack(projectId: number, packId: number) {
  return api<void>(`/api/projects/${projectId}/trace-rule-packs/${packId}/deactivate`, { method: 'POST' })
}

export function archiveTraceRulePack(projectId: number, packId: number) {
  return api<void>(`/api/projects/${projectId}/trace-rule-packs/${packId}/archive`, { method: 'POST' })
}

export function deleteTraceRulePack(projectId: number, packId: number) {
  return api<void>(`/api/projects/${projectId}/trace-rule-packs/${packId}`, { method: 'DELETE' })
}

// ===== Wiki API =====
export interface WikiPack {
  id: number
  projectId: number
  scope: string
  name: string
  status: string
  reviewStatus: string
  trustLevel: string | null
  sourceType: string | null
  promotionSourcePackId?: number | null
  description: string | null
  createdBy: number | null
  createdAt: string
  updatedAt: string
}

export interface WikiEntry {
  id: number
  packId: number
  entryType: string
  title: string
  content: string
  keywordsJson: string | null
  sourceRefsJson: string | null
  reviewStatus: string
  confidence: number | null
  effectiveStatus: string
  createdBy: number | null
  createdAt: string
  updatedAt: string
}

export function listWikiPacks(projectId: number) {
  return api<WikiPack[]>(`/api/wiki/packs?projectId=${projectId}`)
}

export function listAdminWikiPacks(filters: {
  projectId?: number
  scope?: string
  status?: string
  reviewStatus?: string
} = {}) {
  const params = new URLSearchParams()
  if (filters.projectId) params.set('projectId', String(filters.projectId))
  if (filters.scope) params.set('scope', filters.scope)
  if (filters.status) params.set('status', filters.status)
  if (filters.reviewStatus) params.set('reviewStatus', filters.reviewStatus)
  const query = params.toString()
  return api<WikiPack[]>(`/api/wiki/admin/packs${query ? `?${query}` : ''}`)
}

export function createWikiPack(projectId: number, scope: string, name: string, description: string) {
  return api<WikiPack>('/api/wiki/packs', { method: 'POST', body: JSON.stringify({ projectId, scope, name, description }) })
}

export function listWikiPromotions(packId: number) {
  return api<WikiPack[]>(`/api/wiki/packs/${packId}/promotions`)
}

export function submitWikiPromotion(packId: number, targetScope: 'REUSABLE' | 'SYSTEM') {
  return api<WikiPack>(`/api/wiki/packs/${packId}/promotions`, {
    method: 'POST', body: JSON.stringify({ targetScope }),
  })
}

export function reviewWikiPack(packId: number, reviewStatus: string) {
  return api<WikiPack>(`/api/wiki/packs/${packId}/review`, {
    method: 'PATCH', body: JSON.stringify({ reviewStatus }),
  })
}

export function updateWikiPackStatus(packId: number, status: string) {
  return api<WikiPack>(`/api/wiki/packs/${packId}/status`, {
    method: 'PATCH', body: JSON.stringify({ status }),
  })
}

export function updateWikiPack(packId: number, name: string, description: string) {
  return api<WikiPack>(`/api/wiki/packs/${packId}`, {
    method: 'PATCH', body: JSON.stringify({ name, description }),
  })
}

export function deleteWikiPack(packId: number) {
  return api<void>(`/api/wiki/packs/${packId}`, { method: 'DELETE' })
}

export function listWikiEntries(packId: number) {
  return api<WikiEntry[]>(`/api/wiki/packs/${packId}/entries`)
}

export function listWikiTomLinks(entryId: number) {
  return api<number[]>(`/api/wiki/entries/${entryId}/tom-links`)
}

export function updateWikiTomLinks(entryId: number, tomIds: number[]) {
  return api<number[]>(`/api/wiki/entries/${entryId}/tom-links`, {
    method: 'PUT', body: JSON.stringify({ tomIds }),
  })
}

export function createWikiEntry(packId: number, entryType: string, title: string, content: string) {
  return api<WikiEntry>(`/api/wiki/packs/${packId}/entries`, { method: 'POST', body: JSON.stringify({ entryType, title, content }) })
}

export function reviewWikiEntry(entryId: number, reviewStatus: string) {
  return api<WikiEntry>(`/api/wiki/entries/${entryId}/review`, { method: 'PATCH', body: JSON.stringify({ reviewStatus }) })
}

export function updateWikiEntry(entryId: number, entryType: string, title: string, content: string) {
  return api<WikiEntry>(`/api/wiki/entries/${entryId}`, {
    method: 'PATCH', body: JSON.stringify({ entryType, title, content }),
  })
}

// ===== Loop API =====
export interface LoopEvent {
  id: number
  projectId: number
  eventType: string
  sourceStage: string | null
  rawInput: string | null
  normalizedIssue: string | null
  suggestedAssetType: string | null
  sourceRefsJson: string | null
  status: string
  createdBy: number | null
  createdAt: string
  updatedAt: string
}

export interface LoopCluster {
  id: number
  projectId: number
  theme: string | null
  eventCount: number
  suggestedAction: string | null
  targetAssetType: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export function getLoopStatus() {
  return api<boolean>('/api/loop/status')
}

export function setLoopStatus(enabled: boolean) {
  return api<void>('/api/loop/status', { method: 'POST', body: JSON.stringify({ enabled }) })
}

export function listLoopEvents(projectId: number) {
  return api<LoopEvent[]>(`/api/loop/events?projectId=${projectId}`)
}

export function listLoopClusters(projectId: number) {
  return api<LoopCluster[]>(`/api/loop/clusters?projectId=${projectId}`)
}

export function approveLoopCluster(clusterId: number) {
  return api<LoopCluster>(`/api/loop/clusters/${clusterId}/status`, { method: 'PATCH', body: JSON.stringify({ status: 'APPROVED' }) })
}

export function rejectLoopCluster(clusterId: number) {
  return api<LoopCluster>(`/api/loop/clusters/${clusterId}/status`, { method: 'PATCH', body: JSON.stringify({ status: 'REJECTED' }) })
}

export function consumeLoopCandidates(projectId: number) {
  return api<{ candidatesGenerated: number }>(`/api/loop/consume?projectId=${projectId}`, { method: 'POST' })
}

// ===== Runtime Diagnostics API =====
export interface LlmInvocationLog {
  id: number
  requestId: string
  userId: number | null
  projectId: number | null
  taskId: number | null
  taskType: string | null
  stage: string
  modelConfigId: number | null
  provider: string | null
  modelName: string | null
  retryIndex: number | null
  status: string
  errorCode: string | null
  errorMessage: string | null
  durationMs: number | null
  tokenInput: number
  tokenCachedInput?: number
  tokenOutput: number
  rawOutputPreview: string
  createdAt: string
}

export interface SecurityEventLog {
  id: number
  eventType: string
  severity: string
  userId: number | null
  projectId: number | null
  taskId: number | null
  requestId: string | null
  detailPreview: string
  createdAt: string
}

export interface LlmInvocationChain {
  rootRequestId: string
  entries: LlmInvocationLog[]
}

export interface LlmInvocationSnapshot {
  id: number
  requestId: string
  userId: number | null
  projectId: number | null
  taskId: number | null
  stage: string | null
  provider: string | null
  modelName: string | null
  status: string
  errorCode: string | null
  errorMessage: string | null
  rawOutput: string
  createdAt: string | null
}

export type RuntimeLogParams = {
  projectId?: number
  taskId?: number
  status?: string
  errorCode?: string
  eventType?: string
  severity?: string
  keyword?: string
  limit?: number
}

function buildRuntimeLogQuery(params: RuntimeLogParams = {}) {
  const qs = new URLSearchParams()
  if (params.projectId) qs.set('projectId', String(params.projectId))
  if (params.taskId) qs.set('taskId', String(params.taskId))
  if (params.status) qs.set('status', params.status)
  if (params.errorCode) qs.set('errorCode', params.errorCode)
  if (params.eventType) qs.set('eventType', params.eventType)
  if (params.severity) qs.set('severity', params.severity)
  if (params.keyword) qs.set('keyword', params.keyword)
  if (params.limit) qs.set('limit', String(params.limit))
  const query = qs.toString()
  return query ? `?${query}` : ''
}

export function listLlmInvocationLogs(params: RuntimeLogParams = {}) {
  return api<LlmInvocationLog[]>(`/api/admin/runtime-diagnostics/llm-invocations${buildRuntimeLogQuery(params)}`)
}

export function getLlmInvocationChain(requestId: string) {
  return api<LlmInvocationChain>(`/api/admin/runtime-diagnostics/llm-invocations/chain?requestId=${encodeURIComponent(requestId)}`)
}

export function getLlmInvocationSnapshot(id: number) {
  return api<LlmInvocationSnapshot>(`/api/admin/runtime-diagnostics/llm-invocations/snapshot?id=${id}`)
}

export function exportLlmInvocationReport(params: RuntimeLogParams = {}) {
  return apiText(`/api/admin/runtime-diagnostics/llm-invocations/export${buildRuntimeLogQuery(params)}`)
}

export function listSecurityEventLogs(params: RuntimeLogParams = {}) {
  return api<SecurityEventLog[]>(`/api/admin/runtime-diagnostics/security-events${buildRuntimeLogQuery(params)}`)
}

export function exportSecurityEventReport(params: RuntimeLogParams = {}) {
  return apiText(`/api/admin/runtime-diagnostics/security-events/export${buildRuntimeLogQuery(params)}`)
}
