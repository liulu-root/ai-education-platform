package com.xiaomi.education.ai.audit;

import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.config.AiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class TokenBudgetService {

    private final JdbcTemplate jdbcTemplate;
    private final AiProperties properties;

    public TokenBudgetService(JdbcTemplate jdbcTemplate, AiProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public void assertAvailable(String tenantId) {
        var configuredBudget = jdbcTemplate.query(
                "SELECT token_budget FROM tenant WHERE id = ?",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : properties.monthlyTokenBudget(),
                tenantId
        );
        var monthStart = LocalDate.now().withDayOfMonth(1);
        var used = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM ai_token_usage_daily "
                        + "WHERE tenant_id = ? AND usage_date >= ?",
                Long.class,
                tenantId,
                monthStart
        );
        if (used != null && used >= configuredBudget) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOKEN_BUDGET_EXCEEDED", "本月 AI Token 预算已用完");
        }
    }
}
