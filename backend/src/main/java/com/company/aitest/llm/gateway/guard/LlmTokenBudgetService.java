package com.company.aitest.llm.gateway.guard;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.company.aitest.llm.gateway.LlmInvocationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 日级 token 预算。默认关闭，避免影响本地试用。
 * <p>
 * 开启后使用已有 {@code llm_quota_usage_day} 记录 user/project 的日累计 token。
 */
@Component
public class LlmTokenBudgetService {
    private static final Logger log = LoggerFactory.getLogger(LlmTokenBudgetService.class);

    private final boolean enabled;
    private final int userDailyTokenLimit;
    private final int projectDailyTokenLimit;
    private final Clock clock;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public LlmTokenBudgetService(@Value("${aitest.llm.budget.enabled:false}") boolean enabled,
                                 @Value("${aitest.llm.budget.user-daily-tokens:0}") int userDailyTokenLimit,
                                 @Value("${aitest.llm.budget.project-daily-tokens:0}") int projectDailyTokenLimit,
                                 JdbcTemplate jdbcTemplate) {
        this(enabled, userDailyTokenLimit, projectDailyTokenLimit, Clock.systemDefaultZone(), jdbcTemplate);
    }

    public LlmTokenBudgetService(boolean enabled, int userDailyTokenLimit, int projectDailyTokenLimit) {
        this(enabled, userDailyTokenLimit, projectDailyTokenLimit, Clock.systemDefaultZone(), null);
    }

    LlmTokenBudgetService(boolean enabled, int userDailyTokenLimit, int projectDailyTokenLimit,
                          Clock clock, JdbcTemplate jdbcTemplate) {
        this.enabled = enabled;
        this.userDailyTokenLimit = Math.max(0, userDailyTokenLimit);
        this.projectDailyTokenLimit = Math.max(0, projectDailyTokenLimit);
        this.clock = clock;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Decision checkBeforeCall(LlmInvocationRequest request, int estimatedInputTokens) {
        if (!enabled || request == null || jdbcTemplate == null) {
            return Decision.allow();
        }
        int estimated = Math.max(0, estimatedInputTokens);
        LocalDate date = LocalDate.now(clock);
        try {
            if (request.userId() != null && userDailyTokenLimit > 0) {
                int used = usedTokens(request.userId(), 0L, date);
                if (used + estimated > userDailyTokenLimit) {
                    return Decision.rejected("USER_DAILY_TOKENS", userDailyTokenLimit, used);
                }
            }
            if (request.projectId() != null && projectDailyTokenLimit > 0) {
                int used = usedTokens(0L, request.projectId(), date);
                if (used + estimated > projectDailyTokenLimit) {
                    return Decision.rejected("PROJECT_DAILY_TOKENS", projectDailyTokenLimit, used);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("LLM token budget check failed, allow current call: {}", ex.getMessage());
        }
        return Decision.allow();
    }

    public void recordUsage(LlmInvocationRequest request, int tokenInput, int tokenOutput) {
        if (!enabled || request == null || jdbcTemplate == null) {
            return;
        }
        int input = Math.max(0, tokenInput);
        int output = Math.max(0, tokenOutput);
        if (input + output <= 0) {
            return;
        }
        LocalDate date = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            if (request.userId() != null) {
                upsertUsage(request.userId(), 0L, date, input, output, now);
            }
            if (request.projectId() != null) {
                upsertUsage(0L, request.projectId(), date, input, output, now);
            }
        } catch (RuntimeException ex) {
            log.warn("LLM token budget usage record failed: {}", ex.getMessage());
        }
    }

    private int usedTokens(Long userId, Long projectId, LocalDate date) {
        Integer value = jdbcTemplate.queryForObject("""
                select coalesce(sum(token_input + token_output), 0)
                from llm_quota_usage_day
                where stat_date = ?
                  and user_id = ?
                  and project_id = ?
                """, Integer.class, date, userId, projectId);
        return value == null ? 0 : value;
    }

    private void upsertUsage(Long userId, Long projectId, LocalDate date, int input, int output, LocalDateTime now) {
        jdbcTemplate.update("""
                insert into llm_quota_usage_day(user_id, project_id, stat_date, token_input, token_output, call_count, updated_at)
                values (?, ?, ?, ?, ?, 1, ?)
                on duplicate key update
                  token_input = token_input + values(token_input),
                  token_output = token_output + values(token_output),
                  call_count = call_count + 1,
                  updated_at = values(updated_at)
                """, userId, projectId, date, input, output, now);
    }

    public record Decision(boolean allowed, String scope, int limit, int used) {
        public static Decision allow() {
            return new Decision(true, null, 0, 0);
        }

        public static Decision rejected(String scope, int limit, int used) {
            return new Decision(false, scope, limit, used);
        }
    }
}
