package com.company.aitest.minitom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.llm.gateway.LlmGateway;
import com.company.aitest.llm.gateway.LlmInvocationRequest;
import com.company.aitest.llm.gateway.LlmInvocationResponse;
import com.company.aitest.llm.gateway.LlmInvocationStatus;
import com.company.aitest.llm.gateway.LlmStage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于 LLM 的语义匹配器。
 * <p>
 * 将需求文本与 TOM 候选列表一起发给 LLM，由大模型判断哪些 TOM 与需求语义相关，
 * 替代原有的 n-gram 关键词子串匹配。
 */
@Component
public class TomSemanticMatcher {

    private static final Logger log = LoggerFactory.getLogger(TomSemanticMatcher.class);

    private static final String SYSTEM_PROMPT = """
            你是资深测试分析师。你的任务是判断哪些测试对象模型（TOM）与给定的需求描述语义相关。

            判断规则：
            1. 理解需求的核心意图，而不是做字面文字匹配
            2. 例如："优化申请撤回功能" 的核心意图是"修改/优化撤回流程"，应匹配与"申请撤回"相关的 TOM
            3. 不要因为 TOM 名称包含相同字面词就匹配——必须是该需求实际涉及的功能对象
            4. 例如："申请审批"虽然包含"申请"，但与"申请撤回"无关，不应匹配
            5. "撤回记录列表"与"申请撤回"相关，应匹配
            6. "撤回确认弹窗"与"申请撤回"相关，应匹配
            7. 参考【系统实际行为（轨迹摘要）】来理解系统有哪些功能和流程，辅助判断 TOM 相关性
            8. 轨迹摘要中标记"待确认事项"的内容不能当作确定事实

            你必须只返回一个 JSON 数组，包含所有语义相关的 TOM 的 id。
            例如：[1, 5, 12]
            如果没有相关的 TOM，返回空数组：[]
            不要输出任何额外解释，不要输出 markdown。
            """;

    private final LlmGateway llmGateway;
    private final TraceSummaryContextBuilder contextBuilder;
    private final ObjectMapper objectMapper;

    public TomSemanticMatcher(LlmGateway llmGateway, TraceSummaryContextBuilder contextBuilder) {
        this.llmGateway = llmGateway;
        this.contextBuilder = contextBuilder;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 从候选 TOM 列表中，语义筛选出与需求相关的 TOM。
     *
     * @param requirementText 需求描述文本
     * @param candidates      全部候选 TOM（按类型混合）
     * @param modelConfigId   LLM 模型配置 ID
     * @param projectId       项目 ID
     * @param user            当前用户
     * @return 语义匹配的 TOM 列表（保持原顺序）
     */
    public List<TestObjectModelRecord> matchSemantically(
            String requirementText,
            List<TestObjectModelRecord> candidates,
            Long modelConfigId,
            Long projectId,
            CurrentUser user) {

        if (candidates.isEmpty()) {
            return List.of();
        }

        // 构建 TOM 候选清单（给 LLM 看的）
        String tomList = buildTomListPrompt(candidates);

        // 通过 TraceSummaryContextBuilder 获取相关性筛选后的轨迹摘要上下文
        TraceSummaryContextBuilder.BuildResult contextResult =
                contextBuilder.build(requirementText, candidates, projectId);

        String userPrompt = """
                【需求描述】
                %s

                【候选测试对象模型列表】
                %s
                %s
                请分析需求意图，从候选列表中选出语义相关的 TOM id。
                """.formatted(requirementText, tomList, contextResult.promptSection());

        LlmInvocationResponse resp = llmGateway.invoke(new LlmInvocationRequest(
                null, user.id(), projectId, null,
                "TOM_SEMANTIC_MATCH", LlmStage.TOM_SEMANTIC_MATCH,
                modelConfigId, null, null, Map.of(),
                SYSTEM_PROMPT, userPrompt, null));

        if (resp.status() != LlmInvocationStatus.OK) {
            log.warn("TOM 语义匹配 LLM 调用失败: {}, 降级为关键词匹配", resp.errorMessage());
            return null; // 返回 null 表示降级
        }

        return parseMatchedIds(resp.content(), candidates);
    }

    private String buildTomListPrompt(List<TestObjectModelRecord> candidates) {
        StringBuilder sb = new StringBuilder();
        for (TestObjectModelRecord tom : candidates) {
            sb.append("- id: %d | 类型: %s | 名称: %s".formatted(
                    tom.id(), tom.modelType(), tom.name()));
            if (tom.description() != null && !tom.description().isBlank()) {
                String desc = tom.description();
                if (desc.length() > 100) {
                    desc = desc.substring(0, 100) + "...";
                }
                sb.append(" | 描述: ").append(desc);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private List<TestObjectModelRecord> parseMatchedIds(
            String rawOutput, List<TestObjectModelRecord> candidates) {

        String jsonText = extractJson(rawOutput);
        try {
            List<Long> matchedIds = objectMapper.readValue(jsonText, new TypeReference<>() {});
            if (matchedIds == null || matchedIds.isEmpty()) {
                return List.of();
            }

            Set<Long> idSet = matchedIds.stream().collect(Collectors.toSet());
            List<TestObjectModelRecord> result = new ArrayList<>();
            for (TestObjectModelRecord tom : candidates) {
                if (idSet.contains(tom.id())) {
                    result.add(tom);
                }
            }

            Set<Long> candidateIds = candidates.stream()
                    .map(TestObjectModelRecord::id)
                    .collect(Collectors.toSet());
            List<Long> invalidIds = matchedIds.stream()
                    .filter(id -> !candidateIds.contains(id))
                    .toList();
            if (!invalidIds.isEmpty()) {
                log.warn("LLM 返回了不在候选列表中的 TOM id: {}", invalidIds);
            }

            return result;
        } catch (Exception e) {
            log.warn("解析 LLM 语义匹配结果失败: {}", e.getMessage());
            return null; // 降级
        }
    }

    private String extractJson(String raw) {
        if (raw == null) return "[]";
        String text = raw.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        return text;
    }
}
