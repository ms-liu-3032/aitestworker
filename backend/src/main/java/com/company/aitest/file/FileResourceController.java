package com.company.aitest.file;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/files")
public class FileResourceController {
    private final FileResourceService service;
    private final ProjectAccessService projectAccessService;

    public FileResourceController(FileResourceService service, ProjectAccessService projectAccessService) {
        this.service = service;
        this.projectAccessService = projectAccessService;
    }

    @GetMapping
    public ApiResponse<List<FileResourceResponse>> list(@PathVariable Long projectId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(service.list(projectId).stream().map(FileResourceResponse::from).toList());
    }

    @PostMapping
    public ApiResponse<FileResourceResponse> register(@PathVariable Long projectId, @Valid @RequestBody RegisterFileRequest request,
                                                       Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        var command = new FileResourceService.RegisterFileCommand(request.fileName(), request.fileType(),
                request.storageType(), request.storagePath(), request.contentHash());
        return ApiResponse.ok(FileResourceResponse.from(service.register(projectId, command, user)));
    }

    public record RegisterFileRequest(@NotBlank String fileName, String fileType, @NotBlank String storageType,
                                      @NotBlank String storagePath, String contentHash) {
    }

    public record FileResourceResponse(Long id, Long projectId, String fileName, String fileType, String storageType,
                                       String contentHash, Long createdBy, java.time.LocalDateTime createdAt) {
        static FileResourceResponse from(FileResourceRecord record) {
            return new FileResourceResponse(record.id(), record.projectId(), record.fileName(), record.fileType(),
                    record.storageType(), record.contentHash(), record.createdBy(), record.createdAt());
        }
    }
}
