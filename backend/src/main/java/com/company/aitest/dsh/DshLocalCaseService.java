package com.company.aitest.dsh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.generation.session.GenerationCaseLibraryService;
import com.company.aitest.project.ProjectAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DshLocalCaseService {
    private final GenerationCaseLibraryService library;
    private final ProjectAccessService access;
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final ObjectMapper mapper;
    private final TimeProvider clock;

    public DshLocalCaseService(GenerationCaseLibraryService library, ProjectAccessService access,
                               JdbcClient jdbc, JdbcTemplate template, ObjectMapper mapper, TimeProvider clock) {
        this.library = library;
        this.access = access;
        this.jdbc = jdbc;
        this.template = template;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record UpdateInput(Integer expectedVersion, String requestKey, String caseTitle, String moduleName,
                              String precondition, String steps, String expectedResult, String priority) {}
    public record UpdateResult(Long localCaseId, String caseNo, int versionNo, boolean replayed) {}

    public GenerationCaseLibraryService.LocalCaseDraftView get(Long projectId, Long localCaseId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        requireId(localCaseId);
        return library.getOwnedDraft(projectId, localCaseId, user);
    }

    @Transactional
    public UpdateResult update(Long projectId, Long localCaseId, UpdateInput input, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        requireId(localCaseId);
        if (input == null || input.expectedVersion() == null || input.expectedVersion() < 1
                || input.requestKey() == null || input.requestKey().isBlank() || input.requestKey().length() > 128)
            throw new BusinessException("须提供当前版本和 1-128 字的幂等键");
        if (input.caseTitle() == null && input.moduleName() == null && input.precondition() == null
                && input.steps() == null && input.expectedResult() == null && input.priority() == null)
            throw new BusinessException("至少修改一个用例字段");
        String hash = hash(input);
        String sql = localCaseId < 0
                ? "SELECT version_no FROM trace_generated_case WHERE id = :id AND project_id = :projectId AND user_id = :userId FOR UPDATE"
                : "SELECT version_no FROM test_case_draft WHERE id = :id AND project_id = :projectId AND created_by = :userId FOR UPDATE";
        int currentVersion = jdbc.sql(sql).param("id", Math.abs(localCaseId))
                .param("projectId", projectId).param("userId", user.id())
                .query(Integer.class).optional().orElseThrow(() -> new BusinessException("本地用例不存在或无权编辑"));
        var current = library.getOwnedDraft(projectId, localCaseId, user);
        if (!"DRAFT".equals(current.caseStatus()) && !"CONFIRMED".equals(current.caseStatus()))
            throw new BusinessException("只有未提交的本地用例可更新");
        var prior = jdbc.sql("""
                SELECT request_hash, result_version FROM dsh_local_case_update_request
                WHERE project_id = :projectId AND user_id = :userId AND local_case_id = :localCaseId
                  AND request_key = :requestKey
                """).param("projectId", projectId).param("userId", user.id())
                .param("localCaseId", localCaseId).param("requestKey", input.requestKey())
                .query((rs, row) -> new Prior(rs.getString("request_hash"), rs.getInt("result_version")))
                .optional();
        if (prior.isPresent()) {
            if (!hash.equals(prior.get().requestHash())) throw new BusinessException("幂等键已用于其他更新内容");
            return new UpdateResult(localCaseId, current.caseNo(), prior.get().resultVersion(), true);
        }
        if (currentVersion != input.expectedVersion())
            throw new BusinessException("本地用例已有新版本，请按 ID 重新读取后再修改");
        String title = input.caseTitle() == null ? current.caseTitle() : input.caseTitle().strip();
        String module = input.moduleName() == null ? current.moduleName() : input.moduleName();
        String precondition = input.precondition() == null ? current.precondition() : input.precondition();
        String steps = input.steps() == null ? current.steps() : input.steps().strip();
        String expected = input.expectedResult() == null ? current.expectedResult() : input.expectedResult().strip();
        if (title == null || title.isBlank() || title.length() > 255 || length(module) > 255
                || length(precondition) > 30_000 || steps == null || steps.isBlank() || steps.length() > 30_000
                || expected == null || expected.isBlank() || expected.length() > 30_000)
            throw new BusinessException("用例标题、步骤、预期不能为空，字段不能超过长度限制");
        if (input.priority() != null && !input.priority().matches("P[0-3]"))
            throw new BusinessException("优先级须为 P0/P1/P2/P3");
        var updated = library.update(projectId, localCaseId,
                new GenerationCaseLibraryService.UpdateLocalCaseCommand(input.caseTitle(), input.moduleName(),
                        input.precondition(), input.steps(), input.expectedResult(), input.priority()), user);
        if (updated.versionNo() != currentVersion + 1 || !updated.id().equals(localCaseId))
            throw new BusinessException("本地用例版本更新未完成");
        template.update("""
                INSERT INTO dsh_local_case_update_request(project_id, user_id, local_case_id, request_key,
                    request_hash, result_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, projectId, user.id(), localCaseId, input.requestKey(), hash, updated.versionNo(), clock.now());
        return new UpdateResult(localCaseId, updated.caseNo(), updated.versionNo(), false);
    }

    private static void requireId(Long id) {
        if (id == null || id == 0 || id == Long.MIN_VALUE) throw new BusinessException("本地用例 ID 无效");
    }

    private String hash(UpdateInput input) {
        try {
            byte[] value = mapper.writeValueAsString(input).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("更新内容无法计算摘要", exception);
        }
    }

    private static int length(String value) { return value == null ? 0 : value.length(); }
    private record Prior(String requestHash, int resultVersion) {}
}
