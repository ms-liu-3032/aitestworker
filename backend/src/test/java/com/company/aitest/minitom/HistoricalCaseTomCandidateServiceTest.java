package com.company.aitest.minitom;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.knowledge.KnowledgeDepositionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoricalCaseTomCandidateServiceTest {

    @Test
    void keepsTheSameHistoricalObjectAsOneCandidateAcrossDifferentFormalCases() {
        String first = HistoricalCaseTomCandidateService.stableCandidateKey("ACTION", "  提交  申请 ");
        String sameObject = HistoricalCaseTomCandidateService.stableCandidateKey("action", "提交 申请");
        String anotherType = HistoricalCaseTomCandidateService.stableCandidateKey("FLOW", "提交 申请");

        assertEquals(first, sameObject);
        assertNotEquals(first, anotherType);
    }

    @Test
    void onlyAllowsExecutableAndNonSampleFormalCasesToBecomeTomEvidence() {
        assertEquals(true, HistoricalCaseTomCandidateService.isEligibleForExtraction(
                "提交申请后进入待审批", "申请管理", "用户已登录", "提交申请", "状态变为待审批"));
        assertEquals(false, HistoricalCaseTomCandidateService.isEligibleForExtraction(
                "示例用例", "申请管理", "", "提交申请", "验证成功"));
        assertEquals(false, HistoricalCaseTomCandidateService.isEligibleForExtraction(
                "验证", "申请管理", "", "", ""));
        assertEquals(false, HistoricalCaseTomCandidateService.isEligibleForExtraction(
                "待确认的规则", "申请管理", "", "提交申请", "验证成功"));
    }

    @Test
    void manualRelationRequiresMatchingActiveEndpointsAndRejectsDuplicate() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new HistoricalCaseTomCandidateService(jdbc, mock(TimeProvider.class),
                new ObjectMapper(), mock(KnowledgeDepositionService.class));
        var user = new CurrentUser(9L, "reviewer", "USER");
        var command = new HistoricalCaseTomCandidateService.ManualRelationCommand(
                11L, "ROLE_CAN_ACTION", 12L, "手册明确访客管理员可以执行邀请操作");
        when(jdbc.queryForList(contains("FROM test_object_model"), eq(2L), eq(11L), eq(12L)))
                .thenReturn(List.of(Map.of("id", 11L, "model_type", "ROLE"),
                        Map.of("id", 12L, "model_type", "ACTION")));
        when(jdbc.queryForObject(contains("FROM test_object_model_relation"), eq(Integer.class),
                eq(2L), eq(11L), eq(12L), eq("ROLE_CAN_ACTION"))).thenReturn(1);
        assertThrows(BusinessException.class, () -> service.proposeManualRelation(2L, command, user));
        verify(jdbc, never()).update(startsWith("INSERT INTO test_object_model_relation"), any(Object[].class));

        var wrongType = new HistoricalCaseTomCandidateService.ManualRelationCommand(
                11L, "ACTION_CHANGES_STATE", 12L, command.evidenceText());
        assertThrows(BusinessException.class, () -> service.proposeManualRelation(2L, wrongType, user));
    }

    @Test
    void manualRelationIsSavedOnlyAsReviewCandidate() {
        var jdbc = mock(JdbcTemplate.class);
        var clock = mock(TimeProvider.class);
        when(clock.now()).thenReturn(LocalDateTime.of(2026, 11, 12, 17, 0));
        var service = new HistoricalCaseTomCandidateService(jdbc, clock,
                new ObjectMapper(), mock(KnowledgeDepositionService.class));
        var user = new CurrentUser(9L, "reviewer", "USER");
        var command = new HistoricalCaseTomCandidateService.ManualRelationCommand(
                11L, "ROLE_CAN_ACTION", 12L, "手册明确访客管理员可以执行邀请操作");
        when(jdbc.queryForList(contains("FROM test_object_model"), eq(2L), eq(11L), eq(12L)))
                .thenReturn(List.of(Map.of("id", 11L, "model_type", "ROLE"),
                        Map.of("id", 12L, "model_type", "ACTION")));
        when(jdbc.queryForObject(contains("FROM test_object_model_relation"), eq(Integer.class),
                eq(2L), eq(11L), eq(12L), eq("ROLE_CAN_ACTION"))).thenReturn(0);
        when(jdbc.queryForObject(eq("SELECT LAST_INSERT_ID()"), eq(Long.class))).thenReturn(77L);
        var candidate = new HistoricalCaseTomCandidateService.RelationCandidateView(77L, 2L, 11L,
                "访客管理员", "ROLE_CAN_ACTION", 12L, "邀请", "MANUAL_SECTION", null,
                null, "CANDIDATE", true, null, clock.now());
        when(jdbc.query(contains("FROM test_object_model_relation r"), any(RowMapper.class), eq(77L), eq(2L)))
                .thenReturn(List.of(candidate));
        assertEquals("CANDIDATE", service.proposeManualRelation(2L, command, user).status());
        verify(jdbc).update(contains("VALUES (?, ?, ?, ?, 1.00, 'CANDIDATE'"), any(Object[].class));
        verify(jdbc).update(contains("INSERT INTO tom_evidence"), any(Object[].class));
    }
}
