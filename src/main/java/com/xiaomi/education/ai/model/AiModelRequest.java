package com.xiaomi.education.ai.model;

import java.util.Map;

public record AiModelRequest(
        AiScenario scenario,
        String systemPrompt,
        String userPrompt,
        Map<String, Object> metadata
) {
    public AiModelRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
