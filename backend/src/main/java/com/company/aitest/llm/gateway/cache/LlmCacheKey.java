package com.company.aitest.llm.gateway.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 唯一允许构造的 LLM 相关缓存 key 工具。
 * <p>
 * 业务代码禁止自己拼字符串 / 使用全局 key（latest_context / current_context / last_messages 等）。
 * <p>
 * 命名范式（见 docs/handover/07_LLM数据污染治理方案.md §10.2）：
 * <ul>
 *   <li>{@code ctx:{projectId}:{userId}:{taskId}:{stage}}</li>
 *   <li>{@code rag:{projectId}:{userId}:{scope}:{queryHash}}</li>
 *   <li>{@code prompt:{promptId}:{version}:{contentHash}}</li>
 *   <li>{@code trace:{projectId}:{userId}:{traceGroupId}}</li>
 *   <li>{@code quota:{userId}:{yyyy-MM-dd}}</li>
 *   <li>{@code quota:project:{projectId}:{yyyy-MM-dd}}</li>
 * </ul>
 * 所有 key 自带 {@code aitest:llm:} namespace 前缀，便于 redis 监控与清理。
 */
public final class LlmCacheKey {

    private static final String PREFIX = "aitest:llm:";

    private LlmCacheKey() {
    }

    public static String ctx(Long projectId, Long userId, Long taskId, String stage) {
        require(projectId, "projectId");
        require(userId, "userId");
        require(taskId, "taskId");
        Objects.requireNonNull(stage, "stage");
        return PREFIX + "ctx:" + projectId + ":" + userId + ":" + taskId + ":" + stage;
    }

    public static String rag(Long projectId, Long userId, String scope, String queryText) {
        require(projectId, "projectId");
        require(userId, "userId");
        Objects.requireNonNull(scope, "scope");
        return PREFIX + "rag:" + projectId + ":" + userId + ":" + scope + ":" + sha256Short(queryText);
    }

    public static String prompt(Long promptId, Integer version, String contentHash) {
        require(promptId, "promptId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(contentHash, "contentHash");
        return PREFIX + "prompt:" + promptId + ":" + version + ":" + contentHash;
    }

    public static String trace(Long projectId, Long userId, Long traceGroupId) {
        require(projectId, "projectId");
        require(userId, "userId");
        require(traceGroupId, "traceGroupId");
        return PREFIX + "trace:" + projectId + ":" + userId + ":" + traceGroupId;
    }

    public static String userQuota(Long userId, LocalDate date) {
        require(userId, "userId");
        Objects.requireNonNull(date, "date");
        return PREFIX + "quota:" + userId + ":" + date;
    }

    public static String projectQuota(Long projectId, LocalDate date) {
        require(projectId, "projectId");
        Objects.requireNonNull(date, "date");
        return PREFIX + "quota:project:" + projectId + ":" + date;
    }

    private static void require(Long v, String name) {
        if (v == null) throw new IllegalArgumentException(name + " 必填");
    }

    private static String sha256Short(String text) {
        String payload = text == null ? "" : text;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            // 取前 8 字节 = 16 hex，足以避免缓存碰撞
            byte[] head = new byte[8];
            System.arraycopy(digest, 0, head, 0, 8);
            return HexFormat.of().formatHex(head);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
