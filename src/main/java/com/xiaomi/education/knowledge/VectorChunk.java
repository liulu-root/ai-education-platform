package com.xiaomi.education.knowledge;

import java.util.Map;

public record VectorChunk(
        String id,
        String tenantId,
        String documentId,
        String courseId,
        String content,
        Map<String, Object> metadata,
        float[] embedding
) {
}
