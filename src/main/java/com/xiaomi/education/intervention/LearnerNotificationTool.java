package com.xiaomi.education.intervention;

import com.xiaomi.education.ai.middleware.SensitiveToolHandler;
import com.xiaomi.education.ai.middleware.ToolApproval;
import com.xiaomi.education.ai.model.AiScenario;
import com.xiaomi.education.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class LearnerNotificationTool implements SensitiveToolHandler {

    public static final String TOOL_NAME = "sendLearnerNotification";

    private final InterventionRepository repository;

    public LearnerNotificationTool(InterventionRepository repository) {
        this.repository = repository;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    @Override
    public void execute(ToolApproval approval, String approvedBy) {
        if (approval.scenario() != AiScenario.LEARNER_INTERVENTION
                || !"learner_intervention".equals(approval.resourceType())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "INVALID_TOOL_APPROVAL",
                    "审批记录与通知工具不匹配"
            );
        }
        repository.saveNotification(
                UUID.randomUUID().toString(),
                approval.tenantId(),
                approval.id(),
                approval.resourceId(),
                requiredArgument(approval, "learnerId"),
                requiredArgument(approval, "courseId"),
                requiredArgument(approval, "message"),
                approvedBy
        );
    }

    private String requiredArgument(ToolApproval approval, String name) {
        var value = approval.arguments().get(name);
        if (value == null || value.isBlank()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "INVALID_TOOL_ARGUMENTS",
                    "敏感工具参数不完整",
                    List.of(name)
            );
        }
        return value;
    }
}
