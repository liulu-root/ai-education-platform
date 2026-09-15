package com.xiaomi.education.ai.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiAuditService {

    private final JdbcTemplate jdbcTemplate;

    public AiAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void record(AiAuditRecord event) {
        jdbcTemplate.update("""
                        INSERT INTO ai_call_audit (
                            id, trace_id, tenant_id, user_id, scenario, provider, model,
                            prompt_template, prompt_version, prompt_hash, prompt_preview, response_hash,
                            input_tokens, output_tokens, cached_tokens, estimated_cost, latency_ms,
                            status, risk_level, risk_codes, error_code, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.id(), event.traceId(), event.tenantId(), event.userId(), event.scenario(),
                event.provider(), event.model(), event.promptTemplate(), event.promptVersion(),
                event.promptHash(), event.promptPreview(), event.responseHash(), event.inputTokens(),
                event.outputTokens(), event.cachedTokens(), event.estimatedCost(), event.latencyMs(),
                event.status(), event.riskLevel(), String.join(",", event.riskCodes()), event.errorCode(),
                Timestamp.from(event.createdAt())
        );
        if ("SUCCESS".equals(event.status()) || event.inputTokens() > 0 || event.outputTokens() > 0) {
            accumulateDailyUsage(event);
        }
    }

    public void recordViolation(AiAuditRecord event, String violationType) {
        jdbcTemplate.update("""
                        INSERT INTO policy_violation (
                            id, trace_id, tenant_id, user_id, violation_type, severity, action, evidence_hash, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID().toString(), event.traceId(), event.tenantId(), event.userId(),
                violationType, event.riskLevel(), "BLOCK",
                event.responseHash() == null ? event.promptHash() : event.responseHash(),
                Timestamp.from(event.createdAt())
        );
    }

    public List<Map<String, Object>> recentEvents(String tenantId, int limit) {
        return jdbcTemplate.queryForList("""
                        SELECT trace_id, user_id, scenario, provider, model, input_tokens, output_tokens,
                               cached_tokens, estimated_cost, latency_ms, status, risk_level, risk_codes, created_at
                        FROM ai_call_audit
                        WHERE tenant_id = ?
                        ORDER BY created_at DESC
                        LIMIT ?
                        """, tenantId, Math.min(Math.max(limit, 1), 200));
    }

    public List<Map<String, Object>> tokenUsage(String tenantId, int days) {
        return jdbcTemplate.queryForList("""
                        SELECT usage_date, scenario, model,
                               SUM(input_tokens) AS input_tokens,
                               SUM(output_tokens) AS output_tokens,
                               SUM(cached_tokens) AS cached_tokens,
                               SUM(request_count) AS request_count,
                               SUM(estimated_cost) AS estimated_cost
                        FROM ai_token_usage_daily
                        WHERE tenant_id = ? AND usage_date >= ?
                        GROUP BY usage_date, scenario, model
                        ORDER BY usage_date DESC, scenario
                        """, tenantId, LocalDate.now().minusDays(Math.min(Math.max(days, 1), 365) - 1L));
    }

    public Map<String, Object> overview(String tenantId, int days) {
        var start = Timestamp.valueOf(LocalDate.now().minusDays(Math.min(Math.max(days, 1), 365) - 1L).atStartOfDay());
        return jdbcTemplate.queryForMap("""
                        SELECT COUNT(*) AS request_count,
                               COALESCE(SUM(input_tokens), 0) AS input_tokens,
                               COALESCE(SUM(output_tokens), 0) AS output_tokens,
                               COALESCE(SUM(estimated_cost), 0) AS estimated_cost,
                               COALESCE(AVG(latency_ms), 0) AS average_latency_ms,
                               COALESCE(SUM(CASE WHEN status = 'BLOCKED' THEN 1 ELSE 0 END), 0) AS blocked_count,
                               COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_count
                        FROM ai_call_audit
                        WHERE tenant_id = ? AND created_at >= ?
                        """, tenantId, start);
    }

    private void accumulateDailyUsage(AiAuditRecord event) {
        var date = LocalDate.now();
        var updated = jdbcTemplate.update("""
                        UPDATE ai_token_usage_daily
                        SET input_tokens = input_tokens + ?, output_tokens = output_tokens + ?,
                            cached_tokens = cached_tokens + ?, request_count = request_count + 1,
                            estimated_cost = estimated_cost + ?
                        WHERE usage_date = ? AND tenant_id = ? AND user_id = ? AND scenario = ? AND model = ?
                        """, event.inputTokens(), event.outputTokens(), event.cachedTokens(), event.estimatedCost(),
                date, event.tenantId(), event.userId(), event.scenario(), event.model());
        if (updated == 0) {
            jdbcTemplate.update("""
                            INSERT INTO ai_token_usage_daily (
                                usage_date, tenant_id, user_id, scenario, model,
                                input_tokens, output_tokens, cached_tokens, request_count, estimated_cost
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                            """, date, event.tenantId(), event.userId(), event.scenario(), event.model(),
                    event.inputTokens(), event.outputTokens(), event.cachedTokens(), event.estimatedCost());
        }
    }
}
