package com.company.aitest.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.project.ProjectAccessService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projects/{projectId}/files")
public class FileUploadController {

    private final FileStorageService fileStorageService;
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final ProjectAccessService projectAccessService;
    private final FileResourceService fileResourceService;

    public FileUploadController(FileStorageService fileStorageService, JdbcClient jdbc,
                                 JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                                 ProjectAccessService projectAccessService,
                                 FileResourceService fileResourceService) {
        this.fileStorageService = fileStorageService;
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.projectAccessService = projectAccessService;
        this.fileResourceService = fileResourceService;
    }

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@PathVariable Long projectId,
                                                    @RequestParam("file") MultipartFile file,
                                                    Authentication auth) throws IOException {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        String storagePath = fileStorageService.store(file, projectId);
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0) ext = originalName.substring(dot + 1);
        LocalDateTime now = timeProvider.now();

        jdbcTemplate.update("""
                INSERT INTO file_resource(project_id, file_name, file_type, storage_type, storage_path, content_hash, created_by, created_at)
                VALUES (?, ?, ?, 'LOCAL', ?, ?, ?, ?)
                """, projectId, originalName, ext, storagePath, "", user.id(), now);
        Long id = jdbc.sql("SELECT last_insert_id()").query(Long.class).single();

        // The physical storage path is an internal implementation detail. Clients only need the file identity.
        return ApiResponse.ok(Map.of("id", id, "fileName", originalName, "fileType", ext));
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long projectId, @PathVariable Long fileId,
                                             Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        FileResourceRecord fileRecord = fileResourceService.getForProject(projectId, fileId);
        String fileName = fileRecord.fileName();
        String storagePath = fileRecord.storagePath();
        Path path = fileStorageService.resolve(storagePath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new BusinessException("文件不存在或已被清理");
        }
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
