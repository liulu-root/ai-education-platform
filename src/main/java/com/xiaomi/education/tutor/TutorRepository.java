package com.xiaomi.education.tutor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Repository
public class TutorRepository {

    private final JdbcTemplate jdbcTemplate;

    public TutorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void createConversation(String id, String tenantId, String learnerId, String courseId, String title) {
        jdbcTemplate.update("""
                        INSERT INTO tutor_conversation (id, tenant_id, learner_id, course_id, title, status)
                        VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                        """, id, tenantId, learnerId, courseId, title);
    }

    public boolean belongsTo(String conversationId, String tenantId, String learnerId) {
        var count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM tutor_conversation
                        WHERE id = ? AND tenant_id = ? AND learner_id = ?
                        """, Long.class, conversationId, tenantId, learnerId);
        return count != null && count > 0;
    }

    public void saveMessage(String id, String conversationId, String role, String content, String traceId) {
        jdbcTemplate.update("""
                        INSERT INTO tutor_message (id, conversation_id, role, content, trace_id)
                        VALUES (?, ?, ?, ?, ?)
                        """, id, conversationId, role, content, traceId);
        jdbcTemplate.update("UPDATE tutor_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", conversationId);
    }

    public List<Map<String, Object>> recentMessages(String conversationId, int limit) {
        return jdbcTemplate.queryForList("""
                        SELECT role, content, trace_id, created_at
                        FROM (
                            SELECT role, content, trace_id, created_at
                            FROM tutor_message
                            WHERE conversation_id = ?
                            ORDER BY created_at DESC
                            LIMIT ?
                        ) recent
                        ORDER BY created_at
                        """, conversationId, Math.min(Math.max(limit, 1), 20));
    }

    public Map<String, Object> learnerProfile(String tenantId, String learnerId, String courseId) {
        var profile = jdbcTemplate.queryForMap("""
                        SELECT c.title AS course_title, e.progress_percent, e.status,
                               COALESCE(AVG(m.mastery), 0) AS average_mastery,
                               COUNT(m.id) AS assessed_skills
                        FROM enrollment e
                        JOIN course c ON c.id = e.course_id
                        LEFT JOIN mastery_record m ON m.learner_id = e.learner_id AND m.course_id = e.course_id
                        WHERE e.tenant_id = ? AND e.learner_id = ? AND e.course_id = ?
                        GROUP BY c.title, e.progress_percent, e.status
                        """, tenantId, learnerId, courseId);
        var weakSkills = jdbcTemplate.queryForList("""
                        SELECT skill_name, mastery FROM mastery_record
                        WHERE tenant_id = ? AND learner_id = ? AND course_id = ?
                        ORDER BY mastery ASC
                        LIMIT 3
                        """, tenantId, learnerId, courseId);
        profile.put("weak_skills", weakSkills);
        return profile;
    }

    public List<Map<String, Object>> conversations(String tenantId, String learnerId) {
        return jdbcTemplate.queryForList("""
                        SELECT id, course_id, title, status, created_at, updated_at
                        FROM tutor_conversation
                        WHERE tenant_id = ? AND learner_id = ?
                        ORDER BY updated_at DESC
                        LIMIT 50
                        """, tenantId, learnerId);
    }
}
