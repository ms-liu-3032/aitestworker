package com.company.aitest.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
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

    public FileUploadController(FileStorageService fileStorageService, JdbcClient jdbc,
                                 JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.fileStorageService = fileStorageService;
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@PathVariable Long projectId,
                                                    @RequestParam("file") MultipartFile file,
                                                    Authentication auth) throws IOException {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
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

        return ApiResponse.ok(Map.of("id", id, "fileName", originalName, "fileType", ext, "storagePath", storagePath));
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long projectId, @PathVariable Long fileId) {
        var row = jdbc.sql("SELECT file_name, storage_path FROM file_resource WHERE id = :id AND project_id = :pid")
                .param("id", fileId).param("pid", projectId).query((rs, rowNum) -> {
                    return new String[]{rs.getString("file_name"), rs.getString("storage_path")};
                }).single();
        String fileName = row[0];
        String storagePath = row[1];
        Path path = fileStorageService.resolve(storagePath);
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
