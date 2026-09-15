package com.xiaomi.education.intervention;

import com.xiaomi.education.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class InterventionRepository {

    private final JdbcTemplate jdbcTemplate;

    public InterventionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void assertEnrollment(String tenantId, String learnerId, String courseId) {
        var count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM enrollment
                        WHERE tenant_id = ? AND learner_id = ? AND course_id = ?
                        """, Integer.class, tenantId, learnerId, courseId);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ENROLLMENT_NOT_FOUND", "未找到该学习者的课程报名记录");
        }
    }

    public void saveIntervention(
            String id,
            String traceId,
            String tenantId,
            String learnerId,
            String courseId,
            String objective,
            String message,
            List<String> postModelChecks,
            String requestedBy
    ) {
        jdbcTemplate.update("""
                        INSERT INTO learner_intervention (
                            id, trace_id, tenant_id, learner_id, course_id, objective, message,
                            post_model_checks, requested_by, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                traceId,
                tenantId,
                learnerId,
                courseId,
                objective,
                message,
                String.join(",", postModelChecks),
                requestedBy,
                Timestamp.from(Instant.now())
        );
    }

    public void saveNotification(
            String id,
            String tenantId,
            String approvalId,
            String interventionId,
            String learnerId,
            String courseId,
            String content,
            String sentBy
    ) {
        jdbcTemplate.update("""
                        INSERT INTO learner_notification (
                            id, tenant_id, approval_id, intervention_id, learner_id,
                            course_id, channel, content, sent_by, sent_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                tenantId,
                approvalId,
                interventionId,
                learnerId,
                courseId,
                "IN_APP",
                content,
                sentBy,
                Timestamp.from(Instant.now())
        );
    }

    public List<NotificationData> notifications(String tenantId) {
        return jdbcTemplate.query("""
                        SELECT id, approval_id, intervention_id, learner_id, course_id,
                               channel, content, sent_by, sent_at
                        FROM learner_notification
                        WHERE tenant_id = ?
                        ORDER BY sent_at DESC, id DESC
                        """, (resultSet, rowNum) -> new NotificationData(
                resultSet.getString("id"),
                resultSet.getString("approval_id"),
                resultSet.getString("intervention_id"),
                resultSet.getString("learner_id"),
                resultSet.getString("course_id"),
                resultSet.getString("channel"),
                resultSet.getString("content"),
                resultSet.getString("sent_by"),
                resultSet.getTimestamp("sent_at").toInstant()
        ), tenantId);
    }

    public record NotificationData(
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
