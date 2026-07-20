package com.company.aitest.llm.gateway.guard;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.company.aitest.llm.gateway.LlmInvocationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 轻量限流，保护开发/小团队试用场景。
 * <p>
 * 默认使用内存窗口；多实例部署可切换为 DB 窗口，依赖
 * {@code llm_quota_usage_minute} 的唯一键原子累加。
 */
@Component
public class LlmQuotaService {
    private static final Logger log = LoggerFactory.getLogger(LlmQuotaService.class);

    private final boolean enabled;
    private final String mode;
    private final int userPerMinuteLimit;
    private final int projectPerMinuteLimit;
    private final Clock clock;
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Autowired
    public LlmQuotaService(@Value("${aitest.llm.quota.enabled:true}") boolean enabled,
                           @Value("${aitest.llm.quota.mode:MEMORY}") String mode,
                           @Value("${aitest.llm.quota.user-per-minute:120}") int userPerMinuteLimit,
                           @Value("${aitest.llm.quota.project-per-minute:600}") int projectPerMinuteLimit,
                           JdbcTemplate jdbcTemplate) {
        this(enabled, mode, userPerMinuteLimit, projectPerMinuteLimit, Clock.systemUTC(), jdbcTemplate);
    }

    public LlmQuotaService(boolean enabled, int userPerMinuteLimit, int projectPerMinuteLimit) {
        this(enabled, "MEMORY", userPerMinuteLimit, projectPerMinuteLimit, Clock.systemUTC(), null);
    }

    LlmQuotaService(boolean enabled, int userPerMinuteLimit, int projectPerMinuteLimit, Clock clock) {
        this(enabled, "MEMORY", userPerMinuteLimit, projectPerMinuteLimit, clock, null);
    }

    LlmQuotaService(boolean enabled, String mode, int userPerMinuteLimit, int projectPerMinuteLimit,
                    Clock clock, JdbcTemplate jdbcTemplate) {
        this.enabled = enabled;
        this.mode = normalizeMode(mode);
        this.userPerMinuteLimit = Math.max(1, userPerMinuteLimit);
        this.projectPerMinuteLimit = Math.max(1, projectPerMinuteLimit);
        this.clock = clock;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Decision tryAcquire(LlmInvocationRequest request) {
        if (!enabled || request == null) {
            return Decision.allow();
        }
        long minute = clock.millis() / 60_000L;
        cleanup(minute);
        if (request.userId() != null) {
            Decision userDecision = acquire("u:" + request.userId(), minute, userPerMinuteLimit, "USER_PER_MINUTE");
            if (!userDecision.allowed()) {
                return userDecision;
            }
        }
        if (request.projectId() != null) {
            return acquire("p:" + request.projectId(), minute, projectPerMinuteLimit, "PROJECT_PER_MINUTE");
        }
        return Decision.allow();
    }

    private Decision acquire(String key, long minute, int limit, String scope) {
        if ("DB".equals(mode) && jdbcTemplate != null) {
            try {
                return acquireDb(key, minute, limit, scope);
            } catch (RuntimeException ex) {
                log.warn("LLM DB quota failed, fallback to memory quota: {}", ex.getMessage());
            }
        }
        return acquireMemory(key, minute, limit, scope);
    }

    private Decision acquireMemory(String key, long minute, int limit, String scope) {
        WindowCounter counter = counters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.minute != minute) {
                return new WindowCounter(minute);
            }
            return existing;
        });
        int value = counter.count.incrementAndGet();
        if (value > limit) {
            return Decision.rejected(scope, limit);
        }
        return Decision.allow();
    }

    private Decision acquireDb(String key, long minute, int limit, String scope) {
        LocalDateTime now = LocalDateTime.now(clock);
        jdbcTemplate.update("""
                insert into llm_quota_usage_minute(quota_key, window_minute, count_value, updated_at)
                values (?, ?, 1, ?)
                on duplicate key update count_value = count_value + 1, updated_at = values(updated_at)
                """, key, minute, now);
        Integer value = jdbcTemplate.queryForObject("""
                select count_value
                from llm_quota_usage_minute
                where quota_key = ? and window_minute = ?
                """, Integer.class, key, minute);
        if (value != null && value > limit) {
            return Decision.rejected(scope, limit);
        }
        cleanupDb(minute);
        return Decision.allow();
    }

    private void cleanupDb(long currentMinute) {
        if (currentMinute % 10 != 0) {
            return;
        }
        jdbcTemplate.update("delete from llm_quota_usage_minute where window_minute < ?", currentMinute - 10);
    }

    private void cleanup(long currentMinute) {
        if (!"MEMORY".equals(mode) || counters.size() < 512) {
            return;
        }
        Iterator<Map.Entry<String, WindowCounter>> iterator = counters.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().minute < currentMinute) {
                iterator.remove();
            }
        }
    }

    private String normalizeMode(String value) {
        if (value == null) {
            return "MEMORY";
        }
        String normalized = value.trim().toUpperCase();
        return "DB".equals(normalized) ? "DB" : "MEMORY";
    }

    private static final class WindowCounter {
        private final long minute;
        private final AtomicInteger count = new AtomicInteger();

        private WindowCounter(long minute) {
            this.minute = minute;
        }
    }

    public record Decision(boolean allowed, String scope, int limit) {
        static Decision allow() {
            return new Decision(true, null, 0);
        }

        static Decision rejected(String scope, int limit) {
            return new Decision(false, scope, limit);
        }
    }
}
