package com.company.aitest.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import com.company.aitest.llm.gateway.LlmGateway;
import com.company.aitest.llm.gateway.LlmInvocationRequest;
import com.company.aitest.llm.gateway.LlmInvocationResponse;
import com.company.aitest.llm.gateway.LlmInvocationStatus;
import com.company.aitest.llm.gateway.LlmStage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ImageUnderstandingService {

    private final JdbcClient jdbc;
    private final LlmGateway llmGateway;

    public ImageUnderstandingService(JdbcClient jdbc, LlmGateway llmGateway) {
        this.jdbc = jdbc;
        this.llmGateway = llmGateway;
    }

    public boolean supportsVision(Long modelConfigId) {
        if (modelConfigId == null) return false;
        var list = jdbc.sql("SELECT provider, model_name FROM model_config WHERE id = :id")
                .param("id", modelConfigId).query((rs, rowNum) -> {
                    return new String[]{rs.getString("provider"), rs.getString("model_name")};
                }).list();
        if (list.isEmpty()) return false;
        String modelName = list.get(0)[1];
        if (modelName == null) return false;
        String lower = modelName.toLowerCase();
        return lower.contains("gpt-4o") || lower.contains("gpt-4-turbo") || lower.contains("claude-3")
                || lower.contains("claude-4") || lower.contains("vision") || lower.contains("gemini");
    }

    public String analyzeImage(Path file, Long modelConfigId, Long userId, Long projectId) throws IOException {
        if (!supportsVision(modelConfigId)) {
            throw new UnsupportedOperationException("当前模型不支持图片理解，请切换支持 vision 的模型（如 GPT-4o、Claude 3+）或忽略图片");
        }

        String systemPrompt = "你是一个测试分析师。请分析这张图片，识别其中的页面元素、字段、按钮、状态和可能的测试需求。返回 JSON 格式。";
        String userPrompt = "请分析这张图片的内容，识别页面元素和可能的测试需求。";

        var request = new LlmInvocationRequest(
                UUID.randomUUID().toString(), userId, projectId, null,
                "IMAGE_UNDERSTANDING", LlmStage.OTHER, modelConfigId,
                null, null, Map.of(), systemPrompt, userPrompt, null
        );
        LlmInvocationResponse response = llmGateway.invoke(request);
        if (response.status() != LlmInvocationStatus.OK) {
            throw new IOException("图片理解失败: " + response.errorMessage());
        }
        return response.content();
    }
}
