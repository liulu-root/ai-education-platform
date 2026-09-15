package com.xiaomi.education.tutor;

import com.xiaomi.education.ai.model.*;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:context-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "edu.ai.context.context-window-tokens=2200", "edu.ai.context.max-output-tokens=300",
        "edu.ai.context.summary-max-tokens=180", "edu.ai.context.summary-timeout-seconds=1",
        "edu.ai.context.request-timeout-seconds=4"})
class TutorContextIntegrationTest {
    @Autowired TutorService service;
    @Autowired TutorRepository repository;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AiModelClient model;
    TenantContext.Scope scope;
    List<AiModelRequest> requests;

    @BeforeEach void setup() {
        scope = TenantContext.open(new TenantContext("tenant-demo", "learner-001", "LEARNER"));
        requests = new CopyOnWriteArrayList<>();
        when(model.provider()).thenReturn("test");
        when(model.model()).thenReturn("test-model");
        stub(invocation -> {
            var request = invocation.<AiModelRequest>getArgument(0);
            requests.add(request);
            return success(request, invocation.getArgument(1));
        });
    }
    @AfterEach void cleanup() { scope.close(); }

    private void stub(org.mockito.stubbing.Answer<AiModelStream> answer) {
        doAnswer(answer).when(model).generateStream(any(), any());
    }

