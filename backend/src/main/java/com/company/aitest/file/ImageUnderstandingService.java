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
import com.company.aitest.model.ModelConfigService;
import org.springframework.stereotype.Service;

@Service
public class ImageUnderstandingService {

    private final ModelConfigService modelConfigService;
    private final LlmGateway llmGateway;

    public ImageUnderstandingService(ModelConfigService modelConfigService, LlmGateway llmGateway) {
        this.modelConfigService = modelConfigService;
        this.llmGateway = llmGateway;
    }

    public boolean supportsVision(Long modelConfigId) {
        if (modelConfigId == null) return false;
        var config = modelConfigService.getRuntimeConfig(modelConfigId);
        if (config == null) return false;
        if (config.visionEnabled() != null) return config.visionEnabled();
        String modelName = config.modelName();
        if (modelName == null) return false;
        String lower = modelName.toLowerCase();
        return lower.contains("gpt-4o") || lower.contains("gpt-4-turbo") || lower.contains("gpt-5")
                || lower.contains("claude") || lower.contains("vision") || lower.contains("gemini")
                || lower.contains("qwen-vl") || lower.contains("qwen2-vl");
    }

    public String analyzeImage(Path file, Long modelConfigId, Long userId, Long projectId) throws IOException {
        if (!supportsVision(modelConfigId)) {
            throw new UnsupportedOperationException("当前模型未启用图片理解，请在模型配置中将“图片理解能力”设为“支持”，并确认该 endpoint 兼容 OpenAI image_url 请求");
        }

        String systemPrompt = "你是一个测试分析师。请分析这张图片，识别其中的页面元素、字段、按钮、状态和可能的测试需求。返回 JSON 格式。";
        String userPrompt = "请仅依据随请求附带的截图分析页面内容，识别页面元素、字段、按钮、状态、文案和可能的测试需求。"
                + "无法从截图确认的信息必须标注为待确认，不得编造。";
        long size = Files.size(file);
        if (size <= 0 || size > 10L * 1024 * 1024) {
            throw new IOException("图片大小必须在 1 字节到 10MB 之间");
        }
        String mimeType = detectMimeType(file);
        String imageDataUrl = "data:" + mimeType + ";base64,"
                + Base64.getEncoder().encodeToString(Files.readAllBytes(file));

        var request = new LlmInvocationRequest(
                UUID.randomUUID().toString(), userId, projectId, null,
                "IMAGE_UNDERSTANDING", LlmStage.REQ_CLARIFY, modelConfigId,
                null, null, Map.of("image_data_url", imageDataUrl), systemPrompt, userPrompt, null
        );
        LlmInvocationResponse response = llmGateway.invoke(request);
        if (response.status() != LlmInvocationStatus.OK) {
            throw new IOException("图片理解失败: " + response.errorMessage());
        }
        return response.content();
    }

    private String detectMimeType(Path file) throws IOException {
        String detected = Files.probeContentType(file);
        if (detected != null && detected.startsWith("image/")) return detected;
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        throw new IOException("不支持的图片格式，请上传 PNG、JPG 或 WEBP");
    }
}
