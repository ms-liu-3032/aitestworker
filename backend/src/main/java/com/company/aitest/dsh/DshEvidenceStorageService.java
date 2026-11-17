package com.company.aitest.dsh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.FileAlreadyExistsException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.company.aitest.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DshEvidenceStorageService {
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final Set<String> KINDS = Set.of("SCREENSHOT", "TRACE", "VIDEO", "MANIFEST", "FRAME");
    private final Path root;

    public DshEvidenceStorageService(@Value("${app.upload.root:./data/uploads}") String uploadRoot) {
        this.root = Paths.get(uploadRoot).toAbsolutePath().normalize().resolve("dsh-evidence");
    }

    public StoredFile store(Long projectId, Long sessionId, String kind, MultipartFile file) {
        if (!KINDS.contains(kind) || file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new BusinessException("证据类型无效或文件超过 50MB");
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) throw new BusinessException("证据文件名不能为空");
        String lower = original.toLowerCase(Locale.ROOT);
        String extension = switch (kind) {
            case "SCREENSHOT" -> {
                if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg"))
                    throw new BusinessException("截图必须是 PNG/JPEG");
                yield lower.endsWith(".png") ? ".png" : ".jpg";
            }
            case "TRACE" -> {
                if (!lower.endsWith(".zip")) throw new BusinessException("Playwright trace 必须是 ZIP");
                yield ".zip";
            }
            case "VIDEO" -> {
                if (!lower.endsWith(".webm")) throw new BusinessException("视频必须是 WebM");
                yield ".webm";
            }
            case "MANIFEST" -> {
                if (!lower.endsWith(".json")) throw new BusinessException("录屏清单必须是 JSON");
                yield ".json";
            }
            case "FRAME" -> {
                if (!original.matches("^\\d{6}\\.jpg$")) throw new BusinessException("录屏帧文件名无效");
                yield ".jpg";
            }
            default -> throw new BusinessException("未知证据类型");
        };
        Path dir = root.resolve(String.valueOf(projectId)).resolve(String.valueOf(sessionId));
        Path target = switch (kind) {
            case "MANIFEST" -> dir.resolve("screencast/manifest.json");
            case "FRAME" -> dir.resolve("screencast/frames").resolve(original);
            default -> dir.resolve(UUID.randomUUID() + extension);
        };
        Path temporary = dir.resolve(UUID.randomUUID() + ".upload");
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temporary);
            }
            long actualSize = Files.size(temporary);
            if (actualSize > MAX_BYTES) {
                throw new BusinessException("证据文件超过 50MB");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            try {
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException exception) {
                if (Files.size(target) != actualSize || !hash.equals(hashFile(target))) {
                    throw new BusinessException("证据文件已存在且内容不同");
                }
            }
            return new StoredFile(target.toString(), actualSize, hash, original);
        } catch (IOException exception) {
            throw new BusinessException("证据写入失败：" + exception.getMessage());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        } finally {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static String hashFile(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public Path resolveStored(String storagePath) {
        Path path = Paths.get(storagePath).toAbsolutePath().normalize();
        if (!path.startsWith(root)) throw new BusinessException("证据路径不属于 DSH 存储目录");
        if (!Files.isRegularFile(path)) throw new BusinessException("证据文件不存在");
        return path;
    }

    public record StoredFile(String storagePath, long sizeBytes, String sha256, String originalName) {}
}
