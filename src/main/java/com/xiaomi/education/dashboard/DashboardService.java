package com.xiaomi.education.dashboard;

import com.xiaomi.education.ai.audit.AiAuditService;
import com.xiaomi.education.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final JdbcTemplate jdbcTemplate;
    private final AiAuditService auditService;

    public DashboardService(JdbcTemplate jdbcTemplate, AiAuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    public Map<String, Object> overview() {
        var tenantId = TenantContext.current().tenantId();
        var result = new LinkedHashMap<String, Object>();
        result.put("tenant", jdbcTemplate.queryForMap("SELECT id, name, status, token_budget FROM tenant WHERE id = ?", tenantId));
        result.put("business", jdbcTemplate.queryForMap("""
                        SELECT (SELECT COUNT(*) FROM course WHERE tenant_id = ?) AS courses,
                               (SELECT COUNT(*) FROM enrollment WHERE tenant_id = ?) AS enrollments,
                               (SELECT COUNT(*) FROM enrollment WHERE tenant_id = ? AND status = 'AT_RISK') AS at_risk,
                               (SELECT COUNT(*) FROM knowledge_document WHERE tenant_id = ?) AS knowledge_documents,
                               (SELECT COUNT(*) FROM submission WHERE tenant_id = ?) AS submissions,
                               (SELECT COUNT(*) FROM learning_plan WHERE tenant_id = ?) AS learning_plans
                        """, tenantId, tenantId, tenantId, tenantId, tenantId, tenantId));
        result.put("ai", auditService.overview(tenantId, 30));
        return result;
    }

    public List<Map<String, Object>> courses() {
        return jdbcTemplate.queryForList("""
                        SELECT c.id, c.code, c.title, c.level, c.status,
                               COUNT(e.id) AS enrollments,
                               COALESCE(AVG(e.progress_percent), 0) AS average_progress
                        FROM course c LEFT JOIN enrollment e ON e.course_id = c.id
                        WHERE c.tenant_id = ?
                        GROUP BY c.id, c.code, c.title, c.level, c.status
                        ORDER BY c.code
                        """, TenantContext.current().tenantId());
    }

    public List<Map<String, Object>> assignments() {
        return jdbcTemplate.queryForList("""
                        SELECT id, course_id, title, instructions, max_score, due_at
                        FROM assignment WHERE tenant_id = ? ORDER BY due_at
                        """, TenantContext.current().tenantId());
    }
}
