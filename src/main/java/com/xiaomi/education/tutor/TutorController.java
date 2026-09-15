package com.xiaomi.education.tutor;

import com.xiaomi.education.ai.audit.AiCallResult;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.config.TutorContextProperties;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/ai/tutor")
public class TutorController {

    private final TutorService tutorService;
    private final TutorContextProperties contextProperties;
    private final ScheduledExecutorService heartbeatExecutor;

    public TutorController(TutorService tutorService, TutorContextProperties contextProperties) {
        this.tutorService = tutorService;
        this.contextProperties = contextProperties;
        var heartbeatThreadNumber = new AtomicInteger();
        this.heartbeatExecutor = Executors.newScheduledThreadPool(4, runnable -> {
            var thread = new Thread(
                    runnable,
                    "tutor-sse-heartbeat-" + heartbeatThreadNumber.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostMapping("/chat")
    public TutorService.TutorResponse chat(@Valid @RequestBody TutorChatRequest request) {
        return tutorService.chat(request.conversationId(), request.courseId(), request.question());
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @Valid @RequestBody TutorChatRequest request,
            HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        var timeoutMillis = (contextProperties.requestTimeoutSeconds() + 2L) * 1_000L;
        var emitter = new SseEmitter(timeoutMillis);
        var terminal = new AtomicBoolean();
        var cancellation = new StreamCancellation();

        emitter.onCompletion(cancellation::cancel);
        emitter.onError(exception -> cancellation.cancel());
        emitter.onTimeout(() -> {
            if (terminal.compareAndSet(false, true)) {
                cancellation.cancel();
                emitter.complete();
            }
        });

        var heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> sendHeartbeat(emitter, terminal, cancellation),
                1,
                1,
                TimeUnit.SECONDS
        );
        cancellation.attachHeartbeat(heartbeat);

        try {
            var stream = tutorService.chatStream(
                    request.conversationId(),
                    request.courseId(),
                    request.question(),
                    new TutorService.TutorStreamListener() {
                        @Override
                        public void onCitations(List<TutorService.Citation> citations) {
                            if (!sendEvent(emitter, "citations", Map.of("citations", citations), terminal, cancellation))
                                throw new ClientDisconnectedException();
                        }

                        @Override
                        public void onStatus(String status) {
                            if (!sendEvent(emitter, "status", Map.of("message", status), terminal, cancellation))
                                throw new ClientDisconnectedException();
                        }

                        @Override
                        public void onStart(TutorService.TutorStreamStart start) {
                            if (!sendEvent(emitter, "start", start, terminal, cancellation)) {
                                throw new ClientDisconnectedException();
                            }
                        }

                        @Override
                        public void onDelta(String delta) {
                            if (!sendEvent(emitter, "delta", new TutorStreamDelta(delta), terminal, cancellation))
                                throw new ClientDisconnectedException();
                        }

                        @Override
                        public void onComplete(TutorService.TutorResponse tutorResponse) {
                            if (!terminal.compareAndSet(false, true)) {
                                return;
                            }
                            cancellation.stopHeartbeat();
                            var done = new TutorStreamDone(
                                    tutorResponse.conversationId(),
                                    tutorResponse.citations(),
                                    tutorResponse.usedTools(),
                                    tutorResponse.usage()
                            );
                            try {
                                emitter.send(SseEmitter.event().name("done").data(done));
                                emitter.complete();
                            } catch (IOException | IllegalStateException exception) {
                                cancellation.cancel();
                                emitter.completeWithError(exception);
                            }
                        }

                        @Override
                        public void onError(Throwable exception) {
                            if (!terminal.compareAndSet(false, true)) {
                                return;
                            }
                            cancellation.stopHeartbeat();
                            var errorCode = exception instanceof ApiException apiException
                                    ? apiException.code()
                                    : "MODEL_CALL_FAILED";
                            try {
                                emitter.send(SseEmitter.event().name("error")
                                        .data(new TutorStreamError(errorCode, exception.getMessage())));
                                emitter.complete();
                            } catch (IOException | IllegalStateException sendException) {
                                cancellation.cancel();
                                emitter.completeWithError(sendException);
                            }
                        }
                    }
            );
            cancellation.attachStream(stream);
        } catch (ClientDisconnectedException exception) {
            cancellation.cancel();
            terminal.set(true);
            emitter.complete();
        } catch (RuntimeException exception) {
            cancellation.cancel();
            throw exception;
        }
        return emitter;
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations() {
        return tutorService.conversations();
    }

    @PreDestroy
    void shutdownHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }

    private boolean sendEvent(
            SseEmitter emitter,
            String eventName,
            Object data,
            AtomicBoolean terminal,
            StreamCancellation cancellation
    ) {
        if (terminal.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (IOException | IllegalStateException exception) {
            if (terminal.compareAndSet(false, true)) {
                cancellation.cancel();
                emitter.completeWithError(exception);
            }
            return false;
        }
    }

    private void sendHeartbeat(
            SseEmitter emitter,
            AtomicBoolean terminal,
            StreamCancellation cancellation
    ) {
        if (terminal.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("keepalive"));
        } catch (IOException | IllegalStateException exception) {
            if (terminal.compareAndSet(false, true)) {
                cancellation.cancel();
                emitter.completeWithError(exception);
            }
        }
    }

    private record TutorStreamDelta(String text) {
    }

    private record TutorStreamDone(
            String conversationId,
            List<TutorService.Citation> citations,
            List<String> usedTools,
            AiCallResult usage
    ) {
    }

    private record TutorStreamError(String code, String message) {
    }

    private static final class ClientDisconnectedException extends RuntimeException {
    }

    private static final class StreamCancellation {
        private final AtomicBoolean requested = new AtomicBoolean();
        private final AtomicReference<TutorService.TutorStream> stream = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

        private void attachStream(TutorService.TutorStream value) {
            stream.set(value);
            if (requested.get()) {
                value.cancel();
            }
        }

        private void attachHeartbeat(ScheduledFuture<?> value) {
            heartbeat.set(value);
            if (requested.get()) {
                value.cancel(false);
            }
        }

        private void stopHeartbeat() {
            var task = heartbeat.getAndSet(null);
            if (task != null) {
                task.cancel(false);
            }
        }

        private void cancel() {
            requested.set(true);
            stopHeartbeat();
            var activeStream = stream.get();
            if (activeStream != null) {
                activeStream.cancel();
            }
        }
    }

    public record TutorChatRequest(
            String conversationId,
            @NotBlank String courseId,
            @NotBlank @Size(max = 4000) String question
    ) {
    }
}
