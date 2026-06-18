package com.company.aitest.businesspack;

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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务包核心服务。
 * <p>
 * 支持从项目资产（TOM、页面画像、轨迹摘要等）自动生成 business_pack draft，
 * 以及人工确认、合并、拆分、启用/停用/归档。
 */
@Service
public class BusinessPackService {

    private static final Logger log = LoggerFactory.getLogger(BusinessPackService.class);

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public BusinessPackService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    // =====================================================================
    // 查询
    // =====================================================================

    public List<BusinessPackRecord> listPacks(Long projectId, String status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM business_pack WHERE project_id = :projectId");
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.put("status", status);
        }
        sql.append(" ORDER BY updated_at DESC");
        return jdbc.sql(sql.toString()).params(params).query(this::mapPack).list();
    }

    public BusinessPackRecord getPack(Long packId) {
        List<BusinessPackRecord> results = jdbc.sql("SELECT * FROM business_pack WHERE id = :id")
                .param("id", packId).query(this::mapPack).list();
        return results.isEmpty() ? null : results.get(0);
    }

    public List<BusinessPackItemRecord> listItems(Long packId) {
        return jdbc.sql("SELECT * FROM business_pack_item WHERE pack_id = :packId ORDER BY item_type, item_key")
                .param("packId", packId).query(this::mapItem).list();
    }

    public List<BusinessPackItemRecord> listItemsByProject(Long projectId, String itemType) {
        StringBuilder sql = new StringBuilder("""
                SELECT bi.* FROM business_pack_item bi
                JOIN business_pack bp ON bi.pack_id = bp.id
                WHERE bi.project_id = :projectId AND bp.status = 'ACTIVE'
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        if (itemType != null && !itemType.isBlank()) {
            sql.append(" AND bi.item_type = :itemType");
            params.put("itemType", itemType);
        }
        sql.append(" ORDER BY bi.item_type, bi.item_key");
        return jdbc.sql(sql.toString()).params(params).query(this::mapItem).list();
    }

    public List<RefreshDiagnosticRecord> listRefreshDiagnostics(Long projectId) {
        return jdbc.sql("""
                SELECT * FROM business_pack_refresh_diagnostic
                WHERE project_id = :projectId
                ORDER BY created_at DESC, id DESC
                LIMIT 20
                """)
                .param("projectId", projectId)
                .query(this::mapRefreshDiagnostic)
                .list();
    }

    // =====================================================================
    // 自动刷新（无需用户上下文，由系统触发）
    // =====================================================================

    /**
     * 系统级自动刷新：当项目资产变更时，自动更新 business_pack draft。
     * 不需要用户上下文，由 semantic_pack 刷新链路触发。
     * <p>
     * 聚类策略：
     * 1. 先按 business_domain 分组（主聚类）
     * 2. 再按页面路由前缀补充聚类（辅助聚类，合并到已有 domain 或创建新 domain）
     */
    public void refreshForProject(Long projectId) {
        if (projectId == null) return;
        LocalDateTime startedAt = timeProvider.now();
        int tomCount = 0;
        int pageProfileCount = 0;
        int patternCount = 0;
        int summaryCount = 0;
        int generatedPackCount = 0;
        int inferredRelationCount = 0;
        try {
            LocalDateTime now = startedAt;
            Map<String, List<TomRow>> tomsByDomain = loadActiveTomsByDomain(projectId);
            List<PageProfileRow> pageProfiles = loadActivePageProfiles(projectId);
            List<PatternRow> patterns = loadConfirmedPatterns(projectId);
            List<SummaryRow> summaries = loadConfirmedSummaries(projectId);
            tomCount = tomsByDomain.values().stream().mapToInt(List::size).sum();
            pageProfileCount = pageProfiles.size();
            patternCount = patterns.size();
            summaryCount = summaries.size();

            // 辅助聚类：按页面路由前缀补充 domain 分组
            Map<String, List<String>> clusterHints = buildClusterHints(projectId);
            for (Map.Entry<String, List<String>> hint : clusterHints.entrySet()) {
                String domain = hint.getKey();
                if (!tomsByDomain.containsKey(domain) && !"通用".equals(domain) && !"root".equals(domain)) {
                    tomsByDomain.put(domain, new ArrayList<>());
                }
            }

            for (Map.Entry<String, List<TomRow>> entry : tomsByDomain.entrySet()) {
                generatedPackCount++;
                String domain = entry.getKey();
                List<TomRow> toms = entry.getValue();
                BusinessPackRecord pack = createOrGetDraftPack(projectId, domain, "AUTO_GENERATED", null, now);
                int itemCount = 0;

                for (TomRow tom : toms) {
                    upsertItem(pack.id(), projectId, "TOM", tom.name,
                            tom.description, tom.confidence, "TOM", tom.id, null, now);
                    itemCount++;
                    // 自动创建 TOM 绑定
                    autoCreateTomBinding(pack.id(), projectId, tom.id, tom.name, null, now);
                }
                for (PageProfileRow profile : pageProfiles) {
                    if (matchesDomain(profile, domain)) {
                        upsertItem(pack.id(), projectId, "PAGE", profile.routePath,
                                profile.pageLabel, new BigDecimal("0.80"), "PAGE_SCAN", profile.id, null, now);
                        itemCount++;
                        // 自动创建扫描绑定
                        autoCreateScanBinding(pack.id(), projectId, profile.id, profile.routePath, profile.pageLabel, now);
                    }
                }
                for (PatternRow pattern : patterns) {
                    upsertItem(pack.id(), projectId, "TERM", pattern.operationType,
                            pattern.confirmedStepText, new BigDecimal("0.75"), "TRACE_CORRECTION", pattern.id, null, now);
                    itemCount++;
                }
                for (SummaryRow summary : summaries) {
                    upsertItem(pack.id(), projectId, "FLOW", truncate(summary.overview, 128),
                            summary.businessSummary, new BigDecimal("0.70"), "TRACE_SUMMARY", summary.id, null, now);
                    itemCount++;
                }
                updatePackStats(pack.id(), itemCount, now);
            }

            if (tomsByDomain.isEmpty()) {
                createOrGetDraftPack(projectId, "通用", "AUTO_GENERATED", null, now);
                generatedPackCount++;
            }

            // 自动生成包间关系
            inferredRelationCount = inferRelations(projectId);
            recordRefreshDiagnostic(projectId, "SUCCESS", null, tomCount, pageProfileCount,
                    patternCount, summaryCount, generatedPackCount, inferredRelationCount,
                    startedAt, timeProvider.now());
        } catch (Exception e) {
            recordRefreshDiagnostic(projectId, "FAILED", e.getMessage(), tomCount, pageProfileCount,
                    patternCount, summaryCount, generatedPackCount, inferredRelationCount,
                    startedAt, timeProvider.now());
            log.warn("business_pack 自动刷新失败（非致命）: {}", e.getMessage());
        }
    }

    private void recordRefreshDiagnostic(Long projectId, String status, String errorMessage,
                                         int tomCount, int pageProfileCount, int patternCount,
                                         int summaryCount, int generatedPackCount,
                                         int inferredRelationCount, LocalDateTime startedAt,
                                         LocalDateTime finishedAt) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO business_pack_refresh_diagnostic(project_id, status, error_message,
                        tom_count, page_profile_count, pattern_count, summary_count,
                        generated_pack_count, inferred_relation_count, started_at, finished_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, projectId, status, truncate(errorMessage, 1024),
                    tomCount, pageProfileCount, patternCount, summaryCount,
                    generatedPackCount, inferredRelationCount, startedAt, finishedAt);
        } catch (Exception ex) {
            log.debug("business_pack 刷新诊断记录失败（非致命）: {}", ex.getMessage());
        }
    }

    /**
     * 自动创建 TOM 绑定（best-error，不阻塞主流程）。
     */
    private void autoCreateTomBinding(Long packId, Long projectId, Long tomId,
                                        String tomName, String tomType, LocalDateTime now) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO business_pack_tom_binding(pack_id, project_id, tom_id,
                        tom_name, tom_type, confidence, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 0.80, 'ACTIVE', ?, ?)
                    ON DUPLICATE KEY UPDATE tom_name = VALUES(tom_name), updated_at = VALUES(updated_at)
                    """, packId, projectId, tomId, tomName, tomType, now, now);
        } catch (Exception e) {
            log.debug("TOM 绑定创建失败（非致命）: {}", e.getMessage());
        }
    }

    /**
     * 自动创建扫描绑定（best-effort，不阻塞主流程）。
     */
    private void autoCreateScanBinding(Long packId, Long projectId, Long scanProfileId,
                                         String routePath, String pageLabel, LocalDateTime now) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO business_pack_scan_binding(pack_id, project_id, scan_profile_id,
                        route_path, page_label, confidence, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 0.80, 'ACTIVE', ?, ?)
                    ON DUPLICATE KEY UPDATE route_path = VALUES(route_path), updated_at = VALUES(updated_at)
                    """, packId, projectId, scanProfileId, routePath, pageLabel, now, now);
        } catch (Exception e) {
            log.debug("扫描绑定创建失败（非致命）: {}", e.getMessage());
        }
    }

    // =====================================================================
    // 手动生成 business_pack draft
    // =====================================================================

    /**
     * 从项目资产自动生成一个或多个 business_pack draft。
     * <p>
     * 策略：
     * 1. 收集所有 ACTIVE TOM，按 business_domain 分组
     * 2. 每个 domain 生成一个 business_pack draft
     * 3. 无 domain 的 TOM 归入 "通用" pack
     * 4. 同时收集页面画像、步骤模板、业务摘要作为补充条目
     */
    @Transactional
    public List<BusinessPackRecord> generateDrafts(Long projectId, CurrentUser user) {
        LocalDateTime now = timeProvider.now();

        // 1. 收集 ACTIVE TOM，按 business_domain 分组
        Map<String, List<TomRow>> tomsByDomain = loadActiveTomsByDomain(projectId);

        // 2. 收集页面画像
        List<PageProfileRow> pageProfiles = loadActivePageProfiles(projectId);

        // 3. 收集已确认步骤模板
        List<PatternRow> patterns = loadConfirmedPatterns(projectId);

        // 4. 收集已确认摘要
        List<SummaryRow> summaries = loadConfirmedSummaries(projectId);

        // 5. 按 domain 生成 pack
        List<BusinessPackRecord> generated = new ArrayList<>();

        for (Map.Entry<String, List<TomRow>> entry : tomsByDomain.entrySet()) {
            String domain = entry.getKey();
            List<TomRow> toms = entry.getValue();

            BusinessPackRecord pack = createOrGetDraftPack(projectId, domain, "AUTO_GENERATED", user, now);
            int itemCount = 0;

            // 5a. TOM 条目
            for (TomRow tom : toms) {
                upsertItem(pack.id(), projectId, "TOM", tom.name,
                        tom.description, tom.confidence, "TOM", tom.id, user, now);
                itemCount++;
            }

            // 5b. 与该 domain 相关的页面画像
            for (PageProfileRow profile : pageProfiles) {
                if (matchesDomain(profile, domain)) {
                    upsertItem(pack.id(), projectId, "PAGE", profile.routePath,
                            profile.pageLabel, new BigDecimal("0.80"), "PAGE_SCAN", profile.id, user, now);
                    itemCount++;
                }
            }

            // 5c. 步骤模板
            for (PatternRow pattern : patterns) {
                upsertItem(pack.id(), projectId, "TERM", pattern.operationType,
                        pattern.confirmedStepText, new BigDecimal("0.75"), "TRACE_CORRECTION", pattern.id, user, now);
                itemCount++;
            }

            // 5d. 业务摘要
            for (SummaryRow summary : summaries) {
                upsertItem(pack.id(), projectId, "FLOW", truncate(summary.overview, 128),
                        summary.businessSummary, new BigDecimal("0.70"), "TRACE_SUMMARY", summary.id, user, now);
                itemCount++;
            }

            // 更新 pack 统计
            updatePackStats(pack.id(), itemCount, now);
            generated.add(getPack(pack.id()));
        }

        // 6. 如果没有分组结果，生成一个空的通用 pack
        if (generated.isEmpty()) {
            BusinessPackRecord pack = createOrGetDraftPack(projectId, "通用", "AUTO_GENERATED", user, now);
            generated.add(getPack(pack.id()));
        }

        int tomCount = tomsByDomain.values().stream().mapToInt(List::size).sum();
        recordRefreshDiagnostic(projectId, "SUCCESS", null, tomCount, pageProfiles.size(),
                patterns.size(), summaries.size(), generated.size(), 0, now, timeProvider.now());

        return generated;
    }

    // =====================================================================
    // 生命周期管理
    // =====================================================================

    @Transactional
    public BusinessPackRecord activate(Long packId, CurrentUser user) {
        BusinessPackRecord pack = getPack(packId);
        if (pack == null) throw new BusinessException("业务包不存在");
        if (!"DRAFT".equals(pack.status()) && !"INACTIVE".equals(pack.status())) {
            throw new BusinessException("只有 DRAFT 或 INACTIVE 状态的业务包才能激活");
        }
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                UPDATE business_pack SET status = 'ACTIVE', activated_at = ?, updated_at = ? WHERE id = ?
                """, now, now, packId);
        createSnapshot(packId, "激活业务包", user);
        return getPack(packId);
    }

    @Transactional
    public BusinessPackRecord deactivate(Long packId, CurrentUser user) {
        BusinessPackRecord pack = getPack(packId);
        if (pack == null) throw new BusinessException("业务包不存在");
        if (!"ACTIVE".equals(pack.status())) {
            throw new BusinessException("只有 ACTIVE 状态的业务包才能停用");
        }
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                UPDATE business_pack SET status = 'INACTIVE', updated_at = ? WHERE id = ?
                """, now, packId);
        createSnapshot(packId, "停用业务包", user);
        return getPack(packId);
    }

    @Transactional
    public BusinessPackRecord archive(Long packId, CurrentUser user) {
        BusinessPackRecord pack = getPack(packId);
        if (pack == null) throw new BusinessException("业务包不存在");
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                UPDATE business_pack SET status = 'ARCHIVED', updated_at = ? WHERE id = ?
                """, now, packId);
        createSnapshot(packId, "归档业务包", user);
        return getPack(packId);
    }

    @Transactional
    public BusinessPackRecord updateDescription(Long packId, String description, CurrentUser user) {
        BusinessPackRecord pack = getPack(packId);
        if (pack == null) throw new BusinessException("业务包不存在");
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                UPDATE business_pack SET description = ?, updated_at = ? WHERE id = ?
                """, description, now, packId);
        return getPack(packId);
    }

    @Transactional
    public BusinessPackRecord rename(Long packId, String packName, CurrentUser user) {
        BusinessPackRecord pack = getPack(packId);
        if (pack == null) throw new BusinessException("业务包不存在");
        if (packName == null || packName.isBlank()) throw new BusinessException("名称不能为空");
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                UPDATE business_pack SET pack_name = ?, updated_at = ? WHERE id = ?
                """, packName, now, packId);
        return getPack(packId);
    }

    @Transactional
    public void deleteItem(Long itemId) {
        jdbcTemplate.update("DELETE FROM business_pack_item WHERE id = ?", itemId);
    }

    // =====================================================================
    // 绑定关系管理
    // =====================================================================

    @Transactional
    public RuleBindingRecord createRuleBinding(Long packId, String ruleType, String ruleRef,
                                                String ruleConfigJson, CurrentUser user) {
        BusinessPackRecord pack = getPack(packId);
        if (pack == null) throw new BusinessException("业务包不存在");
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO business_pack_rule_binding(pack_id, project_id, rule_type, rule_ref,
                    rule_config_json, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON DUPLICATE KEY UPDATE rule_config_json = VALUES(rule_config_json), updated_at = VALUES(updated_at)
                """, packId, pack.projectId(), ruleType, ruleRef, ruleConfigJson, now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getRuleBinding(id);
    }

    public List<RuleBindingRecord> listRuleBindings(Long packId) {
        return jdbc.sql("SELECT * FROM business_pack_rule_binding WHERE pack_id = :packId AND status = 'ACTIVE'")
                .param("packId", packId).query(this::mapRuleBinding).list();
    }

    public RuleBindingRecord getRuleBinding(Long id) {
        List<RuleBindingRecord> results = jdbc.sql("SELECT * FROM business_pack_rule_binding WHERE id = :id")
                .param("id", id).query(this::mapRuleBinding).list();
        return results.isEmpty() ? null : results.get(0);
    }

    @Transactional
    public ScanBindingRecord createScanBinding(Long packId, Long scanProfileId,
                                                String routePath, String pageLabel, CurrentUser user) {
        BusinessPackRecord pack = getPack(packId);
        if (pack == null) throw new BusinessException("业务包不存在");
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO business_pack_scan_binding(pack_id, project_id, scan_profile_id,
                    route_path, page_label, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON DUPLICATE KEY UPDATE route_path = VALUES(route_path), updated_at = VALUES(updated_at)
                """, packId, pack.projectId(), scanProfileId, routePath, pageLabel, now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getScanBinding(id);
    }

    public List<ScanBindingRecord> listScanBindings(Long packId) {
        return jdbc.sql("SELECT * FROM business_pack_scan_binding WHERE pack_id = :packId AND status = 'ACTIVE'")
                .param("packId", packId).query(this::mapScanBinding).list();
    }

    public ScanBindingRecord getScanBinding(Long id) {
        List<ScanBindingRecord> results = jdbc.sql("SELECT * FROM business_pack_scan_binding WHERE id = :id")
                .param("id", id).query(this::mapScanBinding).list();
        return results.isEmpty() ? null : results.get(0);
    }

    @Transactional
    public TomBindingRecord createTomBinding(Long packId, Long tomId,
                                               String tomName, String tomType, CurrentUser user) {
        BusinessPackRecord pack = getPack(packId);
        if (pack == null) throw new BusinessException("业务包不存在");
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO business_pack_tom_binding(pack_id, project_id, tom_id,
                    tom_name, tom_type, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON DUPLICATE KEY UPDATE tom_name = VALUES(tom_name), updated_at = VALUES(updated_at)
                """, packId, pack.projectId(), tomId, tomName, tomType, now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getTomBinding(id);
    }

    public List<TomBindingRecord> listTomBindings(Long packId) {
        return jdbc.sql("SELECT * FROM business_pack_tom_binding WHERE pack_id = :packId AND status = 'ACTIVE'")
                .param("packId", packId).query(this::mapTomBinding).list();
    }

    public TomBindingRecord getTomBinding(Long id) {
        List<TomBindingRecord> results = jdbc.sql("SELECT * FROM business_pack_tom_binding WHERE id = :id")
                .param("id", id).query(this::mapTomBinding).list();
        return results.isEmpty() ? null : results.get(0);
    }

    public SemanticBindingRecord getSemanticBinding(Long id) {
        List<SemanticBindingRecord> results = jdbc.sql("SELECT * FROM business_pack_semantic_binding WHERE id = :id")
                .param("id", id).query(this::mapSemanticBinding).list();
        return results.isEmpty() ? null : results.get(0);
    }

    @Transactional
    public SemanticBindingRecord createSemanticBinding(Long packId, Long semanticPackId,
                                                        String signalCategory, String signalTitle, CurrentUser user) {
        BusinessPackRecord pack = getPack(packId);
        if (pack == null) throw new BusinessException("业务包不存在");
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO business_pack_semantic_binding(pack_id, project_id, semantic_pack_id,
                    signal_category, signal_title, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON DUPLICATE KEY UPDATE signal_title = VALUES(signal_title), updated_at = VALUES(updated_at)
                """, packId, pack.projectId(), semanticPackId, signalCategory, signalTitle, now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getSemanticBinding(id);
    }

    public List<SemanticBindingRecord> listSemanticBindings(Long packId) {
        return jdbc.sql("SELECT * FROM business_pack_semantic_binding WHERE pack_id = :packId AND status = 'ACTIVE'")
                .param("packId", packId).query(this::mapSemanticBinding).list();
    }

    // =====================================================================
    // 消费记录
    // =====================================================================

    @Transactional
    public void recordConsumption(Long packId, String consumerType, String consumerRef, int signalCount) {
        BusinessPackRecord pack = getPack(packId);
        if (pack == null) return;
        jdbcTemplate.update("""
                INSERT INTO business_pack_consumption_log(pack_id, project_id, consumer_type,
                    consumer_ref, signal_count, consumed_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                """, packId, pack.projectId(), consumerType, consumerRef, signalCount);
    }

    public List<ConsumptionLogRecord> listConsumptionLogs(Long packId) {
        return jdbc.sql("SELECT * FROM business_pack_consumption_log WHERE pack_id = :packId ORDER BY consumed_at DESC LIMIT 50")
                .param("packId", packId).query(this::mapConsumptionLog).list();
    }

    public List<ConsumptionLogRecord> listConsumptionLogsByProject(Long projectId, String consumerType) {
        StringBuilder sql = new StringBuilder("SELECT * FROM business_pack_consumption_log WHERE project_id = :projectId");
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        if (consumerType != null && !consumerType.isBlank()) {
            sql.append(" AND consumer_type = :consumerType");
            params.put("consumerType", consumerType);
        }
        sql.append(" ORDER BY consumed_at DESC LIMIT 100");
        return jdbc.sql(sql.toString()).params(params).query(this::mapConsumptionLog).list();
    }

    // =====================================================================
    // 生命周期增强
    // =====================================================================

    /**
     * 获取业务包的可用状态转换。
     */
    public List<String> getAvailableTransitions(Long packId) {
        BusinessPackRecord pack = getPack(packId);
        if (pack == null) return List.of();
        return switch (pack.status()) {
            case "DRAFT" -> List.of("ACTIVE", "ARCHIVED");
            case "ACTIVE" -> List.of("INACTIVE", "ARCHIVED");
            case "INACTIVE" -> List.of("ACTIVE", "ARCHIVED");
            case "ARCHIVED" -> List.of();
            default -> List.of();
        };
    }

    // =====================================================================
    // 快照（版本化）
    // =====================================================================

    /**
     * 创建业务包快照。每次状态变更时调用，支持版本回溯。
     */
    @Transactional
    public SnapshotRecord createSnapshot(Long packId, String changeSummary, CurrentUser user) {
        BusinessPackRecord pack = getPack(packId);
        if (pack == null) throw new BusinessException("业务包不存在");
        LocalDateTime now = timeProvider.now();

        // 获取当前快照序号
        Integer maxNo = jdbc.sql("SELECT MAX(snapshot_no) FROM business_pack_snapshot WHERE pack_id = :packId")
                .param("packId", packId).query(Integer.class).single();
        int nextNo = (maxNo == null ? 0 : maxNo) + 1;

        // 收集当前所有条目
        List<BusinessPackItemRecord> items = listItems(packId);
        String snapshotJson;
        try {
            snapshotJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    items.stream().map(i -> Map.of(
                            "itemType", i.itemType(),
                            "itemKey", i.itemKey(),
                            "itemValue", i.itemValue() != null ? i.itemValue() : "",
                            "confidence", i.confidence(),
                            "sourceType", i.sourceType() != null ? i.sourceType() : ""
                    )).toList());
        } catch (Exception e) {
            snapshotJson = "[]";
        }

        Long createdBy = user != null ? user.id() : 0L;
        jdbcTemplate.update("""
                INSERT INTO business_pack_snapshot(pack_id, project_id, snapshot_no, pack_name,
                    business_domain, status, item_count, confidence_avg, snapshot_json,
                    change_summary, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, packId, pack.projectId(), nextNo, pack.packName(),
                pack.businessDomain(), pack.status(), pack.itemCount(), pack.confidenceAvg(),
                snapshotJson, changeSummary, createdBy, now);

        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getSnapshot(id);
    }

    public List<SnapshotRecord> listSnapshots(Long packId) {
        return jdbc.sql("SELECT * FROM business_pack_snapshot WHERE pack_id = :packId ORDER BY snapshot_no DESC")
                .param("packId", packId).query(this::mapSnapshot).list();
    }

    public SnapshotRecord getSnapshot(Long snapshotId) {
        List<SnapshotRecord> results = jdbc.sql("SELECT * FROM business_pack_snapshot WHERE id = :id")
                .param("id", snapshotId).query(this::mapSnapshot).list();
        return results.isEmpty() ? null : results.get(0);
    }

    // =====================================================================
    // 关系管理
    // =====================================================================

    @Transactional
    public RelationRecord createRelation(Long sourcePackId, Long targetPackId,
                                          String relationType, String description, CurrentUser user) {
        if (sourcePackId.equals(targetPackId)) throw new BusinessException("不能创建自引用关系");
        BusinessPackRecord source = getPack(sourcePackId);
        BusinessPackRecord target = getPack(targetPackId);
        if (source == null || target == null) throw new BusinessException("业务包不存在");
        if (!Objects.equals(source.projectId(), target.projectId())) throw new BusinessException("只能在同一项目内创建关系");

        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO business_pack_relation(project_id, source_pack_id, target_pack_id,
                    relation_type, confidence, description, source_type, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0.50, ?, 'MANUAL', 'ACTIVE', ?, ?)
                ON DUPLICATE KEY UPDATE
                    description = VALUES(description), updated_at = VALUES(updated_at)
                """, source.projectId(), sourcePackId, targetPackId,
                relationType, description, now, now);

        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getRelation(id);
    }

    public List<RelationRecord> listRelations(Long packId) {
        return jdbc.sql("""
                SELECT * FROM business_pack_relation
                WHERE (source_pack_id = :packId OR target_pack_id = :packId) AND status = 'ACTIVE'
                ORDER BY relation_type
                """).param("packId", packId).query(this::mapRelation).list();
    }

    public RelationRecord getRelation(Long relationId) {
        List<RelationRecord> results = jdbc.sql("SELECT * FROM business_pack_relation WHERE id = :id")
                .param("id", relationId).query(this::mapRelation).list();
        return results.isEmpty() ? null : results.get(0);
    }

    @Transactional
    public void deleteRelation(Long relationId) {
        jdbcTemplate.update("UPDATE business_pack_relation SET status = 'DEPRECATED', updated_at = NOW() WHERE id = ?", relationId);
    }

    // =====================================================================
    // 自动推断包间关系
    // =====================================================================

    /**
     * 自动推断项目内业务包之间的关系。
     * <p>
     * 策略：
     * 1. 共享 TOM 的包 → LINKED
     * 2. 共享页面画像的包 → SUPPLEMENTS
     * 3. 包含关系（大包包含小包的 TOM 子集）→ CONTAINS
     */
    @Transactional
    public int inferRelations(Long projectId) {
        LocalDateTime now = timeProvider.now();
        List<BusinessPackRecord> packs = listPacks(projectId, null);
        if (packs.size() < 2) return 0;

        int created = 0;

        // 收集每个包的 TOM 名称集合
        Map<Long, Set<String>> packTomNames = new LinkedHashMap<>();
        Map<Long, Set<String>> packPageRoutes = new LinkedHashMap<>();
        for (BusinessPackRecord pack : packs) {
            Set<String> tomNames = jdbc.sql("""
                    SELECT item_key FROM business_pack_item
                    WHERE pack_id = ? AND item_type = 'TOM' AND status = 'ACTIVE'
                    """).param(pack.id()).query(String.class).list().stream()
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> pageRoutes = jdbc.sql("""
                    SELECT item_key FROM business_pack_item
                    WHERE pack_id = ? AND item_type = 'PAGE' AND status = 'ACTIVE'
                    """).param(pack.id()).query(String.class).list().stream()
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            packTomNames.put(pack.id(), tomNames);
            packPageRoutes.put(pack.id(), pageRoutes);
        }

        // 推断关系
        for (int i = 0; i < packs.size(); i++) {
            for (int j = i + 1; j < packs.size(); j++) {
                BusinessPackRecord packA = packs.get(i);
                BusinessPackRecord packB = packs.get(j);

                Set<String> tomsA = packTomNames.getOrDefault(packA.id(), Set.of());
                Set<String> tomsB = packTomNames.getOrDefault(packB.id(), Set.of());
                Set<String> pagesA = packPageRoutes.getOrDefault(packA.id(), Set.of());
                Set<String> pagesB = packPageRoutes.getOrDefault(packB.id(), Set.of());

                // 共享 TOM → LINKED
                Set<String> sharedToms = new HashSet<>(tomsA);
                sharedToms.retainAll(tomsB);
                if (!sharedToms.isEmpty()) {
                    double confidence = Math.min(0.9, 0.5 + sharedToms.size() * 0.1);
                    String desc = "共享 " + sharedToms.size() + " 个 TOM";
                    createAutoRelation(packA.id(), packB.id(), "LINKED", confidence, desc, now);
                    created++;
                }

                // 共享页面 → SUPPLEMENTS
                Set<String> sharedPages = new HashSet<>(pagesA);
                sharedPages.retainAll(pagesB);
                if (!sharedPages.isEmpty() && sharedToms.isEmpty()) {
                    double confidence = Math.min(0.8, 0.5 + sharedPages.size() * 0.05);
                    String desc = "共享 " + sharedPages.size() + " 个页面";
                    createAutoRelation(packA.id(), packB.id(), "SUPPLEMENTS", confidence, desc, now);
                    created++;
                }

                // 包含关系（A 的 TOM 是 B 的子集）→ CONTAINS
                if (!tomsA.isEmpty() && tomsB.containsAll(tomsA) && !tomsA.containsAll(tomsB)) {
                    createAutoRelation(packB.id(), packA.id(), "CONTAINS", 0.7,
                            "包含 " + tomsA.size() + " 个 TOM", now);
                    created++;
                }
                if (!tomsB.isEmpty() && tomsA.containsAll(tomsB) && !tomsB.containsAll(tomsA)) {
                    createAutoRelation(packA.id(), packB.id(), "CONTAINS", 0.7,
                            "包含 " + tomsB.size() + " 个 TOM", now);
                    created++;
                }
            }
        }

        return created;
    }

    private void createAutoRelation(Long sourcePackId, Long targetPackId, String relationType,
                                     double confidence, String description, LocalDateTime now) {
        try {
            BusinessPackRecord source = getPack(sourcePackId);
            if (source == null) return;
            jdbcTemplate.update("""
                    INSERT INTO business_pack_relation(project_id, source_pack_id, target_pack_id,
                        relation_type, confidence, description, source_type, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'AUTO_INFERRED', 'ACTIVE', ?, ?)
                    ON DUPLICATE KEY UPDATE
                        confidence = GREATEST(confidence, VALUES(confidence)),
                        description = VALUES(description),
                        updated_at = VALUES(updated_at)
                    """, source.projectId(), sourcePackId, targetPackId,
                    relationType, confidence, description, now, now);
        } catch (Exception e) {
            log.debug("自动推断关系失败（非致命）: {}", e.getMessage());
        }
    }

    // =====================================================================
    // 内部方法
    // =====================================================================

    private BusinessPackRecord createOrGetDraftPack(Long projectId, String domain,
                                                      String packType, CurrentUser user, LocalDateTime now) {
        String packName = (domain == null || domain.isBlank() ? "通用" : domain) + "业务包";
        List<BusinessPackRecord> existing = jdbc.sql("""
                SELECT * FROM business_pack
                WHERE project_id = ? AND business_domain <=> ? AND status = 'DRAFT' AND pack_type = ?
                """).params(projectId, domain, packType).query(this::mapPack).list();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Long createdBy = user != null ? user.id() : 0L;
        jdbcTemplate.update("""
                INSERT INTO business_pack(project_id, pack_name, pack_type, business_domain, version, status,
                    source_types, item_count, built_at, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'DRAFT', '[]', 0, ?, ?, ?, ?)
                """, projectId, packName, packType, domain, now, createdBy, now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getPack(id);
    }

    private void upsertItem(Long packId, Long projectId, String itemType, String itemKey,
                              String itemValue, BigDecimal confidence, String sourceType,
                              Long sourceRefId, CurrentUser user, LocalDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO business_pack_item(pack_id, project_id, item_type, item_key, item_value,
                    confidence, source_type, source_ref_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON DUPLICATE KEY UPDATE
                    item_value = VALUES(item_value),
                    confidence = VALUES(confidence),
                    source_type = VALUES(source_type),
                    source_ref_id = VALUES(source_ref_id),
                    updated_at = VALUES(updated_at)
                """, packId, projectId, itemType, truncate(itemKey, 256), itemValue,
                confidence, sourceType, sourceRefId, now, now);
    }

    private void updatePackStats(Long packId, int itemCount, LocalDateTime now) {
        BigDecimal avgConf = jdbc.sql("""
                SELECT AVG(confidence) FROM business_pack_item WHERE pack_id = ? AND status = 'ACTIVE'
                """).param(packId).query(BigDecimal.class).single();
        jdbcTemplate.update("""
                UPDATE business_pack SET item_count = ?, confidence_avg = ?, built_at = ?, updated_at = ?
                WHERE id = ?
                """, itemCount, avgConf, now, now, packId);
    }

    private Map<String, List<TomRow>> loadActiveTomsByDomain(Long projectId) {
        List<TomRow> toms = jdbc.sql("""
                SELECT id, name, description, business_domain, confidence
                FROM test_object_model
                WHERE status = 'ACTIVE'
                  AND ((project_id = ? AND scope = 'PROJECT') OR scope = 'SYSTEM')
                ORDER BY business_domain, model_type, name
                """).param(projectId).query(this::mapTom).list();

        Map<String, List<TomRow>> grouped = new LinkedHashMap<>();
        for (TomRow tom : toms) {
            String domain = tom.businessDomain != null && !tom.businessDomain.isBlank()
                    ? tom.businessDomain : "通用";
            grouped.computeIfAbsent(domain, k -> new ArrayList<>()).add(tom);
        }
        return grouped;
    }

    private List<PageProfileRow> loadActivePageProfiles(Long projectId) {
        return jdbc.sql("""
                SELECT id, page_label, route_path, field_labels_json, action_labels_json
                FROM page_scan_profile
                WHERE project_id = ? AND status = 'ACTIVE'
                ORDER BY source_key, page_label
                """).param(projectId).query(this::mapPageProfile).list();
    }

    private List<PatternRow> loadConfirmedPatterns(Long projectId) {
        return jdbc.sql("""
                SELECT id, operation_type, source_text, confirmed_step_text
                FROM trace_correction_candidate
                WHERE project_id = ? AND status = 'CONFIRMED' AND correction_scope = 'STEP'
                ORDER BY confirmed_at DESC
                LIMIT 50
                """).param(projectId).query(this::mapPattern).list();
    }

    private List<SummaryRow> loadConfirmedSummaries(Long projectId) {
        return jdbc.sql("""
                SELECT id, overview, business_summary
                FROM trace_summary
                WHERE project_id = ? AND status = 'CONFIRMED'
                ORDER BY confirmed_at DESC
                LIMIT 20
                """).param(projectId).query(this::mapSummary).list();
    }

    private boolean matchesDomain(PageProfileRow profile, String domain) {
        if ("通用".equals(domain)) return true;
        String text = (profile.pageLabel != null ? profile.pageLabel : "")
                + " " + (profile.routePath != null ? profile.routePath : "")
                + " " + (profile.fieldLabelsJson != null ? profile.fieldLabelsJson : "")
                + " " + (profile.actionLabelsJson != null ? profile.actionLabelsJson : "");
        return text.toLowerCase().contains(domain.toLowerCase());
    }

    /**
     * 按页面路由前缀聚类页面画像。
     * 例如：/crm/customer/list, /crm/customer/detail → "crm"
     *       /ops/device/list, /ops/device/detail → "ops"
     */
    private Map<String, List<PageProfileRow>> clusterByPageRoutes(List<PageProfileRow> profiles) {
        Map<String, List<PageProfileRow>> clustered = new LinkedHashMap<>();
        String dominantShellPrefix = dominantShellPrefix(profiles);
        for (PageProfileRow profile : profiles) {
            String prefix = extractRoutePrefix(profile.routePath, dominantShellPrefix);
            clustered.computeIfAbsent(prefix, k -> new ArrayList<>()).add(profile);
        }
        return clustered;
    }

    /**
     * 从路由路径提取前缀作为聚类键。
     * 例如：/crm/customer/list → "crm"
     *       /api/ops/device/detail → "ops"
     *       / → "root"
     */
    private String extractRoutePrefix(String routePath) {
        return extractRoutePrefix(routePath, null);
    }

    private String extractRoutePrefix(String routePath, String dominantShellPrefix) {
        if (routePath == null || routePath.isBlank()) return "root";
        String[] parts = routePath.split("/");
        for (String part : parts) {
            String normalized = part == null ? "" : part.trim().toLowerCase();
            if (!normalized.isBlank() && !isTechnicalRouteSegment(normalized, dominantShellPrefix)) {
                return normalized;
            }
        }
        return "root";
    }

    private String dominantShellPrefix(List<PageProfileRow> profiles) {
        if (profiles == null || profiles.size() < 3) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (PageProfileRow profile : profiles) {
            List<String> segments = routeSegments(profile.routePath);
            if (segments.size() < 2) {
                continue;
            }
            String first = segments.get(0);
            if (isCommonTechnicalSegment(first)) {
                continue;
            }
            counts.put(first, counts.getOrDefault(first, 0) + 1);
            total++;
        }
        if (total == 0) return null;
        int threshold = Math.max(3, (int) Math.ceil(total * 0.70));
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private List<String> routeSegments(String routePath) {
        if (routePath == null || routePath.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : routePath.split("/")) {
            String normalized = part == null ? "" : part.trim().toLowerCase();
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private boolean isTechnicalRouteSegment(String segment, String dominantShellPrefix) {
        if (segment == null || segment.isBlank()) return true;
        if (segment.equals(dominantShellPrefix)) return true;
        if (isCommonTechnicalSegment(segment)) return true;
        return segment.matches("v\\d+") || segment.matches("\\d+");
    }

    private boolean isCommonTechnicalSegment(String segment) {
        return Set.of("api", "web", "app", "admin", "console", "pages", "page", "system", "manage", "management")
                .contains(segment);
    }

    /**
     * 基于 TOM 名称和页面路由的综合聚类。
     * 返回 domain → (packName, tomNames, pageRoutes) 的映射。
     */
    Map<String, List<String>> buildClusterHints(Long projectId) {
        Map<String, List<TomRow>> tomsByDomain = loadActiveTomsByDomain(projectId);
        List<PageProfileRow> profiles = loadActivePageProfiles(projectId);
        Map<String, List<PageProfileRow>> pagesByRoute = clusterByPageRoutes(profiles);

        // 加载 TOM 关系图用于智能聚类
        Map<Long, List<Long>> tomRelations = loadTomRelationGraph(projectId);

        Map<String, List<String>> hints = new LinkedHashMap<>();

        // 构建反向查找：tomName → domain，避免 O(n²) 嵌套扫描
        Map<String, String> tomNameToDomain = new HashMap<>();
        Map<Long, String> tomIdToName = new HashMap<>();

        // 从 TOM domain 聚类
        for (Map.Entry<String, List<TomRow>> entry : tomsByDomain.entrySet()) {
            String domain = entry.getKey();
            List<String> tomNames = entry.getValue().stream().map(TomRow::name).toList();
            hints.computeIfAbsent(domain, k -> new ArrayList<>()).addAll(tomNames);
            for (TomRow tom : entry.getValue()) {
                tomNameToDomain.put(tom.name, domain);
                tomIdToName.put(tom.id, tom.name);
            }
        }

        // 从 TOM 关系图聚类：有关系的 TOM 合并到同一个 domain
        Map<Long, String> tomIdToDomain = new HashMap<>();
        for (Map.Entry<String, List<TomRow>> entry : tomsByDomain.entrySet()) {
            for (TomRow tom : entry.getValue()) {
                tomIdToDomain.put(tom.id, entry.getKey());
            }
        }
        // 使用 union-find 合并有关联的 TOM 的 domain
        Map<Long, Long> parent = new HashMap<>();
        for (Long id : tomIdToDomain.keySet()) parent.put(id, id);
        for (Map.Entry<Long, List<Long>> rel : tomRelations.entrySet()) {
            Long from = find(parent, rel.getKey());
            for (Long to : rel.getValue()) {
                Long toRoot = find(parent, to);
                if (!from.equals(toRoot)) {
                    parent.put(toRoot, from);
                }
            }
        }
        // 合并同一连通分量的 TOM 到同一个 domain
        Map<Long, List<String>> componentTomNames = new HashMap<>();
        for (Map.Entry<Long, String> entry : tomIdToDomain.entrySet()) {
            Long root = find(parent, entry.getKey());
            String name = tomIdToName.get(entry.getKey());
            if (name != null && !name.isEmpty()) {
                componentTomNames.computeIfAbsent(root, k -> new ArrayList<>()).add(name);
            }
        }
        // 将合并后的 domain 找到最大的 domain 作为主域名，其他合并进来
        for (Map.Entry<Long, List<String>> entry : componentTomNames.entrySet()) {
            if (entry.getValue().size() > 1) {
                // 找到这个 component 中所有 tom 的 domain
                Map<String, Integer> domainCount = new HashMap<>();
                for (String name : entry.getValue()) {
                    String domain = tomNameToDomain.get(name);
                    if (domain != null) {
                        domainCount.merge(domain, 1, Integer::sum);
                    }
                }
                String mainDomain = domainCount.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("通用");
                // 确保主 domain 在 hints 中
                hints.computeIfAbsent(mainDomain, k -> new ArrayList<>());
                // 将 component 中所有 tom 名称添加到主 domain
                Set<String> mainDomainSet = new HashSet<>(hints.get(mainDomain));
                for (String name : entry.getValue()) {
                    if (!mainDomainSet.contains(name)) {
                        hints.get(mainDomain).add(name);
                    }
                }
            }
        }

        // 从页面路由聚类，合并到已有 domain 或创建新 domain
        for (Map.Entry<String, List<PageProfileRow>> entry : pagesByRoute.entrySet()) {
            String routePrefix = entry.getKey();
            List<String> pageLabels = entry.getValue().stream().map(PageProfileRow::pageLabel).toList();

            // 尝试匹配已有 domain
            boolean matched = false;
            for (String domain : hints.keySet()) {
                if (routePrefix.contains(domain) || domain.contains(routePrefix)) {
                    hints.get(domain).addAll(pageLabels);
                    matched = true;
                    break;
                }
            }
            if (!matched && !routePrefix.equals("root")) {
                hints.computeIfAbsent(routePrefix, k -> new ArrayList<>()).addAll(pageLabels);
            }
        }

        return hints;
    }

    private Long find(Map<Long, Long> parent, Long id) {
        if (!parent.containsKey(id)) return id;
        Long p = parent.get(id);
        if (p.equals(id)) return id;
        Long root = find(parent, p);
        parent.put(id, root);
        return root;
    }

    private Map<Long, List<Long>> loadTomRelationGraph(Long projectId) {
        var rows = jdbc.sql("""
                SELECT from_model_id, to_model_id
                FROM test_object_model_relation
                WHERE project_id = ? AND status IN ('CONFIRMED', 'ACTIVE')
                """).param(projectId)
                .query((rs, rowNum) -> new long[]{rs.getLong("from_model_id"), rs.getLong("to_model_id")})
                .list();
        Map<Long, List<Long>> graph = new HashMap<>();
        for (long[] row : rows) {
            graph.computeIfAbsent(row[0], k -> new ArrayList<>()).add(row[1]);
            graph.computeIfAbsent(row[1], k -> new ArrayList<>()).add(row[0]);
        }
        return graph;
    }

    // =====================================================================
    // Mapper
    // =====================================================================

    private BusinessPackRecord mapPack(ResultSet rs, int rowNum) throws SQLException {
        return new BusinessPackRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("pack_name"),
                rs.getString("pack_type"),
                rs.getString("business_domain"),
                rs.getInt("version"),
                rs.getString("status"),
                rs.getString("description"),
                rs.getString("source_types"),
                rs.getInt("item_count"),
                rs.getObject("confidence_avg") == null ? null : rs.getBigDecimal("confidence_avg"),
                toLocalDateTime(rs, "built_at"),
                toLocalDateTime(rs, "activated_at"),
                rs.getLong("created_by"),
                toLocalDateTime(rs, "created_at"),
                toLocalDateTime(rs, "updated_at")
        );
    }

    private BusinessPackItemRecord mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new BusinessPackItemRecord(
                rs.getLong("id"),
                rs.getLong("pack_id"),
                rs.getLong("project_id"),
                rs.getString("item_type"),
                rs.getString("item_key"),
                rs.getString("item_value"),
                rs.getBigDecimal("confidence"),
                rs.getString("source_type"),
                rs.getObject("source_ref_id") == null ? null : rs.getLong("source_ref_id"),
                rs.getString("source_ref_json"),
                rs.getString("status"),
                toLocalDateTime(rs, "created_at"),
                toLocalDateTime(rs, "updated_at")
        );
    }

    private TomRow mapTom(ResultSet rs, int rowNum) throws SQLException {
        return new TomRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("business_domain"),
                rs.getBigDecimal("confidence")
        );
    }

    private PageProfileRow mapPageProfile(ResultSet rs, int rowNum) throws SQLException {
        return new PageProfileRow(
                rs.getLong("id"),
                rs.getString("page_label"),
                rs.getString("route_path"),
                rs.getString("field_labels_json"),
                rs.getString("action_labels_json")
        );
    }

    private PatternRow mapPattern(ResultSet rs, int rowNum) throws SQLException {
        return new PatternRow(
                rs.getLong("id"),
                rs.getString("operation_type"),
                rs.getString("source_text"),
                rs.getString("confirmed_step_text")
        );
    }

    private SummaryRow mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new SummaryRow(
                rs.getLong("id"),
                rs.getString("overview"),
                rs.getString("business_summary")
        );
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    // =====================================================================
    // Records
    // =====================================================================

    public record BusinessPackRecord(
            Long id, Long projectId, String packName, String packType,
            String businessDomain, int version, String status, String description,
            String sourceTypes, int itemCount, BigDecimal confidenceAvg,
            LocalDateTime builtAt, LocalDateTime activatedAt,
            Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    public record BusinessPackItemRecord(
            Long id, Long packId, Long projectId, String itemType,
            String itemKey, String itemValue, BigDecimal confidence,
            String sourceType, Long sourceRefId, String sourceRefJson,
            String status, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    public record SnapshotRecord(
            Long id, Long packId, Long projectId, int snapshotNo,
            String packName, String businessDomain, String status,
            int itemCount, BigDecimal confidenceAvg, String snapshotJson,
            String changeSummary, Long createdBy, LocalDateTime createdAt
    ) {}

    public record RelationRecord(
            Long id, Long projectId, Long sourcePackId, Long targetPackId,
            String relationType, BigDecimal confidence, String description,
            String sourceType, String status, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    private SnapshotRecord mapSnapshot(ResultSet rs, int rowNum) throws SQLException {
        return new SnapshotRecord(
                rs.getLong("id"),
                rs.getLong("pack_id"),
                rs.getLong("project_id"),
                rs.getInt("snapshot_no"),
                rs.getString("pack_name"),
                rs.getString("business_domain"),
                rs.getString("status"),
                rs.getInt("item_count"),
                rs.getObject("confidence_avg") == null ? null : rs.getBigDecimal("confidence_avg"),
                rs.getString("snapshot_json"),
                rs.getString("change_summary"),
                rs.getLong("created_by"),
                toLocalDateTime(rs, "created_at")
        );
    }

    private RelationRecord mapRelation(ResultSet rs, int rowNum) throws SQLException {
        return new RelationRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getLong("source_pack_id"),
                rs.getLong("target_pack_id"),
                rs.getString("relation_type"),
                rs.getBigDecimal("confidence"),
                rs.getString("description"),
                rs.getString("source_type"),
                rs.getString("status"),
                toLocalDateTime(rs, "created_at"),
                toLocalDateTime(rs, "updated_at")
        );
    }

    private record TomRow(Long id, String name, String description, String businessDomain, BigDecimal confidence) {}
    private record PageProfileRow(Long id, String pageLabel, String routePath, String fieldLabelsJson, String actionLabelsJson) {}
    private record PatternRow(Long id, String operationType, String sourceText, String confirmedStepText) {}
    private record SummaryRow(Long id, String overview, String businessSummary) {}

    // =====================================================================
    // 绑定记录
    // =====================================================================

    public record RuleBindingRecord(
            Long id, Long packId, Long projectId, String ruleType, String ruleRef,
            String ruleConfigJson, BigDecimal confidence, String status,
            LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    public record ScanBindingRecord(
            Long id, Long packId, Long projectId, Long scanProfileId,
            String routePath, String pageLabel, BigDecimal confidence, String status,
            LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    public record TomBindingRecord(
            Long id, Long packId, Long projectId, Long tomId,
            String tomName, String tomType, BigDecimal confidence, String status,
            LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    public record SemanticBindingRecord(
            Long id, Long packId, Long projectId, Long semanticPackId,
            String signalCategory, String signalTitle, BigDecimal confidence, String status,
            LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    public record ConsumptionLogRecord(
            Long id, Long packId, Long projectId, String consumerType,
            String consumerRef, int signalCount, LocalDateTime consumedAt
    ) {}

    private RuleBindingRecord mapRuleBinding(ResultSet rs, int rowNum) throws SQLException {
        return new RuleBindingRecord(
                rs.getLong("id"), rs.getLong("pack_id"), rs.getLong("project_id"),
                rs.getString("rule_type"), rs.getString("rule_ref"),
                rs.getString("rule_config_json"), rs.getBigDecimal("confidence"),
                rs.getString("status"), toLocalDateTime(rs, "created_at"), toLocalDateTime(rs, "updated_at")
        );
    }

    private ScanBindingRecord mapScanBinding(ResultSet rs, int rowNum) throws SQLException {
        return new ScanBindingRecord(
                rs.getLong("id"), rs.getLong("pack_id"), rs.getLong("project_id"),
                rs.getLong("scan_profile_id"), rs.getString("route_path"),
                rs.getString("page_label"), rs.getBigDecimal("confidence"),
                rs.getString("status"), toLocalDateTime(rs, "created_at"), toLocalDateTime(rs, "updated_at")
        );
    }

    private TomBindingRecord mapTomBinding(ResultSet rs, int rowNum) throws SQLException {
        return new TomBindingRecord(
                rs.getLong("id"), rs.getLong("pack_id"), rs.getLong("project_id"),
                rs.getLong("tom_id"), rs.getString("tom_name"),
                rs.getString("tom_type"), rs.getBigDecimal("confidence"),
                rs.getString("status"), toLocalDateTime(rs, "created_at"), toLocalDateTime(rs, "updated_at")
        );
    }

    private SemanticBindingRecord mapSemanticBinding(ResultSet rs, int rowNum) throws SQLException {
        return new SemanticBindingRecord(
                rs.getLong("id"), rs.getLong("pack_id"), rs.getLong("project_id"),
                rs.getLong("semantic_pack_id"), rs.getString("signal_category"),
                rs.getString("signal_title"), rs.getBigDecimal("confidence"),
                rs.getString("status"), toLocalDateTime(rs, "created_at"), toLocalDateTime(rs, "updated_at")
        );
    }

    private ConsumptionLogRecord mapConsumptionLog(ResultSet rs, int rowNum) throws SQLException {
        return new ConsumptionLogRecord(
                rs.getLong("id"), rs.getLong("pack_id"), rs.getLong("project_id"),
                rs.getString("consumer_type"), rs.getString("consumer_ref"),
                rs.getInt("signal_count"), toLocalDateTime(rs, "consumed_at")
        );
    }

    private RefreshDiagnosticRecord mapRefreshDiagnostic(ResultSet rs, int rowNum) throws SQLException {
        return new RefreshDiagnosticRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("status"),
                rs.getString("error_message"),
                rs.getInt("tom_count"),
                rs.getInt("page_profile_count"),
                rs.getInt("pattern_count"),
                rs.getInt("summary_count"),
                rs.getInt("generated_pack_count"),
                rs.getInt("inferred_relation_count"),
                toLocalDateTime(rs, "started_at"),
                toLocalDateTime(rs, "finished_at"),
                toLocalDateTime(rs, "created_at")
        );
    }

    public record RefreshDiagnosticRecord(
            Long id, Long projectId, String status, String errorMessage,
            int tomCount, int pageProfileCount, int patternCount, int summaryCount,
            int generatedPackCount, int inferredRelationCount,
            LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime createdAt
    ) {}
}
