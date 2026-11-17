package com.company.aitest.generation.session;

import com.company.aitest.common.TimeProvider;
import com.company.aitest.semantic.ProjectSemanticContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ContextContractServiceTest {

    @Test
    void reconstructsTheFrozenSemanticContextWithoutLiveRetrieval() {
        ContextContractService service = new ContextContractService(mock(JdbcTemplate.class), mock(TimeProvider.class), new ObjectMapper());
        ContextContractService.ContractView contract = new ContextContractService.ContractView(
                12L, 1, "FROZEN", Map.of("requirement_understanding", "提交申请后进入待审批"),
                List.of(Map.of("category", "Wiki:DECISION", "title", "历史约定", "summary", "审批失败保留原记录", "routeHint", "", "scoreBoost", 0.4)),
                List.of(Map.of("category", "TOM:流程", "title", "申请审批", "summary", "提交 -> 待审批", "routeHint", "/apply", "scoreBoost", 0.9)),
                List.of(Map.of("title", "历史候选与当前规则冲突")), "冻结提示上下文");

        ProjectSemanticContextService.BuildResult context = service.semanticContext(contract);

        assertEquals("冻结提示上下文", context.promptSection());
        assertEquals(2, context.signals().size());
        assertTrue(context.signals().stream().anyMatch(signal -> "TOM:流程".equals(signal.category())));
        assertEquals(1, service.summary(contract).get("conflict_count"));
    }
}
