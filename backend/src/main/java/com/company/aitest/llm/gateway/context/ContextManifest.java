package com.company.aitest.llm.gateway.context;

import java.util.List;

import com.company.aitest.llm.gateway.retrieval.RetrievedAsset;

/**
 * 一次 LLM 调用前组装好的上下文清单。
 * 持久化到 context_manifest 表。
 */
public record ContextManifest(
        String requestId,
        Long userId,
        Long projectId,
        Long taskId,
        String stage,
        Long modelConfigId,
        Long promptTemplateId,
        Integer promptVersion,
        List<RetrievedAsset> includedAssets,
        String excludedPolicyJson,
        String conflictsJson
) {
    public ContextManifest {
        if (includedAssets == null) {
            includedAssets = List.of();
        }
    }
}
