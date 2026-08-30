package com.xiaomi.education.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class LearningPathRepository {

    private final JdbcTemplate jdbcTemplate;

    public LearningPathRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SkillMastery> mastery(String tenantId, String learnerId, String courseId) {
        return jdbcTemplate.query("""
                        SELECT skill_code, skill_name, mastery, evidence_count
                        FROM mastery_record
                        WHERE tenant_id = ? AND learner_id = ? AND course_id = ?
                        ORDER BY mastery ASC, evidence_count ASC
                        """, (resultSet, rowNum) -> new SkillMastery(
                resultSet.getString("skill_code"), resultSet.getString("skill_name"),
                resultSet.getDouble("mastery"), resultSet.getInt("evidence_count")
        ), tenantId, learnerId, courseId);
    }

    @Transactional
    public void savePlan(
            String planId, String tenantId, String learnerId, String courseId, String goal,
            String rationale, List<LearningPlanItem> items
    ) {
        jdbcTemplate.update("""
                        INSERT INTO learning_plan (id, tenant_id, learner_id, course_id, goal, rationale, status)
                        VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                        """, planId, tenantId, learnerId, courseId, goal, rationale);
        for (var item : items) {
            jdbcTemplate.update("""
                            INSERT INTO learning_plan_item (
                                id, plan_id, sequence_no, skill_code, activity, estimated_minutes, completion_status
                            ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                            """, UUID.randomUUID().toString(), planId, item.sequence(), item.skillCode(),
                    item.activity(), item.estimatedMinutes());
        }
    }

    public List<Map<String, Object>> plans(String tenantId, String learnerId) {
        return jdbcTemplate.queryForList("""
                        SELECT p.id, p.course_id, p.goal, p.rationale, p.status, p.created_at,
                               COUNT(i.id) AS item_count,
                               COALESCE(SUM(i.estimated_minutes), 0) AS estimated_minutes
                        FROM learning_plan p
                        LEFT JOIN learning_plan_item i ON i.plan_id = p.id
                        WHERE p.tenant_id = ? AND p.learner_id = ?
                        GROUP BY p.id, p.course_id, p.goal, p.rationale, p.status, p.created_at
                        ORDER BY p.created_at DESC
                        """, tenantId, learnerId);
    }

    public record SkillMastery(String code, String name, double mastery, int evidenceCount) {
    }

    public record LearningPlanItem(
            int sequence, String skillCode, String skillName, double currentMastery,
            String activity, int estimatedMinutes
    ) {
    }
}
