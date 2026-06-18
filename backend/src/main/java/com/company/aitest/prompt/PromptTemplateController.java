package com.company.aitest.prompt;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/prompts")
public class PromptTemplateController {
    private final PromptTemplateService service;

    public PromptTemplateController(PromptTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<List<PromptTemplateRecord>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<PromptTemplateRecord> create(@Valid @RequestBody CreatePromptRequest request, Authentication authentication) {
        var command = new PromptTemplateService.CreatePromptCommand(request.promptName(), request.promptType(), request.content());
        return ApiResponse.ok(service.create(command, (CurrentUser) authentication.getPrincipal()));
    }

    /** 管理员更新公共/系统提示词内容 → 实际生成一个 version+1 的新记录，旧版 DEPRECATED。 */
    @PostMapping("/{id}/new-version")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<PromptTemplateRecord> newVersion(@PathVariable Long id,
                                                       @Valid @RequestBody ContentPayload payload,
                                                       Authentication authentication) {
        return ApiResponse.ok(service.updateAsNewVersion(id, payload.content(), (CurrentUser) authentication.getPrincipal()));
    }

    /** 普通用户 fork 公共/系统提示词为自己的 PERSONAL 副本。 */
    @PostMapping("/{id}/fork")
    public ApiResponse<PromptTemplateRecord> fork(@PathVariable Long id, Authentication authentication) {
        return ApiResponse.ok(service.forkToPersonal(id, (CurrentUser) authentication.getPrincipal()));
    }

    /** 把自己的 PERSONAL 提示词提议为候选 PUBLIC（待管理员审核）。 */
    @PostMapping("/{id}/propose-public")
    public ApiResponse<PromptTemplateRecord> proposePublic(@PathVariable Long id, Authentication authentication) {
        return ApiResponse.ok(service.proposeAsPublic(id, (CurrentUser) authentication.getPrincipal()));
    }

    /** 管理员审核 PUBLIC 候选提示词。 */
    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<PromptTemplateRecord> review(@PathVariable Long id,
                                                    @RequestBody ReviewPayload payload,
                                                    Authentication authentication) {
        return ApiResponse.ok(service.review(id, payload.approved(), payload.reason(),
                (CurrentUser) authentication.getPrincipal()));
    }

    public record CreatePromptRequest(@NotBlank String promptName, @NotBlank String promptType, @NotBlank String content) {
    }

    public record ContentPayload(@NotBlank String content) {
    }

    public record ReviewPayload(boolean approved, String reason) {
    }
}

