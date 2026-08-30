package com.xiaomi.education.ai.audit;

import com.xiaomi.education.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai-audit")
public class AiAuditController {

    private final AiAuditService auditService;

    public AiAuditController(AiAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events(@RequestParam(defaultValue = "50") int limit) {
        return auditService.recentEvents(TenantContext.current().tenantId(), limit);
    }

    @GetMapping("/token-usage")
    public List<Map<String, Object>> tokenUsage(@RequestParam(defaultValue = "30") int days) {
        return auditService.tokenUsage(TenantContext.current().tenantId(), days);
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "30") int days) {
        return auditService.overview(TenantContext.current().tenantId(), days);
    }
}
