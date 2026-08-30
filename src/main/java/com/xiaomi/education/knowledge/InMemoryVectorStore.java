package com.xiaomi.education.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "edu.vector", name = "provider", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryVectorStore implements VectorStore {

    private final ConcurrentHashMap<String, VectorChunk> chunks = new ConcurrentHashMap<>();

    @Override
    public void upsert(List<VectorChunk> newChunks) {
        newChunks.forEach(chunk -> chunks.put(chunk.id(), chunk));
    }

    @Override
    public List<KnowledgeHit> search(
            String tenantId, String courseId, float[] queryVector, int limit, double minimumScore
    ) {
        return chunks.values().stream()
                .filter(chunk -> chunk.tenantId().equals(tenantId))
                .filter(chunk -> courseId == null || courseId.isBlank() || courseId.equals(chunk.courseId()))
                .map(chunk -> new KnowledgeHit(chunk.id(), chunk.documentId(), chunk.courseId(), chunk.content(),
                        cosine(queryVector, chunk.embedding()), chunk.metadata()))
                .filter(hit -> hit.score() >= minimumScore)
                .sorted(Comparator.comparingDouble(KnowledgeHit::score).reversed())
                .limit(Math.min(Math.max(limit, 1), 20))
                .toList();
    }

    @Override
    public String provider() {
        return "in-memory";
    }

    private double cosine(float[] left, float[] right) {
        if (left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
