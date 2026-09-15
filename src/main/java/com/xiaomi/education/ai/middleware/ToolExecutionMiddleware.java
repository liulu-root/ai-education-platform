package com.xiaomi.education.ai.middleware;

import com.xiaomi.education.ai.model.AiScenario;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ToolExecutionMiddleware {

    private final ToolApprovalRepository repository;
    private final Map<String, SensitiveToolHandler> handlers;

    public ToolExecutionMiddleware(
            ToolApprovalRepository repository,
            List<SensitiveToolHandler> handlers
    ) {
        this.repository = repository;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                SensitiveToolHandler::toolName,
                Function.identity()
        ));
    }

    @Transactional
    public ToolApproval requestApproval(SensitiveToolInvocation invocation) {
        if (!handlers.containsKey(invocation.toolName())) {
            throw new IllegalArgumentException("No sensitive tool handler registered for " + invocation.toolName());
        }
        var context = TenantContext.current();
        var approval = new ToolApproval(
                UUID.randomUUID().toString(),
                context.tenantId(),
                invocation.scenario(),
                invocation.toolName(),
                invocation.resourceType(),
                invocation.resourceId(),
                invocation.arguments(),
                ToolApprovalStatus.PENDING_APPROVAL,
                context.userId(),
                Instant.now(),
                null,
                null,
                null
        );
        repository.create(approval);
        return approval;
    }

    @Transactional
    public ToolApproval approve(
            String approvalId,
            AiScenario expectedScenario,
            String expectedToolName,
            String expectedResourceType,
            String comment
    ) {
        var context = TenantContext.current();
        var approval = repository.findForUpdate(context.tenantId(), approvalId);
        assertExpectedApproval(approval, expectedScenario, expectedToolName, expectedResourceType);
        if (approval.status() == ToolApprovalStatus.APPROVED) {
            return approval;
        }
        if (approval.status() == ToolApprovalStatus.REJECTED) {
            throw alreadyDecided(approval);
        }

        var handler = handlers.get(approval.toolName());
        if (handler == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "TOOL_HANDLER_UNAVAILABLE",
                    "敏感工具当前不可用，审批仍保持待处理状态"
            );
        }

        handler.execute(approval, context.userId());
        return repository.markDecision(
                approval,
                ToolApprovalStatus.APPROVED,
                context.userId(),
                Instant.now(),
                comment
        );
    }

    @Transactional
    public ToolApproval reject(
            String approvalId,
            AiScenario expectedScenario,
            String expectedToolName,
            String expectedResourceType,
            String comment
    ) {
        var context = TenantContext.current();
        var approval = repository.findForUpdate(context.tenantId(), approvalId);
        assertExpectedApproval(approval, expectedScenario, expectedToolName, expectedResourceType);
        if (approval.status() == ToolApprovalStatus.REJECTED) {
            return approval;
        }
        if (approval.status() == ToolApprovalStatus.APPROVED) {
            throw alreadyDecided(approval);
        }
        return repository.markDecision(
                approval,
                ToolApprovalStatus.REJECTED,
                context.userId(),
                Instant.now(),
                comment
        );
    }

    public List<ToolApproval> approvals() {
        return repository.list(TenantContext.current().tenantId());
    }

    private void assertExpectedApproval(
            ToolApproval approval,
            AiScenario expectedScenario,
            String expectedToolName,
            String expectedResourceType
    ) {
        if (approval.scenario() != expectedScenario
                || !approval.toolName().equals(expectedToolName)
                || !approval.resourceType().equals(expectedResourceType)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", "审批记录不存在");
        }
    }

    private ApiException alreadyDecided(ToolApproval approval) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "APPROVAL_ALREADY_DECIDED",
                "审批已完成，不能改为相反决策",
                List.of(approval.status().name())
        );
    }
}
