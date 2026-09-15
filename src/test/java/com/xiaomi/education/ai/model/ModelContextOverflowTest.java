package com.xiaomi.education.ai.model;

import com.sun.net.httpserver.HttpServer;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.config.AiProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ModelContextOverflowTest {
    @Test void classifiesExplicitOverflowInSyncAndStreamAndSendsOutputLimit() throws Exception {
        verify(400, "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"too long\"}}", "MODEL_CONTEXT_OVERFLOW");
    }
    @Test void genericBadRequestAndRateLimitAreNotContextOverflow() throws Exception {
        verify(400, "{\"error\":{\"code\":\"invalid_request\",\"message\":\"bad temperature\"}}", "MODEL_PROVIDER_ERROR");
        verify(429, "{\"error\":{\"code\":\"context_length_exceeded\"}}", "MODEL_PROVIDER_ERROR");
    }
    @Test void recognizesErrorInsideSuccessfulSseResponse() throws Exception {
        verify(200, "data: {\"error\":{\"code\":\"context_length_exceeded\"}}\n\n", "MODEL_CONTEXT_OVERFLOW");
    }

    private void verify(int status, String response, String expected) throws Exception {
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var client = new OpenAiCompatibleModelClient(new AiProperties("openai-compatible",
                "http://127.0.0.1:" + server.getAddress().getPort(), "test", "model", .2, 3, 1000000, false, 0),
                new ObjectMapper(), new TokenEstimator());
        var request = new AiModelRequest(AiScenario.TUTOR_CHAT, "system", "question", Map.of("maxOutputTokens", 128));
        try {
            if (status != 200) assertThatThrownBy(() -> client.generate(request)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(expected));
            assertThatThrownBy(() -> client.generateStream(request, ignored -> { }).completion().get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiException.class).satisfies(e -> assertThat(((ApiException) e.getCause()).code()).isEqualTo(expected));
            assertThat(new ObjectMapper().readTree(received.get()).path("max_tokens").asInt()).isEqualTo(128);
        } finally { client.shutdownStreamExecutor(); server.stop(0); }
    }
}
