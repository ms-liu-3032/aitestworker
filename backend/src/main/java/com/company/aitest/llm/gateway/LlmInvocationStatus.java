package com.company.aitest.llm.gateway;

/**
 * Gateway 调用结果状态。
 * 与 {@code llm_invocation_log.status} 字段一一对应。
 */
public enum LlmInvocationStatus {
    /** 模型成功返回 */
    OK,
    /** Prompt 注入 / 越权 / 其它安全守卫拦截 */
    GUARD_BLOCKED,
    /** 配额超限 */
    QUOTA_EXCEEDED,
    /** 模型 / 上游错误 */
    MODEL_ERROR,
    /** 超时 */
    TIMEOUT,
    /** 入参非法（缺 userId / projectId / taskId 等） */
    INVALID_REQUEST
}
