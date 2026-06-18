package com.company.aitest.minitom;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.knowledge.KnowledgeChunk;
import com.company.aitest.llm.gateway.LlmGateway;
import com.company.aitest.llm.gateway.LlmInvocationRequest;
import com.company.aitest.llm.gateway.LlmInvocationResponse;
import com.company.aitest.llm.gateway.LlmInvocationStatus;
import com.company.aitest.llm.gateway.LlmStage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * LLM 结构化 TOM 抽取器。
 * <p>
 * 从手册切片中通过 LLM 抽取 Mini-TOM 候选。
 */
@Component
public class TomLlmExtractor {

    private static final String EXTRACTION_SYSTEM_PROMPT = """
            你是资深测试分析师。从以下用户手册片段中提取测试对象模型候选。

            业务域: %s

            请严格按以下 JSON 数组格式输出，每个元素代表一个 TOM 候选:
            [
              {
                "modelType": "MODULE|PAGE|FIELD|ACTION|FLOW|STATE|ASSERTION",
                "name": "对象名称（简短，2-20字）",
                "description": "详细描述（50-200字）",
                "evidenceText": "手册中的原文证据（直接引用，30-100字）",
                "priority": "P0|P1|P2|P3",
                "confidence": 0.0到1.0的浮点数
              }
            ]

            提取规则:
            1. MODULE: 功能模块，如"订单管理"、"审批管理"
            2. PAGE: 页面/界面，如"新建申请页面"、"审批列表页"
            3. FIELD: 表单字段，如"申请人姓名"、"审批意见"、"计划日期"
            4. ACTION: 用户操作，如"提交申请"、"审批通过"、"扫码签入"
            5. FLOW: 业务流程，如"申请提交流程"、"审批处理流程"
            6. STATE: 状态变化，如"单据状态:待审批→已通过"
            7. ASSERTION: 验证点，如"提交成功后显示结果提示"

            优先级规则:
            - P0: 核心业务流程，不可绕过
            - P1: 重要功能，影响主要用户场景
            - P2: 辅助功能，有替代路径
            - P3: 边缘场景，极少使用

            特别注意:
            - 仅从文本中抽取，不要根据截图编造内容
            - 多步骤流程必须保留完整步骤，不要拆散
            - 同一功能下的"说明+操作+规则"尽量放在同一个候选中
            - 证据文本必须是手册原文的直接引用
            - 如果文本表达不完整或依赖截图才能确认，将 confidence 降低到 0.3-0.5
            - 如果片段中没有可提取的内容，输出空数组 []

            只输出 JSON 数组，不要输出任何额外解释，不要输出 markdown。
            """;

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public TomLlmExtractor(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 从单个 chunk 中抽取 TOM 候选。
     */
    public List<TomExtractionResult> extractFromChunk(
            Long taskId, Long projectId, String businessDomain, String docTitle,
            KnowledgeChunk chunk, Long modelConfigId, CurrentUser user) {

        String systemPrompt = EXTRACTION_SYSTEM_PROMPT.formatted(businessDomain);
        String userPrompt = """
                【文档】%s
                【章节路径】%s
                【内容】
                %s
                """.formatted(docTitle, chunk.headingPath(), chunk.content());

        LlmInvocationResponse resp = llmGateway.invoke(new LlmInvocationRequest(
                null, user.id(), projectId, taskId,
                "MINI_TOM_EXTRACTION", LlmStage.MINI_TOM_EXTRACTION,
                modelConfigId, null, null, Map.of(),
                systemPrompt, userPrompt, null));

        if (resp.status() != LlmInvocationStatus.OK) {
            throw new BusinessException("TOM 抽取失败：" + (resp.errorMessage() == null ? resp.status().name() : resp.errorMessage()));
        }

        return parseExtractionResults(resp.content());
    }

    private List<TomExtractionResult> parseExtractionResults(String raw) {
        String jsonText = extractJson(raw);
        try {
            List<TomExtractionResult> results = objectMapper.readValue(jsonText, new TypeReference<>() {});
            return results != null ? results : List.of();
        } catch (JsonProcessingException e) {
            return List.of();
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

    public record TomExtractionResult(
            String modelType,
            String name,
            String description,
            String evidenceText,
            String priority,
            BigDecimal confidence
    ) {
    }
}
