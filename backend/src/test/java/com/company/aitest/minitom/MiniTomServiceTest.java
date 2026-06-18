package com.company.aitest.minitom;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MiniTomServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void selectSemanticCandidatesKeepsSystemTomWhenKeywordPrefilterMisses() throws Exception {
        var service = new MiniTomService(null, null, null, null, null, null, null);
        List<TestObjectModelRecord> toms = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            toms.add(tom((long) i + 1, 3L, "PROJECT", "FIELD", "项目字段" + i));
        }
        toms.add(tom(1001L, 0L, "SYSTEM", "FLOW", "公共审批流"));

        Method method = MiniTomService.class.getDeclaredMethod("selectSemanticCandidates", List.class, List.class);
        method.setAccessible(true);
        List<TestObjectModelRecord> selected = (List<TestObjectModelRecord>) method.invoke(
                service,
                toms,
                List.of("完全不相关关键词")
        );

        assertTrue(selected.size() <= 80);
        assertTrue(selected.stream().anyMatch(tom -> "SYSTEM".equals(tom.scope()) && "公共审批流".equals(tom.name())));
    }

    private TestObjectModelRecord tom(Long id, Long projectId, String scope, String type, String name) {
        LocalDateTime now = LocalDateTime.of(2026, 6, 16, 10, 0);
        return new TestObjectModelRecord(
                id,
                projectId,
                scope,
                null,
                null,
                type,
                name,
                name + " 描述",
                "{}",
                "TEST",
                id,
                name + " 上下文",
                BigDecimal.ONE,
                "ACTIVE",
                false,
                "VALID",
                1L,
                1L,
                now,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                "通用业务",
                "P1",
                "doc",
                "section",
                null,
                name + " evidence",
                null,
                1
        );
    }
}
