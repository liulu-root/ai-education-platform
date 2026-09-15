package com.xiaomi.education.ai;

import com.xiaomi.education.ai.audit.AiCallResult;

import java.util.concurrent.CompletableFuture;

public interface AiStreamingCall {

    CompletableFuture<AiCallResult> completion();

    void cancel();
}
