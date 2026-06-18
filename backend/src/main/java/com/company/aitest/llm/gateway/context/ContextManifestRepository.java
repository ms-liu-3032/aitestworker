package com.company.aitest.llm.gateway.context;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.TimeProvider;
import com.company.aitest.llm.gateway.retrieval.RetrievedAsset;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * 写 context_manifest 表。失败不抛业务异常（与 LlmInvocationLogger 一致）。
 */
@Component
public class ContextManifestRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ContextManifestRepository(JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    public Long save(ContextManifest manifest) {
        LocalDateTime now = timeProvider.now();
        try {
            String includedJson = serializeAssets(manifest.includedAssets());
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        insert into context_manifest(
                          request_id, user_id, project_id, task_id, stage,
                          model_config_id, prompt_template_id, prompt_version,
                          included_assets_json, excluded_policy_json, conflicts_json, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                int i = 1;
                ps.setString(i++, manifest.requestId());
                ps.setLong(i++, manifest.userId());
                if (manifest.projectId() == null) ps.setNull(i++, java.sql.Types.BIGINT);
                else ps.setLong(i++, manifest.projectId());
                if (manifest.taskId() == null) ps.setNull(i++, java.sql.Types.BIGINT);
                else ps.setLong(i++, manifest.taskId());
                ps.setString(i++, manifest.stage());
                if (manifest.modelConfigId() == null) ps.setNull(i++, java.sql.Types.BIGINT);
                else ps.setLong(i++, manifest.modelConfigId());
                if (manifest.promptTemplateId() == null) ps.setNull(i++, java.sql.Types.BIGINT);
                else ps.setLong(i++, manifest.promptTemplateId());
                if (manifest.promptVersion() == null) ps.setNull(i++, java.sql.Types.INTEGER);
                else ps.setInt(i++, manifest.promptVersion());
                ps.setString(i++, includedJson);
                ps.setString(i++, manifest.excludedPolicyJson());
                ps.setString(i++, manifest.conflictsJson());
                ps.setObject(i, now);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            return key == null ? null : key.longValue();
        } catch (RuntimeException ex) {
            System.err.println("[ContextManifestRepository] save failed: " + ex.getMessage());
            return null;
        }
    }

    private String serializeAssets(List<RetrievedAsset> assets) {
        try {
            List<Map<String, Object>> out = new java.util.ArrayList<>(assets.size());
            for (RetrievedAsset a : assets) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("assetId", a.assetId());
                m.put("assetType", a.assetType());
                m.put("scope", a.scope());
                m.put("sourceType", a.sourceType());
                m.put("sourceRefId", a.sourceRefId());
                m.put("status", a.status());
                m.put("trustLevel", a.trustLevel());
                m.put("title", a.title());
                m.put("contentHash", a.contentHash());
                m.put("similarity", a.similarity());
                out.add(m);
            }
            return objectMapper.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
