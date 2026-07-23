const LABELS: Record<string, string> = {
  ACTIVE: '已生效', INACTIVE: '未生效', DRAFT: '草稿', ARCHIVED: '已归档',
  PENDING: '待处理', RUNNING: '执行中', SUCCEEDED: '已成功', SUCCESS: '成功',
  FAILED: '失败', TIMEOUT: '已超时', CANCELED: '已取消', CANCELLED: '已取消',
  NEED_RETRY: '待重试', PARTIAL_DONE: '部分完成', COMPLETED: '已完成',
  RECORDING: '采集中', STOPPED: '已停止', UP: '在线', DOWN: '离线',
  ATTEMPT_OK: '本次调用成功', ATTEMPT_RETRY: '等待重试', ATTEMPT_FAILED: '本次调用失败',
  CONFIRMED: '已确认', SUBMITTED: '已提交', DEPRECATED: '已弃用',
  APPROVED: '审核通过', REJECTED: '已驳回', CANDIDATE: '候选',
  CREATED: '已创建', ENABLED: '已启用', DISABLED: '已停用', DELETED: '已删除',
  PROJECT: '项目级', REUSABLE: '跨项目复用级', SYSTEM: '系统级',
  RULE: '业务规则', DECISION: '设计决策', HANDOVER: '历史交接',
  IMPLEMENTATION: '实现说明', EXPERIENCE: '测试经验', REQUIREMENT: '需求知识',
  MEMBER: '普通用户', ADMIN: '管理员', SUB_ADMIN: '子管理员', OWNER: '项目负责人',
  DIRECT: '不使用 TOM', PROJECT_TOM: '仅项目 TOM',
  PROJECT_AND_SYSTEM_TOM: '项目与系统 TOM',
  TRACE: '轨迹回放', GENERATION: '需求生成',
  HIGH: '高', MEDIUM: '中', LOW: '低', UNKNOWN: '未知',
  REQUIREMENT_ANALYSIS: '需求分析', REQUIREMENT_ANALYSIS_INCREMENTAL: '增量需求分析',
  TEST_POINT_GENERATION: '测试点生成', TEST_CASE_GENERATION: '测试用例生成',
  TRACE_SUMMARY: '轨迹摘要', COVERAGE_MATRIX: '覆盖矩阵',
  FUNCTIONAL: '功能用例', POSITIVE: '正向场景', NEGATIVE: '反向场景',
  BOUNDARY: '边界场景', COMBINATION: '组合场景', STATE: '状态场景', RECOVERY: '恢复与一致性',
};

export function displayLabel(value?: string | null, fallback = '未知') {
  if (!value) return fallback;
  return LABELS[value] || '未配置中文标识';
}

export function statusLabel(value?: string | null) {
  return displayLabel(value, '未知状态');
}

export function wikiScopeLabel(value?: string | null) {
  return displayLabel(value, '未知层级');
}

export function wikiEntryTypeLabel(value?: string | null) {
  return displayLabel(value, '未知类型');
}

const ERROR_LABELS: Record<string, string> = {
  TIMEOUT: '调用超时', RATE_LIMITED: '调用频率受限', PROVIDER_ERROR: '模型服务异常',
  AUTH_ERROR: '模型鉴权失败', CONTEXT_TOO_LONG: '上下文过长',
  OUTPUT_PARSE_ERROR: '输出格式解析失败', INVALID_REQUEST: '请求参数无效',
  UNKNOWN_ERROR: '未知调用异常', PROVIDER_5XX: '模型服务异常', NETWORK_ERROR: '网络异常',
};

export function errorCodeLabel(value?: string | null) {
  if (!value) return '-';
  return ERROR_LABELS[value] || '未配置中文错误类型';
}

export const wikiScopeOptions = [
  { value: 'PROJECT', label: '项目级' },
  { value: 'REUSABLE', label: '跨项目复用级' },
  { value: 'SYSTEM', label: '系统级' },
];
