package com.company.aitest.llm.gateway;

import java.util.UUID;

import com.company.aitest.llm.LlmAdapter;
import com.company.aitest.llm.LlmAdapter.CompletionResponse;
import com.company.aitest.llm.gateway.audit.LlmAttemptContext;
import com.company.aitest.llm.gateway.audit.LlmInvocationLogEntry;
import com.company.aitest.llm.gateway.audit.LlmInvocationLogger;
import com.company.aitest.llm.gateway.context.ContextManifest;
import com.company.aitest.llm.gateway.context.ContextManifestRepository;
import com.company.aitest.llm.gateway.guard.LlmQuotaService;
import com.company.aitest.llm.gateway.guard.LlmTokenBudgetService;
import com.company.aitest.llm.gateway.guard.PromptInjectionGuard;
import com.company.aitest.llm.gateway.guard.SecurityEventLogger;
import com.company.aitest.llm.gateway.guard.SensitiveDataMasker;
import com.company.aitest.llm.gateway.prompt.PromptSnapshotEntry;
import com.company.aitest.llm.gateway.prompt.PromptSnapshotService;
import com.company.aitest.llm.gateway.retrieval.RagRetrievalService;
import com.company.aitest.llm.gateway.retrieval.RetrievalPolicy;
import com.company.aitest.llm.gateway.retrieval.RetrievalResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * LLM Gateway 主实现。
 * <p>
 * Sprint 1 实现：step 1 校验、step 3 prompt snapshot、step 9 adapter、step 11 写日志。
 * Sprint 2 接入：step 4 RAG 检索 → step 5/6 context_manifest 入库（结果 id 回写 invocation_log）。
 * Sprint 5 接入：step 7 注入扫描、step 8/10 脱敏；Sprint 6 接入 step 2 限流。
 */
@Component
public class LlmGatewayImpl implements LlmGateway {

    private final LlmAdapter llmAdapter;
    private final PromptSnapshotService promptSnapshotService;
    private final LlmInvocationLogger invocationLogger;
    private final RagRetrievalService ragRetrievalService;
    private final ContextManifestRepository contextManifestRepository;
    private final SensitiveDataMasker sensitiveDataMasker;
    private final PromptInjectionGuard promptInjectionGuard;
    private final SecurityEventLogger securityEventLogger;
    private final LlmQuotaService quotaService;
    private final LlmTokenBudgetService tokenBudgetService;

    @Autowired
    public LlmGatewayImpl(LlmAdapter llmAdapter,
                         PromptSnapshotService promptSnapshotService,
                         LlmInvocationLogger invocationLogger,
                         RagRetrievalService ragRetrievalService,
                         ContextManifestRepository contextManifestRepository,
                         SensitiveDataMasker sensitiveDataMasker,
                         PromptInjectionGuard promptInjectionGuard,
                         SecurityEventLogger securityEventLogger,
                         LlmQuotaService quotaService,
                         LlmTokenBudgetService tokenBudgetService) {
        this.llmAdapter = llmAdapter;
        this.promptSnapshotService = promptSnapshotService;
        this.invocationLogger = invocationLogger;
        this.ragRetrievalService = ragRetrievalService;
        this.contextManifestRepository = contextManifestRepository;
        this.sensitiveDataMasker = sensitiveDataMasker;
        this.promptInjectionGuard = promptInjectionGuard;
        this.securityEventLogger = securityEventLogger;
        this.quotaService = quotaService;
        this.tokenBudgetService = tokenBudgetService;
    }

    LlmGatewayImpl(LlmAdapter llmAdapter,
                   PromptSnapshotService promptSnapshotService,
                   LlmInvocationLogger invocationLogger,
                   RagRetrievalService ragRetrievalService,
                   ContextManifestRepository contextManifestRepository,
                   SensitiveDataMasker sensitiveDataMasker,
                   PromptInjectionGuard promptInjectionGuard,
                   SecurityEventLogger securityEventLogger,
                   LlmQuotaService quotaService) {
        this(llmAdapter, promptSnapshotService, invocationLogger, ragRetrievalService, contextManifestRepository,
                sensitiveDataMasker, promptInjectionGuard, securityEventLogger, quotaService,
                new LlmTokenBudgetService(false, 0, 0));
    }

