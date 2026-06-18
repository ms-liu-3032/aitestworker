package com.company.aitest.generation.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class GenerationMessageService {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public GenerationMessageService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    public GenerationMessageRecord appendUserMessage(Long sessionId, String content, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO generation_message(session_id, role, content, analysis_version, created_at)
                VALUES (?, 'USER', ?, 0, ?)
                """, sessionId, content, now);
        Long id = jdbc.sql("SELECT last_insert_id()").query(Long.class).single();
        return getById(id);
    }

    public GenerationMessageRecord appendAssistantMessage(Long sessionId, String content,
                                                           String structuredPayload, String stage, int analysisVersion) {
        LocalDateTime now = timeProvider.now();
        String normalizedPayload = normalizeStructuredPayload(structuredPayload);
        jdbcTemplate.update("""
                INSERT INTO generation_message(session_id, role, content, structured_payload, analysis_version, stage, created_at)
                VALUES (?, 'ASSISTANT', ?, ?, ?, ?, ?)
                """, sessionId, content, normalizedPayload, analysisVersion, stage, now);
        Long id = jdbc.sql("SELECT last_insert_id()").query(Long.class).single();
        return getById(id);
    }

    public GenerationMessageRecord appendSystemMessage(Long sessionId, String content) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO generation_message(session_id, role, content, analysis_version, created_at)
                VALUES (?, 'SYSTEM', ?, 0, ?)
                """, sessionId, content, now);
        Long id = jdbc.sql("SELECT last_insert_id()").query(Long.class).single();
        return getById(id);
    }

    public List<GenerationMessageRecord> listMessages(Long sessionId) {
        return jdbc.sql("SELECT * FROM generation_message WHERE session_id = :sid ORDER BY id ASC")
                .param("sid", sessionId).query(this::map).list();
    }

    public List<GenerationMessageRecord> listMessagesSince(Long sessionId, Long sinceId) {
        return jdbc.sql("SELECT * FROM generation_message WHERE session_id = :sid AND id > :sinceId ORDER BY id ASC")
                .param("sid", sessionId).param("sinceId", sinceId).query(this::map).list();
    }

    private GenerationMessageRecord getById(Long id) {
        return jdbc.sql("SELECT * FROM generation_message WHERE id = :id").param("id", id).query(this::map).single();
    }

    static String normalizeStructuredPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(payload));
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private GenerationMessageRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new GenerationMessageRecord(
                rs.getLong("id"), rs.getLong("session_id"), rs.getString("role"),
                rs.getString("content"), rs.getString("structured_payload"),
                rs.getInt("analysis_version"), rs.getString("stage"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}
