package com.xiaomi.education.tutor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiaomi.education.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TutorStreamingDisconnectIntegrationTest.StreamingTestSecurity.class)
class TutorStreamingDisconnectIntegrationTest {

    private static final CountDownLatch UPSTREAM_DISCONNECTED = new CountDownLatch(1);
    private static final AtomicReference<String> PROVIDER_REQUEST = new AtomicReference<>("");
    private static final AtomicBoolean PROVIDER_COMPLETED = new AtomicBoolean();
    private static final ExecutorService PROVIDER_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer PROVIDER = startProvider();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void modelProviderProperties(DynamicPropertyRegistry registry) {
        registry.add("edu.ai.provider", () -> "openai-compatible");
        registry.add("edu.ai.api-key", () -> "test-api-key");
        registry.add("edu.ai.base-url",
                () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort());
        registry.add("edu.ai.chat-model", () -> "stream-test-model");
        registry.add("edu.ai.timeout-seconds", () -> 15);
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop(0);
        PROVIDER_EXECUTOR.shutdownNow();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void closingBrowserBeforeTheFirstTokenCancelsTheUpstreamModelConnection() throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/ai/tutor/chat/stream"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("X-Trace-Id", "stream-disconnect-test")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "courseId": "course-java",
                          "question": "请解释为什么 RAG 答案必须保留引用"
                        }
                        """))
                .build();

        var response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/event-stream");

        var sawStart = false;
        try (var body = response.body();
             var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String eventName = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).strip();
                } else if (line.startsWith("data:") && "start".equals(eventName)) {
                    assertThat(line).contains("conversationId");
                    sawStart = true;
                    break;
                } else if (line.isEmpty()) {
                    eventName = null;
                }
            }
        }

        assertThat(sawStart).isTrue();
        assertThat(PROVIDER_REQUEST.get())
                .contains("\"stream\":true")
                .contains("\"include_usage\":true")
                .contains("\"model\":\"stream-test-model\"");
        assertThat(UPSTREAM_DISCONNECTED.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(PROVIDER_COMPLETED).isFalse();

        var audits = awaitAudits("stream-disconnect-test", Duration.ofSeconds(3));
        assertThat(audits).singleElement().satisfies(audit -> {
            assertThat(audit.get("status")).isEqualTo("CANCELLED");
            assertThat(audit.get("error_code")).isEqualTo("CLIENT_DISCONNECTED");
        });
    }

    private List<Map<String, Object>> awaitAudits(String traceId, Duration timeout) throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var matches = jdbcTemplate.queryForList(
                    "SELECT status, error_code FROM ai_call_audit WHERE trace_id = ?",
                    traceId
            );
            if (!matches.isEmpty()) {
                return matches;
            }
            Thread.sleep(50);
        }
        return List.of();
    }

    private static HttpServer startProvider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(PROVIDER_EXECUTOR);
            server.createContext("/v1/chat/completions", TutorStreamingDisconnectIntegrationTest::streamForever);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void streamForever(HttpExchange exchange) throws IOException {
        PROVIDER_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (var output = exchange.getResponseBody()) {
            try {
                Thread.sleep(1_500);
                sendProviderEvent(output, """
                        {"id":"provider-stream-1","model":"stream-test-model","choices":[{"delta":{"content":"首"}}]}
                        """.strip());
                for (var index = 0; index < 200; index++) {
                    sendProviderEvent(output, """
                            {"id":"provider-stream-1","model":"stream-test-model","choices":[{"delta":{"content":"续"}}]}
                            """.strip());
                    Thread.sleep(25);
                }
                PROVIDER_COMPLETED.set(true);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                UPSTREAM_DISCONNECTED.countDown();
            }
        } catch (IOException exception) {
            UPSTREAM_DISCONNECTED.countDown();
        } finally {
            exchange.close();
        }
    }

    private static void sendProviderEvent(java.io.OutputStream output, String json) throws IOException {
        output.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StreamingTestSecurity {

        @Bean
        @Order(0)
        SecurityFilterChain streamingEndpointTestChain(HttpSecurity http) throws Exception {
            http
                    .securityMatcher("/api/ai/tutor/chat/stream")
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .addFilterBefore(new TestTenantContextFilter(), AuthorizationFilter.class);
            return http.build();
        }
    }

    private static final class TestTenantContextFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException {
            try (var ignored = TenantContext.open(
                    new TenantContext("tenant-demo", "learner-001", "LEARNER"))) {
                filterChain.doFilter(request, response);
            }
        }
    }
}
