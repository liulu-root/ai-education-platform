package com.xiaomi.education.ai.middleware;

import com.xiaomi.education.ai.model.AiScenario;
import com.xiaomi.education.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ToolApprovalRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ToolApprovalRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void create(ToolApproval approval) {
        jdbcTemplate.update("""
                        INSERT INTO ai_tool_approval (
                            id, tenant_id, scenario, tool_name, resource_type, resource_id,
                            arguments_json, status, requested_by, requested_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                approval.id(),
                approval.tenantId(),
                approval.scenario().name(),
                approval.toolName(),
                approval.resourceType(),
                approval.resourceId(),
                serializeArguments(approval.arguments()),
                approval.status().name(),
                approval.requestedBy(),
                Timestamp.from(approval.requestedAt())
        );
    }

    public ToolApproval findForUpdate(String tenantId, String approvalId) {
        var approvals = jdbcTemplate.query("""
                        SELECT id, tenant_id, scenario, tool_name, resource_type, resource_id,
                               arguments_json, status, requested_by, requested_at,
                               decided_by, decided_at, decision_comment
                        FROM ai_tool_approval
                        WHERE tenant_id = ? AND id = ?
                        FOR UPDATE
                        """, this::mapApproval, tenantId, approvalId);
        if (approvals.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", "审批记录不存在");
        }
        return approvals.getFirst();
    }

    public List<ToolApproval> list(String tenantId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, scenario, tool_name, resource_type, resource_id,
                               arguments_json, status, requested_by, requested_at,
                               decided_by, decided_at, decision_comment
                        FROM ai_tool_approval
                        WHERE tenant_id = ?
                        ORDER BY requested_at DESC, id DESC
                        """, this::mapApproval, tenantId);
    }

    public ToolApproval markDecision(
            ToolApproval approval,
            ToolApprovalStatus status,
            String decidedBy,
            Instant decidedAt,
            String comment
    ) {
        var updated = jdbcTemplate.update("""
                        UPDATE ai_tool_approval
                        SET status = ?, decided_by = ?, decided_at = ?, decision_comment = ?
                        WHERE tenant_id = ? AND id = ? AND status = ?
                        """,
                status.name(),
                decidedBy,
                Timestamp.from(decidedAt),
                normalizeComment(comment),
                approval.tenantId(),
                approval.id(),
                ToolApprovalStatus.PENDING_APPROVAL.name()
        );
        if (updated != 1) {
            throw new IllegalStateException("Approval state changed while holding its row lock");
        }
        // Always return the persisted representation. Database timestamp precision can differ
        // from Instant, so rebuilding the value in memory would make a retry's response differ
        // from the first successful decision even though the side effect is idempotent.
        return findForUpdate(approval.tenantId(), approval.id());
    }

    private ToolApproval mapApproval(ResultSet resultSet, int rowNum) throws SQLException {
        var decidedTimestamp = resultSet.getTimestamp("decided_at");
        return new ToolApproval(
                resultSet.getString("id"),
                resultSet.getString("tenant_id"),
                AiScenario.valueOf(resultSet.getString("scenario")),
                resultSet.getString("tool_name"),
                resultSet.getString("resource_type"),
                resultSet.getString("resource_id"),
                deserializeArguments(resultSet.getString("arguments_json")),
                ToolApprovalStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("requested_by"),
                resultSet.getTimestamp("requested_at").toInstant(),
                resultSet.getString("decided_by"),
                decidedTimestamp == null ? null : decidedTimestamp.toInstant(),
                resultSet.getString("decision_comment")
        );
    }

    private String serializeArguments(Map<String, String> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to serialize sensitive tool arguments", exception);
        }
    }

    private Map<String, String> deserializeArguments(String value) {
        try {
            Map<?, ?> raw = objectMapper.readValue(value, Map.class);
            var arguments = new LinkedHashMap<String, String>();
            raw.forEach((key, item) -> arguments.put(String.valueOf(key), String.valueOf(item)));
            return Map.copyOf(arguments);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to deserialize sensitive tool arguments", exception);
        }
    }

    private String normalizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        return comment.strip();
    }
}
