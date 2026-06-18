package com.company.aitest.llm.gateway;

/**
 * LLM 调用的唯一入口。
 * <p>
 * 业务服务必须通过此接口调用模型；禁止直接持有 {@link com.company.aitest.llm.LlmAdapter}。
 * <p>
 * 完整流程（M1 实现校验 → snapshot → adapter → log，其余 step 在后续 Sprint 接入）：
 * <ol>
 *   <li>校验 user/project/task/stage/modelConfig 与权限</li>
 *   <li>限流（{@code LlmQuotaService}）</li>
 *   <li>取 prompt_template + 渲染 + 落 {@code prompt_snapshot}</li>
 *   <li>RAG 检索（{@code RagRetrievalService}，带权限/作用域/状态过滤）</li>
 *   <li>组装当前任务上下文（仅本任务 + 命中过滤的资产）</li>
 *   <li>生成 {@code context_manifest} 并入库</li>
 *   <li>{@code PromptInjectionGuard} 扫描外部资料</li>
 *   <li>{@code SensitiveDataMasker} 对入参脱敏</li>
 *   <li>调 {@code LlmAdapter.complete}</li>
 *   <li>{@code SensitiveDataMasker} 对出参脱敏</li>
 *   <li>{@code LlmInvocationLogger} 记录</li>
 * </ol>
 *
 * @see docs/handover/07_LLM数据污染治理方案.md §3
 */
public interface LlmGateway {

    /**
     * 同步执行 LLM 调用并返回结构化结果。
     * 实现必须保证：即使内部任何阶段抛错，也会有 invocation_log 写入，
     * 并通过 {@link LlmInvocationResponse#status()} 暴露失败原因，不抛 RuntimeException。
     */
    LlmInvocationResponse invoke(LlmInvocationRequest request);
}
