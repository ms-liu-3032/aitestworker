package com.company.aitest.dsh;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.project.ProjectAccessService;
import com.company.aitest.wiki.WikiService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.company.aitest.minitom.TomRelationTypes;

/** DSH can propose project assets; existing Web review remains the only activation path. */
@RestController
@RequestMapping("/api/dsh/tools/projects/{projectId}/asset-proposals")
public class DshAssetProposalController {
    private static final Set<String> TOM_TYPES = Set.of("MODULE", "PAGE", "FIELD", "ROLE", "ACTION", "FLOW", "STATE", "ASSERTION");
    private final JdbcTemplate template;
    private final JdbcClient jdbc;
    private final ProjectAccessService access;
    private final DshWorkSessionService sessions;
    private final WikiService wiki;
    private final TimeProvider clock;
    private final ObjectMapper mapper;

    public DshAssetProposalController(JdbcTemplate template, JdbcClient jdbc, ProjectAccessService access,
            DshWorkSessionService sessions, WikiService wiki, TimeProvider clock, ObjectMapper mapper) {
        this.template = template;
        this.jdbc = jdbc;
        this.access = access;
        this.sessions = sessions;
        this.wiki = wiki;
        this.clock = clock;
        this.mapper = mapper;
    }

    public record WikiItem(String type, String title, String content, String source) {}
    public record WikiRequest(Long workSessionId, String packName, List<WikiItem> entries) {}
    public record TomItem(String type, String name, String description, String evidence) {}
    public record TomRequest(Long workSessionId, List<TomItem> nodes) {}
    public record RelationItem(Long fromModelId, String type, Long toModelId, String evidence) {}
    public record RelationRequest(Long workSessionId, List<RelationItem> relations) {}
    public record LocatorItem(String page, String control, String strategy, String selector, String evidence) {}
    public record LocatorRequest(Long workSessionId, String name, List<LocatorItem> locators) {}

    @GetMapping("/tom-relation-types")
    public ApiResponse<List<Map<String, String>>> relationTypes(@PathVariable Long projectId, Authentication auth) {
        access.ensureCanAccess(projectId, user(auth));
        return ApiResponse.ok(TomRelationTypes.definitions());
    }

    @PostMapping("/wiki")
    @Transactional
    public ApiResponse<Map<String, Object>> wiki(@PathVariable Long projectId, @RequestBody WikiRequest request,
            Authentication auth) {
        if (request == null) throw new BusinessException("Wiki 候选内容不能为空");
        CurrentUser user = user(auth);
        verify(projectId, request.workSessionId(), user);
        requireText(request.packName(), 1, 170, "知识包名称");
        requireBatch(request.entries(), 30, "Wiki 条目");
        for (WikiItem item : request.entries()) {
            if (item == null || item.type() == null || !Set.of("RULE", "FLOW", "PAGE", "DIALOG", "STATE", "IMPLEMENTATION", "DECISION").contains(item.type()))
                throw new BusinessException("Wiki 条目类型无效");
            requireText(item.title(), 1, 512, "Wiki 标题");
            requireText(item.content(), 10, 50000, "Wiki 正文");
            requireText(item.source(), 3, 1000, "Wiki 来源依据");
        }
        String packName = request.packName().strip() + "（DSH 候选 " + user.id() + "-"
                + sha256(json(request.entries())).substring(0, 12) + "）";
        List<Long> existingPack = jdbc.sql("SELECT id FROM wiki_pack WHERE project_id = ? AND name = ? AND created_by = ?")
                .param(projectId).param(packName).param(user.id()).query(Long.class).list();
        if (!existingPack.isEmpty()) {
            List<Long> existingEntries = jdbc.sql("SELECT id FROM wiki_entry WHERE pack_id = ? ORDER BY id")
                    .param(existingPack.get(0)).query(Long.class).list();
            return ApiResponse.ok(Map.of("packId", existingPack.get(0), "entryIds", existingEntries,
                    "scope", "PROJECT", "status", "ALREADY_SUBMITTED"));
        }
        var pack = wiki.createPack(projectId, "PROJECT", packName, "DSH 提交；待人工审核。任务 " + request.workSessionId(), user);
        List<Long> ids = new ArrayList<>();
        for (WikiItem item : request.entries()) {
            String refs = json(Map.of("source", item.source(), "workSessionId", request.workSessionId(), "proposedBy", user.id()));
            ids.add(wiki.createEntry(pack.id(), item.type(), item.title().strip(), item.content().strip(), null, refs, user).id());
        }
        return ApiResponse.ok(Map.of("packId", pack.id(), "entryIds", ids, "scope", "PROJECT", "status", "PENDING_REVIEW"));
    }

