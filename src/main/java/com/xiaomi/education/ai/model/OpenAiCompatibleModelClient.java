package com.xiaomi.education.ai.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.config.AiProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(prefix = "edu.ai", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleModelClient implements AiModelClient {

    private final AiProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final TokenEstimator tokenEstimator;
    private final HttpClient httpClient;
    private final ExecutorService streamExecutor;

    public OpenAiCompatibleModelClient(
            AiProperties properties,
            ObjectMapper objectMapper,
            TokenEstimator tokenEstimator
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.tokenEstimator = tokenEstimator;
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("AI_API_KEY is required for provider=openai-compatible");
        }
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.timeoutSeconds())))
                .build();
        this.streamExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("openai-model-stream-", 0).factory()
        );
    }

    @Override
    public AiModelResponse generate(AiModelRequest request) {
        var body = new java.util.HashMap<String, Object>(Map.of(
                "model", properties.chatModel(),
                "temperature", properties.temperature(),
                "messages", List.of(
                        Map.of("role", "system", "content", request.systemPrompt()),
                        Map.of("role", "user", "content", request.userPrompt())
                )
        ));
        addOutputLimit(body, request);
        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/v1/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            throw providerError(exception.getStatusCode().value(), exception.getResponseBodyAsString());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_PROVIDER_ERROR", "模型服务调用失败");
        }
        if (response == null || response.path("choices").isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_MODEL_RESPONSE", "模型服务返回了无效响应");
        }
        var usage = response.path("usage");
        return new AiModelResponse(
                response.path("choices").path(0).path("message").path("content").asText(),
                provider(),
                response.path("model").asText(properties.chatModel()),
                usage.path("prompt_tokens").asInt(),
                usage.path("completion_tokens").asInt(),
                usage.path("prompt_tokens_details").path("cached_tokens").asInt(),
                response.path("id").asText()
        );
    }

    @Override
    public AiModelStream generateStream(AiModelRequest request, Consumer<String> onDelta) {
        var body = new java.util.HashMap<String, Object>(Map.of(
                "model", properties.chatModel(),
                "temperature", properties.temperature(),
                "stream", true,
                "stream_options", Map.of("include_usage", true),
                "messages", List.of(
                        Map.of("role", "system", "content", request.systemPrompt()),
                        Map.of("role", "user", "content", request.userPrompt())
                )
        ));
        addOutputLimit(body, request);
        var httpRequest = HttpRequest.newBuilder(chatCompletionsUri())
                .timeout(Duration.ofSeconds(Math.max(1, properties.timeoutSeconds())))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.ACCEPT, "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
                .build();

        var cancelled = new AtomicBoolean();
        var responseLines = new AtomicReference<Stream<String>>();
        var responseFuture = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofLines());
        responseFuture.whenComplete((response, exception) -> {
            if (response == null) {
                return;
            }
            responseLines.compareAndSet(null, response.body());
            if (cancelled.get()) {
                closeResponseLines(responseLines);
            }
        });
        var rawCompletion = responseFuture.thenApplyAsync(response ->
                readStreamingResponse(request, response, responseLines, cancelled, onDelta), streamExecutor);
        CompletableFuture<AiModelResponse> completion = rawCompletion.handle((response, exception) -> {
            if (exception == null) {
                return response;
            }
            var cause = unwrap(exception);
            if (cause instanceof CancellationException cancellationException) {
                throw cancellationException;
            }
            if (cause instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_PROVIDER_ERROR", "模型服务流式调用失败");
        });

        return new AiModelStream() {
            @Override
            public CompletableFuture<AiModelResponse> completion() {
                return completion;
            }

            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    responseFuture.cancel(true);
                    closeResponseLines(responseLines);
                    rawCompletion.cancel(true);
                    completion.cancel(true);
                }
            }
        };
    }

    private AiModelResponse readStreamingResponse(
            AiModelRequest request,
            HttpResponse<Stream<String>> response,
            AtomicReference<Stream<String>> responseLines,
            AtomicBoolean cancelled,
            Consumer<String> onDelta
    ) {
        var lines = response.body();
        responseLines.compareAndSet(null, lines);
        if (cancelled.get()) {
            closeResponseLines(responseLines);
            throw new CancellationException("Model stream was cancelled");
        }
        try (lines) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw providerError(response.statusCode(), lines.limit(64).map(s -> s.substring(0, Math.min(s.length(), 4096)))
                        .collect(java.util.stream.Collectors.joining("\n")));
            }
            var accumulator = new StreamAccumulator(properties.chatModel());
            var eventData = new StringBuilder();
            Iterator<String> iterator = lines.iterator();
            var providerDone = false;
            while (!providerDone) {
                if (cancelled.get()) {
                    throw new CancellationException("Model stream was cancelled");
                }
                if (!iterator.hasNext()) {
                    break;
                }
                var line = iterator.next();
                if (line.isEmpty()) {
                    providerDone = !consumeEvent(eventData, accumulator, onDelta);
                } else if (!line.startsWith(":") && line.startsWith("data:")) {
                    if (!eventData.isEmpty()) {
                        eventData.append('\n');
                    }
                    eventData.append(line.substring(5).stripLeading());
                }
            }
            if (!providerDone && !eventData.isEmpty()) {
                providerDone = !consumeEvent(eventData, accumulator, onDelta);
            }
            if (cancelled.get()) {
                throw new CancellationException("Model stream was cancelled");
            }
            if (!providerDone) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_STREAM_INTERRUPTED",
                        "模型服务在流式响应完成前断开连接");
            }
            if (accumulator.content.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_MODEL_RESPONSE",
                        "模型服务返回了无效的流式响应");
            }
            var inputTokens = accumulator.inputTokens > 0
                    ? accumulator.inputTokens
                    : tokenEstimator.estimate(request.systemPrompt() + request.userPrompt());
            var outputTokens = accumulator.outputTokens > 0
                    ? accumulator.outputTokens
                    : tokenEstimator.estimate(accumulator.content.toString());
            return new AiModelResponse(
                    accumulator.content.toString(),
                    provider(),
                    accumulator.model,
                    inputTokens,
                    outputTokens,
                    accumulator.cachedTokens,
                    accumulator.providerRequestId
            );
        } finally {
            responseLines.compareAndSet(lines, null);
        }
    }

    private void closeResponseLines(AtomicReference<Stream<String>> responseLines) {
        var lines = responseLines.getAndSet(null);
        if (lines != null) {
            lines.close();
        }
    }

    private boolean consumeEvent(
            StringBuilder eventData,
            StreamAccumulator accumulator,
            Consumer<String> onDelta
    ) {
        if (eventData.isEmpty()) {
            return true;
        }
        var data = eventData.toString().strip();
        eventData.setLength(0);
        if ("[DONE]".equals(data)) {
            return false;
        }
        try {
            var event = objectMapper.readTree(data);
            if (event.has("error")) throw providerError(400, data);
            accumulator.model = event.path("model").asText(accumulator.model);
            accumulator.providerRequestId = event.path("id").asText(accumulator.providerRequestId);
            var choices = event.path("choices");
            if (!choices.isEmpty()) {
                var delta = choices.path(0).path("delta").path("content").asText("");
                if (!delta.isEmpty()) {
                    accumulator.content.append(delta);
                    onDelta.accept(delta);
                }
            }
            var usage = event.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                accumulator.inputTokens = usage.path("prompt_tokens").asInt(accumulator.inputTokens);
                accumulator.outputTokens = usage.path("completion_tokens").asInt(accumulator.outputTokens);
                accumulator.cachedTokens = usage.path("prompt_tokens_details")
                        .path("cached_tokens").asInt(accumulator.cachedTokens);
            }
            return true;
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_MODEL_RESPONSE",
                    "模型服务返回了无效的流式事件");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to serialize model request", exception);
        }
    }

    private void addOutputLimit(Map<String, Object> body, AiModelRequest request) {
        if (request.metadata().get("maxOutputTokens") instanceof Number limit && limit.intValue() > 0) {
            body.put("max_tokens", limit.intValue());
        }
    }

    private ApiException providerError(int status, String body) {
        // Only explicit provider context errors qualify; ordinary 400/429 responses must not retry.
        try {
            var error = objectMapper.readTree(body).path("error");
            var code = error.path("code").asText("");
            var type = error.path("type").asText("");
            var message = error.path("message").asText("").toLowerCase(java.util.Locale.ROOT);
            if ((status == 400 || status == 413 || status == 422)
                    && ("context_length_exceeded".equals(code) || "context_window_exceeded".equals(code)
                    || "context_length_exceeded".equals(type)
                    || message.contains("maximum context length") || message.contains("exceeds the context window"))) {
                return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_CONTEXT_OVERFLOW", "模型上下文超限");
            }
        } catch (RuntimeException ignored) { }
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_PROVIDER_ERROR", "模型服务调用失败，HTTP " + status);
    }

    private URI chatCompletionsUri() {
        var baseUrl = properties.baseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return URI.create(baseUrl + "/v1/chat/completions");
    }

    @PreDestroy
    void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
    }

    private Throwable unwrap(Throwable exception) {
        var current = exception;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private String model;
        private String providerRequestId = "";
        private int inputTokens;
        private int outputTokens;
        private int cachedTokens;

        private StreamAccumulator(String model) {
            this.model = model;
        }
    }

    @Override
    public String provider() {
        return "openai-compatible";
    }

    @Override
    public String model() {
        return properties.chatModel();
    }
}
