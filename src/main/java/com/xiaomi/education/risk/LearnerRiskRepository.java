package com.xiaomi.education.risk;

import com.xiaomi.education.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class LearnerRiskRepository {

    private final JdbcTemplate jdbcTemplate;

    public LearnerRiskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RiskFeatures features(String tenantId, String learnerId, String courseId) {
        var rows = jdbcTemplate.query("""
                        SELECT e.progress_percent, e.status, e.last_active_at,
                               COALESCE(AVG(m.mastery), 0) AS average_mastery,
                               COUNT(DISTINCT m.id) AS mastery_evidence,
                               COUNT(DISTINCT a.id) AS activities_14d,
                               COALESCE(SUM(a.duration_seconds), 0) AS study_seconds_14d
                        FROM enrollment e
                        LEFT JOIN mastery_record m ON m.learner_id = e.learner_id AND m.course_id = e.course_id
                        LEFT JOIN learner_activity a ON a.learner_id = e.learner_id AND a.course_id = e.course_id
                             AND a.occurred_at >= ?
                        WHERE e.tenant_id = ? AND e.learner_id = ? AND e.course_id = ?
                        GROUP BY e.progress_percent, e.status, e.last_active_at
                        """, (resultSet, rowNum) -> new RiskFeatures(
                resultSet.getDouble("progress_percent"), resultSet.getString("status"),
                resultSet.getTimestamp("last_active_at") == null ? null : resultSet.getTimestamp("last_active_at").toInstant(),
                resultSet.getDouble("average_mastery"), resultSet.getInt("mastery_evidence"),
                resultSet.getInt("activities_14d"), resultSet.getLong("study_seconds_14d")
        ), java.sql.Timestamp.from(Instant.now().minusSeconds(14L * 86400)), tenantId, learnerId, courseId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ENROLLMENT_NOT_FOUND", "未找到课程报名记录");
        }
        return rows.getFirst();
    }

    public record RiskFeatures(
            double progressPercent,
            String enrollmentStatus,
            Instant lastActiveAt,
            double averageMastery,
            int masteryEvidence,
            int activities14d,
            long studySeconds14d
    ) {
    }
}