    @PostMapping("/tom")
    @Transactional
    public ApiResponse<Map<String, Object>> tom(@PathVariable Long projectId, @RequestBody TomRequest request,
            Authentication auth) {
        if (request == null) throw new BusinessException("TOM 候选内容不能为空");
        CurrentUser user = user(auth);
        verify(projectId, request.workSessionId(), user);
        requireBatch(request.nodes(), 50, "TOM 节点");
        for (TomItem item : request.nodes()) {
            if (item == null || item.type() == null || !TOM_TYPES.contains(item.type())) throw new BusinessException("TOM 类型无效");
            requireText(item.name(), 1, 256, "TOM 名称");
            requireText(item.description(), 10, 10000, "TOM 描述");
            requireText(item.evidence(), 10, 1000, "TOM 来源依据");
        }
        List<Long> ids = new ArrayList<>();
        LocalDateTime now = clock.now();
        for (TomItem item : request.nodes()) {
            String key = "DSH_NODE:" + request.workSessionId() + ":" + sha256(json(item)).substring(0, 32);
            List<Long> existing = jdbc.sql("""
                    SELECT id FROM test_object_model WHERE project_id = ? AND candidate_key = ?
                      AND status IN ('CANDIDATE', 'ACTIVE') ORDER BY id DESC LIMIT 1
                    """).param(projectId).param(key).query(Long.class).list();
            if (!existing.isEmpty()) { ids.add(existing.get(0)); continue; }
            template.update("""
                    INSERT INTO test_object_model(project_id, scope, model_type, name, description,
                        source_type, source_ref_id, source_context, source_refs_json, candidate_key, source_hash, confidence, status,
                        requires_human_confirm, validity_label, created_by, created_at, updated_at)
                    VALUES (?, 'PROJECT', ?, ?, ?, 'DSH_PROPOSAL', ?, ?, ?, ?, ?, 0.50, 'CANDIDATE', 1, 'TO_CONFIRM', ?, ?, ?)
                    """, projectId, item.type(), item.name().strip(), item.description().strip(),
                    request.workSessionId(), item.evidence().strip(),
                    json(Map.of("evidence", item.evidence(), "workSessionId", request.workSessionId())),
                    key, sha256(json(item)), user.id(), now, now);
            Long id = lastId();
            template.update("""
                    INSERT INTO tom_evidence(project_id, tom_id, source_type, source_ref_type, source_ref_id,
                        source_version, source_hash, source_excerpt, evidence_level, conflict_status,
                        review_status, candidate_key, created_at)
                    VALUES (?, ?, 'DSH_PROPOSAL', 'DSH_WORK_SESSION', ?, 1, ?, ?, 'DIRECT', 'NONE', 'PENDING', ?, ?)
                    """, projectId, id, request.workSessionId(), sha256(item.evidence()), item.evidence().strip(),
                    key, now);
            ids.add(id);
        }
        return ApiResponse.ok(Map.of("nodeIds", ids, "status", "CANDIDATE"));
    }

