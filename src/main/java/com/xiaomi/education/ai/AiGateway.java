package com.xiaomi.education.ai;

import com.xiaomi.education.ai.audit.AiAuditRecord;
import com.xiaomi.education.ai.audit.AiAuditService;
import com.xiaomi.education.ai.audit.AiCallResult;
import com.xiaomi.education.ai.audit.TokenBudgetService;
import com.xiaomi.education.ai.guardrail.GuardrailService;
import com.xiaomi.education.ai.model.AiModelClient;
import com.xiaomi.education.ai.model.AiModelRequest;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.common.Hashing;
import com.xiaomi.education.config.AiProperties;
import com.xiaomi.education.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AiGateway {

    private static final BigDecimal INPUT_COST_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal OUTPUT_COST_PER_MILLION = new BigDecimal("3.20");

    private final AiModelClient modelClient;
    private final GuardrailService guardrailService;
    private final TokenBudgetService budgetService;
    private final AiAuditService auditService;
    private final AiProperties properties;
    private final MeterRegistry meterRegistry;

    public AiGateway(
            AiModelClient modelClient,
            GuardrailService guardrailService,
            TokenBudgetService budgetService,
            AiAuditService auditService,
            AiProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.modelClient = modelClient;
        this.guardrailService = guardrailService;
        this.budgetService = budgetService;
        this.auditService = auditService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public AiCallResult execute(AiModelRequest request) {
        var tenant = TenantContext.current();
        var traceId = MDC.get("traceId") == null ? UUID.randomUUID().toString() : MDC.get("traceId");
        var guarded = guardrailService.inspect(request.userPrompt());
        var promptMaterial = request.systemPrompt() + "\n" + request.userPrompt();
        var promptHash = Hashing.sha256(promptMaterial);

        if (!guarded.allowed()) {
            var blocked = auditRecord(request, traceId, tenant, promptHash, preview(guarded.sanitizedText()), null,
                    0, 0, 0, BigDecimal.ZERO, 0, "BLOCKED", guarded.riskLevel(), guarded.riskCodes(),
                    "POLICY_BLOCKED");
            auditService.record(blocked);
            guarded.riskCodes().forEach(code -> auditService.recordViolation(blocked, code));
            meterRegistry.counter("edu.ai.requests", "scenario", request.scenario().name(), "status", "blocked").increment();
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_POLICY_BLOCKED",
                    "请求触发 AI 安全策略，已阻断并记录审计事件", guarded.riskCodes());
        }

        budgetService.assertAvailable(tenant.tenantId());
        var safeRequest = new AiModelRequest(request.scenario(), request.systemPrompt(), guarded.sanitizedText(), request.metadata());
        var started = System.nanoTime();
        try {
            var response = modelClient.generate(safeRequest);
            var latencyMs = (System.nanoTime() - started) / 1_000_000;
            var cost = estimateCost(response.inputTokens(), response.outputTokens());
            auditService.record(auditRecord(request, traceId, tenant, promptHash, preview(guarded.sanitizedText()),
                    Hashing.sha256(response.content()), response.inputTokens(), response.outputTokens(), response.cachedTokens(),
                    cost, latencyMs, "SUCCESS", guarded.riskLevel(), guarded.riskCodes(), null));
            meterRegistry.counter("edu.ai.requests", "scenario", request.scenario().name(), "status", "success").increment();
            meterRegistry.counter("edu.ai.tokens", "type", "input", "model", response.model()).increment(response.inputTokens());
            meterRegistry.counter("edu.ai.tokens", "type", "output", "model", response.model()).increment(response.outputTokens());
            return new AiCallResult(response.content(), traceId, response.provider(), response.model(),
                    response.inputTokens(), response.outputTokens(), response.cachedTokens(), cost, latencyMs,
                    guarded.riskCodes());
        } catch (RuntimeException exception) {
            var latencyMs = (System.nanoTime() - started) / 1_000_000;
            var errorCode = exception instanceof ApiException apiException ? apiException.code() : "MODEL_CALL_FAILED";
            auditService.record(auditRecord(request, traceId, tenant, promptHash, preview(guarded.sanitizedText()), null,
                    0, 0, 0, BigDecimal.ZERO, latencyMs, "FAILED", guarded.riskLevel(), guarded.riskCodes(), errorCode));
            meterRegistry.counter("edu.ai.requests", "scenario", request.scenario().name(), "status", "failed").increment();
            throw exception;
        }
    }

    private AiAuditRecord auditRecord(
            AiModelRequest request, String traceId, TenantContext tenant, String promptHash, String promptPreview,
            String responseHash, int inputTokens, int outputTokens, int cachedTokens, BigDecimal cost,
            long latencyMs, String status, String riskLevel, java.util.List<String> riskCodes, String errorCode
    ) {
        return new AiAuditRecord(UUID.randomUUID().toString(), traceId, tenant.tenantId(), tenant.userId(),
                request.scenario().name(), modelClient.provider(), modelClient.model(), request.scenario().promptTemplate(),
                request.scenario().promptVersion(), promptHash, promptPreview, responseHash, inputTokens, outputTokens,
                cachedTokens, cost, latencyMs, status, riskLevel, riskCodes, errorCode, Instant.now());
    }

    private BigDecimal estimateCost(int inputTokens, int outputTokens) {
        var input = INPUT_COST_PER_MILLION.multiply(BigDecimal.valueOf(inputTokens));
        var output = OUTPUT_COST_PER_MILLION.multiply(BigDecimal.valueOf(outputTokens));
        return input.add(output).divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }

    private String preview(String prompt) {
        if (!properties.storePromptPreview()) {
            return null;
        }
        var length = Math.max(0, Math.min(properties.promptPreviewLength(), prompt.length()));
        return prompt.substring(0, length);
    }
}
