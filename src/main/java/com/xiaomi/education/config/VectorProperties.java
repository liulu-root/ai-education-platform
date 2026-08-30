package com.xiaomi.education.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edu.vector")
public record VectorProperties(
        String provider,
        int dimensions,
        String embeddingProvider,
        String embeddingModel,
        Milvus milvus
) {
    public record Milvus(String baseUrl, String token, String database, String collection) {
    }
}
