package com.xiaomi.education.ai.model;

public record AiModelResponse(
        String content,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        int cachedTokens,
        String providerRequestId
) {
}
