package com.company.aitest.minitom;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.common.TomUsageMode;
import com.company.aitest.model.ModelConfigService;
import com.company.aitest.semantic.ProjectSemanticContextService;
import com.company.aitest.trace.TraceSummaryRecord;
import com.company.aitest.trace.TraceSummaryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mini-TOM 核心服务。
 * <p>
 * 用关系型数据库验证 Mini-TOM 可行性，不依赖 Weaviate / Neo4j。
 * <p>
 * 最小闭环：CONFIRMED trace_summary → 候选抽取 → 人工确认 → ACTIVE → 测试范围构建
 *
 * @see docs/handover/11_轨迹定位与语义修正建议设计方案.md
 */
@Service
public class MiniTomService {

    private static final Logger log = LoggerFactory.getLogger(MiniTomService.class);
    private static final int DEFAULT_SEMANTIC_MATCH_MAX_CANDIDATES = 30;
    private static final int DEFAULT_SEMANTIC_MATCH_TIMEOUT_SECONDS = 8;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final TraceSummaryService traceSummaryService;
    private final TomSemanticMatcher semanticMatcher;
    private final ModelConfigService modelConfigService;
    private final ProjectSemanticContextService semanticContextService;
    private final ObjectMapper objectMapper;

    @Value("${aitest.tom.semantic-match.enabled:false}")
    private boolean semanticMatchEnabled;

    @Value("${aitest.tom.semantic-match.max-candidates:30}")
    private int semanticMatchMaxCandidates;

    @Value("${aitest.tom.semantic-match.timeout-seconds:8}")
    private int semanticMatchTimeoutSeconds;

