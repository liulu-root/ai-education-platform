package com.xiaomi.education.ai;

import com.xiaomi.education.ai.audit.AiAuditRecord;
import com.xiaomi.education.ai.audit.AiAuditService;
import com.xiaomi.education.ai.audit.AiCallResult;
import com.xiaomi.education.ai.audit.TokenBudgetService;
import com.xiaomi.education.ai.guardrail.GuardrailService;
import com.xiaomi.education.ai.middleware.PostModelMiddleware;
import com.xiaomi.education.ai.model.AiModelClient;
import com.xiaomi.education.ai.model.AiModelRequest;
import com.xiaomi.education.ai.model.AiModelResponse;
import com.xiaomi.education.ai.model.TokenEstimator;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.common.Hashing;
import com.xiaomi.education.config.AiProperties;
import com.xiaomi.education.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);
    private static final BigDecimal INPUT_COST_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal OUTPUT_COST_PER_MILLION = new BigDecimal("3.20");

    private final AiModelClient modelClient;
    private final GuardrailService guardrailService;
    private final PostModelMiddleware postModelMiddleware;
    private final TokenBudgetService budgetService;
    private final AiAuditService auditService;
    private final AiProperties properties;
    private final MeterRegistry meterRegistry;
    private final TokenEstimator tokenEstimator;

    public AiGateway(
            AiModelClient modelClient,
            GuardrailService guardrailService,
            PostModelMiddleware postModelMiddleware,
            TokenBudgetService budgetService,
            AiAuditService auditService,
            AiProperties properties,
            MeterRegistry meterRegistry,
            TokenEstimator tokenEstimator
    ) {
        this.modelClient = modelClient;
        this.guardrailService = guardrailService;
        this.postModelMiddleware = postModelMiddleware;
        this.budgetService = budgetService;
        this.auditService = auditService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.tokenEstimator = tokenEstimator;
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
        AiModelResponse response = null;
        try {
            response = modelClient.generate(safeRequest);
            var processed = postModelMiddleware.process(request.scenario(), response.content());
            var riskCodes = mergeRiskCodes(guarded.riskCodes(), processed.riskCodes());
            var latencyMs = (System.nanoTime() - started) / 1_000_000;
            var cost = estimateCost(response.inputTokens(), response.outputTokens());
            auditService.record(auditRecord(request, traceId, tenant, promptHash, preview(guarded.sanitizedText()),
                    Hashing.sha256(processed.content()), response.inputTokens(), response.outputTokens(),
                    response.cachedTokens(), cost, latencyMs, "SUCCESS", guarded.riskLevel(), riskCodes, null));
            meterRegistry.counter("edu.ai.requests", "scenario", request.scenario().name(), "status", "success").increment();
            meterRegistry.counter("edu.ai.tokens", "type", "input", "model", response.model()).increment(response.inputTokens());
            meterRegistry.counter("edu.ai.tokens", "type", "output", "model", response.model()).increment(response.outputTokens());
            return new AiCallResult(processed.content(), traceId, response.provider(), response.model(),
                    response.inputTokens(), response.outputTokens(), response.cachedTokens(), cost, latencyMs,
                    riskCodes, processed.checks());
        } catch (RuntimeException exception) {
            var latencyMs = (System.nanoTime() - started) / 1_000_000;
            var apiException = exception instanceof ApiException value ? value : null;
            var errorCode = apiException == null ? "MODEL_CALL_FAILED" : apiException.code();
            var outputAvailable = response != null;
            var outputPolicyBlocked = outputAvailable && "MODEL_OUTPUT_POLICY_BLOCKED".equals(errorCode);
            var outputCodes = outputAvailable && apiException != null ? apiException.details() : List.<String>of();
            var riskCodes = mergeRiskCodes(guarded.riskCodes(), outputCodes);
            var status = outputPolicyBlocked ? "BLOCKED" : "FAILED";
            var inputTokens = outputAvailable ? response.inputTokens() : 0;
            var outputTokens = outputAvailable ? response.outputTokens() : 0;
            var cachedTokens = outputAvailable ? response.cachedTokens() : 0;
            var cost = outputAvailable ? estimateCost(inputTokens, outputTokens) : BigDecimal.ZERO;
            var failed = auditRecord(
                    request,
                    traceId,
                    tenant,
                    promptHash,
                    preview(guarded.sanitizedText()),
                    outputAvailable ? Hashing.sha256(response.content()) : null,
                    inputTokens,
                    outputTokens,
                    cachedTokens,
                    cost,
                    latencyMs,
                    status,
                    outputPolicyBlocked ? "HIGH" : guarded.riskLevel(),
                    riskCodes,
                    errorCode
            );
            auditService.record(failed);
            if (outputPolicyBlocked) {
                var violations = outputCodes.isEmpty() ? List.of("MODEL_OUTPUT_POLICY_BLOCKED") : outputCodes;
                violations.forEach(code -> auditService.recordViolation(
                        failed,
                        code.startsWith("OUTPUT_") ? code : "OUTPUT_" + code
                ));
            }
            if (outputAvailable) {
                meterRegistry.counter("edu.ai.tokens", "type", "input", "model", response.model())
                        .increment(inputTokens);
                meterRegistry.counter("edu.ai.tokens", "type", "output", "model", response.model())
                        .increment(outputTokens);
            }
            meterRegistry.counter(
                    "edu.ai.requests",
                    "scenario",
                    request.scenario().name(),
                    "status",
                    status.toLowerCase()
            ).increment();
            throw exception;
        }
    }

    public AiStreamingCall executeStream(AiModelRequest request, Consumer<String> onDelta) {
        if (postModelMiddleware.requiresBufferedOutput(request.scenario())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "STREAMING_NOT_SUPPORTED_FOR_SCENARIO",
                    "该 AI 场景必须在完整输出通过安全检查后才能返回，不支持流式调用"
            );
        }
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
        var safeRequest = new AiModelRequest(
                request.scenario(),
                request.systemPrompt(),
                guarded.sanitizedText(),
                request.metadata()
        );
        var started = System.nanoTime();
        var terminal = new AtomicBoolean();
        var completion = new CompletableFuture<AiCallResult>();
        var partialContent = new StringBuilder();
        Consumer<String> trackedDelta = delta -> {
            synchronized (partialContent) {
                partialContent.append(delta);
            }
            onDelta.accept(delta);
        };

        final com.xiaomi.education.ai.model.AiModelStream modelStream;
        try {
            modelStream = modelClient.generateStream(safeRequest, trackedDelta);
        } catch (RuntimeException exception) {
            recordStreamFailure(request, traceId, tenant, promptHash, guarded, started, exception);
            throw exception;
        }

        modelStream.completion().whenComplete((response, exception) -> {
            var cause = exception == null ? null : unwrap(exception);
            if (cause == null) {
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
                try {
                    var processed = postModelMiddleware.process(request.scenario(), response.content());
                    var riskCodes = mergeRiskCodes(guarded.riskCodes(), processed.riskCodes());
                    var latencyMs = elapsedMillis(started);
                    var cost = estimateCost(response.inputTokens(), response.outputTokens());
                    auditService.record(auditRecord(
                            request, traceId, tenant, promptHash, preview(guarded.sanitizedText()),
                            Hashing.sha256(processed.content()), response.inputTokens(), response.outputTokens(),
                            response.cachedTokens(), cost, latencyMs, "SUCCESS", guarded.riskLevel(),
                            riskCodes, null
                    ));
                    meterRegistry.counter("edu.ai.requests", "scenario", request.scenario().name(),
                            "status", "success").increment();
                    meterRegistry.counter("edu.ai.tokens", "type", "input", "model", response.model())
                            .increment(response.inputTokens());
                    meterRegistry.counter("edu.ai.tokens", "type", "output", "model", response.model())
                            .increment(response.outputTokens());
                    completion.complete(new AiCallResult(
                            processed.content(), traceId, response.provider(), response.model(),
                            response.inputTokens(), response.outputTokens(), response.cachedTokens(), cost,
                            latencyMs, riskCodes, processed.checks()
                    ));
                } catch (RuntimeException completionException) {
                    recordStreamFailure(
                            request, traceId, tenant, promptHash, guarded, started, completionException
                    );
                    completion.completeExceptionally(completionException);
                }
                return;
            }

            if (cause instanceof CancellationException) {
                if (terminal.compareAndSet(false, true)) {
                    recordStreamCancellation(request, traceId, tenant, promptHash, guarded, promptMaterial,
                            partialContent, started, "MODEL_STREAM_CANCELLED");
                }
                completion.cancel(false);
                return;
            }

            if (terminal.compareAndSet(false, true)) {
                recordStreamFailure(request, traceId, tenant, promptHash, guarded, started, cause);
                completion.completeExceptionally(cause);
            }
        });

        return new AiStreamingCall() {
            @Override
            public CompletableFuture<AiCallResult> completion() {
                return completion;
            }

            @Override
            public void cancel() {
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
                try {
                    modelStream.cancel();
                } finally {
                    try {
                        recordStreamCancellation(
                                request, traceId, tenant, promptHash, guarded, promptMaterial,
                                partialContent, started, "CLIENT_DISCONNECTED"
                        );
                    } finally {
                        completion.cancel(false);
                    }
                }
            }
        };
    }

    private void recordStreamFailure(
            AiModelRequest request,
            String traceId,
            TenantContext tenant,
            String promptHash,
            GuardrailService.GuardrailResult guarded,
            long started,
            Throwable exception
    ) {
        try {
            var errorCode = exception instanceof ApiException apiException
                    ? apiException.code()
                    : "MODEL_CALL_FAILED";
            auditService.record(auditRecord(
                    request, traceId, tenant, promptHash, preview(guarded.sanitizedText()), null,
                    0, 0, 0, BigDecimal.ZERO, elapsedMillis(started), "FAILED",
                    guarded.riskLevel(), guarded.riskCodes(), errorCode
            ));
            meterRegistry.counter("edu.ai.requests", "scenario", request.scenario().name(),
                    "status", "failed").increment();
        } catch (RuntimeException auditException) {
            log.warn("Unable to record failed AI stream, traceId={}", traceId, auditException);
        }
    }

    private void recordStreamCancellation(
            AiModelRequest request,
            String traceId,
            TenantContext tenant,
            String promptHash,
            GuardrailService.GuardrailResult guarded,
            String promptMaterial,
            StringBuilder partialContent,
            long started,
            String errorCode
    ) {
        try {
            final String content;
            synchronized (partialContent) {
                content = partialContent.toString();
            }
            var inputTokens = tokenEstimator.estimate(promptMaterial);
            var outputTokens = tokenEstimator.estimate(content);
            auditService.record(auditRecord(
                    request, traceId, tenant, promptHash, preview(guarded.sanitizedText()),
                    content.isEmpty() ? null : Hashing.sha256(content),
                    inputTokens, outputTokens, 0, estimateCost(inputTokens, outputTokens), elapsedMillis(started),
                    "CANCELLED", guarded.riskLevel(), guarded.riskCodes(), errorCode
            ));
            meterRegistry.counter("edu.ai.requests", "scenario", request.scenario().name(),
                    "status", "cancelled").increment();
        } catch (RuntimeException exception) {
            log.warn("Unable to record cancelled AI stream, traceId={}", traceId, exception);
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private Throwable unwrap(Throwable exception) {
        var current = exception;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private List<String> mergeRiskCodes(List<String> inputCodes, List<String> outputCodes) {
        var merged = new LinkedHashSet<String>();
        merged.addAll(inputCodes);
        merged.addAll(outputCodes);
        return List.copyOf(merged);
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
