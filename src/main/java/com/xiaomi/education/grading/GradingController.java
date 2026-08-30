package com.xiaomi.education.grading;

import jakarta.validation.Valid;
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
@RequestMapping("/api/ai/grading")
public class GradingController {

    private final GradingService gradingService;

    public GradingController(GradingService gradingService) {
        this.gradingService = gradingService;
    }

    @PostMapping
    public GradingService.GradingResult grade(@Valid @RequestBody GradeRequest request) {
        return gradingService.grade(request.assignmentId(), request.answer());
    }

    @GetMapping("/submissions")
    public List<Map<String, Object>> submissions() {
        return gradingService.submissions();
    }

    public record GradeRequest(
            @NotBlank String assignmentId,
            @NotBlank @Size(min = 20, max = 20000) String answer
    ) {
    }
}
