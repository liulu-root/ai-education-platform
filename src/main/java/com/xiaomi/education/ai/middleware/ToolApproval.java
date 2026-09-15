package com.xiaomi.education.ai.middleware;

import com.xiaomi.education.ai.model.AiScenario;

import java.time.Instant;
import java.util.Map;

public record ToolApproval(
        String id,
        String tenantId,
        AiScenario scenario,
        String toolName,
        String resourceType,
        String resourceId,
        Map<String, String> arguments,
        ToolApprovalStatus status,
        String requestedBy,
        Instant requestedAt,
        String decidedBy,
        Instant decidedAt,
        String comment
) {
    public ToolApproval {
        arguments = Map.copyOf(arguments);
    }
}
