package com.company.aitest.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileStorageService {

    private final Path uploadRoot;

    public FileStorageService(@Value("${app.upload.root:./data/uploads}") String uploadRoot) {
        this.uploadRoot = Paths.get(uploadRoot).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file, Long projectId) throws IOException {
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0) ext = originalName.substring(dot);

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
        return uploadRoot.resolve(storagePath).normalize();
    }
}
