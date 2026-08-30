package com.xiaomi.education.knowledge;

import java.util.List;

public interface VectorStore {

    void upsert(List<VectorChunk> chunks);

    List<KnowledgeHit> search(String tenantId, String courseId, float[] queryVector, int limit, double minimumScore);

    String provider();
}
