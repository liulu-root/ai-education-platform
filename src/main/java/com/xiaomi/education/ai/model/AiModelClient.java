package com.xiaomi.education.ai.model;

public interface AiModelClient {

    AiModelResponse generate(AiModelRequest request);

    String provider();

    String model();
}
