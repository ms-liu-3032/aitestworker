package com.company.aitest.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.company.aitest.common.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 用例导出服务。
 * <p>
 * 支持导出为 xmind 思维导图格式。
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
        String sql = "SELECT * FROM test_case_asset WHERE project_id = ?";
        if (caseIds != null && !caseIds.isEmpty()) {
            sql += " AND id IN (" + caseIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0") + ")";
        }
        sql += " ORDER BY module_name, case_no";

        List<Map<String, Object>> cases = jdbcTemplate.queryForList(sql, projectId);
        return buildXmind(cases, "正式用例库");
    }

    /**
     * 导出本地用例为 xmind 格式。
     */
    public byte[] exportLocalCasesToXmind(Long projectId, List<Long> caseIds) {
        String sql = "SELECT * FROM test_case_draft WHERE project_id = ?";
        if (caseIds != null && !caseIds.isEmpty()) {
            sql += " AND id IN (" + caseIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0") + ")";
        }
        sql += " ORDER BY module_name, id";

        List<Map<String, Object>> cases = jdbcTemplate.queryForList(sql, projectId);
        return buildXmind(cases, "本地用例库");
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

                    String topicTitle = (priority.isEmpty() ? "" : "tc-" + priority.toLowerCase() + "：") + caseTitle;

                    List<Map<String, Object>> children = new java.util.ArrayList<>();

                    // 前置条件
                    if (!precondition.isBlank()) {
                        children.add(Map.of("id", "pc-" + caseNo, "title", "pc：" + precondition));
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