    @Override
    public LlmInvocationResponse invoke(LlmInvocationRequest request) {
        String requestId = (request.requestId() == null || request.requestId().isBlank())
                ? UUID.randomUUID().toString()
                : request.requestId();
        long started = System.currentTimeMillis();

        // step 1: 校验
        String validationError = validate(request);
        if (validationError != null) {
            return logAndReturn(request, requestId, null, null, "", 0, 0,
                    LlmInvocationStatus.INVALID_REQUEST,
                    "INVALID_REQUEST", validationError, started);
        }

        String systemPrompt = nullSafe(request.systemPromptOverride());
        String userPrompt = nullSafe(request.userPromptOverride());

        // step 2: 轻量限流
        LlmQuotaService.Decision quota = quotaService.tryAcquire(request);
        if (!quota.allowed()) {
            recordSecurityEvent("LLM_RATE_LIMITED", "WARN", request, requestId,
                    "{\"scope\":\"" + quota.scope() + "\",\"limit\":" + quota.limit() + "}");
            return logAndReturn(request, requestId, null, null, "", 0, 0,
                    LlmInvocationStatus.QUOTA_EXCEEDED,
                    LlmErrorCode.RATE_LIMITED.name(),
                    "LLM 调用超过限流阈值：" + quota.scope() + " 每分钟最多 " + quota.limit() + " 次",
                    started);
        }

        // step 7: Prompt Injection 基础检测（记录事件，不阻断）
        PromptInjectionGuard.Result guardResult = promptInjectionGuard.scan(systemPrompt, userPrompt);
        if (guardResult.suspicious()) {
            recordSecurityEvent("PROMPT_INJECTION_SUSPECTED", "WARN", request, requestId,
                    "{\"signals\":\"" + String.join(",", guardResult.signals()) + "\"}");
            systemPrompt = systemPrompt + promptInjectionGuard.systemReminder(guardResult);
        }

        // step 8: 入参脱敏。只处理明显密钥/Token/密码类内容，避免破坏业务测试数据。
        systemPrompt = sensitiveDataMasker.mask(systemPrompt);
        userPrompt = sensitiveDataMasker.mask(userPrompt);

        int estimatedInputTokens = estimateTokens(systemPrompt) + estimateTokens(userPrompt);
        LlmTokenBudgetService.Decision budget = tokenBudgetService.checkBeforeCall(request, estimatedInputTokens);
        if (!budget.allowed()) {
            recordSecurityEvent("LLM_DAILY_TOKEN_BUDGET_EXCEEDED", "WARN", request, requestId,
                    "{\"scope\":\"" + budget.scope() + "\",\"limit\":" + budget.limit()
                            + ",\"used\":" + budget.used() + "}");
            return logAndReturn(request, requestId, null, null, "", estimatedInputTokens, 0,
                    LlmInvocationStatus.QUOTA_EXCEEDED,
                    LlmErrorCode.RATE_LIMITED.name(),
                    "LLM 调用超过日 token 预算：" + budget.scope()
                            + " 每日最多 " + budget.limit() + " tokens，当前已使用 " + budget.used(),
                    started);
        }

        // step 4: RAG 检索（按 stage 取默认 policy）
        RetrievalPolicy policy = RetrievalPolicy.forStage(request.stage(), userPrompt);
        RetrievalResult retrieval = safeRetrieve(request, policy);

        // step 3: prompt snapshot
        Long promptSnapshotId = safeSaveSnapshot(request, requestId, systemPrompt, userPrompt);

        // step 5/6: context_manifest 入库
        Long manifestId = contextManifestRepository.save(new ContextManifest(
                requestId,
                request.userId(),
                request.projectId(),
                request.taskId(),
                request.stage().name(),
                request.modelConfigId(),
                request.promptTemplateId(),
                request.promptVersion(),
                retrieval.assets(),
                retrieval.excludedPolicyJson(),
                null));

        // step 9: 调模型
        CompletionResponse completion;
        try {
            LlmAttemptContext.bind(requestId, request, promptSnapshotId, manifestId);
            completion = llmAdapter.completeWithUsage(new LlmAdapter.CompletionRequest(
                    request.modelConfigId(), systemPrompt, userPrompt, request.maxTokens()));
        } catch (LlmRuntimeException ex) {
            return logAndReturn(request, requestId, promptSnapshotId, manifestId, "", estimatedInputTokens, 0,
                    statusFor(ex.errorCode()),
                    ex.errorCode().name(), ex.getMessage(), started);
        } catch (RuntimeException ex) {
            return logAndReturn(request, requestId, promptSnapshotId, manifestId, "", estimatedInputTokens, 0,
                    LlmInvocationStatus.MODEL_ERROR,
                    LlmErrorCode.UNKNOWN_ERROR.name(), ex.getMessage(), started);
        } finally {
            LlmAttemptContext.clear();
        }

        // step 10: TODO 出参脱敏

        // step 11: 写日志（成功）
        long duration = System.currentTimeMillis() - started;
        String content = completion.content();
        int tokenIn = tokenOrFallback(completion.promptTokens(), estimatedInputTokens);
        int tokenOut = tokenOrFallback(completion.completionTokens(), estimateTokens(content));
        int tokenCachedIn = Math.min(tokenIn, Math.max(0,
                completion.cachedPromptTokens() == null ? 0 : completion.cachedPromptTokens()));
        Long logId = invocationLogger.record(new LlmInvocationLogEntry(
                requestId,
                request.userId(),
                request.projectId(),
                request.taskId(),
                request.taskType(),
                request.stage().name(),
                request.modelConfigId(),
                request.promptTemplateId(),
                request.promptVersion(),
                promptSnapshotId,
                null,
                null,
                null,
                snapshot(content),
                manifestId,
                tokenIn,
                tokenCachedIn,
                tokenOut,
                LlmInvocationStatus.OK.name(),
                null, null, duration));
        tokenBudgetService.recordUsage(request, tokenIn, tokenOut);

        return new LlmInvocationResponse(
                requestId, content, tokenIn, tokenOut, duration,
                logId, manifestId, promptSnapshotId,
                LlmInvocationStatus.OK, null, null);
    }

