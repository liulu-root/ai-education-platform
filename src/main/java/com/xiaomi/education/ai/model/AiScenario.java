package com.xiaomi.education.ai.model;

public enum AiScenario {
    TUTOR_CHAT("tutor-rag", "1.0.0"),
    ASSIGNMENT_GRADING("rubric-grading", "1.0.0"),
    LEARNING_PATH("adaptive-path", "1.0.0"),
    RISK_EXPLANATION("dropout-risk", "1.0.0"),
    LEARNER_INTERVENTION("learner-intervention", "1.0.0"),
    CONTENT_SUMMARY("content-summary", "1.0.0"),
    CONVERSATION_SUMMARY("conversation-summary", "1.0.0");

    private final String promptTemplate;
    private final String promptVersion;

    AiScenario(String promptTemplate, String promptVersion) {
        this.promptTemplate = promptTemplate;
        this.promptVersion = promptVersion;
    }

    public String promptTemplate() {
        return promptTemplate;
    }

    public String promptVersion() {
        return promptVersion;
    }
}
