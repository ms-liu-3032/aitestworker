package com.company.aitest.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CaseExportServiceTest {

    private final CurrentUser user = new CurrentUser(7L, "tester", "USER");

    @Test
    void localExportIncludesOwnedConfirmedGenerationAndSubmittedTraceCases() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertTrue(sql.contains("case_status IN ('CONFIRMED', 'SUBMITTED')"));
            if (sql.contains("FROM test_case_draft")) {
                assertTrue(sql.contains("created_by = ?"));
                return List.of(caseRow("TC-8", "确认的需求用例"));
            }
            assertTrue(sql.contains("FROM trace_generated_case"));
            assertTrue(sql.contains("user_id = ?"));
            return List.of(caseRow("TRACE-9", "提交的轨迹用例"));
        });
        CaseExportService service = new CaseExportService(jdbc);

        byte[] bytes = service.exportLocalCasesToXmind(9L, null, user);
        String content = zipEntry(bytes, "content.json");

        assertTrue(content.contains("确认的需求用例"));
        assertTrue(content.contains("提交的轨迹用例"));
        assertTrue(content.contains("预期结果"));
    }

    @Test
    void localExportRejectsSelectionWithoutEligibleCases() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        CaseExportService service = new CaseExportService(jdbc);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.exportLocalCasesToXmind(9L, List.of(8L, -9L), user));

        assertTrue(error.getMessage().contains("已确认或已提交"));
    }

    @Test
    void selectedLocalExportBindsGenerationAndTraceIdsWithoutWeakeningGuards() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object[] arguments = invocation.getArguments();
            Object[] params = java.util.Arrays.copyOfRange(arguments, 1, arguments.length);
            assertTrue(sql.contains("case_status IN ('CONFIRMED', 'SUBMITTED')"));
            assertTrue(sql.contains("id IN (?,?)"));
            assertTrue(java.util.Arrays.equals(params, new Object[]{9L, 7L, 8L, 10L})
                    || java.util.Arrays.equals(params, new Object[]{9L, 7L, 11L, 12L}));
            if (sql.contains("FROM test_case_draft")) {
                assertTrue(sql.contains("created_by = ?"));
                return List.of(caseRow("TC-8", "选中的需求用例"));
            }
            assertTrue(sql.contains("FROM trace_generated_case"));
            assertTrue(sql.contains("user_id = ?"));
            return List.of(caseRow("TRACE-11", "选中的轨迹用例"));
        });
        CaseExportService service = new CaseExportService(jdbc);

        byte[] bytes = service.exportLocalCasesToXmind(9L, List.of(8L, 10L, -11L, -12L, 8L), user);
        String content = zipEntry(bytes, "content.json");

        assertTrue(content.contains("选中的需求用例"));
        assertTrue(content.contains("选中的轨迹用例"));
    }

    @Test
    void excelExportMatchesProvidedTemplateStructureAndExpandsSteps() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.ofEntries(
                Map.entry("case_no", "TC-8"),
                Map.entry("case_title", "审批通过后通知业务对象"),
                Map.entry("module_name", "业务对象审批"),
                Map.entry("precondition", "存在待审批预约"),
                Map.entry("steps", "打开待审批预约\n点击通过"),
                Map.entry("expected_result", "展示审批详情\n通知已发送"),
                Map.entry("priority", "P1"),
                Map.entry("case_type", "FUNCTIONAL"),
                Map.entry("scenario_type", "POSITIVE"),
                Map.entry("design_method", "场景法"),
                Map.entry("case_status", "CONFIRMED"),
                Map.entry("created_by", 7L)
        )));
        CaseExportService service = new CaseExportService(jdbc);

        byte[] bytes = service.exportFormalCasesToExcel(9L, List.of(8L));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("用例名称", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("所属模块", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("审批通过后通知业务对象", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("/业务对象审批", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("[1] 打开待审批预约", sheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("[2] 点击通过", sheet.getRow(2).getCell(4).getStringCellValue());
            assertEquals("STEP", sheet.getRow(1).getCell(6).getStringCellValue());
            assertEquals("Underway", sheet.getRow(1).getCell(8).getStringCellValue());
            assertTrue(sheet.getMergedRegions().stream().anyMatch(region -> region.formatAsString().equals("A2:A3")));
        }
    }

    private Map<String, Object> caseRow(String caseNo, String title) {
        return Map.of(
                "case_no", caseNo,
                "case_title", title,
                "module_name", "审批模块",
                "precondition", "存在待处理数据",
                "steps", "1. 执行操作",
                "expected_result", "操作成功",
                "priority", "P1");
    }

    private String zipEntry(byte[] bytes, String name) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("missing zip entry " + name);
    }
}
