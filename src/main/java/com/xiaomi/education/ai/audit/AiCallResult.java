package com.xiaomi.education.ai.audit;

import java.math.BigDecimal;
import java.util.List;

public record AiCallResult(
        String content,
        String traceId,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        int cachedTokens,
        BigDecimal estimatedCost,
        long latencyMs,
        List<String> riskCodes
) {
}
