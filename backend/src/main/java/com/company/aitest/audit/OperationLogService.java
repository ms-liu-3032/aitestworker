package com.company.aitest.audit;

import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OperationLogService {
    private final JdbcTemplate jdbcTemplate;

    public OperationLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(Long operatorId, String action, String targetType, Long targetId, String detail) {
        jdbcTemplate.update("""
                insert into operation_log(operator_id, action, target_type, target_id, detail, created_at)
                values (?, ?, ?, ?, ?, ?)
                """, operatorId, action, targetType, targetId, detail, LocalDateTime.now());
    }

    public void recordQuietly(Long operatorId, String action, String targetType, Long targetId, String detail) {
        try {
            record(operatorId, action, targetType, targetId, detail);
        } catch (RuntimeException ignored) {
            // 审计日志失败不影响主业务；部署侧可通过应用日志排查数据库问题。
        }
    }
}
