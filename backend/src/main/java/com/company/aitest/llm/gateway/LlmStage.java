package com.company.aitest.llm.gateway;

/**
 * 统一的 LLM 调用阶段标识。
 * <p>
 * 参考 docs/handover/07_LLM数据污染治理方案.md §4.2 上下文表。
 * 每次 {@link LlmGateway#invoke(LlmInvocationRequest)} 必须指定 stage，
 * 决定上下文构建策略、可用资产范围与默认 token 上限。
 */
public enum LlmStage {
    REQ_CLARIFY,
    TEST_POINT_GEN,
    TEST_CASE_GEN,
    TRACE_SUMMARY,
    TRACE_CORRECTION,
    TRACE_CASE_GEN,
    QUALITY_CHECK,
    SKILL_EXEC,
    MINI_TOM_EXTRACTION,
    TOM_SEMANTIC_MATCH,
    OTHER
}
