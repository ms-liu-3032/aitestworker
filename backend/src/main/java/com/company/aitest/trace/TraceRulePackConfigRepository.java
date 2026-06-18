package com.company.aitest.trace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TraceRulePackConfigRepository {
    private static final Logger log = LoggerFactory.getLogger(TraceRulePackConfigRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    TraceRulePackConfigRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    List<RulePackConfig> loadActiveGlobalPacks() {
        return loadActivePacks(null);
    }

    List<RulePackConfig> loadActivePacks(Long projectId) {
        try {
            if (projectId == null) {
                return jdbc.sql("""
                        SELECT pack_name, config_json
                        FROM trace_rule_pack_config
                        WHERE project_id IS NULL AND status = 'ACTIVE'
                        ORDER BY priority DESC, updated_at DESC, id DESC
                        """)
                        .query(this::mapConfig)
                        .list()
                        .stream()
                        .filter(item -> item.config() != null)
                        .toList();
            }
            return jdbc.sql("""
                    SELECT pack_name, config_json
                    FROM trace_rule_pack_config
                    WHERE status = 'ACTIVE'
                      AND (project_id IS NULL OR project_id = :projectId)
                    ORDER BY CASE WHEN project_id = :projectId THEN 0 ELSE 1 END,
                             priority DESC, updated_at DESC, id DESC
                    """)
                    .param("projectId", projectId)
                    .query(this::mapConfig)
                    .list()
                    .stream()
                    .filter(item -> item.config() != null)
                    .toList();
        } catch (Exception ex) {
            log.warn("加载数据库轨迹规则包失败（非致命）: {}", ex.getMessage());
            return List.of();
        }
    }

    private RulePackConfig mapConfig(ResultSet rs, int rowNum) throws SQLException {
        String packName = rs.getString("pack_name");
        String configJson = rs.getString("config_json");
        try {
            TraceRuleSetConfig config = objectMapper.readValue(configJson, TraceRuleSetConfig.class);
            return new RulePackConfig(packName, config);
        } catch (Exception ex) {
            log.warn("忽略无效轨迹规则包 {}: {}", packName, ex.getMessage());
            return new RulePackConfig(packName, null);
        }
    }

    record RulePackConfig(String name, TraceRuleSetConfig config) {
    }
}
