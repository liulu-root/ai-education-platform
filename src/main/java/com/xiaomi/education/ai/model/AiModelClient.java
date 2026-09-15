package com.xiaomi.education.ai.model;

import java.util.function.Consumer;

public interface AiModelClient {

    AiModelResponse generate(AiModelRequest request);

    AiModelStream generateStream(AiModelRequest request, Consumer<String> onDelta);

    String provider();

    String model();
}
