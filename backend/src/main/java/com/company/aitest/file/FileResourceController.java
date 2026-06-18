package com.company.aitest.file;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
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

    public FileResourceController(FileResourceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<FileResourceRecord>> list(@PathVariable Long projectId) {
        return ApiResponse.ok(service.list(projectId));
    }

    @PostMapping
    public ApiResponse<FileResourceRecord> register(@PathVariable Long projectId, @Valid @RequestBody RegisterFileRequest request,
                                                    Authentication authentication) {
        var command = new FileResourceService.RegisterFileCommand(request.fileName(), request.fileType(),
                request.storageType(), request.storagePath(), request.contentHash());
        return ApiResponse.ok(service.register(projectId, command, (CurrentUser) authentication.getPrincipal()));
    }

    public record RegisterFileRequest(@NotBlank String fileName, String fileType, @NotBlank String storageType,
                                      @NotBlank String storagePath, String contentHash) {
    }
}
