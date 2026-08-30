package com.xiaomi.education.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edu.ai")
public record AiProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String chatModel,
        double temperature,
        int timeoutSeconds,
        long monthlyTokenBudget,
        boolean storePromptPreview,
        int promptPreviewLength
) {
}
