package com.company.aitest.dsh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.generation.session.GenerationCaseLibraryService;
import com.company.aitest.project.ProjectAccessService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DshHumanReviewService {
    private final ProjectAccessService access;
    private final GenerationCaseLibraryService library;
    private final JdbcClient jdbc;

    public DshHumanReviewService(ProjectAccessService access, GenerationCaseLibraryService library, JdbcClient jdbc) {
        this.access = access;
        this.library = library;
        this.jdbc = jdbc;
    }

    public ReviewPreview preview(Long projectId, Long draftId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        var draft = library.getOwnedDraft(projectId, draftId, user);
        return new ReviewPreview(draft, fingerprint(draft));
    }

    @Transactional
    public void submit(Long projectId, Long draftId, String reviewedTitle, String reviewedFingerprint,
                       CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        if (draftId == null || draftId < 1 || reviewedTitle == null || reviewedTitle.isBlank()) {
            throw new BusinessException("请提供有效草稿及审核过的完整标题");
        }
        // Lock the exact owned draft so its content cannot change between review and publication.
        boolean owned = jdbc.sql("""
                SELECT id FROM test_case_draft
                WHERE id = :draftId AND project_id = :projectId AND created_by = :userId
                FOR UPDATE
                """).param("draftId", draftId).param("projectId", projectId).param("userId", user.id())
                .query(Long.class).optional().isPresent();
        if (!owned) throw new BusinessException("草稿不存在或不属于当前用户");
        var draft = library.getOwnedDraft(projectId, draftId, user);
        if (!reviewedTitle.equals(draft.caseTitle()) || !fingerprint(draft).equals(reviewedFingerprint)) {
            throw new BusinessException("草稿内容与审核时不一致，请重新查看草稿");
        }
        library.submitToFormal(projectId, draftId, user);
    }

    static String fingerprint(GenerationCaseLibraryService.LocalCaseDraftView draft) {
        String content = String.join("\u001f", value(draft.caseTitle()), value(draft.moduleName()),
                value(draft.precondition()), value(draft.steps()), value(draft.expectedResult()),
                value(draft.priority()), value(draft.caseType()), value(draft.scenarioType()),
                value(draft.designMethod()), value(draft.sourceRefsJson()), value(draft.caseStatus()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String value(String value) { return value == null ? "" : value; }

    public record ReviewPreview(GenerationCaseLibraryService.LocalCaseDraftView draft, String fingerprint) {}
}
