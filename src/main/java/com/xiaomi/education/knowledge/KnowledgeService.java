package com.xiaomi.education.knowledge;

import com.xiaomi.education.ai.model.TokenEstimator;
import com.xiaomi.education.common.Hashing;
import com.xiaomi.education.knowledge.KnowledgeRepository.KnowledgeChunkRow;
import com.xiaomi.education.knowledge.KnowledgeRepository.KnowledgeDocument;
import com.xiaomi.education.tenant.TenantContext;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeService {

    private static final int CHUNK_SIZE = 600;
    private static final int CHUNK_OVERLAP = 80;

    private final KnowledgeRepository repository;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final TokenEstimator tokenEstimator;

    public KnowledgeService(
            KnowledgeRepository repository,
            EmbeddingService embeddingService,
            VectorStore vectorStore,
            TokenEstimator tokenEstimator
    ) {
        this.repository = repository;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.tokenEstimator = tokenEstimator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildRuntimeIndex() {
        var chunks = repository.findAllChunks().stream().map(this::toVectorChunk).toList();
        vectorStore.upsert(chunks);
    }

    public IngestionResult ingest(String courseId, String title, String sourceType, String sourceUri, String content) {
        var context = TenantContext.current();
        var documentId = UUID.randomUUID().toString();
        var pieces = split(content);
        var rows = new ArrayList<KnowledgeChunkRow>();
        for (int index = 0; index < pieces.size(); index++) {
            var piece = pieces.get(index);
            rows.add(new KnowledgeChunkRow(UUID.randomUUID().toString(), context.tenantId(), documentId, courseId,
                    index, piece, tokenEstimator.estimate(piece), Map.of("title", title)));
        }
        var document = new KnowledgeDocument(documentId, context.tenantId(), courseId, title,
                sourceType, sourceUri, Hashing.sha256(content), context.userId());
        repository.insertDocument(document, rows);
        vectorStore.upsert(rows.stream().map(this::toVectorChunk).toList());
        return new IngestionResult(documentId, rows.size(), vectorStore.provider(), "INDEXED");
    }

    public List<KnowledgeHit> search(String courseId, String query, int limit) {
        return vectorStore.search(TenantContext.current().tenantId(), courseId, embeddingService.embed(query), limit, 0.05);
    }

    public List<Map<String, Object>> listDocuments() {
        return repository.listDocuments(TenantContext.current().tenantId());
    }

    private VectorChunk toVectorChunk(KnowledgeChunkRow row) {
        return new VectorChunk(row.id(), row.tenantId(), row.documentId(), row.courseId(), row.content(),
                row.metadata(), embeddingService.embed(row.content()));
    }

    private List<String> split(String content) {
        var cleaned = content.replace("\r\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        if (cleaned.length() <= CHUNK_SIZE) {
            return List.of(cleaned);
        }
        var chunks = new ArrayList<String>();
        var start = 0;
        while (start < cleaned.length()) {
            var end = Math.min(start + CHUNK_SIZE, cleaned.length());
            if (end < cleaned.length()) {
                var paragraphBreak = cleaned.lastIndexOf("\n\n", end);
                if (paragraphBreak > start + CHUNK_SIZE / 2) {
                    end = paragraphBreak;
                }
            }
            chunks.add(cleaned.substring(start, end).trim());
            if (end >= cleaned.length()) {
                break;
            }
            start = Math.max(start + 1, end - CHUNK_OVERLAP);
        }
        return chunks;
    }

    public record IngestionResult(String documentId, int chunkCount, String vectorProvider, String status) {
    }
}
