package com.xiaomi.education.learning;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/learning-paths")
public class LearningPathController {

    private final LearningPathService service;

    public LearningPathController(LearningPathService service) {
        this.service = service;
    }

    @PostMapping
    public LearningPathService.LearningPathResult generate(@Valid @RequestBody LearningPathRequest request) {
        return service.generate(request.courseId(), request.goal(), request.weeklyMinutes());
    }

    @GetMapping
    public List<Map<String, Object>> plans() {
        return service.plans();
    }

    public record LearningPathRequest(
            @NotBlank String courseId,
            @NotBlank @Size(max = 500) String goal,
            @Min(60) @Max(1200) int weeklyMinutes
    ) {
    }
}
