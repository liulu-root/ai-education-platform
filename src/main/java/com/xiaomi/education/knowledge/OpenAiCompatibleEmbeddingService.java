package com.xiaomi.education.knowledge;

import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.config.AiProperties;
import com.xiaomi.education.config.VectorProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "edu.vector", name = "embedding-provider", havingValue = "openai-compatible")
public class OpenAiCompatibleEmbeddingService implements EmbeddingService {

    private final VectorProperties vectorProperties;
    private final RestClient restClient;

    public OpenAiCompatibleEmbeddingService(
            VectorProperties vectorProperties,
            AiProperties aiProperties,
            RestClient.Builder builder
    ) {
        if (aiProperties.apiKey() == null || aiProperties.apiKey().isBlank()) {
            throw new IllegalStateException("AI_API_KEY is required for embedding-provider=openai-compatible");
        }
        this.vectorProperties = vectorProperties;
        this.restClient = builder.baseUrl(aiProperties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + aiProperties.apiKey())
                .build();
    }

    @Override
    public float[] embed(String text) {
        try {
            JsonNode response = restClient.post().uri("/v1/embeddings")
                    .body(Map.of("model", vectorProperties.embeddingModel(), "input", text))
                    .retrieve().body(JsonNode.class);
            var values = response == null ? null : response.path("data").path(0).path("embedding");
            if (values == null || !values.isArray() || values.isEmpty()) {
                throw new IllegalStateException("Embedding response is empty");
            }
            var vector = new float[values.size()];
            for (int index = 0; index < values.size(); index++) {
                vector[index] = values.path(index).floatValue();
            }
            return vector;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EMBEDDING_PROVIDER_ERROR", "向量模型调用失败");
        }
    }
}