    public MiniTomService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                          TraceSummaryService traceSummaryService,
                          TomSemanticMatcher semanticMatcher,
                          ModelConfigService modelConfigService,
                          ProjectSemanticContextService semanticContextService) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.traceSummaryService = traceSummaryService;
        this.semanticMatcher = semanticMatcher;
        this.modelConfigService = modelConfigService;
        this.semanticContextService = semanticContextService;
        this.objectMapper = new ObjectMapper();
    }

    // =====================================================================
    // 生效条件检查
    // =====================================================================

    /**
     * 只有 CONFIRMED 的 trace_summary 才允许进入 Mini-TOM 抽取。
     *
     * @throws BusinessException 如果 summary 不存在或非 CONFIRMED
     */
    private void assertConfirmedForMiniTom(Long summaryId) {
        if (!traceSummaryService.isConfirmedForMiniTom(summaryId)) {
            throw new BusinessException("只有 CONFIRMED 的 trace_summary 才允许进入 Mini-TOM 抽取。请先确认摘要。");
        }
    }

    // =====================================================================
    // 候选抽取：从 trace_summary
    // =====================================================================

    /**
     * 从 CONFIRMED trace_summary 中抽取 Mini-TOM 候选。
     * <p>
     * 默认 status = CANDIDATE，requires_human_confirmation = true。
     */
    @Transactional
    public List<TestObjectModelRecord> extractFromTraceSummary(Long summaryId, CurrentUser user) {
        // 生效条件检查
        assertConfirmedForMiniTom(summaryId);

        TraceSummaryRecord summary = traceSummaryService.getSummary(summaryId, user);
        Long projectId = summary.projectId();
        LocalDateTime now = timeProvider.now();

        List<TestObjectModelRecord> candidates = new ArrayList<>();

        // 抽取 PAGE
        candidates.addAll(extractPages(projectId, summary, user, now));

        // 抽取 FIELD
        candidates.addAll(extractFields(projectId, summary, user, now));

        // 抽取 ACTION
        candidates.addAll(extractActions(projectId, summary, user, now));

        // 抽取 FLOW
        candidates.addAll(extractFlows(projectId, summary, user, now));

        // 抽取 STATE
        candidates.addAll(extractStates(projectId, summary, user, now));

        // 抽取 ASSERTION
        candidates.addAll(extractAssertions(projectId, summary, user, now));

        return candidates;
    }

    private List<TestObjectModelRecord> extractPages(Long projectId, TraceSummaryRecord summary,
                                                     CurrentUser user, LocalDateTime now) {
        List<TestObjectModelRecord> results = new ArrayList<>();

        // 从 overview 和 business_summary 中提取页面
        String overview = summary.overview();
        String businessSummary = summary.businessSummary();

        // 简单策略：提取包含"页面"、"页"、"界面"的描述
        List<String> pageDescriptions = extractByKeywords(
                overview + " " + businessSummary,
                List.of("页面", "页", "界面", "面板", "弹窗", "对话框"));

        for (String desc : pageDescriptions) {
            if (desc.length() < 3) continue;
            String name = truncate(desc, 128);
            Long id = insertCandidate(projectId, "PAGE", name, desc,
                    "TRACE_SUMMARY", summary.id(), desc, user.id(), now);
            results.add(getById(id));
        }

        return results;
    }

    private List<TestObjectModelRecord> extractFields(Long projectId, TraceSummaryRecord summary,
                                                      CurrentUser user, LocalDateTime now) {
        List<TestObjectModelRecord> results = new ArrayList<>();

        // 从 key_steps_json 中提取字段操作
        String keyStepsJson = summary.keyStepsJson();
        if (keyStepsJson == null || keyStepsJson.isBlank()) return results;

        List<String> steps = parseJsonArray(keyStepsJson);
        for (String step : steps) {
            // 提取包含"输入"、"填写"、"选择"、"勾选"的步骤
            if (containsAny(step, List.of("输入", "填写", "选择", "勾选", "填写表单", "表单字段"))) {
                String name = truncate(extractFieldName(step), 128);
                if (name.length() < 2) continue;
                Long id = insertCandidate(projectId, "FIELD", name, step,
                        "TRACE_SUMMARY", summary.id(), step, user.id(), now);
                results.add(getById(id));
            }
        }

        return results;
    }

    private List<TestObjectModelRecord> extractActions(Long projectId, TraceSummaryRecord summary,
                                                       CurrentUser user, LocalDateTime now) {
        List<TestObjectModelRecord> results = new ArrayList<>();

        // 从 key_steps_json 中提取用户操作
        String keyStepsJson = summary.keyStepsJson();
        if (keyStepsJson == null || keyStepsJson.isBlank()) return results;

        List<String> steps = parseJsonArray(keyStepsJson);
        for (String step : steps) {
            // 提取包含动作关键词的步骤
            if (containsAny(step, List.of("点击", "提交", "保存", "删除", "修改", "新增", "搜索", "查询", "登录", "退出"))) {
                String name = truncate(extractActionName(step), 128);
                if (name.length() < 2) continue;
                Long id = insertCandidate(projectId, "ACTION", name, step,
                        "TRACE_SUMMARY", summary.id(), step, user.id(), now);
                results.add(getById(id));
            }
        }

        return results;
    }

    private List<TestObjectModelRecord> extractFlows(Long projectId, TraceSummaryRecord summary,
                                                     CurrentUser user, LocalDateTime now) {
        List<TestObjectModelRecord> results = new ArrayList<>();

        // 从 overview 中提取业务流程
        String overview = summary.overview();
        if (overview != null && !overview.isBlank() && overview.length() > 10) {
            String name = truncate(overview, 128);
            Long id = insertCandidate(projectId, "FLOW", name, overview,
                    "TRACE_SUMMARY", summary.id(), overview, user.id(), now);
            results.add(getById(id));
        }

        return results;
    }

    private List<TestObjectModelRecord> extractStates(Long projectId, TraceSummaryRecord summary,
                                                      CurrentUser user, LocalDateTime now) {
        List<TestObjectModelRecord> results = new ArrayList<>();

        // 从 key_steps_json 中提取状态变化
        String keyStepsJson = summary.keyStepsJson();
        if (keyStepsJson == null || keyStepsJson.isBlank()) return results;

        List<String> steps = parseJsonArray(keyStepsJson);
        for (String step : steps) {
            // 提取包含状态变化关键词的步骤
            if (containsAny(step, List.of("状态变为", "变为", "变成", "切换到", "进入", "退出"))) {
                String name = truncate(extractStateName(step), 128);
                if (name.length() < 2) continue;
                Long id = insertCandidate(projectId, "STATE", name, step,
                        "TRACE_SUMMARY", summary.id(), step, user.id(), now);
                results.add(getById(id));
            }
        }

        return results;
    }

    private List<TestObjectModelRecord> extractAssertions(Long projectId, TraceSummaryRecord summary,
                                                          CurrentUser user, LocalDateTime now) {
        List<TestObjectModelRecord> results = new ArrayList<>();

        // 从 key_api_json 中提取断言
        String keyApiJson = summary.keyApiJson();
        if (keyApiJson == null || keyApiJson.isBlank()) return results;

        List<Map<String, Object>> apis = parseJsonArrayMap(keyApiJson);
        for (Map<String, Object> api : apis) {
            String method = (String) api.get("method");
            String url = (String) api.get("url");
            Object status = api.get("status");
            String remark = (String) api.get("remark");

            if (url != null && !url.isBlank()) {
                String desc = "%s %s status=%s %s".formatted(
                        method != null ? method : "GET", url,
                        status != null ? status : "-",
                        remark != null ? remark : "");
                String name = truncate("API断言: " + url, 128);
                Long id = insertCandidate(projectId, "ASSERTION", name, desc,
                        "TRACE_SUMMARY", summary.id(), desc, user.id(), now);
                results.add(getById(id));
            }
        }

        return results;
    }

    // =====================================================================
    // 候选抽取：从手动片段（使用手册）
    // =====================================================================

    /**
     * 从使用手册片段中抽取 Mini-TOM 候选。
     * <p>
     * 按功能块切片，默认 status = CANDIDATE。
     */
    @Transactional
    public List<TestObjectModelRecord> extractFromManualSection(ManualSectionCommand command, CurrentUser user) {
        Long projectId = command.projectId();
        LocalDateTime now = timeProvider.now();

        List<TestObjectModelRecord> candidates = new ArrayList<>();

        // 按段落切片
        List<String> sections = splitToSections(command.content());

        for (String section : sections) {
            if (section.length() < 10) continue;

            // 根据内容特征判断类型
            String modelType = inferModelType(section, command.moduleName());
            String name = truncate(extractSectionName(section), 128);

            Long id = insertCandidate(projectId, modelType, name, section,
                    "MANUAL_SECTION", null, section, user.id(), now);
            candidates.add(getById(id));
        }

        return candidates;
    }

    // =====================================================================
    // 候选抽取：从测试资产
    // =====================================================================

    /**
     * 从已沉淀的测试资产中抽取 Mini-TOM 候选。
     * <p>
     * 资产来源：test_case_asset、test_point_draft、test_skill_template、test_tool_template。
     * 同名同类型去重，source_type = 'TEST_ASSET'。
     */
    @Transactional
    public List<TestObjectModelRecord> extractFromTestAssets(Long projectId, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        List<TestObjectModelRecord> candidates = new ArrayList<>();

        // 已有候选去重集合（同项目、同类型、同名）
        Set<String> existingKeys = loadExistingTomKeys(projectId);

        // 1. 从 test_case_asset 提取 MODULE 和 ACTION
        candidates.addAll(extractFromCaseAssets(projectId, user, now, existingKeys));

        // 2. 从 test_point_draft 提取 MODULE 和 ACTION/FIELD
        candidates.addAll(extractFromTestPoints(projectId, user, now, existingKeys));

        // 3. 从 test_skill_template 提取 ACTION/FLOW
        candidates.addAll(extractFromSkillTemplates(projectId, user, now, existingKeys));

        // 4. 从 test_tool_template 提取 ACTION/FIELD
        candidates.addAll(extractFromToolTemplates(projectId, user, now, existingKeys));

        return candidates;
    }

    private Set<String> loadExistingTomKeys(Long projectId) {
        Set<String> keys = new HashSet<>();
        jdbc.sql("""
                SELECT model_type, name FROM test_object_model
                WHERE project_id = :projectId AND status IN ('CANDIDATE', 'ACTIVE')
                """).param("projectId", projectId)
                .query((rs, rowNum) -> {
                    keys.add(rs.getString("model_type") + ":" + rs.getString("name"));
                    return rowNum;
                }).list();
        return keys;
    }

    private List<TestObjectModelRecord> extractFromCaseAssets(Long projectId, CurrentUser user,
                                                               LocalDateTime now, Set<String> existingKeys) {
        List<TestObjectModelRecord> results = new ArrayList<>();
        var rows = jdbc.sql("""
                SELECT id, module_name, case_title, steps FROM test_case_asset
                WHERE project_id = :projectId AND case_status != 'DEPRECATED'
                """).param("projectId", projectId)
                .query((rs, rowNum) -> Map.of(
                        "id", rs.getLong("id"),
                        "module", rs.getString("module_name"),
                        "title", rs.getString("case_title"),
                        "steps", rs.getString("steps")))
                .list();

        for (var row : rows) {
            String module = (String) row.get("module");
            if (module != null && !module.isBlank()) {
                String key = "MODULE:" + module;
                if (!existingKeys.contains(key)) {
                    existingKeys.add(key);
                    Long id = insertCandidate(projectId, "MODULE", truncate(module, 128),
                            "来自测试用例资产", "TEST_ASSET", (Long) row.get("id"),
                            module, user.id(), now);
                    results.add(getById(id));
                }
            }
            String title = (String) row.get("title");
            if (title != null && !title.isBlank() && title.length() >= 3) {
                String key = "ACTION:" + title;
                if (!existingKeys.contains(key)) {
                    existingKeys.add(key);
                    String desc = "用例: " + title;
                    Long id = insertCandidate(projectId, "ACTION", truncate(title, 128),
                            desc, "TEST_ASSET", (Long) row.get("id"), desc, user.id(), now);
                    results.add(getById(id));
                }
            }
        }
        return results;
    }

    private List<TestObjectModelRecord> extractFromTestPoints(Long projectId, CurrentUser user,
                                                               LocalDateTime now, Set<String> existingKeys) {
        List<TestObjectModelRecord> results = new ArrayList<>();
        var rows = jdbc.sql("""
                SELECT id, module_name, point_content FROM test_point_draft
                WHERE project_id = :projectId AND status != 'REJECTED'
                """).param("projectId", projectId)
                .query((rs, rowNum) -> Map.of(
                        "id", rs.getLong("id"),
                        "module", rs.getString("module_name"),
                        "content", rs.getString("point_content")))
                .list();

        for (var row : rows) {
            String module = (String) row.get("module");
            if (module != null && !module.isBlank()) {
                String key = "MODULE:" + module;
                if (!existingKeys.contains(key)) {
                    existingKeys.add(key);
                    Long id = insertCandidate(projectId, "MODULE", truncate(module, 128),
                            "来自测试点资产", "TEST_ASSET", (Long) row.get("id"),
                            module, user.id(), now);
                    results.add(getById(id));
                }
            }
            String content = (String) row.get("content");
            if (content != null && !content.isBlank() && content.length() >= 3) {
                String name = truncate(content, 128);
                String key = "ACTION:" + name;
                if (!existingKeys.contains(key)) {
                    existingKeys.add(key);
                    Long id = insertCandidate(projectId, "ACTION", name,
                            "测试点: " + content, "TEST_ASSET", (Long) row.get("id"),
                            content, user.id(), now);
                    results.add(getById(id));
                }
            }
        }
        return results;
    }

    private List<TestObjectModelRecord> extractFromSkillTemplates(Long projectId, CurrentUser user,
                                                                   LocalDateTime now, Set<String> existingKeys) {
        List<TestObjectModelRecord> results = new ArrayList<>();
        var rows = jdbc.sql("""
                SELECT id, skill_name, description FROM test_skill_template
                WHERE project_id = :projectId AND status != 'DEPRECATED'
                """).param("projectId", projectId)
                .query((rs, rowNum) -> Map.of(
                        "id", rs.getLong("id"),
                        "name", rs.getString("skill_name"),
                        "desc", rs.getString("description")))
                .list();

        for (var row : rows) {
            String name = (String) row.get("name");
            if (name == null || name.isBlank()) continue;
            String desc = (String) row.get("desc");
            String modelType = containsAny(name + desc, List.of("流程", "步骤", "业务")) ? "FLOW" : "ACTION";
            String key = modelType + ":" + name;
            if (!existingKeys.contains(key)) {
                existingKeys.add(key);
                Long id = insertCandidate(projectId, modelType, truncate(name, 128),
                        desc != null ? desc : "来自技能模板", "TEST_ASSET", (Long) row.get("id"),
                        desc, user.id(), now);
                results.add(getById(id));
            }
        }
        return results;
    }

    private List<TestObjectModelRecord> extractFromToolTemplates(Long projectId, CurrentUser user,
                                                                  LocalDateTime now, Set<String> existingKeys) {
        List<TestObjectModelRecord> results = new ArrayList<>();
        var rows = jdbc.sql("""
                SELECT id, tool_name, description FROM test_tool_template
                WHERE project_id = :projectId AND status != 'DEPRECATED'
                """).param("projectId", projectId)
                .query((rs, rowNum) -> Map.of(
                        "id", rs.getLong("id"),
                        "name", rs.getString("tool_name"),
                        "desc", rs.getString("description")))
                .list();

        for (var row : rows) {
            String name = (String) row.get("name");
            if (name == null || name.isBlank()) continue;
            String desc = (String) row.get("desc");
            String key = "ACTION:" + name;
            if (!existingKeys.contains(key)) {
                existingKeys.add(key);
                Long id = insertCandidate(projectId, "ACTION", truncate(name, 128),
                        desc != null ? desc : "来自工具模板", "TEST_ASSET", (Long) row.get("id"),
                        desc, user.id(), now);
                results.add(getById(id));
            }
        }
        return results;
    }

    private List<String> splitToSections(String content) {
        List<String> sections = new ArrayList<>();
        if (content == null || content.isBlank()) return sections;

        // 按空行分段
        String[] parts = content.split("\n\s*\n");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                sections.add(trimmed);
            }
        }

        return sections;
    }

    private String inferModelType(String section, String moduleName) {
        String lower = section.toLowerCase();

        if (containsAny(section, List.of("页面", "界面", "面板", "弹窗"))) {
            return "PAGE";
        } else if (containsAny(section, List.of("字段", "输入框", "下拉框", "选择框", "表单"))) {
            return "FIELD";
        } else if (containsAny(section, List.of("角色", "用户", "管理员", "操作员"))) {
            return "ROLE";
        } else if (containsAny(section, List.of("点击", "提交", "保存", "操作", "按钮"))) {
            return "ACTION";
        } else if (containsAny(section, List.of("流程", "步骤", "流程图", "业务流程"))) {
            return "FLOW";
        } else if (containsAny(section, List.of("状态", "状态机", "流转"))) {
            return "STATE";
        } else if (containsAny(section, List.of("断言", "验证", "检查", "校验"))) {
            return "ASSERTION";
        } else if (moduleName != null && !moduleName.isBlank()) {
            return "MODULE";
        }

        return "PAGE"; // 默认
    }

    private String extractSectionName(String section) {
        // 尝试提取标题行
        String[] lines = section.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("##") || trimmed.startsWith("###")) {
                return trimmed.replaceFirst("^#+\\s*", "");
            }
            if (trimmed.length() >= 3 && trimmed.length() <= 100) {
                return trimmed;
            }
        }
        return section.substring(0, Math.min(section.length(), 50));
    }

    // =====================================================================
    // 候选管理
    // =====================================================================

    /**
     * 列出候选 TOM。支持按 modelType 和 businessDomain 过滤。
     */
    public List<TestObjectModelRecord> listCandidates(Long projectId, String modelType,
                                                       String businessDomain, CurrentUser user) {
        String sql = "SELECT * FROM test_object_model WHERE project_id = :projectId AND status = 'CANDIDATE'";
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);

        if (modelType != null && !modelType.isBlank()) {
            sql += " AND model_type = :modelType";
            params.put("modelType", modelType);
        }
        if (businessDomain != null && !businessDomain.isBlank()) {
            sql += " AND business_domain = :businessDomain";
            params.put("businessDomain", businessDomain);
        }

        sql += " ORDER BY confidence DESC, created_at DESC";

        return jdbc.sql(sql).params(params).query(this::mapTestObjectModel).list();
    }

    /**
     * 确认候选 TOM。
     */
    @Transactional
    public TestObjectModelRecord confirmCandidate(Long id, CurrentUser user) {
        TestObjectModelRecord record = getById(id);
        if (record == null) {
            throw new BusinessException("候选 TOM 不存在");
        }
        if (!"CANDIDATE".equals(record.status())) {
            throw new BusinessException("该资产已被其他用户处理，请刷新后查看最新状态");
        }

        LocalDateTime now = timeProvider.now();
        int affected = jdbcTemplate.update("""
                UPDATE test_object_model SET
                    status = 'ACTIVE',
                    requires_human_confirm = 0,
                    validity_label = 'HIGH',
                    confirmed_by = ?,
                    confirmed_at = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND status = 'CANDIDATE' AND version = ?
                """, user.id(), now, now, id, record.version());
        if (affected == 0) {
            throw new BusinessException("该资产已被其他用户处理，请刷新后查看最新状态");
        }
        return handleUpgradeMerge(record, id, user, now);
    }

    /**
     * 驳回候选 TOM。
     */
    @Transactional
    public TestObjectModelRecord rejectCandidate(Long id, String reason, CurrentUser user) {
        TestObjectModelRecord record = getById(id);
        if (record == null) {
            throw new BusinessException("候选 TOM 不存在");
        }
        if (!"CANDIDATE".equals(record.status()) && !"REVIEWING".equals(record.status())) {
            throw new BusinessException("该资产已被其他用户处理，请刷新后查看最新状态");
        }

        LocalDateTime now = timeProvider.now();
        int affected = jdbcTemplate.update("""
                UPDATE test_object_model SET
                    status = 'REJECTED',
                    rejected_by = ?,
                    rejected_at = ?,
                    rejected_reason = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND status IN ('CANDIDATE', 'REVIEWING') AND version = ?
                """, user.id(), now, reason != null ? reason : "", now, id, record.version());
        if (affected == 0) {
            throw new BusinessException("该资产已被其他用户处理，请刷新后查看最新状态");
        }
        return handleDowngradeOnReject(record, id, now);
    }

    /**
     * 编辑后确认。
     */
    @Transactional
    public TestObjectModelRecord editAndConfirm(Long id, EditTomCommand command, CurrentUser user) {
        TestObjectModelRecord record = getById(id);
        if (record == null) {
            throw new BusinessException("候选 TOM 不存在");
        }
        if (!"CANDIDATE".equals(record.status())) {
            throw new BusinessException("该资产已被其他用户处理，请刷新后查看最新状态");
        }

        LocalDateTime now = timeProvider.now();
        int affected = jdbcTemplate.update("""
                UPDATE test_object_model SET
                    name = ?,
                    description = ?,
                    properties_json = ?,
                    status = 'ACTIVE',
                    requires_human_confirm = 0,
                    validity_label = 'HIGH',
                    confirmed_by = ?,
                    confirmed_at = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND status = 'CANDIDATE' AND version = ?
                """, command.name(), command.description(), command.propertiesJson(),
                user.id(), now, now, id, record.version());
        if (affected == 0) {
            throw new BusinessException("该资产已被其他用户处理，请刷新后查看最新状态");
        }
        return handleUpgradeMerge(record, id, user, now);
    }

    /**
     * 驳回候选 TOM。
     */
    @Transactional
    public TestObjectModelRecord mergeCandidates(Long targetId, List<Long> sourceIds, CurrentUser user) {
        TestObjectModelRecord target = getById(targetId);
        if (target == null) {
            throw new BusinessException("目标 TOM 不存在");
        }

        LocalDateTime now = timeProvider.now();

        // 将源 TOM 标记为 DEPRECATED
        for (Long sourceId : sourceIds) {
            jdbcTemplate.update("""
                    UPDATE test_object_model SET
                        status = 'DEPRECATED',
                        updated_at = ?
                    WHERE id = ? AND status = 'CANDIDATE'
                    """, now, sourceId);
        }

        // 将目标 TOM 确认为 ACTIVE
        jdbcTemplate.update("""
                UPDATE test_object_model SET
                    status = 'ACTIVE',
                    requires_human_confirm = 0,
                    validity_label = 'HIGH',
                    confirmed_by = ?,
                    confirmed_at = ?,
                    updated_at = ?
                WHERE id = ?
                """, user.id(), now, now, targetId);

        refreshSemanticSnapshotQuietly(target.projectId());
        return getById(targetId);
    }

    /**
     * 弃用 TOM。
     */
    @Transactional
    public TestObjectModelRecord deprecateTom(Long id, String reason, CurrentUser user) {
        TestObjectModelRecord record = getById(id);
        if (record == null) {
            throw new BusinessException("TOM 不存在");
        }
        if ("DEPRECATED".equals(record.status())) {
            throw new BusinessException("该资产已弃用");
        }
        LocalDateTime now = timeProvider.now();
        int affected = jdbcTemplate.update("""
                UPDATE test_object_model SET
                    status = 'DEPRECATED',
                    rejected_by = ?,
                    rejected_at = ?,
                    rejected_reason = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND status != 'DEPRECATED' AND version = ?
                """, user.id(), now, reason, now, id, record.version());
        if (affected == 0) {
            throw new BusinessException("该资产已被其他用户处理，请刷新后查看最新状态");
        }
        refreshSemanticSnapshotQuietly(record.projectId());
        return getById(id);
    }

    /**
     * 恢复弃用的 TOM。
     */
    @Transactional
    public TestObjectModelRecord restoreTom(Long id, CurrentUser user) {
        TestObjectModelRecord record = getById(id);
        if (record == null) {
            throw new BusinessException("TOM 不存在");
        }
        if (!"DEPRECATED".equals(record.status())) {
            throw new BusinessException("只有已弃用的资产才能恢复");
        }
        LocalDateTime now = timeProvider.now();
        int affected = jdbcTemplate.update("""
                UPDATE test_object_model SET
                    status = 'CANDIDATE',
                    rejected_by = NULL,
                    rejected_at = NULL,
                    rejected_reason = NULL,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND status = 'DEPRECATED' AND version = ?
                """, now, id, record.version());
        if (affected == 0) {
            throw new BusinessException("该资产已被其他用户处理，请刷新后查看最新状态");
        }
        return getById(id);
    }

    /**
     * 列出 ACTIVE TOM。
     */
    public List<TestObjectModelRecord> listActive(Long projectId, String modelType, CurrentUser user) {
        return loadMergedActiveToms(projectId, modelType);
    }

    /**
     * 获取单条 TOM。
     */
    public TestObjectModelRecord getById(Long id) {
        var results = jdbc.sql("SELECT * FROM test_object_model WHERE id = :id")
                .param("id", id).query(this::mapTestObjectModel).list();
        return results.isEmpty() ? null : results.get(0);
    }

    private Long getOptimisticVersion(Long id) {
        var results = jdbc.sql("SELECT version FROM test_object_model WHERE id = :id")
                .param("id", id).query(Long.class).list();
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 确认/编辑确认 SYSTEM CANDIDATE 后，将原 PROJECT TOM 升为 SYSTEM 并删除 CANDIDATE 副本。
     */
    private TestObjectModelRecord handleUpgradeMerge(TestObjectModelRecord record, Long id, CurrentUser user, LocalDateTime now) {
        if ("SYSTEM".equals(record.scope()) && record.sourceProjectTomId() != null) {
            Long origId = record.sourceProjectTomId();
            Long origVersion = getOptimisticVersion(origId);
            if (origVersion != null) {
                jdbcTemplate.update("""
                        UPDATE test_object_model SET
                            scope = 'SYSTEM',
                            upgraded_by = ?,
                            upgraded_at = ?,
                            confirmed_by = ?,
                            confirmed_at = ?,
                            updated_at = ?,
                            version = version + 1
                        WHERE id = ? AND scope = 'PROJECT' AND version = ?
                        """, user.id(), now, user.id(), now, now, origId, origVersion);
                jdbcTemplate.update("DELETE FROM test_object_model WHERE id = ?", id);
                refreshSemanticSnapshotQuietly(record.projectId());
                return getById(origId);
            }
        }
        deprecateSupersededCandidateVersions(id, now);
        refreshSemanticSnapshotQuietly(record.projectId());
        return getById(id);
    }

    private void deprecateSupersededCandidateVersions(Long confirmedId, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE test_object_model previous
                JOIN test_object_model current
                  ON current.id = ?
                 AND current.project_id = previous.project_id
                 AND current.candidate_key IS NOT NULL
                 AND current.candidate_key = previous.candidate_key
                SET previous.status = 'DEPRECATED', previous.updated_at = ?, previous.version = previous.version + 1
                WHERE previous.id <> current.id AND previous.status = 'ACTIVE'
                """, confirmedId, now);
    }

    /**
     * 驳回/弃用 SYSTEM CANDIDATE 后，将原 PROJECT TOM 降回 CANDIDATE 状态并删除 SYSTEM 副本。
     */
    private TestObjectModelRecord handleDowngradeOnReject(TestObjectModelRecord record, Long id, LocalDateTime now) {
        if ("SYSTEM".equals(record.scope()) && record.sourceProjectTomId() != null) {
            Long origId = record.sourceProjectTomId();
            Long origVersion = getOptimisticVersion(origId);
            if (origVersion != null) {
                jdbcTemplate.update("""
                        UPDATE test_object_model SET
                            status = 'CANDIDATE',
                            requires_human_confirm = 1,
                            validity_label = 'TO_CONFIRM',
                            updated_at = ?,
                            version = version + 1
                        WHERE id = ? AND status = 'ACTIVE' AND scope = 'PROJECT' AND version = ?
                        """, now, origId, origVersion);
                jdbcTemplate.update("DELETE FROM test_object_model WHERE id = ?", id);
                return getById(origId);
            }
        }
        return getById(id);
    }

    // =====================================================================
    // 测试范围构建
    // =====================================================================

    /**
     * 构建测试范围。
     * <p>
     * 输入：需求文本 + 当前项目 ACTIVE Mini-TOM
     * 输出：影响模块、页面、字段、角色、流程、状态、断言
     */
    public TestScopeResult buildTestScope(Long projectId, String requirementText, CurrentUser user) {
        return buildTestScope(projectId, requirementText, null, user,
                TomUsageMode.PROJECT_AND_SYSTEM_TOM);
    }

    public TestScopeResult buildTestScope(Long projectId, String requirementText, Long modelConfigId, CurrentUser user) {
        return buildTestScope(projectId, requirementText, modelConfigId, user,
                TomUsageMode.PROJECT_AND_SYSTEM_TOM);
    }

    public TestScopeResult buildTestScope(Long projectId, String requirementText, Long modelConfigId,
                                          CurrentUser user, TomUsageMode tomMode) {
        TomUsageMode resolvedMode = tomMode == null
                ? TomUsageMode.PROJECT_AND_SYSTEM_TOM : tomMode;
        if (!resolvedMode.usesTom()) {
            TestScopeResult empty = new TestScopeResult();
            empty.requirementText = requirementText;
            return empty;
        }
        // 1. 读取并融合 Project/System ACTIVE TOM
        List<TestObjectModelRecord> projectToms = loadProjectActiveToms(projectId, null);
        List<TestObjectModelRecord> systemToms = resolvedMode.includesSystemTom()
                ? loadSystemActiveToms(null) : List.of();
        List<TestObjectModelRecord> activeToms = mergeActiveToms(projectToms, systemToms);

        TestScopeResult result = new TestScopeResult();
        result.requirementText = requirementText;
        result.projectTomCount = projectToms.size();
        result.systemTomCount = systemToms.size();

        // 按类型分组
        Map<String, List<TestObjectModelRecord>> byType = new HashMap<>();
        for (TestObjectModelRecord tom : activeToms) {
            byType.computeIfAbsent(tom.modelType(), k -> new ArrayList<>()).add(tom);
        }

        // 默认不再为测试范围构建额外发起一轮 LLM 语义匹配。
        // 真实使用中一次需求分析已经会调用 REQ_CLARIFY；这里再串行调用 TOM_SEMANTIC_MATCH 会显著放大超时概率。
        // 如需更强召回，可通过 aitest.tom.semantic-match.enabled=true 打开，仍会按候选数和超时降级。
        List<TestObjectModelRecord> allMatched = null;
        List<String> keywords = expandKeywordsWithSemanticContext(
                projectId, requirementText, extractKeywords(requirementText), resolvedMode);
        if (semanticMatchEnabled) {
            Long resolvedModelId = resolveModelConfigId(modelConfigId);
            List<TestObjectModelRecord> semanticCandidates = selectSemanticCandidates(activeToms, keywords);
            if (resolvedModelId != null && !semanticCandidates.isEmpty()) {
                try {
                    allMatched = matchSemanticallyWithTimeout(
                            requirementText, semanticCandidates, resolvedModelId, projectId, user);
                } catch (Exception e) {
                    log.warn("TOM 语义匹配异常，降级为关键词匹配: {}", e.getMessage());
                }
            }
        }

        List<TestObjectModelRecord> keywordMatched = new ArrayList<>();
        if (allMatched != null) {
            result.matchedByLlm = true;
            Map<String, List<TestObjectModelRecord>> matchedByType = new HashMap<>();
            for (TestObjectModelRecord tom : allMatched) {
                matchedByType.computeIfAbsent(tom.modelType(), k -> new ArrayList<>()).add(tom);
            }
            result.affectedModules = matchedByType.getOrDefault("MODULE", List.of());
            result.affectedPages = matchedByType.getOrDefault("PAGE", List.of());
            result.affectedFields = matchedByType.getOrDefault("FIELD", List.of());
            result.affectedRoles = matchedByType.getOrDefault("ROLE", List.of());
            result.affectedFlows = matchedByType.getOrDefault("FLOW", List.of());
            result.affectedStates = matchedByType.getOrDefault("STATE", List.of());
            result.suggestedAssertions = matchedByType.getOrDefault("ASSERTION", List.of());
        } else {
            result.matchedByLlm = false;
            result.affectedModules = matchToms(byType.getOrDefault("MODULE", List.of()), keywords);
            result.affectedPages = matchToms(byType.getOrDefault("PAGE", List.of()), keywords);
            result.affectedFields = matchToms(byType.getOrDefault("FIELD", List.of()), keywords);
            result.affectedRoles = matchToms(byType.getOrDefault("ROLE", List.of()), keywords);
            result.affectedFlows = matchToms(byType.getOrDefault("FLOW", List.of()), keywords);
            result.affectedStates = matchToms(byType.getOrDefault("STATE", List.of()), keywords);
            result.suggestedAssertions = matchToms(byType.getOrDefault("ASSERTION", List.of()), keywords);
            keywordMatched.addAll(result.affectedModules);
            keywordMatched.addAll(result.affectedPages);
            keywordMatched.addAll(result.affectedFields);
            keywordMatched.addAll(result.affectedRoles);
            keywordMatched.addAll(result.affectedFlows);
            keywordMatched.addAll(result.affectedStates);
            keywordMatched.addAll(result.suggestedAssertions);
        }

        // 4. 构建 tomEvidence
        List<TestObjectModelRecord> matchedForEvidence = allMatched != null ? allMatched : keywordMatched;
        for (TestObjectModelRecord tom : matchedForEvidence) {
            boolean isProject = "PROJECT".equals(tom.scope());
            result.tomEvidence.add(new TomEvidence(
                    tom.id(),
                    tom.scope(),
                    isProject ? 0.6 : 0.4,
                    allMatched != null ? "LLM语义匹配" : "关键词匹配",
                    tom.sourceType() + ":" + tom.sourceRefId()));
        }

        // 5. 冲突检测
        result.conflicts = detectConflicts(result, projectToms, systemToms);

        // 6. 生成建议
        result.suggestedQuestions = generateSuggestedQuestions(result);
        result.unsuggestedExtensions = generateUnsuggestedExtensions(result);

        return result;
    }

    private List<String> expandKeywordsWithSemanticContext(Long projectId, String requirementText,
                                                           List<String> baseKeywords,
                                                           TomUsageMode tomMode) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>(baseKeywords);
        ProjectSemanticContextService.BuildResult semanticContext =
                semanticContextService.build(projectId, requirementText, List.of(), 8, tomMode);
        for (ProjectSemanticContextService.SemanticSignal signal : semanticContext.signals()) {
            expanded.addAll(extractKeywords(signal.title()));
            expanded.addAll(extractKeywords(signal.summary()));
        }
        return new ArrayList<>(expanded);
    }

    private List<TestObjectModelRecord> loadProjectActiveToms(Long projectId, String modelType) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM test_object_model
                WHERE project_id = :projectId AND scope = 'PROJECT' AND status = 'ACTIVE'
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        if (modelType != null && !modelType.isBlank()) {
            sql.append(" AND model_type = :modelType");
            params.put("modelType", modelType);
        }
        sql.append(" ORDER BY model_type, id");
        return jdbc.sql(sql.toString()).params(params).query(this::mapTestObjectModel).list();
    }

    private List<TestObjectModelRecord> loadMergedActiveToms(Long projectId, String modelType) {
        return mergeActiveToms(
                loadProjectActiveToms(projectId, modelType),
                loadSystemActiveToms(modelType)
        );
    }

    private List<TestObjectModelRecord> loadSystemActiveToms(String modelType) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM test_object_model
                WHERE scope = 'SYSTEM' AND status = 'ACTIVE'
                """);
        Map<String, Object> params = new HashMap<>();
        if (modelType != null && !modelType.isBlank()) {
            sql.append(" AND model_type = :modelType");
            params.put("modelType", modelType);
        }
        sql.append(" ORDER BY model_type, id");
        var query = jdbc.sql(sql.toString());
        if (!params.isEmpty()) {
            query = query.params(params);
        }
        return query.query(this::mapTestObjectModel).list();
    }

    private List<TestObjectModelRecord> mergeActiveToms(List<TestObjectModelRecord> projectToms,
                                                        List<TestObjectModelRecord> systemToms) {
        Map<String, TestObjectModelRecord> merged = new LinkedHashMap<>();
        for (TestObjectModelRecord tom : projectToms) {
            merged.put(tom.modelType() + ":" + tom.name(), tom);
        }
        for (TestObjectModelRecord tom : systemToms) {
            merged.putIfAbsent(tom.modelType() + ":" + tom.name(), tom);
        }
        return new ArrayList<>(merged.values());
    }

    private List<TestObjectModelRecord> selectSemanticCandidates(List<TestObjectModelRecord> activeToms, List<String> keywords) {
        if (activeToms.size() <= semanticMatchCandidateLimit()) {
            return activeToms;
        }
        List<TestObjectModelRecord> prefiltered = matchToms(activeToms, keywords);
        if (!prefiltered.isEmpty()) {
            if (prefiltered.size() > semanticMatchCandidateLimit()) {
                log.info("TOM 语义匹配候选从 {} 预筛到 {}，截断为 {}", activeToms.size(), prefiltered.size(), semanticMatchCandidateLimit());
                return limitSemanticCandidatesWithCoverage(prefiltered);
            }
            log.info("TOM 语义匹配候选从 {} 预筛到 {}", activeToms.size(), prefiltered.size());
            return prefiltered;
        }
        List<TestObjectModelRecord> covered = limitSemanticCandidatesWithCoverage(activeToms);
        log.info("TOM 候选 {} 条且未命中预筛关键词，保留 {} 条项目/系统分层覆盖候选进入 LLM 语义匹配",
                activeToms.size(), covered.size());
        return covered;
    }

    private List<TestObjectModelRecord> limitSemanticCandidatesWithCoverage(List<TestObjectModelRecord> toms) {
        if (toms.size() <= semanticMatchCandidateLimit()) {
            return toms;
        }
        Map<String, List<TestObjectModelRecord>> groups = new LinkedHashMap<>();
        for (TestObjectModelRecord tom : toms) {
            String scope = tom.scope() == null ? "UNKNOWN" : tom.scope();
            String type = tom.modelType() == null ? "UNKNOWN" : tom.modelType();
            groups.computeIfAbsent(scope + ":" + type, key -> new ArrayList<>()).add(tom);
        }
        List<TestObjectModelRecord> limited = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        boolean added;
        int offset = 0;
        do {
            added = false;
            for (List<TestObjectModelRecord> group : groups.values()) {
                if (offset < group.size()) {
                    TestObjectModelRecord tom = group.get(offset);
                    if (tom.id() == null || seen.add(tom.id())) {
                        limited.add(tom);
                        added = true;
                        if (limited.size() >= semanticMatchCandidateLimit()) {
                            return limited;
                        }
                    }
                }
            }
            offset++;
        } while (added);
        return limited;
    }


    private int semanticMatchCandidateLimit() {
        return semanticMatchMaxCandidates > 0 ? semanticMatchMaxCandidates : DEFAULT_SEMANTIC_MATCH_MAX_CANDIDATES;
    }

    private int semanticMatchTimeoutSeconds() {
        return semanticMatchTimeoutSeconds > 0 ? semanticMatchTimeoutSeconds : DEFAULT_SEMANTIC_MATCH_TIMEOUT_SECONDS;
    }

    private List<TestObjectModelRecord> matchSemanticallyWithTimeout(String requirementText,
                                                                     List<TestObjectModelRecord> candidates,
                                                                     Long modelConfigId,
                                                                     Long projectId,
                                                                     CurrentUser user) {
        try {
            return CompletableFuture.supplyAsync(() ->
                            semanticMatcher.matchSemantically(requirementText, candidates, modelConfigId, projectId, user))
                    .orTimeout(semanticMatchTimeoutSeconds(), TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("TOM 语义匹配超时/失败，{} 秒后降级为关键词匹配: {}", semanticMatchTimeoutSeconds(), e.getMessage());
            return null;
        }
    }

    private Long resolveModelConfigId(Long modelConfigId) {
        if (modelConfigId != null) return modelConfigId;
        try {
            var configs = modelConfigService.listEnabled();
            return configs.isEmpty() ? null : configs.get(0).id();
        } catch (Exception e) {
            log.warn("获取默认模型配置失败: {}", e.getMessage());
            return null;
        }
    }

    // =====================================================================
    // 冲突检测
    // =====================================================================

    private List<TomConflict> detectConflicts(TestScopeResult result,
                                               List<TestObjectModelRecord> projectToms,
                                               List<TestObjectModelRecord> systemToms) {
        List<TomConflict> conflicts = new ArrayList<>();
        // 检查匹配到的 Project TOM 和 System TOM 是否有同名冲突
        Map<String, TestObjectModelRecord> projectByName = new HashMap<>();
        for (TestObjectModelRecord tom : projectToms) {
            projectByName.put(tom.modelType() + ":" + tom.name(), tom);
        }
        for (TestObjectModelRecord sysTom : systemToms) {
            String key = sysTom.modelType() + ":" + sysTom.name();
            TestObjectModelRecord projTom = projectByName.get(key);
            if (projTom != null) {
                // 同名同类型，检查描述是否不同
                String projDesc = projTom.description() != null ? projTom.description().trim() : "";
                String sysDesc = sysTom.description() != null ? sysTom.description().trim() : "";
                if (!projDesc.equals(sysDesc)) {
                    conflicts.add(new TomConflict(
                            "DESCRIPTION_MISMATCH",
                            "%s '%s' 在项目级和系统级描述不同".formatted(sysTom.modelType(), sysTom.name()),
                            projTom.id(), sysTom.id()));
                }
            }
        }
        return conflicts;
    }

    // =====================================================================
    // Project TOM 升级为 System TOM
    // =====================================================================

    @Transactional
    public TestObjectModelRecord upgradeToSystemTom(Long projectTomId, CurrentUser user) {
        TestObjectModelRecord record = getById(projectTomId);
        if (record == null) {
            throw new BusinessException("TOM 不存在");
        }
        if (!"PROJECT".equals(record.scope())) {
            throw new BusinessException("只有 Project TOM 才能升级为 System TOM");
        }
        if (!"ACTIVE".equals(record.status())) {
            throw new BusinessException("只有 ACTIVE 状态的 Project TOM 才能升级");
        }

        LocalDateTime now = timeProvider.now();

        // 创建 System TOM CANDIDATE
        jdbcTemplate.update("""
                INSERT INTO test_object_model(
                    project_id, scope, source_project_id, source_project_tom_id,
                    model_type, name, description, properties_json,
                    source_type, source_ref_id, source_context,
                    business_domain, priority, source_doc, source_section, evidence_text,
                    confidence, status, requires_human_confirm, validity_label,
                    created_by, created_at, updated_at
                ) VALUES (?, 'SYSTEM', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CANDIDATE', 1, 'TO_CONFIRM', ?, ?, ?)
                """, 0L, record.projectId(), record.id(),
                record.modelType(), record.name(), record.description(), record.propertiesJson(),
                record.sourceType(), record.sourceRefId(), record.sourceContext(),
                record.businessDomain(), record.priority(), record.sourceDoc(), record.sourceSection(), record.evidenceText(),
                record.confidence(), user.id(), now, now);

        // 标记原 Project TOM 已升级
        jdbcTemplate.update("""
                UPDATE test_object_model SET upgraded_by = ?, upgraded_at = ?, updated_at = ? WHERE id = ?
                """, user.id(), now, now, projectTomId);

        Long newId = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getById(newId);
    }

    /**
     * 列出 System TOM CANDIDATE（管理后台用）。
     */
    public List<TestObjectModelRecord> listSystemCandidates() {
        return jdbc.sql("""
                SELECT * FROM test_object_model
                WHERE scope = 'SYSTEM' AND status = 'CANDIDATE'
                ORDER BY created_at DESC
                """).query(this::mapTestObjectModel).list();
    }

    /**
     * 列出 System TOM ACTIVE。
     */
    public List<TestObjectModelRecord> listSystemActive() {
        return jdbc.sql("""
                SELECT * FROM test_object_model
                WHERE scope = 'SYSTEM' AND status = 'ACTIVE'
                ORDER BY model_type, id
                """).query(this::mapTestObjectModel).list();
    }

    private List<TestObjectModelRecord> matchToms(List<TestObjectModelRecord> toms, List<String> keywords) {
        List<TestObjectModelRecord> matched = new ArrayList<>();
        for (TestObjectModelRecord tom : toms) {
            String name = tom.name() == null ? "" : tom.name().toLowerCase();
            String desc = tom.description() == null ? "" : tom.description().toLowerCase();
            String text = name + " " + desc;
            for (String keyword : keywords) {
                String kw = keyword.toLowerCase();
                // 双向子串匹配：关键词包含 TOM 名称，或 TOM 名称包含关键词
                if (text.contains(kw) || kw.contains(name)) {
                    matched.add(tom);
                    break;
                }
            }
        }
        return matched;
    }

    private List<String> extractKeywords(String text) {
        List<String> keywords = new ArrayList<>();
        if (text == null || text.isBlank()) return keywords;

        // 按标点和空格分割
        String[] words = text.split("[\\s,;.!?，。；！？、：:（）()\\[\\]【】]+");
        for (String word : words) {
            String trimmed = word.trim();
            if (trimmed.length() >= 2 && trimmed.length() <= 20) {
                keywords.add(trimmed);
            }
        }

        // 对中文文本做滑动窗口 2-gram 提取，增强子串匹配
        String clean = text.replaceAll("[\\s,;.!?，。；！？、：:（）()\\[\\]【】]+", "");
        for (int len = 2; len <= Math.min(clean.length(), 4); len++) {
            for (int i = 0; i <= clean.length() - len; i++) {
                String gram = clean.substring(i, i + len);
                if (!keywords.contains(gram)) {
                    keywords.add(gram);
                }
            }
        }

        return keywords;
    }

    private List<String> generateSuggestedQuestions(TestScopeResult result) {
        List<String> questions = new ArrayList<>();

        if (!result.affectedPages.isEmpty()) {
            questions.add("涉及的页面是否都有测试覆盖？");
        }
        if (!result.affectedFields.isEmpty()) {
            questions.add("表单字段的边界值和异常输入是否验证？");
        }
        if (!result.affectedFlows.isEmpty()) {
            questions.add("业务流程的异常分支是否覆盖？");
        }

        return questions;
    }

    private List<String> generateUnsuggestedExtensions(TestScopeResult result) {
        List<String> extensions = new ArrayList<>();

        if (result.affectedModules.isEmpty()) {
            extensions.add("未匹配到相关模块，建议人工确认影响范围");
        }
        if (result.affectedStates.isEmpty()) {
            extensions.add("未匹配到状态变化，可能需要补充状态断言");
        }

        return extensions;
    }

    // =====================================================================
    // 内部工具方法
    // =====================================================================

    private Long insertCandidate(Long projectId, String modelType, String name, String description,
                                 String sourceType, Long sourceRefId, String sourceContext,
                                 Long createdBy, LocalDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO test_object_model(
                    project_id, model_type, name, description, properties_json,
                    source_type, source_ref_id, source_context,
                    confidence, status, requires_human_confirm, validity_label,
                    created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, 0.50, 'CANDIDATE', 1, 'TO_CONFIRM', ?, ?, ?)
                """, projectId, modelType, name, description,
                sourceType, sourceRefId, sourceContext,
                createdBy, now, now);

        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private List<String> extractByKeywords(String text, List<String> keywords) {
        List<String> results = new ArrayList<>();
        if (text == null || text.isBlank()) return results;

        String[] sentences = text.split("[。；！？\n]+");
        for (String sentence : sentences) {
            for (String keyword : keywords) {
                if (sentence.contains(keyword)) {
                    results.add(sentence.trim());
                    break;
                }
            }
        }

        return results;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null) return false;
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private String extractFieldName(String step) {
        // 尝试提取字段名：如"在XXX输入YYY" -> "XXX"
        int idx = step.indexOf("输入");
        if (idx > 0) {
            String before = step.substring(0, idx).trim();
            int lastSpace = before.lastIndexOf("在");
            if (lastSpace >= 0) {
                return before.substring(lastSpace + 1).trim();
            }
        }

        idx = step.indexOf("填写");
        if (idx > 0) {
            String before = step.substring(0, idx).trim();
            int lastSpace = before.lastIndexOf("在");
            if (lastSpace >= 0) {
                return before.substring(lastSpace + 1).trim();
            }
        }

        return step.substring(0, Math.min(step.length(), 30));
    }

    private String extractActionName(String step) {
        // 提取动作名称
        for (String keyword : List.of("点击", "提交", "保存", "删除", "修改", "新增", "搜索", "查询")) {
            int idx = step.indexOf(keyword);
            if (idx >= 0) {
                String after = step.substring(idx);
                int end = after.indexOf("按钮");
                if (end > 0) {
                    return after.substring(0, end + 2);
                }
                return after.substring(0, Math.min(after.length(), 30));
            }
        }
        return step.substring(0, Math.min(step.length(), 30));
    }

    private String extractStateName(String step) {
        // 提取状态名称
        for (String keyword : List.of("状态变为", "变为", "变成", "切换到")) {
            int idx = step.indexOf(keyword);
            if (idx >= 0) {
                String after = step.substring(idx + keyword.length()).trim();
                return after.substring(0, Math.min(after.length(), 20));
            }
        }
        return step.substring(0, Math.min(step.length(), 20));
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private List<String> parseJsonArray(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> parseJsonArrayMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    public TestObjectModelRecord mapTestObjectModel(ResultSet rs, int rowNum) throws SQLException {
        return new TestObjectModelRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("scope"),
                getLongNullable(rs, "source_project_id"),
                getLongNullable(rs, "source_project_tom_id"),
                rs.getString("model_type"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("properties_json"),
                rs.getString("source_type"),
                getLongNullable(rs, "source_ref_id"),
                rs.getString("source_context"),
                rs.getBigDecimal("confidence"),
                rs.getString("status"),
                rs.getBoolean("requires_human_confirm"),
                rs.getString("validity_label"),
                rs.getLong("created_by"),
                getLongNullable(rs, "confirmed_by"),
                toLocalDateTime(rs, "confirmed_at"),
                getLongNullable(rs, "rejected_by"),
                toLocalDateTime(rs, "rejected_at"),
                rs.getString("rejected_reason"),
                getLongNullable(rs, "upgraded_by"),
                toLocalDateTime(rs, "upgraded_at"),
                toLocalDateTime(rs, "created_at"),
                toLocalDateTime(rs, "updated_at"),
                rs.getString("business_domain"),
                rs.getString("priority"),
                rs.getString("source_doc"),
                rs.getString("source_section"),
                getIntegerNullable(rs, "source_page"),
                rs.getString("evidence_text"),
                rs.getString("cross_validation_json"),
                rs.getInt("version")
        );
    }

    private Long getLongNullable(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer getIntegerNullable(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private void refreshSemanticSnapshotQuietly(Long projectId) {
        try {
            semanticContextService.refreshSnapshot(projectId);
        } catch (Exception ignored) {
            // 语义快照是附加缓存，不阻塞 TOM 主链路
        }
    }

    // =====================================================================
    // 命令和结果记录
    // =====================================================================

    public record ManualSectionCommand(
            Long projectId,
            String moduleName,
            String content,
            String sourceUrl
    ) {
    }

    public record EditTomCommand(
            String name,
            String description,
            String propertiesJson
    ) {
    }

    public static class TestScopeResult {
        public String requirementText;
        public boolean matchedByLlm;
        public List<TestObjectModelRecord> affectedModules = new ArrayList<>();
        public List<TestObjectModelRecord> affectedPages = new ArrayList<>();
        public List<TestObjectModelRecord> affectedFields = new ArrayList<>();
        public List<TestObjectModelRecord> affectedRoles = new ArrayList<>();
        public List<TestObjectModelRecord> affectedFlows = new ArrayList<>();
        public List<TestObjectModelRecord> affectedStates = new ArrayList<>();
        public List<TestObjectModelRecord> suggestedAssertions = new ArrayList<>();
        public List<String> suggestedQuestions = new ArrayList<>();
        public List<String> unsuggestedExtensions = new ArrayList<>();
        public List<TomEvidence> tomEvidence = new ArrayList<>();
        public List<TomConflict> conflicts = new ArrayList<>();
        public int projectTomCount;
        public int systemTomCount;
    }

    public record TomEvidence(Long tomId, String scope, double weight, String matchReason, String sourceRef) {}

    public record TomConflict(String conflictType, String description, Long projectTomId, Long systemTomId) {}
}
