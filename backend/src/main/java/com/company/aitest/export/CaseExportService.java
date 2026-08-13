package com.company.aitest.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 用例导出服务。
 * <p>
 * 支持导出为 xmind 思维导图和 Excel 用例模板格式。
 */
@Service
public class CaseExportService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CaseExportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 导出正式用例为 xmind 格式。
     */
    public byte[] exportFormalCasesToXmind(Long projectId, List<Long> caseIds) {
        return buildXmind(loadFormalCases(projectId, caseIds), "正式用例库");
    }

    /**
     * 按用户提供的测试用例模板导出正式用例。
     */
    public byte[] exportFormalCasesToExcel(Long projectId, List<Long> caseIds) {
        return buildExcel(loadFormalCases(projectId, caseIds), "正式用例库");
    }

    private List<Map<String, Object>> loadFormalCases(Long projectId, List<Long> caseIds) {
        List<Long> ids = normalizedIds(caseIds);
        String sql = "SELECT * FROM test_case_asset WHERE project_id = ?";
        List<Object> params = new ArrayList<>();
        params.add(projectId);
        if (!ids.isEmpty()) {
            sql += " AND id IN (" + placeholders(ids.size()) + ")";
            params.addAll(ids);
        }
        sql += " ORDER BY module_name, case_no";

        return jdbcTemplate.queryForList(sql, params.toArray());
    }

    /**
     * 导出本地用例为 xmind 格式。
     */
    public byte[] exportLocalCasesToXmind(Long projectId, List<Long> caseIds, CurrentUser user) {
        return buildXmind(loadLocalCases(projectId, caseIds, user), "本地用例库");
    }

    /**
     * 按用户提供的测试用例模板导出已确认或已提交的本地用例。
     */
    public byte[] exportLocalCasesToExcel(Long projectId, List<Long> caseIds, CurrentUser user) {
        return buildExcel(loadLocalCases(projectId, caseIds, user), "本地用例库");
    }

    private List<Map<String, Object>> loadLocalCases(Long projectId, List<Long> caseIds, CurrentUser user) {
        List<Long> requestedIds = normalizedIds(caseIds);
        boolean selectedExport = caseIds != null && !caseIds.isEmpty();
        List<Long> generatedIds = requestedIds.stream().filter(id -> id > 0).toList();
        List<Long> traceIds = requestedIds.stream().filter(id -> id < 0).map(Math::abs).toList();
        List<Map<String, Object>> cases = new ArrayList<>();

        if (!selectedExport || !generatedIds.isEmpty()) {
            List<Object> params = new ArrayList<>(List.of(projectId, user.id()));
            String sql = """
                    SELECT case_no, case_title, module_name, precondition, steps, expected_result, priority,
                           case_type, scenario_type, design_method, case_status, created_by
                    FROM test_case_draft
                    WHERE project_id = ? AND created_by = ?
                      AND case_status IN ('CONFIRMED', 'SUBMITTED')
                    """;
            if (selectedExport) {
                sql += " AND id IN (" + placeholders(generatedIds.size()) + ")";
                params.addAll(generatedIds);
            }
            cases.addAll(jdbcTemplate.queryForList(sql, params.toArray()));
        }
        if (!selectedExport || !traceIds.isEmpty()) {
            List<Object> params = new ArrayList<>(List.of(projectId, user.id()));
            String sql = """
                    SELECT CONCAT('TRACE-', id) AS case_no, case_title, module_name,
                           precondition, steps, expected_result, priority,
                           case_type, 'POSITIVE' AS scenario_type, '轨迹回放法' AS design_method,
                           case_status, user_id AS created_by
                    FROM trace_generated_case
                    WHERE project_id = ? AND user_id = ?
                      AND case_status IN ('CONFIRMED', 'SUBMITTED')
                    """;
            if (selectedExport) {
                sql += " AND id IN (" + placeholders(traceIds.size()) + ")";
                params.addAll(traceIds);
            }
            cases.addAll(jdbcTemplate.queryForList(sql, params.toArray()));
        }
        if (cases.isEmpty()) {
            throw new BusinessException("没有可导出的已确认或已提交用例");
        }
        cases.sort(Comparator
                .comparing((Map<String, Object> item) -> String.valueOf(item.getOrDefault("module_name", "")))
                .thenComparing(item -> String.valueOf(item.getOrDefault("case_no", ""))));
        return cases;
    }

    private List<Long> normalizedIds(List<Long> caseIds) {
        if (caseIds == null) return List.of();
        return caseIds.stream().filter(java.util.Objects::nonNull).filter(id -> id != 0).distinct().toList();
    }

    private String placeholders(int count) {
        if (count <= 0) throw new BusinessException("导出用例编号不能为空");
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    /**
     * 构建与导入模板同列结构的 Excel。每条用例按步骤展开，非步骤列跨步骤合并，
     * 既可直接阅读，也能按模板的 STEP 规则重新导入。
     */
    private byte[] buildExcel(List<Map<String, Object>> cases, String sheetTitle) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetTitle);
            String[] headers = {"用例名称", "所属模块", "标签", "前置条件", "步骤描述", "预期结果",
                    "编辑模式", "备注", "用例状态", "责任人", "用例等级"};
            int[] widths = {34, 24, 30, 32, 52, 52, 13, 42, 16, 14, 12};
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle contentStyle = contentStyle(workbook);
            CreationHelper helper = workbook.getCreationHelper();

            Row header = sheet.createRow(0);
            header.setHeightInPoints(24);
            for (int column = 0; column < headers.length; column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(headers[column]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(column, widths[column] * 256);
            }
            addTemplateComments(header, helper);
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headers.length - 1));

            int rowIndex = 1;
            for (Map<String, Object> testCase : cases) {
                List<String> steps = meaningfulLines(stringValue(testCase, "steps"));
                List<String> expected = meaningfulLines(stringValue(testCase, "expected_result"));
                int rowCount = Math.max(1, Math.max(steps.size(), expected.size()));
                int firstRow = rowIndex;
                for (int offset = 0; offset < rowCount; offset++, rowIndex++) {
                    Row row = sheet.createRow(rowIndex);
                    row.setHeightInPoints(42);
                    for (int column = 0; column < headers.length; column++) {
                        row.createCell(column).setCellStyle(contentStyle);
                    }
                    setCell(row, 4, numberedValue(steps, offset));
                    setCell(row, 5, numberedValue(expected, offset));
                }

                Row first = sheet.getRow(firstRow);
                setCell(first, 0, stringValue(testCase, "case_title", "未命名用例"));
                setCell(first, 1, modulePath(stringValue(testCase, "module_name", "未分模块")));
                setCell(first, 2, tags(testCase));
                setCell(first, 3, stringValue(testCase, "precondition"));
                setCell(first, 6, "STEP");
                setCell(first, 7, remarks(testCase));
                setCell(first, 8, templateStatus(stringValue(testCase, "case_status")));
                setCell(first, 9, stringValue(testCase, "created_by"));
                setCell(first, 10, stringValue(testCase, "priority", "P2"));

                if (rowCount > 1) {
                    for (int column : new int[]{0, 1, 2, 3, 6, 7, 8, 9, 10}) {
                        sheet.addMergedRegion(new CellRangeAddress(firstRow, firstRow + rowCount - 1, column, column));
                    }
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("导出 Excel 用例失败", e);
        }
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        applyBorders(style);
        return style;
    }

    private CellStyle contentStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        applyBorders(style);
        return style;
    }

    private void applyBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private void addTemplateComments(Row header, CreationHelper helper) {
        String[] comments = {null, "若无该模块将自动创建", "标签之间以分号或者逗号隔开", null,
                "编辑模式为 STEP 时，使用 [1] [2] ... 标识拆分多个步骤", "编辑模式为 STEP 时，使用 [1] [2] ... 标识拆分多个预期结果",
                "步骤描述填写 STEP，文本描述请填写 TEXT", null,
                "选项：[Prepare, Underway, Completed]", "请填写该项目下的相关人员 ID", "选项：[P0, P1, P2, P3]，请注意大小写"};
        for (int column = 0; column < comments.length; column++) {
            if (comments[column] == null) continue;
            Comment comment = header.getSheet().createDrawingPatriarch().createCellComment(helper.createClientAnchor());
            comment.setString(helper.createRichTextString(comments[column]));
            header.getCell(column).setCellComment(comment);
        }
    }

    private List<String> meaningfulLines(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split("\\r?\\n"))
                .map(String::trim).filter(line -> !line.isBlank()).toList();
    }

    private String numberedValue(List<String> values, int index) {
        if (index >= values.size()) return "";
        String value = values.get(index);
        return value.matches("^\\[\\d+]\\s*.*") ? value : "[" + (index + 1) + "] " + value;
    }

    private String modulePath(String module) {
        return module.startsWith("/") ? module : "/" + module;
    }

    private String tags(Map<String, Object> testCase) {
        List<String> tags = new ArrayList<>();
        String caseNo = stringValue(testCase, "case_no");
        String scenario = stringValue(testCase, "scenario_type");
        String type = stringValue(testCase, "case_type");
        if (!caseNo.isBlank()) tags.add(caseNo);
        if (!scenario.isBlank()) tags.add(scenario);
        if (!type.isBlank()) tags.add(type);
        return String.join("; ", tags);
    }

    private String remarks(Map<String, Object> testCase) {
        List<String> parts = new ArrayList<>();
        String method = stringValue(testCase, "design_method");
        String originalStatus = stringValue(testCase, "case_status");
        if (!method.isBlank()) parts.add("设计方法：" + method);
        if (!originalStatus.isBlank()) parts.add("平台状态：" + originalStatus);
        return String.join("；", parts);
    }

    private String templateStatus(String status) {
        return switch (status) {
            case "DRAFT" -> "Prepare";
            case "CONFIRMED" -> "Underway";
            default -> "Completed";
        };
    }

    private void setCell(Row row, int column, String value) {
        row.getCell(column).setCellValue(value == null ? "" : value);
    }

    private String stringValue(Map<String, Object> row, String key) {
        return stringValue(row, key, "");
    }

    private String stringValue(Map<String, Object> row, String key, String defaultValue) {
        Object value = row.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    /**
     * 构建 xmind 文件。
     */
    private byte[] buildXmind(List<Map<String, Object>> cases, String title) {
        try {
            // 构建 topic 结构
            Map<String, Object> rootTopic = Map.of(
                    "id", "root",
                    "title", title,
                    "children", Map.of("attached", buildModuleTopics(cases))
            );

            Map<String, Object> sheet = Map.of(
                    "id", "sheet-1",
                    "title", title,
                    "rootTopic", rootTopic
            );

            String contentJson = objectMapper.writeValueAsString(List.of(sheet));

            // 构建 manifest
            String manifestJson = objectMapper.writeValueAsString(Map.of(
                    "file-entries", Map.of(
                            "content.json", Map.of(),
                            "metadata.json", Map.of()
                    )
            ));

            // 构建 metadata
            String metadataJson = objectMapper.writeValueAsString(Map.of(
                    "creator", Map.of("name", "AITest Platform", "version", "1.0.0")
            ));

            // 打包为 xmind (zip)
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(baos);

            zos.putNextEntry(new ZipEntry("content.json"));
            zos.write(contentJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("metadata.json"));
            zos.write(metadataJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("导出 xmind 失败", e);
        }
    }

    /**
     * 按模块分组构建 topic。
     */
    private List<Map<String, Object>> buildModuleTopics(List<Map<String, Object>> cases) {
        // 按模块分组
        Map<String, List<Map<String, Object>>> byModule = cases.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        c -> c.get("module_name") != null ? String.valueOf(c.get("module_name")) : "未分模块",
                        java.util.stream.Collectors.toList()
                ));

        return byModule.entrySet().stream()
                .map(entry -> Map.of(
                        "id", "module-" + entry.getKey(),
                        "title", entry.getKey(),
                        "children", Map.of("attached", buildCaseTopics(entry.getValue()))
                ))
                .toList();
    }

    /**
     * 构建用例 topic。
     */
    private List<Map<String, Object>> buildCaseTopics(List<Map<String, Object>> cases) {
        return cases.stream()
                .map(c -> {
                    String caseNo = c.get("case_no") != null ? String.valueOf(c.get("case_no")) : "";
                    String caseTitle = c.get("case_title") != null ? String.valueOf(c.get("case_title")) : "未命名用例";
                    String priority = c.get("priority") != null ? String.valueOf(c.get("priority")) : "";
                    String steps = c.get("steps") != null ? String.valueOf(c.get("steps")) : "";
                    String expectedResult = c.get("expected_result") != null ? String.valueOf(c.get("expected_result")) : "";
                    String precondition = c.get("precondition") != null ? String.valueOf(c.get("precondition")) : "";
                    String scenarioType = c.get("scenario_type") != null ? String.valueOf(c.get("scenario_type")) : "POSITIVE";
                    String designMethod = c.get("design_method") != null ? String.valueOf(c.get("design_method")) : "";

                    String topicTitle = (priority.isEmpty() ? "" : "tc-" + priority.toLowerCase() + "：") + caseTitle;

                    List<Map<String, Object>> children = new java.util.ArrayList<>();

                    // 前置条件
                    if (!precondition.isBlank()) {
                        children.add(Map.of("id", "pc-" + caseNo, "title", "pc：" + precondition));
                    }
                    children.add(Map.of("id", "scenario-" + caseNo, "title", "场景类型：" + scenarioType));
                    if (!designMethod.isBlank()) {
                        children.add(Map.of("id", "method-" + caseNo, "title", "设计方法：" + designMethod));
                    }

                    // 步骤和预期结果
                    if (!steps.isBlank()) {
                        String[] stepLines = steps.split("\n");
                        String[] expectedLines = expectedResult.isBlank() ? new String[0] : expectedResult.split("\n");
                        for (int i = 0; i < stepLines.length; i++) {
                            String step = stepLines[i].trim();
                            if (step.isBlank()) continue;
                            String expected = i < expectedLines.length ? expectedLines[i].trim() : "";
                            Map<String, Object> stepTopic = Map.of(
                                    "id", "step-" + caseNo + "-" + i,
                                    "title", step
                            );
                            if (!expected.isBlank()) {
                                stepTopic = Map.of(
                                        "id", "step-" + caseNo + "-" + i,
                                        "title", step,
                                        "children", Map.of("attached", List.of(
                                                Map.of("id", "er-" + caseNo + "-" + i, "title", "预期结果：" + expected)
                                        ))
                                );
                            }
                            children.add(stepTopic);
                        }
                    }

                    // 标签
                    children.add(Map.of("id", "tag-" + caseNo, "title", "tag：" + caseNo));

                    return Map.of(
                            "id", "case-" + caseNo,
                            "title", topicTitle,
                            "children", Map.of("attached", children)
                    );
                })
                .toList();
    }
}
