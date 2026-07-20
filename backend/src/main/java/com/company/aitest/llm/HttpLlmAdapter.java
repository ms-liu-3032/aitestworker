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
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

import com.company.aitest.llm.gateway.LlmErrorCode;
import com.company.aitest.llm.gateway.LlmRuntimeException;
import com.company.aitest.llm.gateway.audit.LlmAttemptLogger;
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
    private final int maxRetries;
    private final long retryBackoffMillis;
    private final Semaphore concurrencyLimiter;
    private final boolean jsonResponseFormatEnabled;
    private final LlmAttemptLogger attemptLogger;

    /**
     * DeepSeek V4 defaults to thinking mode. Structured pipeline nodes are intentionally
     * non-thinking by default so reasoning tokens cannot consume the final JSON budget.
     */
    @Value("${aitest.llm.deepseek-thinking-mode:disabled}")
    private String deepseekThinkingMode = "disabled";

    public HttpLlmAdapter(ModelConfigService modelConfigService,
                          LlmAttemptLogger attemptLogger,
                          @Value("${aitest.llm.connect-timeout-seconds:20}") long connectTimeoutSeconds,
                          @Value("${aitest.llm.request-timeout-seconds:420}") long requestTimeoutSeconds,
                          @Value("${aitest.llm.max-concurrent:2}") int maxConcurrent,
                          @Value("${aitest.llm.max-retries:1}") int maxRetries,
                          @Value("${aitest.llm.retry-backoff-millis:1500}") long retryBackoffMillis,
                          @Value("${aitest.llm.json-response-format-enabled:true}") boolean jsonResponseFormatEnabled) {
        this.modelConfigService = modelConfigService;
        this.attemptLogger = attemptLogger;
        this.requestTimeoutSeconds = Math.max(30, requestTimeoutSeconds);
        this.maxRetries = Math.max(0, maxRetries);
        this.retryBackoffMillis = Math.max(0, retryBackoffMillis);
        this.concurrencyLimiter = new Semaphore(Math.max(1, maxConcurrent));
        this.jsonResponseFormatEnabled = jsonResponseFormatEnabled;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, connectTimeoutSeconds)))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String complete(CompletionRequest request) {
        return completeWithUsage(request).content();
    }

    @Override
    public CompletionResponse completeWithUsage(CompletionRequest request) {
        if (request.modelConfigId() == null) {
            throw new LlmRuntimeException(LlmErrorCode.INVALID_REQUEST, "请先选择模型配置");
        }
        ModelConfigService.RuntimeModelConfig model = modelConfigService.getRuntimeConfig(request.modelConfigId());
        if (model == null || model.id() == null) {
            throw new LlmRuntimeException(LlmErrorCode.MODEL_NOT_FOUND,
                    "模型配置不存在或已被删除，请重新选择可用模型配置后再试");
        }
        if (!"ACTIVE".equalsIgnoreCase(model.status())) {
            throw new LlmRuntimeException(LlmErrorCode.INVALID_REQUEST, "模型配置未启用");
        }
        if (model.apiKey() == null || model.apiKey().isBlank()) {
            throw new LlmRuntimeException(LlmErrorCode.AUTH_ERROR, "模型配置缺少 API Key");
        }
        if (model.modelName() == null || model.modelName().isBlank()) {
            throw new LlmRuntimeException(LlmErrorCode.INVALID_REQUEST, "模型名称不能为空");
        }

        String endpoint = resolveEndpoint(model.endpoint());
        boolean jsonObjectMode = shouldUseJsonObjectMode(request.systemPrompt(), request.userPrompt());
        String payload = buildPayload(model, request.systemPrompt(), request.userPrompt(),
                request.maxTokens(), jsonObjectMode);

        log.info("LLM call: model={}, endpoint={}, maxTokens={}, jsonObjectMode={}, promptChars={}",
                model.modelName(), endpoint, request.maxTokens(), jsonObjectMode,
                length(request.systemPrompt()) + length(request.userPrompt()));

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + model.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        acquirePermit(model.modelName(), endpoint);
        try {
            return sendWithRetry(httpRequest, model.modelName(), endpoint);
        } finally {
            concurrencyLimiter.release();
        }
    }

    private CompletionResponse sendWithRetry(HttpRequest httpRequest, String modelName, String endpoint) {
        int attempt = 0;
        while (true) {
            long startedAt = System.currentTimeMillis();
            try {
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                long durationMs = System.currentTimeMillis() - startedAt;
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    LlmErrorCode errorCode = classifyHttpStatus(response.statusCode(), response.body());
                    log.warn("LLM call failed: model={}, endpoint={}, status={}, durationMs={}, attempt={}/{}, response={}",
                            modelName, endpoint, response.statusCode(), durationMs, attempt + 1, maxRetries + 1,
                            brief(response.body()));
                    if (attempt < maxRetries && isRetryableStatus(response.statusCode())) {
                        recordAttempt(attempt + 1, "ATTEMPT_RETRY", errorCode,
                                "HTTP " + response.statusCode(), response.body(), durationMs);
                        sleepBeforeRetry(attempt, modelName, endpoint, "HTTP " + response.statusCode());
                        attempt++;
                        continue;
                    }
                    String message = buildHttpErrorMessage(response.statusCode(), response.body());
                    recordAttempt(attempt + 1, "ATTEMPT_FAILED", errorCode, message, response.body(), durationMs);
                    throw new LlmRuntimeException(errorCode, message);
                }
                try {
                    CompletionResponse completion = parseCompletion(response.body(), modelName, endpoint, durationMs);
                    recordAttempt(attempt + 1, "ATTEMPT_OK", null, null, completion.content(), durationMs);
                    return completion;
                } catch (LlmRuntimeException ex) {
                    if (attempt < maxRetries && isRetryableError(ex.errorCode())) {
                        recordAttempt(attempt + 1, "ATTEMPT_RETRY", ex.errorCode(), ex.getMessage(),
                                response.body(), durationMs);
                        sleepBeforeRetry(attempt, modelName, endpoint, ex.errorCode().name());
                        attempt++;
                        continue;
                    }
                    recordAttempt(attempt + 1, "ATTEMPT_FAILED", ex.errorCode(), ex.getMessage(),
                            response.body(), durationMs);
                    throw ex;
                }
            } catch (HttpTimeoutException e) {
                long durationMs = System.currentTimeMillis() - startedAt;
                log.warn("LLM call timeout: model={}, endpoint={}, timeoutSeconds={}, durationMs={}, attempt={}/{}",
                        modelName, endpoint, requestTimeoutSeconds, durationMs, attempt + 1, maxRetries + 1);
                if (attempt < maxRetries) {
                    recordAttempt(attempt + 1, "ATTEMPT_RETRY", LlmErrorCode.TIMEOUT,
                            "模型调用超时，准备重试", null, durationMs);
                    sleepBeforeRetry(attempt, modelName, endpoint, "timeout");
                    attempt++;
                    continue;
                }
                String message = "模型调用超时（超过 " + requestTimeoutSeconds
                        + " 秒）。通常由模型响应慢、网络波动、上下文过长或一次生成内容过多导致，请稍后重试或减少本轮输入范围";
                recordAttempt(attempt + 1, "ATTEMPT_FAILED", LlmErrorCode.TIMEOUT, message, null, durationMs);
                throw new LlmRuntimeException(LlmErrorCode.TIMEOUT, message);
            } catch (IOException e) {
                long durationMs = System.currentTimeMillis() - startedAt;
                log.warn("LLM call network error: model={}, endpoint={}, durationMs={}, attempt={}/{}, error={}",
                        modelName, endpoint, durationMs, attempt + 1, maxRetries + 1, e.getMessage());
                if (attempt < maxRetries) {
                    recordAttempt(attempt + 1, "ATTEMPT_RETRY", LlmErrorCode.PROVIDER_ERROR,
                            "网络异常，准备重试：" + e.getMessage(), null, durationMs);
                    sleepBeforeRetry(attempt, modelName, endpoint, "network error");
                    attempt++;
                    continue;
                }
                String message = "模型调用失败：网络异常，" + e.getMessage();
                recordAttempt(attempt + 1, "ATTEMPT_FAILED", LlmErrorCode.PROVIDER_ERROR,
                        message, null, durationMs);
                throw new LlmRuntimeException(LlmErrorCode.PROVIDER_ERROR, message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmRuntimeException(LlmErrorCode.TIMEOUT, "模型调用被中断");
            }
        }
    }

    private String buildPayload(ModelConfigService.RuntimeModelConfig model, String systemPrompt, String userPrompt,
                                Integer maxTokens, boolean jsonObjectMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.modelName());
        body.put("temperature", 0.2);
        if (maxTokens != null && maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        if (jsonObjectMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (usesOfficialDeepSeekEndpoint(model.endpoint())) {
            body.put("thinking", Map.of("type", normalizedDeepSeekThinkingMode()));
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", emptySafe(systemPrompt)),
                Map.of("role", "user", "content", emptySafe(userPrompt))
        ));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new LlmRuntimeException(LlmErrorCode.INVALID_REQUEST, "模型请求序列化失败");
        }
    }

    private boolean usesOfficialDeepSeekEndpoint(String endpoint) {
        try {
            String host = URI.create(endpoint == null ? "" : endpoint.trim()).getHost();
            return "api.deepseek.com".equalsIgnoreCase(host);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String normalizedDeepSeekThinkingMode() {
        return "enabled".equalsIgnoreCase(deepseekThinkingMode == null ? "" : deepseekThinkingMode.trim())
                ? "enabled" : "disabled";
    }

    private CompletionResponse parseCompletion(String body, String modelName, String endpoint, long durationMs) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            JsonNode content = message.path("content");
            if (!content.isMissingNode() && !content.isNull() && !content.asText("").isBlank()) {
                JsonNode usage = root.path("usage");
                return new CompletionResponse(
                        content.asText(""),
                        positiveIntOrNull(usage.path("prompt_tokens")),
                        positiveIntOrNull(usage.path("completion_tokens")),
                        cachedPromptTokens(usage));
            }
            String reasoning = message.path("reasoning_content").asText("");
            if (!reasoning.isBlank()) {
                String finishReason = choice.path("finish_reason").asText("unknown");
                log.warn("LLM call exhausted reasoning without final content: model={}, endpoint={}, durationMs={}, finishReason={}, reasoningChars={}",
                        modelName, endpoint, durationMs, finishReason, reasoning.length());
                throw new LlmRuntimeException(LlmErrorCode.REASONING_EXHAUSTED,
                        "模型已返回推理内容，但未产出最终正文。该请求的推理或输出预算已耗尽，"
                                + "请由平台改用更小的补齐节点，或在模型侧降低推理强度/提高输出额度。");
            }
            log.warn("LLM call returned empty content: model={}, endpoint={}, durationMs={}, response={}",
                    modelName, endpoint, durationMs, brief(body));
            throw new LlmRuntimeException(LlmErrorCode.PROVIDER_ERROR, "模型返回内容为空");
        } catch (JsonProcessingException e) {
            log.warn("LLM response parse failed: model={}, endpoint={}, durationMs={}, response={}",
                    modelName, endpoint, durationMs, brief(body));
            throw new LlmRuntimeException(LlmErrorCode.PROVIDER_ERROR, "模型返回解析失败");
        }
    }

    private Integer cachedPromptTokens(JsonNode usage) {
        Integer direct = nonNegativeIntOrNull(usage.path("prompt_cache_hit_tokens"));
        if (direct != null) return direct;
        return nonNegativeIntOrNull(usage.path("prompt_tokens_details").path("cached_tokens"));
    }

    private Integer nonNegativeIntOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.canConvertToInt()) return null;
        int value = node.asInt();
        return value < 0 ? null : value;
    }

    private Integer positiveIntOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.canConvertToInt()) {
            return null;
        }
        int value = node.asInt();
        return value > 0 ? value : null;
    }

    private void recordAttempt(int attemptIndex,
                               String status,
                               LlmErrorCode errorCode,
                               String errorMessage,
                               String rawOutputSnapshot,
                               long durationMs) {
        if (attemptLogger == null) {
            return;
        }
        attemptLogger.record(attemptIndex, status, errorCode, errorMessage, rawOutputSnapshot, durationMs);
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

    private LlmErrorCode classifyHttpStatus(int statusCode, String responseBody) {
        String body = responseBody == null ? "" : responseBody.toLowerCase();
        if (statusCode == 402 || body.contains("insufficient balance")
                || body.contains("insufficient_balance") || body.contains("余额不足")) {
            return LlmErrorCode.INSUFFICIENT_BALANCE;
        }
        if (statusCode == 401 || statusCode == 403) {
            if (body.contains("balance") || body.contains("quota") || body.contains("insufficient")) {
                return LlmErrorCode.INSUFFICIENT_BALANCE;
            }
            return LlmErrorCode.AUTH_ERROR;
        }
        if (statusCode == 408 || statusCode == 524) return LlmErrorCode.TIMEOUT;
        if (statusCode == 429) return LlmErrorCode.RATE_LIMITED;
        if (statusCode == 400 && (body.contains("context") || body.contains("maximum context")
                || body.contains("token") || body.contains("too long"))) {
            return LlmErrorCode.CONTEXT_TOO_LONG;
        }
        if (statusCode == 400 || statusCode == 404) return LlmErrorCode.INVALID_REQUEST;
        if (statusCode >= 500) return LlmErrorCode.PROVIDER_ERROR;
        return LlmErrorCode.UNKNOWN_ERROR;
    }

    private String buildHttpErrorMessage(int statusCode, String responseBody) {
        String body = brief(responseBody);
        return switch (statusCode) {
            case 402 -> "模型调用失败，HTTP 402：模型账号余额不足或额度不可用，请检查模型服务账户余额/套餐后重试。"
                    + appendResponse(body);
            case 401, 403 -> "模型调用失败，HTTP " + statusCode
                    + "：模型服务拒绝访问。可能原因：API Key 无效、余额/权限不足，或当前模型不允许调用。"
                    + appendResponse(body);
            case 408 -> "模型调用失败，HTTP 408：模型服务请求超时。可能原因：网络波动、模型响应慢或请求内容过大。"
                    + appendResponse(body);
            case 429 -> "模型调用失败，HTTP 429：模型服务限流。可能原因：请求过于频繁、额度不足或并发超过限制。"
                    + appendResponse(body);
            case 524 -> "模型调用失败，HTTP 524：上游模型网关等待响应超时。可能原因：中转服务超时、模型响应过慢、上下文过长或模型服务繁忙。建议先用短需求验证该模型配置，仍失败则更换 endpoint/模型。"
                    + appendResponse(body);
            case 500, 502, 503, 504 -> "模型调用失败，HTTP " + statusCode
                    + "：模型服务或中转网关异常。可能原因：服务临时不可用、网关转发失败或上游模型超时。"
                    + appendResponse(body);
            default -> "模型调用失败，HTTP " + statusCode + appendResponse(body);
        };
    }

    private String appendResponse(String body) {
        return body == null || body.isBlank() ? "" : " 返回摘要：" + body;
    }

    private void acquirePermit(String modelName, String endpoint) {
        try {
            if (!concurrencyLimiter.tryAcquire()) {
                log.info("LLM concurrency limit reached, waiting: model={}, endpoint={}, availablePermits={}",
                        modelName, endpoint, concurrencyLimiter.availablePermits());
                concurrencyLimiter.acquire();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmRuntimeException(LlmErrorCode.TIMEOUT, "模型调用排队等待被中断");
        }
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode == 524
                || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private boolean isRetryableError(LlmErrorCode errorCode) {
        return errorCode == LlmErrorCode.PROVIDER_ERROR
                || errorCode == LlmErrorCode.TIMEOUT
                || errorCode == LlmErrorCode.RATE_LIMITED
                || errorCode == LlmErrorCode.UNKNOWN_ERROR;
    }

    private void sleepBeforeRetry(int attempt, String modelName, String endpoint, String reason) {
        long baseDelay = retryBackoffMillis * (1L << Math.min(attempt, 4));
        long jitter = baseDelay <= 0 ? 0 : ThreadLocalRandom.current().nextLong(Math.max(1, baseDelay / 2 + 1));
        long delay = baseDelay + jitter;
        log.info("LLM call retry scheduled: model={}, endpoint={}, reason={}, delayMs={}, nextAttempt={}",
                modelName, endpoint, reason, delay, attempt + 2);
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmRuntimeException(LlmErrorCode.TIMEOUT, "模型调用重试等待被中断");
        }
    }

    private boolean shouldUseJsonObjectMode(String systemPrompt, String userPrompt) {
        String text = (emptySafe(systemPrompt) + "\n" + emptySafe(userPrompt)).toLowerCase();
        boolean asksJson = text.contains("json");
        boolean asksArray = text.contains("json 数组") || text.contains("json数组") || text.contains("array");
        return jsonResponseFormatEnabled && asksJson && !asksArray;
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
