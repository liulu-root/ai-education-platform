package com.xiaomi.education.grading;

import com.xiaomi.education.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class GradingRepository {

    private final JdbcTemplate jdbcTemplate;

    public GradingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AssignmentData assignment(String tenantId, String assignmentId) {
        var results = jdbcTemplate.query("""
                        SELECT id, course_id, title, instructions, rubric_json, max_score
                        FROM assignment WHERE tenant_id = ? AND id = ?
                        """, (resultSet, rowNum) -> new AssignmentData(
                resultSet.getString("id"), resultSet.getString("course_id"), resultSet.getString("title"),
                resultSet.getString("instructions"), resultSet.getString("rubric_json"),
                resultSet.getBigDecimal("max_score")
        ), tenantId, assignmentId);
        if (results.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND", "作业不存在");
        }
        return results.getFirst();
    }

    public void saveSubmission(
            String id, String tenantId, String assignmentId, String learnerId, String answer,
            BigDecimal score, String feedback, double confidence, String reviewStatus
    ) {
        jdbcTemplate.update("""
                        INSERT INTO submission (
                            id, tenant_id, assignment_id, learner_id, answer_text,
                            ai_score, ai_feedback, confidence, review_status, graded_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, id, tenantId, assignmentId, learnerId, answer, score, feedback,
                BigDecimal.valueOf(confidence), reviewStatus, Timestamp.from(Instant.now()));
    }

    public List<Map<String, Object>> submissions(String tenantId, String learnerId) {
        return jdbcTemplate.queryForList("""
                        SELECT s.id, a.title AS assignment_title, s.ai_score, a.max_score,
                               s.confidence, s.review_status, s.submitted_at, s.graded_at
                        FROM submission s JOIN assignment a ON a.id = s.assignment_id
                        WHERE s.tenant_id = ? AND s.learner_id = ?
                        ORDER BY s.submitted_at DESC
                        """, tenantId, learnerId);
    }

    public record AssignmentData(
            String id, String courseId, String title, String instructions, String rubricJson, BigDecimal maxScore
    ) {
    }
}
