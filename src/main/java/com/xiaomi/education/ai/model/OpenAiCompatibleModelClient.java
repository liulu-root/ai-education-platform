package com.xiaomi.education.ai.model;

import tools.jackson.databind.JsonNode;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.config.AiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "edu.ai", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleModelClient implements AiModelClient {

    private final AiProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleModelClient(AiProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("AI_API_KEY is required for provider=openai-compatible");
        }
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
    }

    @Override
    public AiModelResponse generate(AiModelRequest request) {
        var body = Map.of(
                "model", properties.chatModel(),
                "temperature", properties.temperature(),
                "messages", List.of(
                        Map.of("role", "system", "content", request.systemPrompt()),
                        Map.of("role", "user", "content", request.userPrompt())
                )
        );
        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/v1/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_PROVIDER_ERROR", "模型服务调用失败");
        }
        if (response == null || response.path("choices").isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_MODEL_RESPONSE", "模型服务返回了无效响应");
        }
        var usage = response.path("usage");
        return new AiModelResponse(
                response.path("choices").path(0).path("message").path("content").asText(),
                provider(),
                response.path("model").asText(properties.chatModel()),
                usage.path("prompt_tokens").asInt(),
                usage.path("completion_tokens").asInt(),
                usage.path("prompt_tokens_details").path("cached_tokens").asInt(),
                response.path("id").asText()
        );
    }

    @Override
    public String provider() {
        return "openai-compatible";
    }

    @Override
    public String model() {
        return properties.chatModel();
    }
}
