package com.xiaomi.education.knowledge;

import java.util.Map;

public record KnowledgeHit(
        String chunkId,
        String documentId,
        String courseId,
        String content,
        double score,
        Map<String, Object> metadata
) {
}
