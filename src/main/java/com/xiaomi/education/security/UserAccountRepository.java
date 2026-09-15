package com.xiaomi.education.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserAccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AuthenticatedUser> findActiveByEmail(String email) {
        return jdbcTemplate.query("""
                        SELECT u.id, u.tenant_id, u.display_name, u.email, u.role, u.password_hash
                        FROM app_user u
                        JOIN tenant t ON t.id = u.tenant_id
                        WHERE LOWER(u.email) = LOWER(?)
                          AND u.status = 'ACTIVE'
                          AND u.password_hash IS NOT NULL
                          AND t.status = 'ACTIVE'
                        """,
                (resultSet, rowNumber) -> new AuthenticatedUser(
                        resultSet.getString("id"),
                        resultSet.getString("tenant_id"),
                        resultSet.getString("display_name"),
                        resultSet.getString("email"),
                        UserRole.valueOf(resultSet.getString("role")),
                        resultSet.getString("password_hash")
                ),
                email
        ).stream().findFirst();
    }
}
