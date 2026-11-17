package com.company.aitest.yuque;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.file.FileResourceRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class YuqueSyncTaskService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final YuqueManifestParser manifestParser = new YuqueManifestParser();

    public YuqueSyncTaskService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    public List<YuqueSyncTaskRecord> list(Long projectId) {
        return jdbc.sql("select * from yuque_sync_task where project_id = :projectId order by id desc")
                .param("projectId", projectId)
                .query(this::map)
                .list();
    }

    public YuqueSyncTaskRecord create(Long projectId, CreateYuqueSyncTaskCommand command, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        int totalDocCount = resolveDocCount(projectId, command.exportManifestRef());
        jdbcTemplate.update("""
                insert into yuque_sync_task(project_id, yuque_config_id, sync_mode, status, export_manifest_ref,
                                            total_doc_count, skipped_doc_count, success_doc_count, failed_doc_count,
                                            error_message, started_at, finished_at, created_by, created_at, updated_at)
                values (?, ?, ?, 'PENDING', ?, ?, 0, 0, 0, null, null, null, ?, ?, ?)
                """,
                projectId,
                command.yuqueConfigId(),
                command.syncMode(),
                command.exportManifestRef(),
                totalDocCount,
                user.id(),
                now,
                now
        );
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return jdbc.sql("select * from yuque_sync_task where id = :id").param("id", id).query(this::map).single();
    }

    private int resolveDocCount(Long projectId, String exportManifestRef) {
        if (exportManifestRef == null || exportManifestRef.isBlank()) {
            return 0;
        }
        FileResourceRecord file = jdbc.sql("""
                select * from file_resource
                where project_id = :projectId and storage_path = :storagePath
                order by id desc limit 1
                """)
                .param("projectId", projectId)
                .param("storagePath", exportManifestRef)
                .query(this::mapFileResource)
                .optional()
                .orElse(null);
        if (file == null || !"YUQUE_MANIFEST".equals(file.fileType())) {
            return 0;
        }
        try {
            String json = Files.readString(Path.of(file.storagePath()));
            YuqueExportManifest manifest = manifestParser.parse(json);
            return manifest.docs().size();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("读取 manifest 失败: " + e.getMessage());
        }
    }

    private FileResourceRecord mapFileResource(ResultSet rs, int rowNum) throws SQLException {
        return new FileResourceRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("file_name"),
                rs.getString("file_type"),
                rs.getString("storage_type"),
                rs.getString("storage_path"),
                rs.getString("content_hash"),
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    private YuqueSyncTaskRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new YuqueSyncTaskRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getLong("yuque_config_id"),
                rs.getString("sync_mode"),
                rs.getString("status"),
                rs.getString("export_manifest_ref"),
                rs.getInt("total_doc_count"),
                rs.getInt("skipped_doc_count"),
                rs.getInt("success_doc_count"),
                rs.getInt("failed_doc_count"),
                rs.getString("error_message"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime(),
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    public record CreateYuqueSyncTaskCommand(Long yuqueConfigId, String syncMode, String exportManifestRef) {
    }
}
