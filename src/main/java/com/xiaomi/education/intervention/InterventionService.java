package com.xiaomi.education.intervention;

import com.xiaomi.education.ai.AiGateway;
import com.xiaomi.education.ai.audit.AiCallResult;
import com.xiaomi.education.ai.middleware.SensitiveToolInvocation;
import com.xiaomi.education.ai.middleware.ToolApproval;
import com.xiaomi.education.ai.middleware.ToolExecutionMiddleware;
import com.xiaomi.education.ai.model.AiModelRequest;
import com.xiaomi.education.ai.model.AiScenario;
import com.xiaomi.education.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class InterventionService {

    private static final String SYSTEM_PROMPT = """
            你是学习支持消息助手。根据教师给出的干预目标生成一则简短、尊重且可执行的学习提醒。
            不得使用羞辱、威胁、处分、扣分、退学或其他惩罚性措辞，不得虚构事实或承诺结果。
            不要输出手机号、邮箱、身份证号等个人信息。只生成面向学习者的消息正文。
            """;

    private final InterventionRepository repository;
    private final AiGateway aiGateway;
    private final ToolExecutionMiddleware toolMiddleware;
    private final TransactionTemplate transactionTemplate;

    public InterventionService(
            InterventionRepository repository,
            AiGateway aiGateway,
            ToolExecutionMiddleware toolMiddleware,
            TransactionTemplate transactionTemplate
    ) {
        this.repository = repository;
        this.aiGateway = aiGateway;
        this.toolMiddleware = toolMiddleware;
        this.transactionTemplate = transactionTemplate;
    }

    public InterventionResponse create(String learnerId, String courseId, String objective) {
        var context = TenantContext.current();
        var normalizedObjective = objective.strip();
        repository.assertEnrollment(context.tenantId(), learnerId, courseId);

        var prompt = "学习者：" + learnerId
                + "\n课程：" + courseId
                + "\n教师的干预目标：" + normalizedObjective
                + "\n请给出一则具体、友善、非惩罚性的学习支持消息。";
        var usage = aiGateway.execute(new AiModelRequest(
                AiScenario.LEARNER_INTERVENTION,
                SYSTEM_PROMPT,
                prompt,
                Map.of("learnerId", learnerId, "courseId", courseId, "objective", normalizedObjective)
        ));

        var interventionId = UUID.randomUUID().toString();
        var approval = Objects.requireNonNull(transactionTemplate.execute(status -> {
            repository.saveIntervention(
                    interventionId,
                    usage.traceId(),
                    context.tenantId(),
                    learnerId,
                    courseId,
                    normalizedObjective,
                    usage.content(),
                    usage.middlewareChecks(),
                    context.userId()
            );
            return toolMiddleware.requestApproval(new SensitiveToolInvocation(
                    AiScenario.LEARNER_INTERVENTION,
                    LearnerNotificationTool.TOOL_NAME,
                    "learner_intervention",
                    interventionId,
                    Map.of(
                            "learnerId", learnerId,
                            "courseId", courseId,
                            "message", usage.content(),
                            "riskLevel", "HIGH",
                            "postModelChecks", String.join(",", usage.middlewareChecks())
                    )
            ));
        }));

        return new InterventionResponse(
                interventionId,
                learnerId,
                courseId,
                normalizedObjective,
                usage.content(),
                usage.middlewareChecks(),
                approvalView(approval),
                usage
        );
    }

    public List<ApprovalView> approvals() {
        return toolMiddleware.approvals().stream()
                .filter(approval -> approval.scenario() == AiScenario.LEARNER_INTERVENTION)
                .filter(approval -> LearnerNotificationTool.TOOL_NAME.equals(approval.toolName()))
                .map(this::approvalView)
                .toList();
    }

    public ApprovalView approve(String approvalId, String comment) {
        return approvalView(toolMiddleware.approve(
                approvalId,
                AiScenario.LEARNER_INTERVENTION,
                LearnerNotificationTool.TOOL_NAME,
                "learner_intervention",
                comment
        ));
    }

    public ApprovalView reject(String approvalId, String comment) {
        return approvalView(toolMiddleware.reject(
                approvalId,
                AiScenario.LEARNER_INTERVENTION,
                LearnerNotificationTool.TOOL_NAME,
                "learner_intervention",
                comment
        ));
    }

    public List<NotificationView> notifications() {
        return repository.notifications(TenantContext.current().tenantId()).stream()
                .map(notification -> new NotificationView(
                        notification.id(),
                        notification.approvalId(),
                        notification.interventionId(),
                        notification.learnerId(),
                        notification.courseId(),
                        notification.channel(),
                        notification.content(),
                        notification.sentBy(),
                        notification.sentAt()
                ))
                .toList();
    }

    private ApprovalView approvalView(ToolApproval approval) {
        return new ApprovalView(
                approval.id(),
                approval.resourceId(),
                approval.toolName(),
                approval.status().name(),
                approval.arguments().getOrDefault("riskLevel", "HIGH"),
                parseChecks(approval.arguments().get("postModelChecks")),
                approval.arguments().get("learnerId"),
                approval.arguments().get("courseId"),
                approval.arguments().get("message"),
                approval.requestedBy(),
                approval.requestedAt(),
                approval.decidedBy(),
                approval.decidedAt(),
                approval.comment()
        );
    }

    private List<String> parseChecks(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(check -> !check.isEmpty())
                .toList();
    }

    public record InterventionResponse(
            String interventionId,
            String learnerId,
            String courseId,
            String objective,
            String message,
            List<String> postModelChecks,
            ApprovalView approval,
            AiCallResult usage
    ) {
    }

    public record ApprovalView(
            String id,
            String interventionId,
            String toolName,
            String status,
            String riskLevel,
            List<String> postModelChecks,
            String learnerId,
            String courseId,
            String message,
            String requestedBy,
            Instant requestedAt,
            String decidedBy,
            Instant decidedAt,
            String comment
    ) {
    }

    public record NotificationView(
            String id,
            String approvalId,
            String interventionId,
            String learnerId,
            String courseId,
            String channel,
            String content,
            String sentBy,
            Instant sentAt
    ) {
    }
}
