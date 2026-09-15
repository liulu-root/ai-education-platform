package com.xiaomi.education.ai.middleware;

import com.xiaomi.education.ai.model.AiScenario;

import java.util.Map;
import java.util.Objects;

public record SensitiveToolInvocation(
        AiScenario scenario,
        String toolName,
        String resourceType,
        String resourceId,
        Map<String, String> arguments
) {
    public SensitiveToolInvocation {
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(toolName);
        Objects.requireNonNull(resourceType);
        Objects.requireNonNull(resourceId);
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
