package com.company.aitest.llm.gateway;

import java.util.UUID;

import com.company.aitest.common.BusinessException;
import com.company.aitest.llm.LlmAdapter;
import com.company.aitest.llm.gateway.audit.LlmInvocationLogEntry;
import com.company.aitest.llm.gateway.audit.LlmInvocationLogger;
import com.company.aitest.llm.gateway.context.ContextManifest;
import com.company.aitest.llm.gateway.context.ContextManifestRepository;
import com.company.aitest.llm.gateway.prompt.PromptSnapshotEntry;
import com.company.aitest.llm.gateway.prompt.PromptSnapshotService;
import com.company.aitest.llm.gateway.retrieval.RagRetrievalService;
import com.company.aitest.llm.gateway.retrieval.RetrievalPolicy;
import com.company.aitest.llm.gateway.retrieval.RetrievalResult;
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

    public LlmGatewayImpl(LlmAdapter llmAdapter,
                         PromptSnapshotService promptSnapshotService,
                         LlmInvocationLogger invocationLogger,
                         RagRetrievalService ragRetrievalService,
                         ContextManifestRepository contextManifestRepository) {
        this.llmAdapter = llmAdapter;
        this.promptSnapshotService = promptSnapshotService;
        this.invocationLogger = invocationLogger;
        this.ragRetrievalService = ragRetrievalService;
        this.contextManifestRepository = contextManifestRepository;
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

        // step 2: TODO 限流 (Sprint 6 · LlmQuotaService)
        // step 7: TODO Prompt Injection 扫描 (Sprint 5 · PromptInjectionGuard)
        // step 8: TODO 入参脱敏 (Sprint 5 · SensitiveDataMasker)

        String systemPrompt = nullSafe(request.systemPromptOverride());
        String userPrompt = nullSafe(request.userPromptOverride());

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
        String content;
        try {
            content = llmAdapter.complete(new LlmAdapter.CompletionRequest(
                    request.modelConfigId(), systemPrompt, userPrompt, request.maxTokens()));
        } catch (BusinessException ex) {
            return logAndReturn(request, requestId, promptSnapshotId, manifestId, "", 0, 0,
                    LlmInvocationStatus.MODEL_ERROR,
                    "MODEL_ERROR", ex.getMessage(), started);
        } catch (RuntimeException ex) {
            return logAndReturn(request, requestId, promptSnapshotId, manifestId, "", 0, 0,
                    LlmInvocationStatus.MODEL_ERROR,
                    "UNEXPECTED", ex.getMessage(), started);
        }

        // step 10: TODO 出参脱敏

        // step 11: 写日志（成功）
        long duration = System.currentTimeMillis() - started;
        int tokenIn = estimateTokens(systemPrompt) + estimateTokens(userPrompt);
        int tokenOut = estimateTokens(content);
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
                manifestId,
                tokenIn,
                tokenOut,
                LlmInvocationStatus.OK.name(),
                null, null, duration));

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

    private String nullSafe(String s) {
        return s == null ? "" : s;
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
                null, null, manifestId,
                tokenIn, tokenOut,
                status.name(), errorCode, errorMessage, duration));
        return new LlmInvocationResponse(
                requestId, content, tokenIn, tokenOut, duration,
                logId, manifestId, promptSnapshotId,
                status, errorCode, errorMessage);
    }
}
