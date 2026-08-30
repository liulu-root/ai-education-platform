package com.xiaomi.education.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping("/documents")
    public KnowledgeService.IngestionResult ingest(@Valid @RequestBody IngestRequest request) {
        return knowledgeService.ingest(request.courseId(), request.title(), request.sourceType(),
                request.sourceUri(), request.content());
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> documents() {
        return knowledgeService.listDocuments();
    }

    @GetMapping("/search")
    public List<KnowledgeHit> search(
            @RequestParam(required = false) String courseId,
            @RequestParam @NotBlank String query,
            @RequestParam(defaultValue = "5") int limit
    ) {
        return knowledgeService.search(courseId, query, limit);
    }

    public record IngestRequest(
            String courseId,
            @NotBlank @Size(max = 256) String title,
            @NotBlank String sourceType,
            String sourceUri,
            @NotBlank @Size(max = 100000) String content
    ) {
    }
}
