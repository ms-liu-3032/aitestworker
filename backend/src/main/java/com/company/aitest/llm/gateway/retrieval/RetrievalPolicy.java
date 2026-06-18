package com.company.aitest.llm.gateway.retrieval;

import java.util.Set;

import com.company.aitest.llm.gateway.LlmStage;

/**
 * 控制本次 RAG 检索的范围与策略。
 * <p>
 * 默认策略：项目级 ACTIVE 资产 + 公共/系统 ACTIVE + 当前用户 PERSONAL，弃用排除。
 * <p>
 * caller 可通过 {@link #disabled} 关掉 RAG（不参与），或通过
 * {@link #includeDeprecated} 显式打开"包含弃用资产"。这两项都会记录到
 * context_manifest.excluded_policy / included_policy 中。
 */
public record RetrievalPolicy(
        boolean disabled,
        Set<RetrievalAssetKind> kinds,
        int topN,
        boolean includeDeprecated,
        boolean includeUnreviewedPublic,
        String queryText
) {
    public RetrievalPolicy {
        if (kinds == null || kinds.isEmpty()) {
            kinds = Set.of(RetrievalAssetKind.KNOWLEDGE, RetrievalAssetKind.CASE, RetrievalAssetKind.SKILL);
        }
        if (topN <= 0) {
            topN = 8;
        }
    }

    /** 关掉 RAG 的快捷工厂；常用于 prompt 已自带全部上下文的场景。 */
    public static RetrievalPolicy disable() {
        return new RetrievalPolicy(true, null, 0, false, false, null);
    }

    /** 按 stage 给出默认策略；后续 Sprint 可通过配置覆盖。 */
    public static RetrievalPolicy forStage(LlmStage stage, String queryText) {
        return switch (stage) {
            case REQ_CLARIFY -> new RetrievalPolicy(false,
                    Set.of(RetrievalAssetKind.KNOWLEDGE), 6, false, false, queryText);
            case TEST_POINT_GEN -> new RetrievalPolicy(false,
                    Set.of(RetrievalAssetKind.KNOWLEDGE, RetrievalAssetKind.CASE), 8, false, false, queryText);
            case TEST_CASE_GEN -> new RetrievalPolicy(false,
                    Set.of(RetrievalAssetKind.KNOWLEDGE, RetrievalAssetKind.CASE, RetrievalAssetKind.SKILL),
                    10, false, false, queryText);
            case TRACE_SUMMARY, TRACE_CASE_GEN -> new RetrievalPolicy(false,
                    Set.of(RetrievalAssetKind.SKILL), 6, false, false, queryText);
            case TRACE_CORRECTION -> new RetrievalPolicy(false,
                    Set.of(RetrievalAssetKind.KNOWLEDGE, RetrievalAssetKind.SKILL), 6, false, false, queryText);
            case QUALITY_CHECK -> new RetrievalPolicy(false,
                    Set.of(RetrievalAssetKind.CASE), 5, false, false, queryText);
            case SKILL_EXEC, MINI_TOM_EXTRACTION, TOM_SEMANTIC_MATCH, OTHER -> RetrievalPolicy.disable();
        };
    }

    public enum RetrievalAssetKind {
        KNOWLEDGE, CASE, SKILL, TOOL
    }
}
