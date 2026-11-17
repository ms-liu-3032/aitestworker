package com.company.aitest.dsh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DshCaptureBundleService {
    private static final int CHUNK_BYTES = 4 * 1024 * 1024;
    private static final long MAX_BUNDLE_BYTES = 1024L * 1024 * 1024;
    private final Path root;
    private final DshCaptureService captures;
    private final JdbcTemplate template;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TimeProvider clock;

    public DshCaptureBundleService(@Value("${app.upload.root:./data/uploads}") String uploadRoot,
                                   DshCaptureService captures, JdbcTemplate template,
                                   JdbcClient jdbc, ObjectMapper mapper, TimeProvider clock) {
        this.root = Paths.get(uploadRoot).toAbsolutePath().normalize().resolve("dsh-capture-bundles");
        this.captures = captures;
        this.template = template;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record Summary(int artifactCount, long sourceBytes, int eventCount, int networkCount,
                          Map<String, Integer> byKind) {}
    public record CommitRequest(String requestKey, int chunkCount, long sizeBytes, String sha256, Summary summary) {}
    public record Bundle(Long id, Long sessionId, long sizeBytes, String sha256, Summary summary,
                         LocalDateTime createdAt) {}

    public void chunk(Long projectId, Long sessionId, String requestKey, int index, byte[] bytes, CurrentUser user) {
        captures.run(projectId, sessionId, user);
        UUID key = key(requestKey);
        if (index < 0 || index >= 1024 || bytes == null || bytes.length == 0 || bytes.length > CHUNK_BYTES)
            throw new BusinessException("证据分段序号或大小无效");
        Path dir = staging(projectId, sessionId, key);
        Path target = dir.resolve(String.format("%04d.part", index));
        try {
            Files.createDirectories(dir);
            Path temporary = Files.createTempFile(dir, "chunk-", ".tmp");
            try {
                Files.write(temporary, bytes);
                if (Files.exists(target)) {
                    if (!MessageDigest.isEqual(Files.readAllBytes(target), bytes))
                        throw new BusinessException("证据分段内容与已上传记录不一致");
                } else Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException error) { throw new BusinessException("证据分段写入失败"); }
    }

    @Transactional
    public Bundle commit(Long projectId, Long sessionId, CommitRequest request, CurrentUser user) {
        captures.run(projectId, sessionId, user);
        if (request == null) throw new BusinessException("证据压缩包摘要不能为空");
        UUID key = key(request.requestKey());
        Bundle prior = find(projectId, sessionId);
        if (prior != null) {
            if (!prior.sha256().equals(request.sha256()) || prior.sizeBytes() != request.sizeBytes()
                    || !prior.summary().equals(request.summary())) throw new BusinessException("该会话已有不同的证据压缩包");
            return prior;
        }
        if (request.chunkCount() < 1 || request.chunkCount() > 1024 || request.sizeBytes() < 1
                || request.sizeBytes() > MAX_BUNDLE_BYTES || request.sha256() == null
                || !request.sha256().matches("[a-f0-9]{64}") || request.summary() == null
                || request.summary().artifactCount() < 1 || request.summary().sourceBytes() < 1
                || request.summary().eventCount() < 0 || request.summary().networkCount() < 0
                || request.summary().byKind() == null || request.summary().byKind().size() > 8
                || request.summary().byKind().entrySet().stream().anyMatch(e ->
                    !java.util.Set.of("SCREENSHOT", "TRACE", "VIDEO", "FRAME", "MANIFEST").contains(e.getKey())
                    || e.getValue() == null || e.getValue() < 0)) throw new BusinessException("证据压缩包或摘要字段无效");
        var uploaded = captures.evidence(projectId, sessionId, user);
        Map<String, Integer> kinds = new java.util.HashMap<>();
        uploaded.forEach(item -> kinds.merge(item.kind(), 1, Integer::sum));
        if (uploaded.size() != request.summary().artifactCount()
                || uploaded.stream().mapToLong(DshCaptureService.Evidence::sizeBytes).sum() != request.summary().sourceBytes()
                || !kinds.equals(request.summary().byKind()))
            throw new BusinessException("证据压缩包摘要与已上传原始文件不一致");
        Path dir = staging(projectId, sessionId, key);
        Path finalDir = root.resolve(String.valueOf(projectId)).resolve(String.valueOf(sessionId));
        try {
            Files.createDirectories(finalDir);
            Path temporary = Files.createTempFile(finalDir, "bundle-", ".tmp");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0;
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    for (int index = 0; index < request.chunkCount(); index++) {
                        Path part = dir.resolve(String.format("%04d.part", index));
                        if (!Files.isRegularFile(part)) throw new BusinessException("证据分段不完整");
                        try (InputStream in = new DigestInputStream(Files.newInputStream(part), digest)) {
                            total += in.transferTo(out);
                        }
                        if (total > MAX_BUNDLE_BYTES) throw new BusinessException("证据压缩包超过 1GB");
                    }
                }
                if (total != request.sizeBytes() || !HexFormat.of().formatHex(digest.digest()).equals(request.sha256()))
                    throw new BusinessException("证据压缩包大小或 SHA-256 不匹配");
                try (InputStream in = Files.newInputStream(temporary)) {
                    if (in.read() != 0x1f || in.read() != 0x8b) throw new BusinessException("证据压缩包不是 gzip 格式");
                }
                Path target = finalDir.resolve("capture.tar.gz");
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                template.update("""
                    INSERT INTO dsh_capture_bundle(project_id, session_id, request_key, storage_path,
                        size_bytes, sha256, summary_json, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, projectId, sessionId, key.toString(), target.toString(), total, request.sha256(),
                    mapper.writeValueAsString(request.summary()), user.id(), clock.now());
                try (var paths = Files.walk(dir)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                    });
                } catch (IOException ignored) { }
                return find(projectId, sessionId);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException | NoSuchAlgorithmException error) { throw new BusinessException("证据压缩包组装失败"); }
    }

    public Bundle get(Long projectId, Long sessionId, CurrentUser user) {
        captures.run(projectId, sessionId, user);
        Bundle value = find(projectId, sessionId);
        if (value == null) throw new BusinessException("证据压缩包不存在");
        return value;
    }

    public Path file(Long projectId, Long sessionId, CurrentUser user) {
        get(projectId, sessionId, user);
        Path path = root.resolve(String.valueOf(projectId)).resolve(String.valueOf(sessionId)).resolve("capture.tar.gz");
        if (!Files.isRegularFile(path)) throw new BusinessException("证据压缩包文件不存在");
        return path;
    }

    private Bundle find(Long projectId, Long sessionId) {
        return jdbc.sql("SELECT * FROM dsh_capture_bundle WHERE project_id = :projectId AND session_id = :sessionId")
                .param("projectId", projectId).param("sessionId", sessionId)
                .query((rs, row) -> {
                    try { return new Bundle(rs.getLong("id"), rs.getLong("session_id"), rs.getLong("size_bytes"),
                            rs.getString("sha256"), mapper.readValue(rs.getString("summary_json"), Summary.class),
                            rs.getTimestamp("created_at").toLocalDateTime()); }
                    catch (JsonProcessingException error) { throw new IllegalStateException(error); }
                }).optional().orElse(null);
    }

    private Path staging(Long projectId, Long sessionId, UUID key) {
        return root.resolve(String.valueOf(projectId)).resolve(String.valueOf(sessionId)).resolve(key.toString());
    }

    private UUID key(String value) {
        try { return UUID.fromString(value); }
        catch (Exception error) { throw new BusinessException("证据压缩包请求键必须是 UUID"); }
    }
}
