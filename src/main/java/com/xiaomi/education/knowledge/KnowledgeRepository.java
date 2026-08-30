package com.xiaomi.education.knowledge;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Repository
public class KnowledgeRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public KnowledgeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void insertDocument(KnowledgeDocument document, List<KnowledgeChunkRow> chunks) {
        jdbcTemplate.update("""
                        INSERT INTO knowledge_document (
                            id, tenant_id, course_id, title, source_type, source_uri,
                            content_hash, status, version, created_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, document.id(), document.tenantId(), document.courseId(), document.title(),
                document.sourceType(), document.sourceUri(), document.contentHash(), "INDEXED", 1, document.createdBy());
        for (var chunk : chunks) {
            jdbcTemplate.update("""
                            INSERT INTO knowledge_chunk (
                                id, tenant_id, document_id, course_id, chunk_index,
                                content, token_count, metadata_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """, chunk.id(), document.tenantId(), document.id(), document.courseId(), chunk.index(),
                    chunk.content(), chunk.tokenCount(), writeMetadata(chunk.metadata()));
        }
    }

    public List<KnowledgeChunkRow> findAllChunks() {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, document_id, course_id, chunk_index, content, token_count, metadata_json
                        FROM knowledge_chunk
                        """, (resultSet, rowNum) -> new KnowledgeChunkRow(
                resultSet.getString("id"), resultSet.getString("tenant_id"), resultSet.getString("document_id"),
                resultSet.getString("course_id"), resultSet.getInt("chunk_index"), resultSet.getString("content"),
                resultSet.getInt("token_count"), readMetadata(resultSet.getString("metadata_json"))
        ));
    }

    public List<Map<String, Object>> listDocuments(String tenantId) {
        return jdbcTemplate.queryForList("""
                        SELECT d.id, d.course_id, d.title, d.source_type, d.status, d.version, d.created_at,
                               COUNT(c.id) AS chunk_count
                        FROM knowledge_document d
                        LEFT JOIN knowledge_chunk c ON c.document_id = d.id
                        WHERE d.tenant_id = ?
                        GROUP BY d.id, d.course_id, d.title, d.source_type, d.status, d.version, d.created_at
                        ORDER BY d.created_at DESC
                        """, tenantId);
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private Map<String, Object> readMetadata(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    public record KnowledgeDocument(
            String id, String tenantId, String courseId, String title, String sourceType,
            String sourceUri, String contentHash, String createdBy
    ) {
    }

    public record KnowledgeChunkRow(
            String id, String tenantId, String documentId, String courseId, int index,
            String content, int tokenCount, Map<String, Object> metadata
    ) {
    }
}