    private RetrievalResult safeRetrieve(LlmInvocationRequest request, RetrievalPolicy policy) {
        if (policy == null || policy.disabled()) {
            return RetrievalResult.disabled();
        }
        try {
            return ragRetrievalService.retrieve(request, policy);
        } catch (RuntimeException ex) {
            System.err.println("[LlmGateway] retrieval failed: " + ex.getMessage());
            return RetrievalResult.empty(
                    "{\"reason\":\"retrieval_runtime_error\",\"message\":\""
                            + ex.getMessage().replace("\"", "\\\"") + "\"}");
        }
    }

    private Long safeSaveSnapshot(LlmInvocationRequest request, String requestId,
                                  String systemPrompt, String userPrompt) {
        try {
            String hash = promptSnapshotService.contentHash(systemPrompt, userPrompt);
            return promptSnapshotService.save(new PromptSnapshotEntry(
                    requestId,
                    request.userId(),
                    request.projectId(),
                    request.taskId(),
                    request.stage().name(),
                    request.promptTemplateId(),
                    request.promptVersion(),
                    systemPrompt,
                    userPrompt,
                    promptSnapshotService.serializeVariables(request.variables()),
                    hash));
        } catch (RuntimeException ex) {
            System.err.println("[LlmGateway] prompt_snapshot save failed: " + ex.getMessage());
            return null;
        }
    }

    private String validate(LlmInvocationRequest req) {
        if (req.userId() == null) return "userId 必填";
        if (req.stage() == null) return "stage 必填";
        if (req.modelConfigId() == null) return "modelConfigId 必填";
        // 无任务上下文的阶段允许 taskId 为空
        if (req.taskId() == null && req.stage() != LlmStage.TOM_SEMANTIC_MATCH
                && req.stage() != LlmStage.MINI_TOM_EXTRACTION) {
            return "taskId 必填";
        }
        return null;
    }

    /** 粗略按 4 字符 ~ 1 token 估算；后续 Sprint 接入真实 tokenizer。 */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 4);
    }

    private int tokenOrFallback(Integer providerUsage, int fallback) {
        return providerUsage != null && providerUsage > 0 ? providerUsage : fallback;
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private LlmInvocationStatus statusFor(LlmErrorCode errorCode) {
        if (errorCode == LlmErrorCode.TIMEOUT) {
            return LlmInvocationStatus.TIMEOUT;
        }
        if (errorCode == LlmErrorCode.INVALID_REQUEST || errorCode == LlmErrorCode.MODEL_NOT_FOUND) {
            return LlmInvocationStatus.INVALID_REQUEST;
        }
        return LlmInvocationStatus.MODEL_ERROR;
    }

    private String snapshot(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String compact = sensitiveDataMasker.mask(content).replaceAll("\\s+", " ").trim();
        int max = 2000;
        return compact.length() <= max ? compact : compact.substring(0, max);
    }

    private void recordSecurityEvent(String eventType,
                                     String severity,
                                     LlmInvocationRequest request,
                                     String requestId,
                                     String detailJson) {
        try {
            securityEventLogger.record(eventType, severity, request, requestId, detailJson);
        } catch (RuntimeException ex) {
            System.err.println("[LlmGateway] security event record failed: " + ex.getMessage());
        }
    }

    private LlmInvocationResponse logAndReturn(LlmInvocationRequest req,
                                               String requestId,
                                               Long promptSnapshotId,
                                               Long manifestId,
                                               String content,
                                               int tokenIn,
                                               int tokenOut,
                                               LlmInvocationStatus status,
                                               String errorCode,
                                               String errorMessage,
                                               long started) {
        long duration = System.currentTimeMillis() - started;
        Long logId = invocationLogger.record(new LlmInvocationLogEntry(
                requestId,
                req.userId(),
                req.projectId(),
                req.taskId(),
                req.taskType(),
                req.stage() == null ? "UNKNOWN" : req.stage().name(),
                req.modelConfigId(),
                req.promptTemplateId(),
                req.promptVersion(),
                promptSnapshotId,
                null, null, null, snapshot(content), manifestId,
                tokenIn, tokenOut,
                status.name(), errorCode, errorMessage, duration));
        return new LlmInvocationResponse(
                requestId, content, tokenIn, tokenOut, duration,
                logId, manifestId, promptSnapshotId,
                status, errorCode, errorMessage);
    }
}
