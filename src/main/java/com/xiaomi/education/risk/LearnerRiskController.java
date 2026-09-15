package com.xiaomi.education.risk;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/risk")
public class LearnerRiskController {

    private final LearnerRiskService service;

    public LearnerRiskController(LearnerRiskService service) {
        this.service = service;
    }

    @GetMapping("/assessment")
    public LearnerRiskService.RiskAssessment assess(@RequestParam String learnerId, @RequestParam String courseId) {
        return service.assessLearner(learnerId, courseId, false);
    }

    @PostMapping("/explain")
    public LearnerRiskService.RiskAssessment explain(@RequestParam String learnerId, @RequestParam String courseId) {
        return service.assessLearner(learnerId, courseId, true);
    }
}
