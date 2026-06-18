package com.company.aitest.llm.gateway.retrieval;

import java.util.List;

/**
 * 检索请求与结果合并对象。
 * <p>
 * result 永远不会是 null（空集即 List.of()），便于上游统一处理。
 * excludedPolicy 是 manifest 的输入，记录"按规则被排除"的政策。
 */
public record RetrievalResult(
        List<RetrievedAsset> assets,
        boolean rawDisabled,
        String excludedPolicyJson
) {
    public RetrievalResult {
        if (assets == null) {
            assets = List.of();
        }
    }

    public static RetrievalResult empty(String excludedPolicyJson) {
        return new RetrievalResult(List.of(), false, excludedPolicyJson);
    }

    public static RetrievalResult disabled() {
        return new RetrievalResult(List.of(), true,
                "{\"reason\":\"retrieval_disabled_by_policy\"}");
    }
}