    @PostMapping("/tom-relations")
    @Transactional
    public ApiResponse<Map<String, Object>> relations(@PathVariable Long projectId, @RequestBody RelationRequest request,
            Authentication auth) {
        if (request == null) throw new BusinessException("TOM 关系内容不能为空");
        CurrentUser user = user(auth);
        verify(projectId, request.workSessionId(), user);
        requireBatch(request.relations(), 100, "TOM 关系");
        List<Long> ids = new ArrayList<>();
        LocalDateTime now = clock.now();
        for (RelationItem item : request.relations()) {
            if (item == null || item.fromModelId() == null || item.toModelId() == null || item.fromModelId().equals(item.toModelId()))
                throw new BusinessException("关系两端必须是不同的 TOM 节点");
            String expected = TomRelationTypes.expectedPair(item.type());
            if (expected == null) throw new BusinessException("TOM 关系类型无效，请先读取 /tom-relation-types 获取可用类型和节点方向");
            requireText(item.evidence(), 10, 1000, "关系依据");
            List<Map<String, Object>> endpoints = template.queryForList("""
                    SELECT id, model_type FROM test_object_model WHERE project_id = ?
                      AND status IN ('ACTIVE', 'CANDIDATE') AND id IN (?, ?)
                    """, projectId, item.fromModelId(), item.toModelId());
            if (endpoints.size() != 2) throw new BusinessException("关系两端须是同项目有效或待审节点");
            Map<Long, String> types = new LinkedHashMap<>();
            for (var row : endpoints) types.put(((Number) row.get("id")).longValue(), (String) row.get("model_type"));
            if (!expected.equals(types.get(item.fromModelId()) + ":" + types.get(item.toModelId())))
                throw new BusinessException("关系类型与节点类型不匹配");
            List<Long> existing = jdbc.sql("""
                    SELECT id FROM test_object_model_relation
                    WHERE project_id = ? AND from_model_id = ? AND to_model_id = ? AND relation_type = ?
                      AND status IN ('ACTIVE', 'CANDIDATE', 'CONFIRMED') ORDER BY id DESC LIMIT 1
                    """).param(projectId).param(item.fromModelId()).param(item.toModelId()).param(item.type())
                    .query(Long.class).list();
            if (!existing.isEmpty()) { ids.add(existing.get(0)); continue; }
            String refs = json(Map.of("evidence", item.evidence(), "workSessionId", request.workSessionId(), "proposedBy", user.id()));
            template.update("""
                    INSERT INTO test_object_model_relation(project_id, from_model_id, relation_type, to_model_id,
                        confidence, status, source_type, source_ref_id, source_refs_json, requires_human_confirm,
                        created_at, updated_at)
                    VALUES (?, ?, ?, ?, 0.50, 'CANDIDATE', 'DSH_PROPOSAL', ?, ?, 1, ?, ?)
                    """, projectId, item.fromModelId(), item.type(), item.toModelId(), request.workSessionId(), refs, now, now);
            Long id = lastId();
            template.update("""
                    INSERT INTO tom_evidence(project_id, relation_id, source_type, source_ref_type,
                        source_ref_id, source_version, source_hash, source_excerpt, evidence_level,
                        conflict_status, review_status, candidate_key, created_at)
                    VALUES (?, ?, 'DSH_PROPOSAL', 'DSH_WORK_SESSION', ?, 1, ?, ?, 'DIRECT', 'NONE', 'PENDING', ?, ?)
                    """, projectId, id, request.workSessionId(), sha256(item.evidence()), item.evidence().strip(),
                    "DSH_RELATION:" + projectId + ":" + id, now);
            ids.add(id);
        }
        return ApiResponse.ok(Map.of("relationIds", ids, "status", "CANDIDATE"));
    }

