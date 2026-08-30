package com.xiaomi.education.knowledge;

import tools.jackson.databind.JsonNode;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.config.VectorProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "edu.vector", name = "provider", havingValue = "milvus")
public class MilvusVectorStore implements VectorStore {

    private final VectorProperties.Milvus properties;
    private final RestClient restClient;

    public MilvusVectorStore(VectorProperties vectorProperties, RestClient.Builder builder) {
        this.properties = vectorProperties.milvus();
        this.restClient = builder.baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .build();
    }

    @Override
    public void upsert(List<VectorChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        var data = chunks.stream().map(chunk -> Map.<String, Object>of(
                "chunk_id", chunk.id(),
                "tenant_id", chunk.tenantId(),
                "document_id", chunk.documentId(),
                "course_id", chunk.courseId() == null ? "" : chunk.courseId(),
                "content", chunk.content(),
                "vector", toList(chunk.embedding())
        )).toList();
        try {
            restClient.post().uri("/v2/vectordb/entities/upsert")
                    .body(Map.of("dbName", properties.database(), "collectionName", properties.collection(), "data", data))
                    .retrieve().toBodilessEntity();
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MILVUS_INSERT_FAILED",
                    "Milvus 写入失败，请确认集合已由运维侧创建");
        }
    }

    @Override
    public List<KnowledgeHit> search(
            String tenantId, String courseId, float[] queryVector, int limit, double minimumScore
    ) {
        var filter = "tenant_id == \"" + escape(tenantId) + "\"";
        if (courseId != null && !courseId.isBlank()) {
            filter += " && course_id == \"" + escape(courseId) + "\"";
        }
        var body = Map.<String, Object>of(
                "dbName", properties.database(),
                "collectionName", properties.collection(),
                "data", List.of(toList(queryVector)),
                "annsField", "vector",
                "filter", filter,
                "limit", Math.min(Math.max(limit, 1), 20),
                "outputFields", List.of("chunk_id", "document_id", "course_id", "content")
        );
        try {
            var response = restClient.post().uri("/v2/vectordb/entities/search")
                    .body(body).retrieve().body(JsonNode.class);
            var hits = new ArrayList<KnowledgeHit>();
            if (response != null) {
                for (JsonNode item : response.path("data")) {
                    var score = item.path("distance").asDouble();
                    if (score >= minimumScore) {
                        hits.add(new KnowledgeHit(item.path("chunk_id").asText(), item.path("document_id").asText(),
                                item.path("course_id").asText(), item.path("content").asText(), score, Map.of()));
                    }
                }
            }
            return hits;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MILVUS_SEARCH_FAILED", "Milvus 检索失败");
        }
    }

    @Override
    public String provider() {
        return "milvus";
    }

    private List<Float> toList(float[] vector) {
        var values = new ArrayList<Float>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