    @Test void shortConversationHasNoSummaryAndPersistsOnePair() {
        var result = service.chat(null, "course-java", "解释 RAG");
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().scenario()).isEqualTo(AiScenario.TUTOR_CHAT);
        assertThat(repository.messages(result.conversationId())).hasSize(2);
        assertThat(repository.memory(result.conversationId()).version()).isEqualTo(-1);
    }

    @Test void staleSummaryAndExpiredGenerationCannotOverwriteState() {
        var id = conversation(1, 10);
        assertThat(repository.acquire(id, "owner", 10)).isTrue();
        var old = repository.memory(id);
        assertThat(repository.saveMemory(id, "owner", old, "旧摘要", 2, 3, "model")).isTrue();
        assertThat(repository.saveMemory(id, "owner", old, "过期结果", 2, 4, "model")).isFalse();
        jdbc.update("UPDATE tutor_conversation SET generation_expires_at = TIMESTAMP '2020-01-01 00:00:00' WHERE id = ?", id);
        assertThatThrownBy(() -> repository.saveLeasedMessage(id, "owner", "ASSISTANT", "迟到回复", "trace"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CONVERSATION_LEASE_LOST"));
        assertThat(repository.memory(id).content()).isEqualTo("旧摘要");
        assertThat(repository.messages(id)).hasSize(2);
        repository.release(id, "owner");
    }

    @Test void longHistoryCreatesIncrementalSummaryAndRetainsRecentTurns() {
        var id = conversation(8, 180);
        service.chat(id, "course-java", "继续学习");
        var memory = repository.memory(id);
        assertThat(memory.version()).isEqualTo(0);
        assertThat(memory.through()).isPositive().isLessThanOrEqualTo(12);
        assertThat(requests.stream().filter(r -> r.scenario() == AiScenario.CONVERSATION_SUMMARY)).hasSize(1);
        assertThat(requests.getLast().userPrompt()).contains("学习目标：掌握 Java", "第7轮");
        requests.clear();
        for (int i = 0; i < 6; i++) {
            repository.saveMessage(UUID.randomUUID().toString(), id, "USER", "新问题".repeat(100), null);
            repository.saveMessage(UUID.randomUUID().toString(), id, "ASSISTANT", "新解释".repeat(100), null);
        }
        service.chat(id, "course-java", "继续");
        assertThat(repository.memory(id).through()).isGreaterThan(memory.through());
        var summary = requests.stream().filter(r -> r.scenario() == AiScenario.CONVERSATION_SUMMARY).findFirst().orElseThrow();
        assertThat(summary.userPrompt()).contains("学习目标：掌握 Java").doesNotContain("第0轮");
    }

    @Test void invalidSummaryKeepsOldCursorAndFallsBackToTrimming() {
        var id = conversation(8, 180);
        stub(inv -> {
            var request = inv.<AiModelRequest>getArgument(0); requests.add(request);
            return completed(request.scenario() == AiScenario.CONVERSATION_SUMMARY ? "invalid-json" : "答案", inv.getArgument(1));
        });
        assertThat(service.chat(id, "course-java", "继续").answer()).isEqualTo("答案");
        assertThat(repository.memory(id).version()).isEqualTo(-1);
        assertThat(jdbc.queryForObject("SELECT degraded FROM tutor_context_attempt WHERE conversation_id = ?", Boolean.class, id)).isTrue();
    }

    @Test void retriesOverflowOnceWithSmallerInputAndNoDuplicateMessages() {
        var id = conversation(2, 100);
        stub(inv -> {
            var request = inv.<AiModelRequest>getArgument(0); requests.add(request);
            return requests.size() == 1 ? failed("MODEL_CONTEXT_OVERFLOW") : success(request, inv.getArgument(1));
        });
        var result = service.chat(id, "course-java", "解释 RAG");
        assertThat(requests).hasSize(2);
        assertThat(new TokenEstimator().estimate(requests.getLast().userPrompt()))
                .isLessThan(new TokenEstimator().estimate(requests.getFirst().userPrompt()));
        assertThat(repository.messages(id)).hasSize(6);
        assertThat(result.citations()).hasSize(((Number) requests.getLast().metadata().get("retrievedChunks")).intValue());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tutor_context_attempt WHERE conversation_id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test void stopsAfterSecondOverflowAndDoesNotRetryOtherErrors() {
        var id = conversation(2, 100);
        stub(inv -> { requests.add(inv.getArgument(0)); return failed("MODEL_CONTEXT_OVERFLOW"); });
        assertThatThrownBy(() -> service.chat(id, "course-java", "继续")).isInstanceOf(ApiException.class);
        assertThat(requests).hasSize(2);
        assertThat(repository.messages(id)).hasSize(5);
        requests.clear();
        stub(inv -> { requests.add(inv.getArgument(0)); return failed("MODEL_PROVIDER_ERROR"); });
        assertThatThrownBy(() -> service.chat(null, "course-java", "继续")).isInstanceOf(ApiException.class);
        assertThat(requests).hasSize(1);
    }

    @Test void neverRetriesAfterEmittingAnswer() {
        stub(inv -> {
            requests.add(inv.getArgument(0)); inv.<Consumer<String>>getArgument(1).accept("部分答案");
            return failed("MODEL_CONTEXT_OVERFLOW");
        });
        assertThatThrownBy(() -> service.chat(null, "course-java", "继续")).isInstanceOf(ApiException.class);
        assertThat(requests).hasSize(1);
    }

    @Test void leaseRejectsConcurrentConversationAndSummaryCancellationDoesNotAdvanceCursor() throws Exception {
        var id = conversation(8, 180);
        var entered = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        stub(inv -> {
            requests.add(inv.getArgument(0)); entered.countDown();
            return hanging(cancelled);
        });
        var call = service.chatStream(id, "course-java", "继续", listener());
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> service.chat(id, "course-java", "同时提问"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CONVERSATION_BUSY"));
        call.cancel();
        assertThatThrownBy(() -> call.completion().get(3, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
        assertThat(cancelled).isTrue();
        assertThat(repository.memory(id).version()).isEqualTo(-1);
        assertThat(requests).hasSize(1);
        assertThat(repository.acquire(id, "new-owner", 3)).isTrue();
        repository.release(id, "new-owner");
    }

    @Test void summaryTimeoutCancelsUpstreamAndFallsBack() {
        var cancelled = new AtomicBoolean();
        stub(inv -> {
            var request = inv.<AiModelRequest>getArgument(0); requests.add(request);
            return request.scenario() == AiScenario.CONVERSATION_SUMMARY ? hanging(cancelled) : success(request, inv.getArgument(1));
        });
        var id = conversation(8, 180);
        assertThat(service.chat(id, "course-java", "继续").answer()).isNotEmpty();
        assertThat(cancelled).isTrue();
        assertThat(repository.memory(id).version()).isEqualTo(-1);
        assertThat(requests).hasSize(2);
    }

    @Test void mainDeadlineCancelsUpstreamWithoutSavingAnAnswer() {
        var cancelled = new AtomicBoolean();
        stub(inv -> hanging(cancelled));
        var id = conversation(0, 0);
        long start = System.nanoTime();
        assertThatThrownBy(() -> service.chat(id, "course-java", "继续"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TUTOR_REQUEST_TIMEOUT"));
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(6);
        assertThat(cancelled).isTrue();
        assertThat(repository.messages(id)).hasSize(1);
    }

    @Test void startAndFinalCitationsAlwaysPrecedeEvenImmediateProviderDeltas() throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        var stream = service.chatStream(null, "course-java", "继续", new TutorService.TutorStreamListener() {
            public void onStart(TutorService.TutorStreamStart start) { events.add("start"); assertThat(start.citations()).isEmpty(); }
            public void onCitations(List<TutorService.Citation> citations) { events.add("citations"); }
            public void onDelta(String delta) { events.add("delta"); }
            public void onComplete(TutorService.TutorResponse result) { events.add("done"); }
            public void onError(Throwable error) { events.add("error"); }
        });
        stream.completion().get(3, TimeUnit.SECONDS);
        assertThat(events).containsExactly("start", "citations", "delta", "done");
    }

    private String conversation(int turns, int chars) {
        var id = UUID.randomUUID().toString();
        repository.createConversation(id, "tenant-demo", "learner-001", "course-java", "上下文测试");
        for (int i = 0; i < turns; i++) {
            repository.saveMessage(UUID.randomUUID().toString(), id, "USER", "第" + i + "轮" + "学习".repeat(chars / 2), null);
            repository.saveMessage(UUID.randomUUID().toString(), id, "ASSISTANT", "解释".repeat(chars / 2), null);
        }
        return id;
    }
    private AiModelStream success(AiModelRequest request, Consumer<String> delta) {
        return completed(request.scenario() == AiScenario.CONVERSATION_SUMMARY ? "{\"summary\":\"学习目标：掌握 Java\"}" : "参考资料回答 [资料1]", delta);
    }
    private AiModelStream completed(String content, Consumer<String> delta) {
        delta.accept(content);
        return stream(CompletableFuture.completedFuture(new AiModelResponse(content, "test", "test-model", 30, 10, 0, "test")), new AtomicBoolean());
    }
    private AiModelStream failed(String code) {
        return stream(CompletableFuture.failedFuture(new ApiException(HttpStatus.BAD_GATEWAY, code, code)), new AtomicBoolean());
    }
    private AiModelStream hanging(AtomicBoolean cancelled) { return stream(new CompletableFuture<>(), cancelled); }
    private AiModelStream stream(CompletableFuture<AiModelResponse> result, AtomicBoolean cancelled) {
        return new AiModelStream() {
            public CompletableFuture<AiModelResponse> completion() { return result; }
            public void cancel() { cancelled.set(true); result.cancel(false); }
        };
    }
    private TutorService.TutorStreamListener listener() {
        return new TutorService.TutorStreamListener() {
            public void onStart(TutorService.TutorStreamStart start) { }
            public void onDelta(String delta) { }
            public void onComplete(TutorService.TutorResponse result) { }
            public void onError(Throwable error) { }
        };
    }
}

