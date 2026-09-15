package com.xiaomi.education.ai.model;

import java.util.concurrent.CompletableFuture;

/**
 * A running model stream. Cancelling it must close the provider connection, not
 * merely discard locally received tokens.
 */
public interface AiModelStream {

    CompletableFuture<AiModelResponse> completion();

    void cancel();
}
