package com.company.aitest.businesspack;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectAccessService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/business-packs")
public class BusinessPackController {

    private static final Set<String> VALID_STATUSES = Set.of("DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED");
    private static final Set<String> VALID_TRANSITIONS = Set.of("LINKED", "DEPENDS_ON", "CONTAINS", "SUPPLEMENTS");
    private static final Set<String> VALID_RULE_TYPES = Set.of("TRACE_RULE", "CLEANING_PATTERN", "DESCRIPTION_RULE");

    private final BusinessPackService businessPackService;
    private final ProjectAccessService projectAccess;
    private final JdbcClient jdbc;

    public BusinessPackController(BusinessPackService businessPackService,
                                  ProjectAccessService projectAccess, JdbcClient jdbc) {
        this.businessPackService = businessPackService;
        this.projectAccess = projectAccess;
        this.jdbc = jdbc;
    }

    /** Apply the same project boundary to every legacy business-pack route. */
    @ModelAttribute
    public void authorize(@PathVariable Long projectId, @PathVariable Map<String, String> pathVariables,
                          Authentication auth, jakarta.servlet.http.HttpServletRequest request) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccess.ensureCanAccess(projectId, user);
        if (!"GET".equals(request.getMethod())) projectAccess.ensureCanManageProject(projectId, user);
        checkOwner(projectId, pathVariables.get("packId"), "business_pack", "id", null);
        checkOwner(projectId, pathVariables.get("itemId"), "business_pack_item", "id", pathVariables.get("packId"));
        checkOwner(projectId, pathVariables.get("relationId"), "business_pack_relation", "id", null);
        checkOwner(projectId, pathVariables.get("snapshotId"), "business_pack_snapshot", "id", null);
    }

    private void checkOwner(Long projectId, String rawId, String table, String column, String rawPackId) {
        if (rawId == null) return;
        long id;
        try { id = Long.parseLong(rawId); }
        catch (NumberFormatException exception) { throw new BusinessException("业务包资产编号无效"); }
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :id AND project_id = :projectId";
        if (rawPackId != null) sql += " AND pack_id = :packId";
        var query = jdbc.sql(sql).param("id", id).param("projectId", projectId);
        if (rawPackId != null) query = query.param("packId", Long.parseLong(rawPackId));
        if (query.query(Integer.class).single() == 0) throw new BusinessException("业务包资产不属于当前项目");
    }

    @GetMapping
    public List<BusinessPackService.BusinessPackRecord> listPacks(
            @PathVariable Long projectId,
            @RequestParam(required = false) String status) {
        if (status != null && !status.isBlank() && !VALID_STATUSES.contains(status)) {
            throw new BusinessException("无效的状态筛选: " + status);
        }
        return businessPackService.listPacks(projectId, status);
    }

    @GetMapping("/{packId}")
    public BusinessPackService.BusinessPackRecord getPack(@PathVariable Long packId) {
        return businessPackService.getPack(packId);
    }

    @GetMapping("/{packId}/items")
    public List<BusinessPackService.BusinessPackItemRecord> listItems(@PathVariable Long packId) {
        return businessPackService.listItems(packId);
    }

    @GetMapping("/items")
    public List<BusinessPackService.BusinessPackItemRecord> listItemsByProject(
            @PathVariable Long projectId,
            @RequestParam(required = false) String itemType) {
        return businessPackService.listItemsByProject(projectId, itemType);
    }

    @GetMapping("/refresh-diagnostics")
    public List<BusinessPackService.RefreshDiagnosticRecord> listRefreshDiagnostics(@PathVariable Long projectId) {
        return businessPackService.listRefreshDiagnostics(projectId);
    }

    @PostMapping("/generate")
    public List<BusinessPackService.BusinessPackRecord> generateDrafts(
            @PathVariable Long projectId,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return businessPackService.generateDrafts(projectId, user);
    }

    @PostMapping("/{packId}/activate")
    public BusinessPackService.BusinessPackRecord activate(
            @PathVariable Long packId,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return businessPackService.activate(packId, user);
    }

    @PostMapping("/{packId}/deactivate")
    public BusinessPackService.BusinessPackRecord deactivate(
            @PathVariable Long packId,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return businessPackService.deactivate(packId, user);
    }

    @PostMapping("/{packId}/archive")
    public BusinessPackService.BusinessPackRecord archive(
            @PathVariable Long packId,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return businessPackService.archive(packId, user);
    }

    @PatchMapping("/{packId}")
    public BusinessPackService.BusinessPackRecord updatePack(
            @PathVariable Long packId,
            @RequestBody UpdatePackRequest request,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        if (request.description() != null) {
            return businessPackService.updateDescription(packId, request.description(), user);
        }
        if (request.packName() != null) {
            return businessPackService.rename(packId, request.packName(), user);
        }
        return businessPackService.getPack(packId);
    }

    @DeleteMapping("/{packId}/items/{itemId}")
    public void deleteItem(@PathVariable Long itemId) {
        businessPackService.deleteItem(itemId);
    }

    // ===== 快照 =====

    @GetMapping("/{packId}/snapshots")
    public List<BusinessPackService.SnapshotRecord> listSnapshots(@PathVariable Long packId) {
        return businessPackService.listSnapshots(packId);
    }

    @GetMapping("/snapshots/{snapshotId}")
    public BusinessPackService.SnapshotRecord getSnapshot(@PathVariable Long snapshotId) {
        return businessPackService.getSnapshot(snapshotId);
    }

    // ===== 关系 =====

    @GetMapping("/{packId}/relations")
    public List<BusinessPackService.RelationRecord> listRelations(@PathVariable Long packId) {
        return businessPackService.listRelations(packId);
    }

    @PostMapping("/{packId}/relations")
    public BusinessPackService.RelationRecord createRelation(
            @PathVariable Long packId,
            @RequestBody CreateRelationRequest request,
            Authentication auth) {
        if (request.targetPackId() == null || request.targetPackId() <= 0) {
            throw new BusinessException("目标业务包 ID 不能为空");
        }
        if (request.relationType() == null || !VALID_TRANSITIONS.contains(request.relationType())) {
            throw new BusinessException("无效的关系类型: " + request.relationType() + "，有效值: " + VALID_TRANSITIONS);
        }
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return businessPackService.createRelation(packId, request.targetPackId(),
                request.relationType(), request.description(), user);
    }

    @DeleteMapping("/relations/{relationId}")
    public void deleteRelation(@PathVariable Long relationId) {
        businessPackService.deleteRelation(relationId);
    }

    @PostMapping("/infer-relations")
    public Map<String, Integer> inferRelations(@PathVariable Long projectId) {
        int created = businessPackService.inferRelations(projectId);
        return Map.of("created", created);
    }

    // ===== 生命周期 =====

    @GetMapping("/{packId}/transitions")
    public List<String> getAvailableTransitions(@PathVariable Long packId) {
        return businessPackService.getAvailableTransitions(packId);
    }

    // ===== 绑定关系 =====

    @GetMapping("/{packId}/bindings/rules")
    public List<BusinessPackService.RuleBindingRecord> listRuleBindings(@PathVariable Long packId) {
        return businessPackService.listRuleBindings(packId);
    }

    @PostMapping("/{packId}/bindings/rules")
    public BusinessPackService.RuleBindingRecord createRuleBinding(
            @PathVariable Long packId,
            @RequestBody CreateRuleBindingRequest request,
            Authentication auth) {
        if (request.ruleType() == null || !VALID_RULE_TYPES.contains(request.ruleType())) {
            throw new BusinessException("无效的规则类型: " + request.ruleType() + "，有效值: " + VALID_RULE_TYPES);
        }
        if (request.ruleRef() == null || request.ruleRef().isBlank()) {
            throw new BusinessException("规则引用不能为空");
        }
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return businessPackService.createRuleBinding(packId, request.ruleType(),
                request.ruleRef(), request.ruleConfigJson(), user);
    }

    @GetMapping("/{packId}/bindings/scans")
    public List<BusinessPackService.ScanBindingRecord> listScanBindings(@PathVariable Long packId) {
        return businessPackService.listScanBindings(packId);
    }

    @PostMapping("/{packId}/bindings/scans")
    public BusinessPackService.ScanBindingRecord createScanBinding(
            @PathVariable Long packId,
            @RequestBody CreateScanBindingRequest request,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return businessPackService.createScanBinding(packId, request.scanProfileId(),
                request.routePath(), request.pageLabel(), user);
    }

    @GetMapping("/{packId}/bindings/toms")
    public List<BusinessPackService.TomBindingRecord> listTomBindings(@PathVariable Long packId) {
        return businessPackService.listTomBindings(packId);
    }

    @PostMapping("/{packId}/bindings/toms")
    public BusinessPackService.TomBindingRecord createTomBinding(
            @PathVariable Long packId,
            @RequestBody CreateTomBindingRequest request,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return businessPackService.createTomBinding(packId, request.tomId(),
                request.tomName(), request.tomType(), user);
    }

    @GetMapping("/{packId}/bindings/semantic")
    public List<BusinessPackService.SemanticBindingRecord> listSemanticBindings(@PathVariable Long packId) {
        return businessPackService.listSemanticBindings(packId);
    }

    // ===== 消费记录 =====

    @GetMapping("/{packId}/consumption")
    public List<BusinessPackService.ConsumptionLogRecord> listConsumptionLogs(@PathVariable Long packId) {
        return businessPackService.listConsumptionLogs(packId);
    }

    @GetMapping("/consumption")
    public List<BusinessPackService.ConsumptionLogRecord> listConsumptionLogsByProject(
            @PathVariable Long projectId,
            @RequestParam(required = false) String consumerType) {
        return businessPackService.listConsumptionLogsByProject(projectId, consumerType);
    }

    public record UpdatePackRequest(String packName, String description) {}
    public record CreateRelationRequest(Long targetPackId, String relationType, String description) {}
    public record CreateRuleBindingRequest(String ruleType, String ruleRef, String ruleConfigJson) {}
    public record CreateScanBindingRequest(Long scanProfileId, String routePath, String pageLabel) {}
    public record CreateTomBindingRequest(Long tomId, String tomName, String tomType) {}
}
