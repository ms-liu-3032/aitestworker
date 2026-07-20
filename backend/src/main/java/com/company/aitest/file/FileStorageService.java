package com.company.aitest.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.company.aitest.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileStorageService {

    private static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".txt", ".md", ".pdf", ".doc", ".docx",
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp"
    );

    private final Path uploadRoot;

    public FileStorageService(@Value("${app.upload.root:./data/uploads}") String uploadRoot) {
        this.uploadRoot = Paths.get(uploadRoot).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file, Long projectId) throws IOException {
        validateFile(file);
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0) ext = originalName.substring(dot).toLowerCase(Locale.ROOT);

        String storedName = UUID.randomUUID() + ext;
        Path dir = uploadRoot.resolve(String.valueOf(projectId));
        Files.createDirectories(dir);
        Path target = dir.resolve(storedName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return projectId + "/" + storedName;
    }

    public Path resolve(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new BusinessException("文件路径不能为空");
        }
        Path relative = Paths.get(storagePath);
        if (relative.isAbsolute() || storagePath.contains("..")) {
            throw new BusinessException("非法文件路径");
        }
        Path resolved = uploadRoot.resolve(relative).normalize();
        if (!resolved.startsWith(uploadRoot)) {
            throw new BusinessException("非法文件路径");
        }
        return resolved;
    }

    public void validateStoragePath(String storagePath) {
        resolve(storagePath);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BusinessException("上传文件不能超过 50MB");
        }
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
        int dot = originalName.lastIndexOf('.');
        String ext = dot >= 0 ? originalName.substring(dot).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException("不支持的文件类型：" + ext);
        }
    }
}
