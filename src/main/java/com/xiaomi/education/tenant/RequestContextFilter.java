package com.xiaomi.education.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component("tenantRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var traceId = headerOrDefault(request, "X-Trace-Id", UUID.randomUUID().toString());
        var tenantId = headerOrDefault(request, "X-Tenant-Id", "tenant-demo");
        var userId = headerOrDefault(request, "X-User-Id", "learner-001");
        var role = headerOrDefault(request, "X-User-Role", "LEARNER");
        TenantContext.set(new TenantContext(tenantId, userId, role));
        MDC.put("traceId", traceId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            TenantContext.clear();
        }
    }

    private String headerOrDefault(HttpServletRequest request, String name, String defaultValue) {
        var value = request.getHeader(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
