package com.xiaomi.education.ai.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AiAuditRecord(
        String id,
        String traceId,
        String tenantId,
        String userId,
        String scenario,
        String provider,
        String model,
        String promptTemplate,
        String promptVersion,
        String promptHash,
        String promptPreview,
        String responseHash,
        int inputTokens,
        int outputTokens,
        int cachedTokens,
        BigDecimal estimatedCost,
        long latencyMs,
        String status,
        String riskLevel,
        List<String> riskCodes,
        String errorCode,
        Instant createdAt
) {
}
