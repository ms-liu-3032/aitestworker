package com.company.aitest.project;

import com.company.aitest.audit.OperationLogService;
import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/members")
public class ProjectMemberController {
    private final ProjectMemberService members;
    private final OperationLogService logs;

    public ProjectMemberController(ProjectMemberService members, OperationLogService logs) {
        this.members = members;
        this.logs = logs;
    }

    @GetMapping
    public ApiResponse<ProjectMemberService.MemberList> list(@PathVariable Long projectId, Authentication auth) {
        return ApiResponse.ok(members.list(projectId, (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping
    public ApiResponse<ProjectMemberService.Member> add(@PathVariable Long projectId,
            @Valid @RequestBody AddMemberRequest input, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        var member = members.add(projectId, input.username(), user);
        logs.recordQuietly(user.id(), "PROJECT_MEMBER_ADD", "PROJECT", projectId,
                "{\"targetUserId\":" + member.userId() + "}");
        return ApiResponse.ok(member);
    }

    @DeleteMapping("/{userId}")
    public ApiResponse<Void> remove(@PathVariable Long projectId, @PathVariable Long userId, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        members.remove(projectId, userId, user);
        logs.recordQuietly(user.id(), "PROJECT_MEMBER_REMOVE", "PROJECT", projectId,
                "{\"targetUserId\":" + userId + "}");
        return ApiResponse.ok(null);
    }

    public record AddMemberRequest(@NotBlank String username) {}
}