    @PostMapping("/locators")
    @Transactional
    public ApiResponse<Map<String, Object>> locators(@PathVariable Long projectId, @RequestBody LocatorRequest request,
            Authentication auth) {
        if (request == null) throw new BusinessException("定位映射内容不能为空");
        CurrentUser user = user(auth);
        verify(projectId, request.workSessionId(), user);
        requireText(request.name(), 1, 200, "定位表名称");
        requireBatch(request.locators(), 100, "控件定位");
        for (LocatorItem item : request.locators()) {
            if (item == null) throw new BusinessException("控件定位不能为空");
            requireText(item.page(), 1, 200, "页面");
            requireText(item.control(), 1, 200, "控件");
            if (item.strategy() == null || !Set.of("TEST_ID", "ROLE", "LABEL", "PLACEHOLDER", "TEXT", "CSS").contains(item.strategy()))
                throw new BusinessException("控件定位方式无效");
            requireText(item.selector(), 1, 1000, "定位值");
            requireText(item.evidence(), 10, 1000, "定位依据");
        }
        LocalDateTime now = clock.now();
        String body = json(Map.of("workSessionId", request.workSessionId(), "locators", request.locators()));
        List<Long> existing = jdbc.sql("""
                SELECT id FROM test_tool_template WHERE project_id = ? AND source_type = 'DSH_LOCATOR_MAP'
                  AND source_id = ? AND tool_name = ? AND body = ? AND status IN ('DRAFT', 'ACTIVE')
                ORDER BY id DESC LIMIT 1
                """).param(projectId).param(request.workSessionId()).param(request.name().strip()).param(body)
                .query(Long.class).list();
        if (!existing.isEmpty()) return ApiResponse.ok(Map.of("locatorMapId", existing.get(0), "status", "ALREADY_SUBMITTED"));
        template.update("""
                INSERT INTO test_tool_template(project_id, scope, tool_name, description, operation_steps,
                    body, source_type, source_id, status, review_status, trust_level, created_by, created_at, updated_at)
                VALUES (?, 'PROJECT', ?, 'DSH 控件定位映射；审核后仍需执行验证', ?, ?,
                    'DSH_LOCATOR_MAP', ?, 'DRAFT', 'PENDING', 'AI_GENERATED', ?, ?, ?)
                """, projectId, request.name().strip(), "共 " + request.locators().size() + " 个定位；不可直接视为可执行脚本",
                body,
                request.workSessionId(), user.id(), now, now);
        return ApiResponse.ok(Map.of("locatorMapId", lastId(), "status", "PENDING_REVIEW"));
    }

    @GetMapping("/locators")
    public ApiResponse<List<Map<String, Object>>> activeLocators(@PathVariable Long projectId,
            @RequestParam(defaultValue = "") String query, Authentication auth) {
        access.ensureCanAccess(projectId, user(auth));
        if (query.length() > 100) throw new BusinessException("检索词最多 100 字");
        String escaped = query.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return ApiResponse.ok(jdbc.sql("""
                SELECT id, tool_name, body, source_id FROM test_tool_template
                WHERE project_id = :projectId AND source_type = 'DSH_LOCATOR_MAP'
                  AND status = 'ACTIVE' AND review_status = 'APPROVED'
                  AND tool_name LIKE :pattern ESCAPE '!' ORDER BY id DESC LIMIT 20
                """).param("projectId", projectId).param("pattern", "%" + escaped + "%")
                .query((rs, row) -> Map.<String, Object>of("locatorMapId", rs.getLong("id"),
                        "name", rs.getString("tool_name"), "body", rs.getString("body"),
                        "sourceWorkSessionId", rs.getLong("source_id"))).list());
    }

    private void verify(Long projectId, Long sessionId, CurrentUser user) {
        if (sessionId == null) throw new BusinessException("必须提供本轮测试任务 ID");
        var session = sessions.get(projectId, sessionId, user);
        if (!"ACTIVE".equals(session.status())) throw new BusinessException("测试任务已关闭");
    }

    private static CurrentUser user(Authentication auth) {
        return ((DshConnectionService.DshPrincipal) auth.getPrincipal()).user();
    }

    private static void requireBatch(List<?> items, int max, String label) {
        if (items == null || items.isEmpty() || items.size() > max)
            throw new BusinessException(label + "每批须为 1 至 " + max + " 条");
    }

    private static void requireText(String value, int min, int max, String label) {
        if (value == null || value.strip().length() < min || value.length() > max)
            throw new BusinessException(label + "长度须为 " + min + " 至 " + max + " 字");
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new BusinessException("候选内容无法序列化"); }
    }

    private Long lastId() { return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single(); }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
