package com.xiaomi.education.tutor;

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
@RequestMapping("/api/ai/tutor")
public class TutorController {

    private final TutorService tutorService;

    public TutorController(TutorService tutorService) {
        this.tutorService = tutorService;
    }

    @PostMapping("/chat")
    public TutorService.TutorResponse chat(@Valid @RequestBody TutorChatRequest request) {
        return tutorService.chat(request.conversationId(), request.courseId(), request.question());
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations() {
        return tutorService.conversations();
    }

    public record TutorChatRequest(
            String conversationId,
            @NotBlank String courseId,
            @NotBlank @Size(max = 4000) String question
    ) {
    }
}
