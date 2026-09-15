package com.xiaomi.education.intervention;

import com.xiaomi.education.ai.middleware.PostModelMiddleware;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class InterventionWorkflowIntegrationTest {

    private final InterventionService service;
    private TenantContext.Scope tenantScope;

    @Autowired
    InterventionWorkflowIntegrationTest(InterventionService service) {
        this.service = service;
    }

    @BeforeEach
    void openInstructorContext() {
        tenantScope = TenantContext.open(new TenantContext("tenant-demo", "instructor-001", "INSTRUCTOR"));
    }

    @AfterEach
    void closeInstructorContext() {
        tenantScope.close();
    }

    @Test
    void pausesSensitiveToolUntilApprovalThenExecutesExactlyOnce() {
        var created = service.create(
                "learner-001",
                "course-data",
                "针对近期进度下降，发送友善的学习提醒，并给出一个今天就能完成的下一步。"
        );
        var approvalId = created.approval().id();

        assertThat(created.message())
                .startsWith(PostModelMiddleware.INTERVENTION_PREFIX)
                .endsWith(PostModelMiddleware.INTERVENTION_SUFFIX);
        assertThat(created.postModelChecks()).contains(
                "NON_EMPTY_VALIDATED",
                "LENGTH_VALIDATED",
                "SAFETY_LANGUAGE_VALIDATED",
                "STANDARD_FORMAT_APPLIED"
        );
        assertThat(created.approval().status()).isEqualTo("PENDING_APPROVAL");
        assertThat(created.approval().riskLevel()).isEqualTo("HIGH");
        assertThat(created.approval().postModelChecks()).containsAll(created.postModelChecks());
        assertThat(service.notifications())
                .noneMatch(notification -> approvalId.equals(notification.approvalId()));

        var approved = service.approve(approvalId, "措辞友善且建议可执行，同意发送");

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(service.notifications())
                .filteredOn(notification -> approvalId.equals(notification.approvalId()))
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.content()).isEqualTo(created.message());
                    assertThat(notification.learnerId()).isEqualTo("learner-001");
                    assertThat(notification.courseId()).isEqualTo("course-data");
                });

        var repeated = service.approve(approvalId, "重复批准不应再次发送");

        assertThat(repeated).isEqualTo(approved);
        assertThat(service.notifications())
                .filteredOn(notification -> approvalId.equals(notification.approvalId()))
                .hasSize(1);
    }

    @Test
    void concurrentApprovalsReplayOnePersistedDecisionAndSendOnce() throws Exception {
        var created = service.create(
                "learner-001",
                "course-data",
                "并发重试审批时，只允许产生一条学习支持通知。"
        );
        var approvalId = created.approval().id();
        var workers = 8;
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(workers)) {
            var futures = new ArrayList<Future<InterventionService.ApprovalView>>();
            for (var index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> withInstructorContext(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return service.approve(approvalId, "并发审批使用同一决策");
                })));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var decisions = new ArrayList<InterventionService.ApprovalView>();
            for (var future : futures) {
                decisions.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(decisions)
                    .hasSize(workers)
                    .allSatisfy(decision -> assertThat(decision).isEqualTo(decisions.getFirst()));
            assertThat(decisions.getFirst().status()).isEqualTo("APPROVED");
        }

        assertThat(service.notifications())
                .filteredOn(notification -> approvalId.equals(notification.approvalId()))
                .hasSize(1);
    }

    @Test
    void concurrentOppositeDecisionsAreFirstWriterWinsAndCannotBeReversed() throws Exception {
        var created = service.create(
                "learner-001",
                "course-java",
                "并发批准和拒绝发生冲突时，必须只保留一个不可逆的人工决策。"
        );
        var approvalId = created.approval().id();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var approved = executor.submit(decisionTask(approvalId, true, ready, start));
            var rejected = executor.submit(decisionTask(approvalId, false, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var outcomes = java.util.List.of(outcome(approved), outcome(rejected));
            assertThat(outcomes).filteredOn(DecisionOutcome::succeeded).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
                    .singleElement()
                    .extracting(DecisionOutcome::errorCode)
                    .isEqualTo("APPROVAL_ALREADY_DECIDED");

            var winner = outcomes.stream().filter(DecisionOutcome::succeeded).findFirst().orElseThrow().approval();
            var notificationCount = service.notifications().stream()
                    .filter(notification -> approvalId.equals(notification.approvalId()))
                    .count();
            assertThat(notificationCount).isEqualTo("APPROVED".equals(winner.status()) ? 1 : 0);

            if ("APPROVED".equals(winner.status())) {
                assertThatThrownBy(() -> service.reject(approvalId, "终态不可反转"))
                        .isInstanceOf(ApiException.class)
                        .extracting(exception -> ((ApiException) exception).code())
                        .isEqualTo("APPROVAL_ALREADY_DECIDED");
            } else {
                assertThatThrownBy(() -> service.approve(approvalId, "终态不可反转"))
                        .isInstanceOf(ApiException.class)
                        .extracting(exception -> ((ApiException) exception).code())
                        .isEqualTo("APPROVAL_ALREADY_DECIDED");
            }
        }
    }

    @Test
    void rejectionProducesNoNotificationAndCannotBeReversed() {
        var created = service.create(
                "learner-001",
                "course-java",
                "生成一条支持性的学习提醒，但由教师决定是否真的发送给学习者。"
        );
        var approvalId = created.approval().id();

        var rejected = service.reject(approvalId, "本次不需要联系学习者");

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(service.notifications())
                .noneMatch(notification -> approvalId.equals(notification.approvalId()));
        assertThatThrownBy(() -> service.approve(approvalId, "尝试反转决策"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("APPROVAL_ALREADY_DECIDED");
    }

    @Test
    void approvalCannotBeObservedOrDecidedAcrossTenantBoundary() {
        var created = service.create(
                "learner-001",
                "course-data",
                "审批记录和通知副作用都必须保持租户隔离。"
        );
        var approvalId = created.approval().id();

        try (var ignored = TenantContext.open(new TenantContext("tenant-other", "instructor-other", "INSTRUCTOR"))) {
            assertThat(service.approvals()).noneMatch(approval -> approvalId.equals(approval.id()));
            assertThatThrownBy(() -> service.approve(approvalId, "越权审批"))
                    .isInstanceOf(ApiException.class)
                    .extracting(exception -> ((ApiException) exception).code())
                    .isEqualTo("APPROVAL_NOT_FOUND");
            assertThat(service.notifications()).noneMatch(notification -> approvalId.equals(notification.approvalId()));
        }

        assertThat(service.approve(approvalId, "原租户正常审批").status()).isEqualTo("APPROVED");
    }

    private Callable<InterventionService.ApprovalView> decisionTask(
            String approvalId,
            boolean approve,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> withInstructorContext(() -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return approve
                    ? service.approve(approvalId, "并发批准")
                    : service.reject(approvalId, "并发拒绝");
        });
    }

    private DecisionOutcome outcome(Future<InterventionService.ApprovalView> future) throws Exception {
        try {
            return new DecisionOutcome(future.get(10, TimeUnit.SECONDS), null);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof ApiException apiException) {
                return new DecisionOutcome(null, apiException.code());
            }
            throw exception;
        }
    }

    private <T> T withInstructorContext(InterruptibleSupplier<T> action) throws Exception {
        try (var ignored = TenantContext.open(new TenantContext("tenant-demo", "instructor-001", "INSTRUCTOR"))) {
            return action.get();
        }
    }

    private record DecisionOutcome(InterventionService.ApprovalView approval, String errorCode) {
        boolean succeeded() {
            return approval != null;
        }
    }

    @FunctionalInterface
    private interface InterruptibleSupplier<T> {
        T get() throws Exception;
    }
}
