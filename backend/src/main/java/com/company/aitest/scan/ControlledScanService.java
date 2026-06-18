package com.company.aitest.scan;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.llm.gateway.LlmGateway;
import com.company.aitest.llm.gateway.LlmInvocationRequest;
import com.company.aitest.llm.gateway.LlmInvocationResponse;
import com.company.aitest.llm.gateway.LlmInvocationStatus;
import com.company.aitest.llm.gateway.LlmStage;
import com.company.aitest.semantic.ProjectSemanticContextService;
import com.company.aitest.skill.SkillExecutionLogService;
import com.company.aitest.skill.SkillStage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlledScanService {
    private static final String BUILTIN_SCAN_SKILL_CODE = "BUILTIN_PROJECT_PAGE_SCAN_SKILL";
    private static final String URL_SCAN_SOURCE_KEY = "URL_IMPORT";
    private static final String URL_SCAN_SOURCE_LABEL = "页面链接扫描";
    private static final String BUILTIN_SCAN_SYSTEM_PROMPT = """
            你是智能测试平台中的受控扫描分析 Skill。
            你的职责是把项目级页面扫描结果整理成结构化页面画像摘要，用于后续轨迹清洗、测试步骤归纳和功能测试用例生成。
            要求：
            1. 严格基于扫描结果，不要虚构不存在的页面和字段。
            2. 重点提炼：模块、页面、面包屑、表单字段、常见动作、列表操作。
            3. 输出中文结构化文本，适合直接放入系统后台作为项目页面画像摘要。
            4. 不输出代码，不输出 markdown 表格。
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final LlmGateway llmGateway;
    private final SkillExecutionLogService skillExecutionLogService;
    private final ProjectSemanticContextService semanticContextService;
    private final List<BuiltinScanSourceProvider> builtinScanSourceProviders;
    private final ScanSourceConfigService scanSourceConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ControlledScanService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                                 LlmGateway llmGateway, SkillExecutionLogService skillExecutionLogService,
                                 ProjectSemanticContextService semanticContextService,
                                 List<BuiltinScanSourceProvider> builtinScanSourceProviders,
                                 ScanSourceConfigService scanSourceConfigService) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.llmGateway = llmGateway;
        this.skillExecutionLogService = skillExecutionLogService;
        this.semanticContextService = semanticContextService;
        this.builtinScanSourceProviders = builtinScanSourceProviders;
        this.scanSourceConfigService = scanSourceConfigService;
    }

    public List<ControlledSkillDefinitionView> listBuiltinSkills() {
        String supportedSources = listBuiltinSources().stream()
                .map(BuiltinScanSourceDefinition::sourceKey)
                .collect(Collectors.joining(","));
        return List.of(new ControlledSkillDefinitionView(
                BUILTIN_SCAN_SKILL_CODE,
                "项目页面画像扫描 Skill",
                SkillStage.KNOWLEDGE_RETRIEVAL.name(),
                "导入项目级可达页面扫描结果，生成页面画像摘要，并供轨迹清洗与功能测试用例生成复用。",
                supportedSources
        ));
    }

    public List<BuiltinScanSourceOptionView> listBuiltinSourceOptions() {
        return listBuiltinSources().stream()
                .map(source -> new BuiltinScanSourceOptionView(source.sourceKey(), source.sourceLabel(), source.defaultSelected()))
                .toList();
    }

    public List<ControlledScanJobRecord> listJobs(Long projectId) {
        return jdbc.sql("""
                select * from controlled_scan_job
                where project_id = :projectId
                order by id desc
                """).param("projectId", projectId).query(this::mapJob).list();
    }

    public List<PageScanProfileRecord> listProfiles(Long projectId) {
        return jdbc.sql("""
                select * from page_scan_profile
                where project_id = :projectId
                order by source_key asc, page_label asc, id asc
                """).param("projectId", projectId).query(this::mapProfile).list();
    }

    @Transactional
    public int upsertProfiles(Long projectId, List<PageProfileDraft> drafts, CurrentUser user) {
        if (drafts == null || drafts.isEmpty()) {
            return 0;
        }
        LocalDateTime now = timeProvider.now();
        int count = 0;
        for (PageProfileDraft draft : drafts) {
            if (draft == null || draft.routePath() == null || draft.routePath().isBlank()) {
                continue;
            }
            jdbcTemplate.update("""
                    insert into page_scan_profile(project_id, source_key, source_label, page_label, page_url, route_path,
                      page_title, breadcrumb_path, headings_json, field_labels_json, action_labels_json,
                      dialog_titles_json, body_preview, status, created_by, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                    on duplicate key update
                      source_label = values(source_label),
                      page_label = values(page_label),
                      page_url = values(page_url),
                      page_title = values(page_title),
                      breadcrumb_path = values(breadcrumb_path),
                      headings_json = values(headings_json),
                      field_labels_json = values(field_labels_json),
                      action_labels_json = values(action_labels_json),
                      dialog_titles_json = values(dialog_titles_json),
                      body_preview = values(body_preview),
                      status = 'ACTIVE',
                      updated_at = values(updated_at)
                    """, projectId, draft.sourceKey(), draft.sourceLabel(), draft.pageLabel(), draft.pageUrl(),
                    draft.routePath(), draft.pageTitle(), draft.breadcrumbPath(), writeJson(draft.headings()),
                    writeJson(draft.fieldLabels()), writeJson(draft.actionLabels()), writeJson(draft.dialogTitles()),
                    draft.bodyPreview(), user == null ? null : user.id(), now, now);
            count++;
        }
        return count;
    }

    public Map<String, PageScanHint> buildHintIndex(Long projectId, List<String> pageUrls) {
        List<PageScanProfileRecord> profiles = findRelevantProfiles(projectId, pageUrls);
        Map<String, PageScanHint> result = new LinkedHashMap<>();
        for (PageScanProfileRecord profile : profiles) {
            result.put(profile.routePath(), new PageScanHint(
                    profile.sourceKey(),
                    profile.sourceLabel(),
                    profile.pageLabel(),
                    profile.routePath(),
                    profile.breadcrumbPath(),
                    parseJsonList(profile.headingsJson()),
                    parseJsonList(profile.fieldLabelsJson()),
                    parseJsonList(profile.actionLabelsJson()),
                    parseJsonList(profile.dialogTitlesJson())
            ));
        }
        return result;
    }

    public String buildPromptContext(Long projectId, List<String> pageUrls) {
        List<PageScanProfileRecord> profiles = findRelevantProfiles(projectId, pageUrls);
        if (profiles.isEmpty()) {
            return "暂无项目级页面画像参考。";
        }
        return profiles.stream()
                .limit(10)
                .map(profile -> """
                        - 页面：%s
                          面包屑：%s
                          路径：%s
                          关键字段：%s
                          常见动作：%s
                          弹窗标题：%s
                        """.formatted(
                        safe(profile.pageLabel()),
                        safe(profile.breadcrumbPath()),
                        safe(profile.routePath()),
                        joinOrDefault(parseJsonList(profile.fieldLabelsJson()), "无"),
                        joinOrDefault(parseJsonList(profile.actionLabelsJson()), "无"),
                        joinOrDefault(parseJsonList(profile.dialogTitlesJson()), "无")
                ))
                .collect(Collectors.joining("\n"));
    }

    @Transactional
    public ControlledScanJobRecord runScan(Long projectId, RunControlledScanCommand command, CurrentUser user) {
        String scanMode = normalizeScanMode(command.scanMode());
        List<String> sourceDescriptors;
        List<PageProfileDraft> drafts;
        Set<String> touchedSources = new LinkedHashSet<>();
        if ("URL_LIST".equals(scanMode)) {
            List<String> urls = normalizeUrlList(command.urls());
            if (urls.isEmpty()) {
                throw new BusinessException("至少输入一个可扫描的页面链接");
            }
            drafts = loadUrlDrafts(urls);
            if (drafts.isEmpty()) {
                throw new BusinessException("页面链接扫描未提取到任何可用页面画像");
            }
            sourceDescriptors = urls;
            touchedSources.add(URL_SCAN_SOURCE_KEY);
        } else {
            List<String> sourceKeys = normalizeSourceKeys(projectId, command.sourceKeys());
            if (sourceKeys.isEmpty()) {
                throw new BusinessException("至少选择一个内置扫描源");
            }
            drafts = new ArrayList<>();
            Map<String, BuiltinScanSourceDefinition> projectSourceMap = builtinSourceMap(projectId);
            for (String sourceKey : sourceKeys) {
                BuiltinScanSourceDefinition source = projectSourceMap.get(sourceKey);
                if (source == null) {
                    throw new BusinessException("不支持的扫描源: " + sourceKey);
                }
                try {
                    drafts.addAll(loadSourceDrafts(source));
                } catch (IOException e) {
                    throw new BusinessException("读取扫描源失败: " + source.sourceKey());
                }
            }
            sourceDescriptors = sourceKeys;
            touchedSources.addAll(sourceKeys);
        }
        LocalDateTime now = timeProvider.now();
        String sourceKeysJson = writeJson(sourceDescriptors);
        String jobName = "URL_LIST".equals(scanMode) ? "页面链接扫描-" + projectId : "项目页面画像扫描-" + projectId;
        jdbcTemplate.update("""
                insert into controlled_scan_job(project_id, job_name, source_keys_json, model_config_id, status,
                  created_by, started_at, created_at, updated_at)
                values (?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?)
                """, projectId, jobName, sourceKeysJson, command.modelConfigId(), user.id(), now, now, now);
        Long jobId = jdbc.sql("select last_insert_id()").query(Long.class).single();

        LocalDateTime startedAt = now;
        long begin = System.currentTimeMillis();
        String promptSnapshot = null;
        String outputSummary = null;
        int profileCount = 0;
        try {
            for (String sourceKey : touchedSources) {
                jdbcTemplate.update("delete from page_scan_profile where project_id = ? and source_key = ?", projectId, sourceKey);
            }

            profileCount = upsertProfiles(projectId, drafts, user);

            String userPrompt = buildScanSummaryPrompt(projectId, scanMode, sourceDescriptors, drafts);
            promptSnapshot = buildPromptSnapshot(BUILTIN_SCAN_SYSTEM_PROMPT, userPrompt);
            outputSummary = command.modelConfigId() == null
                    ? buildFallbackSummary(projectId, drafts)
                    : invokeScanLlm(command.modelConfigId(), projectId, jobId,
                            BUILTIN_SCAN_SYSTEM_PROMPT, userPrompt, user);
            if (outputSummary == null || outputSummary.isBlank()) {
                outputSummary = buildFallbackSummary(projectId, drafts);
            }

            jdbcTemplate.update("""
                    update controlled_scan_job
                    set status = 'SUCCESS', prompt_snapshot = ?, output_summary = ?, profile_count = ?,
                        finished_at = ?, updated_at = ?
                    where id = ?
                    """, promptSnapshot, outputSummary, profileCount, now, now, jobId);

            jdbcTemplate.update("""
                    insert into knowledge_asset(project_id, asset_type, asset_ref_type, asset_ref_id, title, content,
                      status, visibility, created_by, created_at, updated_at)
                    values (?, 'PAGE_SCAN_SUMMARY', 'CONTROLLED_SCAN_JOB', ?, ?, ?, 'ACTIVE', 'PRIVATE', ?, ?, ?)
                    """, projectId, jobId,
                    ("URL_LIST".equals(scanMode) ? "页面链接扫描摘要 #" : "项目页面画像摘要 #") + jobId,
                    outputSummary, user.id(), now, now);

            refreshSemanticSnapshotQuietly(projectId);

            skillExecutionLogService.record(jobId, projectId, BUILTIN_SCAN_SKILL_CODE, SkillStage.KNOWLEDGE_RETRIEVAL,
                    userPrompt, outputSummary, command.modelConfigId(), promptSnapshot, sourceKeysJson,
                    "SUCCESS", null, null, startedAt, now, System.currentTimeMillis() - begin);

            return getJob(jobId);
        } catch (Exception ex) {
            jdbcTemplate.update("""
                    update controlled_scan_job
                    set status = 'FAILED', prompt_snapshot = ?, output_summary = ?, profile_count = ?,
                        error_message = ?, finished_at = ?, updated_at = ?
                    where id = ?
                    """, promptSnapshot, outputSummary, profileCount, ex.getMessage(), now, now, jobId);
            skillExecutionLogService.record(jobId, projectId, BUILTIN_SCAN_SKILL_CODE, SkillStage.KNOWLEDGE_RETRIEVAL,
                    promptSnapshot == null ? null : extractUserPrompt(promptSnapshot), outputSummary, command.modelConfigId(),
                    promptSnapshot, sourceKeysJson, "FAILED", "SCAN_FAILED", ex.getMessage(), startedAt, now,
                    System.currentTimeMillis() - begin);
            throw ex instanceof BusinessException ? (BusinessException) ex : new BusinessException("受控扫描执行失败: " + ex.getMessage());
        }
    }

    private List<PageScanProfileRecord> findRelevantProfiles(Long projectId, List<String> pageUrls) {
        List<String> routePaths = pageUrls == null ? List.of() : pageUrls.stream()
                .map(this::normalizeRoutePath)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<PageScanProfileRecord> all = listProfiles(projectId).stream()
                .sorted(Comparator
                        .comparingInt((PageScanProfileRecord item) -> profilePriority(item.sourceKey()))
                        .thenComparing(PageScanProfileRecord::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PageScanProfileRecord::id, Comparator.reverseOrder()))
                .toList();
        if (routePaths.isEmpty()) {
            return all.stream().limit(12).toList();
        }
        Map<String, PageScanProfileRecord> matched = new LinkedHashMap<>();
        for (String routePath : routePaths) {
            for (PageScanProfileRecord profile : all) {
                if (Objects.equals(profile.routePath(), routePath)) {
                    matched.put(routePath, profile);
                    break;
                }
            }
        }
        return new ArrayList<>(matched.values());
    }

    private void refreshSemanticSnapshotQuietly(Long projectId) {
        try {
            semanticContextService.refreshSnapshot(projectId);
        } catch (Exception ignored) {
            // 语义快照是附加缓存，不阻塞主链路
        }
    }

    private int profilePriority(String sourceKey) {
        if ("TRACE_AUTO_SCAN".equals(sourceKey)) {
            return 0;
        }
        if (URL_SCAN_SOURCE_KEY.equals(sourceKey)) {
            return 1;
        }
        return 2;
    }

    private List<String> normalizeSourceKeys(Long projectId, List<String> sourceKeys) {
        Map<String, BuiltinScanSourceDefinition> sourceMap = builtinSourceMap(projectId);
        if (sourceKeys == null || sourceKeys.isEmpty()) {
            List<String> defaults = listBuiltinSourcesForProject(projectId).stream()
                    .filter(BuiltinScanSourceDefinition::defaultSelected)
                    .map(BuiltinScanSourceDefinition::sourceKey)
                    .toList();
            return defaults.isEmpty() ? new ArrayList<>(sourceMap.keySet()) : defaults;
        }
        List<String> normalized = new ArrayList<>();
        for (String sourceKey : sourceKeys) {
            String key = sourceKey == null ? null : sourceKey.trim().toUpperCase(Locale.ROOT);
            if (key == null || key.isBlank()) {
                continue;
            }
            if (!sourceMap.containsKey(key)) {
                throw new BusinessException("不支持的扫描源: " + sourceKey);
            }
            if (!normalized.contains(key)) {
                normalized.add(key);
            }
        }
        return normalized;
    }

    private String normalizeScanMode(String scanMode) {
        String mode = scanMode == null ? "BUILTIN_JSON" : scanMode.trim().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "BUILTIN_JSON", "URL_LIST" -> mode;
            default -> throw new BusinessException("不支持的扫描模式: " + scanMode);
        };
    }

    private List<String> normalizeUrlList(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String raw : urls) {
            String url = raw == null ? null : raw.trim();
            if (url == null || url.isBlank()) {
                continue;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            try {
                URI uri = new URI(url);
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new BusinessException("无效页面链接: " + raw);
                }
                String normalizedUrl = uri.toString();
                if (!normalized.contains(normalizedUrl)) {
                    normalized.add(normalizedUrl);
                }
            } catch (URISyntaxException e) {
                throw new BusinessException("无效页面链接: " + raw);
            }
        }
        return normalized;
    }

    private List<PageProfileDraft> loadSourceDrafts(BuiltinScanSourceDefinition source) throws IOException {
        if (source == null) {
            throw new BusinessException("扫描源不存在");
        }
        if (source.sourceUrl() != null && !source.sourceUrl().isBlank()) {
            try {
                String html = fetchPageHtml(source.sourceUrl());
                PageProfileDraft draft = extractDraftFromHtml(source.sourceKey(), source.sourceLabel(), source.sourceUrl(), html);
                return draft == null ? List.of() : List.of(draft);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("扫描源页面读取被中断: " + source.sourceKey());
            }
        }
        if (source.path() == null) {
            throw new BusinessException("扫描源缺少文件路径或页面链接: " + source.sourceKey());
        }
        if (!Files.exists(source.path())) {
            throw new BusinessException("扫描源文件不存在: " + source.path());
        }
        Map<String, Object> root = objectMapper.readValue(Files.readString(source.path()), new TypeReference<>() {
        });
        List<Map<String, Object>> pages = castMapList(root.get("pages"));
        List<PageProfileDraft> drafts = new ArrayList<>();
        for (Map<String, Object> page : pages) {
            String pageUrl = toText(page.get("url"));
            String routePath = normalizeRoutePath(pageUrl);
            if (routePath == null || routePath.isBlank()) {
                continue;
            }
            List<String> breadcrumbs = normalizeTextList(page.get("breadcrumbs"));
            String breadcrumbPath = breadcrumbs.isEmpty() ? null : breadcrumbs.stream()
                    .map(this::normalizeBreadcrumbPart)
                    .filter(Objects::nonNull)
                    .filter(part -> !part.isBlank())
                    .distinct()
                    .collect(Collectors.joining(" / "));
            List<String> headings = normalizeTextList(page.get("headings"));
            List<String> fields = normalizeFieldLabels(page.get("formFields"));
            List<String> actions = normalizeActionLabels(page.get("buttons"));
            List<String> dialogTitles = normalizeTextList(page.get("dialogs"));
            drafts.add(new PageProfileDraft(
                    source.sourceKey(),
                    source.sourceLabel(),
                    firstNonBlank(toText(page.get("label")), inferLabelFromBreadcrumbStatic(breadcrumbPath), inferLabelFromRouteStatic(routePath)),
                    pageUrl,
                    routePath,
                    toText(page.get("title")),
                    breadcrumbPath,
                    headings,
                    fields,
                    actions,
                    dialogTitles,
                    toText(page.get("bodyPreview"))
            ));
        }
        return drafts;
    }

    private List<PageProfileDraft> loadUrlDrafts(List<String> urls) {
        List<PageProfileDraft> drafts = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String url : urls) {
            try {
                String html = fetchPageHtml(url);
                PageProfileDraft draft = extractDraftFromHtml(URL_SCAN_SOURCE_KEY, URL_SCAN_SOURCE_LABEL, url, html);
                if (draft != null) {
                    drafts.add(draft);
                }
            } catch (Exception ex) {
                failures.add(url + " -> " + ex.getMessage());
            }
        }
        if (drafts.isEmpty() && !failures.isEmpty()) {
            throw new BusinessException("页面链接扫描失败：" + String.join("；", failures));
        }
        return drafts;
    }

    private String fetchPageHtml(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "AITest-ControlledScan/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusinessException("页面返回 HTTP " + response.statusCode());
        }
        return response.body();
    }

    static PageProfileDraft extractDraftFromHtml(String sourceKey, String sourceLabel, String pageUrl, String html) {
        Document doc = Jsoup.parse(html == null ? "" : html, pageUrl);
        String routePath = normalizeRoutePathStatic(pageUrl);
        if (routePath == null || routePath.isBlank()) {
            return null;
        }
        String pageTitle = cleanTextStatic(doc.title());
        String breadcrumbPath = extractBreadcrumbPath(doc);
        List<String> headings = extractDistinctTexts(doc, "h1, h2, h3", 12);
        List<String> fields = extractFieldLabels(doc);
        List<String> actions = extractActionLabels(doc);
        List<String> dialogTitles = extractDistinctTexts(doc,
                "[role=dialog] [aria-label], .modal-title, .dialog-title, .ant-modal-title", 8);
        String bodyPreview = extractBodyPreview(doc);
        String pageLabel = firstNonBlankStatic(
                headings.isEmpty() ? null : headings.get(0),
                pageTitle,
                inferLabelFromBreadcrumbStatic(breadcrumbPath),
                inferLabelFromRouteStatic(routePath));
        return new PageProfileDraft(
                sourceKey,
                sourceLabel,
                pageLabel,
                pageUrl,
                routePath,
                pageTitle,
                breadcrumbPath,
                headings,
                fields,
                actions,
                dialogTitles,
                bodyPreview
        );
    }

    private String buildScanSummaryPrompt(Long projectId, String scanMode, List<String> sourceDescriptors, List<PageProfileDraft> drafts) {
        Map<String, List<PageProfileDraft>> grouped = drafts.stream()
                .collect(Collectors.groupingBy(PageProfileDraft::sourceKey, LinkedHashMap::new, Collectors.toList()));
        String semanticContext = buildSemanticContext(projectId, drafts);
        StringBuilder builder = new StringBuilder();
        builder.append("【项目】\n").append(projectId).append("\n\n");
        builder.append("【扫描模式】\n").append(scanMode).append("\n\n");
        builder.append("【扫描输入】\n").append(String.join("\n", sourceDescriptors)).append("\n\n");
        if (!semanticContext.isBlank()) {
            builder.append(semanticContext).append("\n");
        }
        builder.append("【页面画像原始摘要】\n");
        for (Map.Entry<String, List<PageProfileDraft>> entry : grouped.entrySet()) {
            builder.append("来源：").append(entry.getKey()).append("\n");
            for (PageProfileDraft page : entry.getValue().stream().limit(20).toList()) {
                builder.append("- 页面：").append(page.pageLabel()).append("\n")
                        .append("  面包屑：").append(safe(page.breadcrumbPath())).append("\n")
                        .append("  路径：").append(page.routePath()).append("\n")
                        .append("  字段：").append(joinOrDefault(page.fieldLabels(), "无")).append("\n")
                        .append("  动作：").append(joinOrDefault(page.actionLabels(), "无")).append("\n")
                        .append("  弹窗：").append(joinOrDefault(page.dialogTitles(), "无")).append("\n");
            }
            builder.append("\n");
        }
        builder.append("【输出要求补充】\n")
                .append("1. 先总结页面模块和页面类型。\n")
                .append("2. 再总结关键表单字段和常见列表动作。\n")
                .append("3. 明确哪些画像更适合给轨迹清洗与功能测试用例生成使用。\n");
        return builder.toString();
    }

    private String buildFallbackSummary(Long projectId, List<PageProfileDraft> drafts) {
        Map<String, List<PageProfileDraft>> bySource = drafts.stream()
                .collect(Collectors.groupingBy(PageProfileDraft::sourceLabel, LinkedHashMap::new, Collectors.toList()));
        String semanticContext = buildSemanticContext(projectId, drafts);
        StringBuilder builder = new StringBuilder();
        builder.append("项目 ").append(projectId).append(" 已导入页面画像，共 ").append(drafts.size()).append(" 个页面。\n");
        if (!semanticContext.isBlank()) {
            builder.append(semanticContext).append("\n");
        }
        for (Map.Entry<String, List<PageProfileDraft>> entry : bySource.entrySet()) {
            builder.append("【").append(entry.getKey()).append("】\n");
            builder.append("页面：").append(entry.getValue().stream().map(PageProfileDraft::pageLabel).distinct().collect(Collectors.joining("、"))).append("\n");
            Set<String> fields = entry.getValue().stream().flatMap(item -> item.fieldLabels().stream()).collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> actions = entry.getValue().stream().flatMap(item -> item.actionLabels().stream()).collect(Collectors.toCollection(LinkedHashSet::new));
            builder.append("关键字段：").append(joinOrDefault(new ArrayList<>(fields), "无")).append("\n");
            builder.append("常见动作：").append(joinOrDefault(new ArrayList<>(actions), "无")).append("\n");
        }
        builder.append("这些页面画像可直接用于轨迹清洗时的页面名、字段名和动作名规范化，也可作为生成功能测试用例时的页面上下文参考。");
        return builder.toString();
    }

    private String buildSemanticContext(Long projectId, List<PageProfileDraft> drafts) {
        String focusText = drafts.stream()
                .limit(20)
                .map(draft -> String.join(" ",
                        safe(draft.pageLabel()),
                        joinOrDefault(draft.fieldLabels(), ""),
                        joinOrDefault(draft.actionLabels(), ""),
                        joinOrDefault(draft.dialogTitles(), "")))
                .collect(Collectors.joining("\n"));
        List<String> pageUrls = drafts.stream()
                .map(PageProfileDraft::pageUrl)
                .filter(Objects::nonNull)
                .toList();
        return semanticContextService.build(projectId, focusText, pageUrls, 8).promptSection();
    }

    private String buildPromptSnapshot(String systemPrompt, String userPrompt) {
        return systemPrompt + "\n\n---\n\n" + userPrompt;
    }

    private String extractUserPrompt(String promptSnapshot) {
        if (promptSnapshot == null) {
            return null;
        }
        int marker = promptSnapshot.indexOf("\n\n---\n\n");
        if (marker < 0) {
            return promptSnapshot;
        }
        return promptSnapshot.substring(marker + 7);
    }

    private ControlledScanJobRecord getJob(Long id) {
        return jdbc.sql("select * from controlled_scan_job where id = :id").param("id", id).query(this::mapJob).single();
    }

    private ControlledScanJobRecord mapJob(ResultSet rs, int rowNum) throws SQLException {
        return new ControlledScanJobRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("job_name"),
                rs.getString("source_keys_json"),
                rs.getObject("model_config_id") == null ? null : rs.getLong("model_config_id"),
                rs.getString("status"),
                rs.getString("prompt_snapshot"),
                rs.getString("output_summary"),
                rs.getInt("profile_count"),
                rs.getString("error_message"),
                rs.getObject("created_by") == null ? null : rs.getLong("created_by"),
                toLocalDateTime(rs, "started_at"),
                toLocalDateTime(rs, "finished_at"),
                toLocalDateTime(rs, "created_at"),
                toLocalDateTime(rs, "updated_at")
        );
    }

    private PageScanProfileRecord mapProfile(ResultSet rs, int rowNum) throws SQLException {
        return new PageScanProfileRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("source_key"),
                rs.getString("source_label"),
                rs.getString("page_label"),
                rs.getString("page_url"),
                rs.getString("route_path"),
                rs.getString("page_title"),
                rs.getString("breadcrumb_path"),
                rs.getString("headings_json"),
                rs.getString("field_labels_json"),
                rs.getString("action_labels_json"),
                rs.getString("dialog_titles_json"),
                rs.getString("body_preview"),
                rs.getString("status"),
                rs.getObject("created_by") == null ? null : rs.getLong("created_by"),
                toLocalDateTime(rs, "created_at"),
                toLocalDateTime(rs, "updated_at")
        );
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("序列化扫描数据失败");
        }
    }

    private List<Map<String, Object>> castMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    row.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                result.add(row);
            }
        }
        return result;
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            if (item instanceof Map<?, ?> raw) {
                String text = firstNonBlank(
                        cleanText(toText(raw.get("text"))),
                        cleanText(toText(raw.get("label"))),
                        cleanText(toText(raw.get("title"))),
                        cleanText(toText(raw.get("name"))),
                        cleanText(toText(raw.get("placeholder")))
                );
                if (text != null) {
                    result.add(text);
                }
                continue;
            }
            String text = cleanText(String.valueOf(item));
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    private List<String> normalizeTextList(Object value) {
        return toStringList(value).stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<String> normalizeFieldLabels(Object value) {
        List<String> result = new ArrayList<>();
        for (String raw : toStringList(value)) {
            String text = raw;
            if (text == null) {
                continue;
            }
            text = text.replace("请输入", "输入").replace("请选择", "选择").replace("请搜索", "搜索");
            if (!result.contains(text)) {
                result.add(text);
            }
        }
        return result;
    }

    private List<String> normalizeActionLabels(Object value) {
        List<String> result = new ArrayList<>();
        for (String raw : toStringList(value)) {
            String text = raw;
            if (text == null) {
                continue;
            }
            if (!result.contains(text)) {
                result.add(text);
            }
        }
        return result;
    }

    private static String inferLabelFromBreadcrumbStatic(String breadcrumbPath) {
        if (breadcrumbPath == null || breadcrumbPath.isBlank()) {
            return null;
        }
        String[] parts = breadcrumbPath.split("\\s*/\\s*");
        return parts.length == 0 ? breadcrumbPath : parts[parts.length - 1];
    }

    private static String inferLabelFromRouteStatic(String routePath) {
        if (routePath == null || routePath.isBlank()) {
            return "未命名页面";
        }
        String normalized = routePath;
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        int hashIndex = normalized.lastIndexOf('#');
        if (hashIndex >= 0 && hashIndex + 1 < normalized.length()) {
            normalized = normalized.substring(hashIndex + 1);
        }
        String[] parts = normalized.split("/");
        return parts.length == 0 ? routePath : parts[parts.length - 1];
    }

    private String normalizeRoutePath(String pageUrl) {
        return normalizeRoutePathStatic(pageUrl);
    }

    private static String normalizeRoutePathStatic(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(pageUrl);
            String path = uri.getPath();
            String query = uri.getQuery();
            String fragment = uri.getFragment();
            String normalizedPath = (path == null || path.isBlank()) ? "/" : path;
            StringBuilder route = new StringBuilder(normalizedPath);
            if (query != null && !query.isBlank()) {
                route.append('?').append(query);
            }
            if (fragment != null && !fragment.isBlank()) {
                route.append('#').append(fragment);
            }
            return route.toString();
        } catch (URISyntaxException e) {
            int queryIndex = pageUrl.indexOf('?');
            return queryIndex >= 0 ? pageUrl.substring(0, queryIndex) : pageUrl;
        }
    }

    private String normalizeBreadcrumbPart(String text) {
        String cleaned = cleanText(text);
        if (cleaned == null) {
            return null;
        }
        if (cleaned.equals("/") || cleaned.equals("首页")) {
            return null;
        }
        return cleaned;
    }

    private String cleanText(String text) {
        return cleanTextStatic(text);
    }

    private static String cleanTextStatic(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = text.trim().replaceAll("\\s+", " ");
        if (cleaned.startsWith("rc_select_")) {
            return null;
        }
        if (cleaned.equalsIgnoreCase("select all") || cleaned.equals("页码")) {
            return null;
        }
        return cleaned;
    }

    private String toText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String safe(String text) {
        return text == null || text.isBlank() ? "无" : text;
    }

    private String joinOrDefault(List<String> items, String fallback) {
        if (items == null || items.isEmpty()) {
            return fallback;
        }
        return items.stream().filter(Objects::nonNull).filter(item -> !item.isBlank()).distinct()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("、"));
    }

    private String firstNonBlank(String... values) {
        return firstNonBlankStatic(values);
    }

    private static String firstNonBlankStatic(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String extractBreadcrumbPath(Document doc) {
        List<String> breadcrumbParts = new ArrayList<>();
        for (Element element : doc.select("nav a, nav span, .breadcrumb a, .breadcrumb span, .breadcrumbs a, .breadcrumbs span, [aria-label] a, [aria-label] span")) {
            Element parent = element.parent();
            String ariaLabel = parent == null ? "" : parent.attr("aria-label");
            boolean likelyBreadcrumb = (ariaLabel != null && ariaLabel.toLowerCase(Locale.ROOT).contains("breadcrumb"))
                    || element.parents().stream().anyMatch(p -> {
                        String cls = p.className().toLowerCase(Locale.ROOT);
                        return cls.contains("breadcrumb");
                    });
            if (!likelyBreadcrumb) {
                continue;
            }
            String text = cleanTextStatic(element.text());
            if (text != null && !"/".equals(text) && !"首页".equals(text) && !breadcrumbParts.contains(text)) {
                breadcrumbParts.add(text);
            }
        }
        return breadcrumbParts.isEmpty() ? null : String.join(" / ", breadcrumbParts);
    }

    private static List<String> extractDistinctTexts(Document doc, String selector, int limit) {
        List<String> result = new ArrayList<>();
        for (Element element : doc.select(selector)) {
            String text = cleanTextStatic(element.text());
            if (text != null && !result.contains(text)) {
                result.add(text);
            }
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static List<String> extractFieldLabels(Document doc) {
        List<String> result = new ArrayList<>();
        for (Element label : doc.select("label")) {
            String text = cleanTextStatic(label.text());
            if (text != null && !result.contains(text)) {
                result.add(text.replace("请输入", "输入").replace("请选择", "选择").replace("请搜索", "搜索"));
            }
            if (result.size() >= 20) return result;
        }
        for (Element field : doc.select("input[placeholder], textarea[placeholder], select[aria-label], input[aria-label], textarea[aria-label], select")) {
            String text = firstNonBlankStatic(
                    cleanTextStatic(field.attr("placeholder")),
                    cleanTextStatic(field.attr("aria-label")),
                    cleanTextStatic(field.attr("name")));
            if (text != null && !result.contains(text)) {
                result.add(text.replace("请输入", "输入").replace("请选择", "选择").replace("请搜索", "搜索"));
            }
            if (result.size() >= 20) break;
        }
        return result;
    }

    private static List<String> extractActionLabels(Document doc) {
        List<String> result = new ArrayList<>();
        for (Element button : doc.select("button, [role=button], input[type=button], input[type=submit], a.button, .btn")) {
            String text = firstNonBlankStatic(
                    cleanTextStatic(button.text()),
                    cleanTextStatic(button.attr("value")),
                    cleanTextStatic(button.attr("aria-label")),
                    cleanTextStatic(button.attr("title")));
            if (text != null && !result.contains(text)) {
                result.add(text);
            }
            if (result.size() >= 20) break;
        }
        return result;
    }

    private static String extractBodyPreview(Document doc) {
        String text = cleanTextStatic(doc.body() == null ? null : doc.body().text());
        if (text == null) {
            return null;
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    public record RunControlledScanCommand(Long modelConfigId, String scanMode, List<String> sourceKeys, List<String> urls) {
    }

    public record PageScanHint(
            String sourceKey,
            String sourceLabel,
            String pageLabel,
            String routePath,
            String breadcrumbPath,
            List<String> headings,
            List<String> fieldLabels,
            List<String> actionLabels,
            List<String> dialogTitles) {
    }

    private List<BuiltinScanSourceDefinition> listBuiltinSources() {
        // 合并 Java 接口提供的扫描源 + 数据库配置的扫描源
        List<BuiltinScanSourceDefinition> javaSources = builtinScanSourceProviders.stream()
                .flatMap(provider -> provider.listSources().stream())
                .toList();
        List<BuiltinScanSourceDefinition> dbSources = scanSourceConfigService.toBuiltinDefinitions(null);

        // 合并，数据库配置优先（同 key 覆盖）
        Map<String, BuiltinScanSourceDefinition> merged = new LinkedHashMap<>();
        for (BuiltinScanSourceDefinition source : javaSources) {
            merged.put(source.sourceKey(), source);
        }
        for (BuiltinScanSourceDefinition source : dbSources) {
            merged.put(source.sourceKey(), source);
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 列出项目级扫描源（数据库配置 + 全局配置 + Java 接口）。
     */
    private List<BuiltinScanSourceDefinition> listBuiltinSourcesForProject(Long projectId) {
        // 数据库配置的项目级扫描源
        List<BuiltinScanSourceDefinition> projectSources = scanSourceConfigService.toBuiltinDefinitions(projectId);
        // 全局扫描源（Java 接口 + 数据库全局配置）
        List<BuiltinScanSourceDefinition> globalSources = listBuiltinSources();

        // 合并，项目级优先
        Map<String, BuiltinScanSourceDefinition> merged = new LinkedHashMap<>();
        for (BuiltinScanSourceDefinition source : globalSources) {
            merged.put(source.sourceKey(), source);
        }
        for (BuiltinScanSourceDefinition source : projectSources) {
            merged.put(source.sourceKey(), source);
        }
        return new ArrayList<>(merged.values());
    }

    private Map<String, BuiltinScanSourceDefinition> builtinSourceMap() {
        return listBuiltinSources().stream()
                .collect(Collectors.toMap(BuiltinScanSourceDefinition::sourceKey, source -> source, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, BuiltinScanSourceDefinition> builtinSourceMap(Long projectId) {
        return listBuiltinSourcesForProject(projectId).stream()
                .collect(Collectors.toMap(BuiltinScanSourceDefinition::sourceKey, source -> source, (left, right) -> left, LinkedHashMap::new));
    }

    public record BuiltinScanSourceOptionView(String key, String label, boolean defaultSelected) {
    }

    public record PageProfileDraft(
            String sourceKey,
            String sourceLabel,
            String pageLabel,
            String pageUrl,
            String routePath,
            String pageTitle,
            String breadcrumbPath,
            List<String> headings,
            List<String> fieldLabels,
            List<String> actionLabels,
            List<String> dialogTitles,
            String bodyPreview) {
    }

    private String invokeScanLlm(Long modelConfigId, Long projectId, Long jobId,
                                 String systemPrompt, String userPrompt, CurrentUser user) {
        if (user == null || user.id() == null) {
            throw new BusinessException("缺少调用用户上下文");
        }
        LlmInvocationResponse resp = llmGateway.invoke(new LlmInvocationRequest(
                null, user.id(), projectId, jobId,
                "CONTROLLED_SCAN", LlmStage.OTHER,
                modelConfigId, null, null, java.util.Map.of(),
                systemPrompt, userPrompt, null));
        if (resp.status() != LlmInvocationStatus.OK) {
            throw new BusinessException("受控扫描模型调用失败：" +
                    (resp.errorMessage() == null ? resp.status().name() : resp.errorMessage()));
        }
        return resp.content();
    }
}
