package com.company.aitest.minitom;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HistoricalCaseImportServiceTest {

    @Test
    void parsesChineseAndEnglishCsvHeadersForFormalImport() {
        List<HistoricalCaseImportService.CaseInput> rows = HistoricalCaseImportService.parseCsv("""
                用例标题,模块,前置条件,步骤,预期结果,优先级
                提交预约,预约管理,已登录,填写表单并提交,生成待审批预约,P0
                """);

        assertEquals(1, rows.size());
        assertEquals("提交预约", rows.get(0).title());
        assertEquals("预约管理", rows.get(0).module());
        assertEquals("生成待审批预约", rows.get(0).expectedResult());
    }

    @Test
    void acceptsUtf8BomInCsvHeaders() {
        List<HistoricalCaseImportService.CaseInput> rows = HistoricalCaseImportService.parseCsv("""
                \uFEFFtitle,module,steps,expected_result
                Submit request,Booking,Submit,Request created
                """);

        assertEquals(1, rows.size());
        assertEquals("Submit request", rows.get(0).title());
        assertEquals("Request created", rows.get(0).expectedResult());
    }

    @Test
    void rejectsCsvWithoutAnActualCaseRow() {
        assertThrows(BusinessException.class, () -> HistoricalCaseImportService.parseCsv("用例标题,模块\n"));
    }

    @Test
    void importWritesEveryOriginalItemToFormalLibraryAndOnlyEligibleItemsToTom() {
        var jdbc = mock(JdbcTemplate.class);
        var clock = mock(TimeProvider.class);
        var tom = mock(HistoricalCaseTomCandidateService.class);
        var now = LocalDateTime.of(2026, 11, 11, 12, 0);
        when(clock.now()).thenReturn(now);
        when(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(10L, 100L, 101L);
        when(jdbc.queryForObject(eq("SELECT project_name FROM project WHERE id = ?"), eq(String.class), eq(3L)))
                .thenReturn("基础项目");
        when(tom.extractImportedItem(eq(3L), eq(100L), any())).thenReturn(
                new HistoricalCaseTomCandidateService.ExtractionResult(1, 0, 0, 0));
        var service = new HistoricalCaseImportService(jdbc, clock, tom);
        var result = service.importCases(new HistoricalCaseImportService.ImportCommand(3L, "批次", "访客", List.of(
                new HistoricalCaseImportService.CaseInput("提交预约", "预约", "已登录", "提交申请", "显示待审批", "P1", "功能", "场景法"),
                new HistoricalCaseImportService.CaseInput("旧标题占位", "预约", "", "", "", "P2", "功能", "场景法")
        )), "PASTE", new CurrentUser(7L, "tester", "USER"));

        assertEquals(2, result.imported());
        assertEquals(2, result.formalImported());
        assertEquals(1, result.eligible());
        assertEquals(1, result.extraction().candidates());
        long formalWrites = mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("update"))
                .filter(invocation -> ((String) invocation.getArgument(0)).contains("INSERT INTO test_case_asset"))
                .count();
        assertEquals(2, formalWrites);
        verify(tom).extractImportedItem(eq(3L), eq(100L), any());
        verify(tom, never()).extractImportedItem(eq(3L), eq(101L), any());
    }
}
