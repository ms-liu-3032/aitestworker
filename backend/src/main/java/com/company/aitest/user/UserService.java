package com.company.aitest.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final TimeProvider timeProvider;

    public UserService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.timeProvider = timeProvider;
    }

    public boolean hasAnyUser() {
        Integer count = jdbc.sql("select count(*) from sys_user").query(Integer.class).single();
        return count != null && count > 0;
    }

    @Transactional
    public UserRecord initAdmin(String username, String password, String displayName) {
        if (hasAnyUser()) {
            throw new BusinessException("管理员已初始化");
        }
        return createUser(username, password, displayName, "ADMIN");
    }

    public UserRecord login(String username, String password) {
        UserWithPassword user = jdbc.sql("select * from sys_user where username = :username and status = 'ACTIVE'")
                .param("username", username)
                .query(this::mapUserWithPassword)
                .optional()
                .orElseThrow(() -> new BusinessException("用户名或密码错误"));
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        return user.record();
    }

    @Transactional
    public UserRecord createUser(String username, String password, String displayName, String roleCode) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                insert into sys_user(username, password_hash, display_name, role_code, status, created_at, updated_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, username, passwordEncoder.encode(password), displayName, roleCode, now, now);
        return findByUsername(username);
    }

    public List<UserRecord> listUsers() {
        return jdbc.sql("select * from sys_user order by id desc").query(this::mapUser).list();
    }

    private UserRecord findByUsername(String username) {
        return jdbc.sql("select * from sys_user where username = :username")
                .param("username", username)
                .query(this::mapUser)
                .single();
    }

    private UserRecord mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserRecord(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("role_code"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private UserWithPassword mapUserWithPassword(ResultSet rs, int rowNum) throws SQLException {
        return new UserWithPassword(mapUser(rs, rowNum), rs.getString("password_hash"));
    }

    private record UserWithPassword(UserRecord record, String passwordHash) {
    }
}
