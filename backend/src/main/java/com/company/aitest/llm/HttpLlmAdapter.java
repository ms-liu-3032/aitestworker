package com.company.aitest.llm;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.BusinessException;
import com.company.aitest.model.ModelConfigService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HttpLlmAdapter implements LlmAdapter {
    private static final Logger log = LoggerFactory.getLogger(HttpLlmAdapter.class);
    private static final String DEFAULT_CHAT_COMPLETIONS = "https://api.openai.com/v1/chat/completions";

    private final ModelConfigService modelConfigService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final long requestTimeoutSeconds;

    public HttpLlmAdapter(ModelConfigService modelConfigService,
                          @Value("${aitest.llm.connect-timeout-seconds:20}") long connectTimeoutSeconds,
                          @Value("${aitest.llm.request-timeout-seconds:240}") long requestTimeoutSeconds) {
        this.modelConfigService = modelConfigService;
        this.requestTimeoutSeconds = Math.max(30, requestTimeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, connectTimeoutSeconds)))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String complete(CompletionRequest request) {
        if (request.modelConfigId() == null) {
            throw new BusinessException("请先选择模型配置");
        }
        ModelConfigService.RuntimeModelConfig model = modelConfigService.getRuntimeConfig(request.modelConfigId());
        if (model == null || model.id() == null) {
            throw new BusinessException("模型配置不存在");
        }
        if (!"ACTIVE".equalsIgnoreCase(model.status())) {
            throw new BusinessException("模型配置未启用");
        }
        if (model.apiKey() == null || model.apiKey().isBlank()) {
            throw new BusinessException("模型配置缺少 API Key");
        }
        if (model.modelName() == null || model.modelName().isBlank()) {
            throw new BusinessException("模型名称不能为空");
        }

        String endpoint = resolveEndpoint(model.endpoint());
        String payload = buildPayload(model.modelName(), request.systemPrompt(), request.userPrompt(), request.maxTokens());

        log.info("LLM call: model={}, endpoint={}", model.modelName(), endpoint);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + model.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("模型调用失败，HTTP " + response.statusCode() + "：" + brief(response.body()));
            }
            return parseContent(response.body());
        } catch (HttpTimeoutException e) {
            throw new BusinessException("模型调用超时（超过 " + requestTimeoutSeconds
                    + " 秒）。通常由模型响应慢、网络波动、上下文过长或一次生成内容过多导致，请稍后重试或减少本轮输入范围");
        } catch (IOException e) {
            throw new BusinessException("模型调用失败：网络异常，" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("模型调用被中断");
        }
    }

    private String buildPayload(String modelName, String systemPrompt, String userPrompt, Integer maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("temperature", 0.2);
        if (maxTokens != null && maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", emptySafe(systemPrompt)),
                Map.of("role", "user", "content", emptySafe(userPrompt))
        ));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new BusinessException("模型请求序列化失败");
        }
    }

    private String parseContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isMissingNode() && !content.isNull()) {
                return content.asText("");
            }
            throw new BusinessException("模型返回内容为空");
        } catch (JsonProcessingException e) {
            throw new BusinessException("模型返回解析失败");
        }
    }

    private String resolveEndpoint(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.isEmpty()) {
            return DEFAULT_CHAT_COMPLETIONS;
        }
        if (value.endsWith("/chat/completions")) {
            return value;
        }
        return value.endsWith("/") ? value + "chat/completions" : value + "/chat/completions";
    }

    private String emptySafe(String value) {
        return value == null ? "" : value;
    }

    private String brief(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300);
    }
}
