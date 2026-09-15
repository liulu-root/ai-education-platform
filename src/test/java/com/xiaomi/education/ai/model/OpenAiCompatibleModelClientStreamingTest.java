package com.xiaomi.education.ai.model;

import com.sun.net.httpserver.HttpServer;
import com.xiaomi.education.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleModelClientStreamingTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void parsesSplitProviderEventsUsageAndDoneWithoutTrailingBlankLine() throws Exception {
        var requestBody = new AtomicReference<String>();
        var providerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.setExecutor(providerExecutor);
        provider.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                var firstEvent = """
                        data: {"id":"provider-1","model":"provider-model","choices":[{"delta":{"content":"你"}}]}

                        """.getBytes(StandardCharsets.UTF_8);
                output.write(firstEvent, 0, firstEvent.length / 2);
                output.flush();
                output.write(firstEvent, firstEvent.length / 2, firstEvent.length - firstEvent.length / 2);
                output.write("""
                        data: {"id":"provider-1","model":"provider-model","choices":[{"delta":{"content":"好"}}]}

                        data: {"id":"provider-1","model":"provider-model","choices":[],"usage":{"prompt_tokens":17,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":3}}}

                        data: [DONE]""".getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                exchange.close();
            }
        });
        provider.start();

        var properties = new AiProperties(
                "openai-compatible",
                "http://127.0.0.1:" + provider.getAddress().getPort(),
                "test-key",
                "requested-model",
                0.2,
                5,
                1_000_000,
                true,
                240
        );
        var client = new OpenAiCompatibleModelClient(properties, new ObjectMapper(), new TokenEstimator());
        var deltas = new CopyOnWriteArrayList<String>();
        try {
            var stream = client.generateStream(
                    new AiModelRequest(AiScenario.TUTOR_CHAT, "system", "question", Map.of()),
                    deltas::add
            );
            var response = stream.completion().get(5, TimeUnit.SECONDS);

            assertThat(deltas).containsExactly("你", "好");
            assertThat(response.content()).isEqualTo("你好");
            assertThat(response.model()).isEqualTo("provider-model");
            assertThat(response.inputTokens()).isEqualTo(17);
            assertThat(response.outputTokens()).isEqualTo(2);
            assertThat(response.cachedTokens()).isEqualTo(3);
            assertThat(requestBody.get())
                    .contains("\"stream\":true")
                    .contains("\"include_usage\":true")
                    .contains("\"model\":\"requested-model\"");
        } finally {
            client.shutdownStreamExecutor();
            provider.stop(0);
            providerExecutor.shutdownNow();
        }
    }
}
