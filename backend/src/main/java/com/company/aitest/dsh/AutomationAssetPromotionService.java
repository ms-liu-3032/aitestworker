package com.company.aitest.dsh;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.generation.session.GenerationCaseLibraryService;
import com.company.aitest.project.ProjectAccessService;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Promotes owned draft mappings in place, retaining every run and frozen version. */
@Service
public class AutomationAssetPromotionService {
    public record Target(Long assetId, Integer versionNo) {}
    public record BatchInput(List<Target> targets) {}
    public record Result(Long assetId, Long formalCaseId, int versionNo) {}

    private final AutomationAssetService assets;
    private final GenerationCaseLibraryService cases;
    private final ProjectAccessService access;
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TimeProvider clock;

    public AutomationAssetPromotionService(AutomationAssetService assets, GenerationCaseLibraryService cases,
            ProjectAccessService access, JdbcClient jdbc, JdbcTemplate template, TimeProvider clock) {
        this.assets = assets;
        this.cases = cases;
        this.access = access;
        this.jdbc = jdbc;
        this.template = template;
        this.clock = clock;
    }

    @Transactional
    public List<Result> promote(Long projectId, BatchInput input, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        if (input == null || input.targets() == null || input.targets().isEmpty() || input.targets().size() > 100)
            throw new BusinessException("每批请选择 1-100 条个人草稿映射");
        Set<Long> seen = new HashSet<>();
        for (Target target : input.targets()) {
            if (target == null || target.assetId() == null || target.assetId() < 1
                    || target.versionNo() == null || target.versionNo() < 1 || !seen.add(target.assetId()))
                throw new BusinessException("批量提交目标无效或重复");
        }
        return input.targets().stream().sorted(Comparator.comparing(Target::assetId))
                .map(target -> promoteOne(projectId, target, user)).toList();
    }

    private Result promoteOne(Long projectId, Target target, CurrentUser user) {
        jdbc.sql("SELECT id FROM automation_asset WHERE id=:id AND project_id=:projectId FOR UPDATE")
                .param("id", target.assetId()).param("projectId", projectId).query(Long.class).optional()
                .orElseThrow(() -> new BusinessException("自动化资产不存在"));
        var asset = assets.get(projectId, target.assetId(), user);
        if (!"DRAFT".equals(asset.caseKind()) || !user.id().equals(asset.createdBy()))
            throw new BusinessException("只能提交本人个人草稿的自动化映射");
        if (asset.currentVersion() != target.versionNo() || !"VERIFIED".equals(asset.status())
                || !"APPROVED".equals(asset.reviewStatus()) || asset.caseStale() || asset.caseMissing()
                || asset.latestVersion() > asset.currentVersion())
            throw new BusinessException("草稿映射须为当前已审核、已验证且无待验证候选的版本");
        String draftStatus = jdbc.sql("""
                SELECT case_status FROM test_case_draft
                WHERE id=:id AND project_id=:projectId AND created_by=:userId FOR UPDATE
                """).param("id", asset.caseId()).param("projectId", projectId)
                .param("userId", user.id()).query(String.class).optional()
                .orElseThrow(() -> new BusinessException("关联的个人用例草稿不存在"));
        if (!"SUBMITTED".equals(draftStatus)) cases.submitToFormal(projectId, asset.caseId(), user);
        List<FormalCase> formal = jdbc.sql("""
                SELECT c.id, c.version, c.case_title, c.module_name, c.precondition, c.steps, c.expected_result,
                       d.case_title draft_title, d.module_name draft_module, d.precondition draft_precondition,
                       d.steps draft_steps, d.expected_result draft_expected
                FROM test_case_asset c JOIN test_case_draft d ON d.id=c.source_draft_id
                WHERE c.project_id=:projectId AND d.id=:draftId AND c.case_status='SUBMITTED'
                  AND c.deprecated_at IS NULL ORDER BY c.id DESC LIMIT 1
                """).param("projectId", projectId).param("draftId", asset.caseId())
                .query((rs, row) -> new FormalCase(rs.getLong("id"), rs.getInt("version"),
                        rs.getString("case_title"), rs.getString("module_name"), rs.getString("precondition"),
                        rs.getString("steps"), rs.getString("expected_result"), rs.getString("draft_title"),
                        rs.getString("draft_module"), rs.getString("draft_precondition"),
                        rs.getString("draft_steps"), rs.getString("draft_expected"))).list();
        if (formal.isEmpty()) throw new BusinessException("正式用例未找到，请检查草稿提交结果");
        FormalCase c = formal.get(0);
        if (!Objects.equals(c.title, c.draftTitle) || !Objects.equals(c.module, c.draftModule)
                || !Objects.equals(c.precondition, c.draftPrecondition)
                || !Objects.equals(c.steps, c.draftSteps) || !Objects.equals(c.expected, c.draftExpected))
            throw new BusinessException("正式用例与已验证草稿内容不一致，请重新验证映射");
        int duplicate = jdbc.sql("""
                SELECT COUNT(*) FROM automation_asset WHERE project_id=:projectId AND case_kind='FORMAL'
                  AND case_id=:caseId AND asset_type=:type
                """).param("projectId", projectId).param("caseId", c.id)
                .param("type", asset.assetType()).query(Integer.class).single();
        if (duplicate != 0) throw new BusinessException("正式用例已有同类型自动化映射，请先处理重复资产");
        template.update("""
                UPDATE automation_asset SET case_kind='FORMAL', case_id=?, source_case_version=?,
                    promoted_from_draft_id=?, promoted_by=?, promoted_at=?, updated_at=? WHERE id=?
                """, c.id, c.version, asset.caseId(), user.id(), clock.now(), clock.now(), asset.id());
        return new Result(asset.id(), c.id, asset.currentVersion());
    }

    private record FormalCase(long id, int version, String title, String module, String precondition,
                              String steps, String expected, String draftTitle, String draftModule,
                              String draftPrecondition, String draftSteps, String draftExpected) {}
}
