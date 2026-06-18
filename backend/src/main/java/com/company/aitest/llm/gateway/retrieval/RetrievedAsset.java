package com.company.aitest.llm.gateway.retrieval;

/**
 * 一次 RAG 检索命中的资产引用。
 * <p>
 * 用作 {@code context_manifest.included_assets_json} 的元素，
 * 也直接交给 ContextBuilder 用来拼装 prompt 的"参考资料"段。
 */
public record RetrievedAsset(
        Long assetId,
        String assetType,    // KNOWLEDGE_CHUNK | CASE_ASSET | SKILL_TEMPLATE | TOOL_TEMPLATE
        String scope,        // PERSONAL | PROJECT | PUBLIC | SYSTEM
        String sourceType,
        String sourceRefId,
        String status,
        String trustLevel,
        String title,
        String contentSnippet,
        String contentHash,
        Double similarity
) {
}
