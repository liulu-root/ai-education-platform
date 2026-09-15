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

    @Transactional
    public void saveMessage(String id, String conversationId, String role, String content, String traceId) {
        jdbcTemplate.update("UPDATE tutor_conversation SET next_message_seq = next_message_seq + 1 WHERE id = ?", conversationId);
        var sequence = jdbcTemplate.queryForObject("SELECT next_message_seq FROM tutor_conversation WHERE id = ?", Long.class, conversationId);
        jdbcTemplate.update("""
                        INSERT INTO tutor_message (id, conversation_id, role, content, trace_id, message_seq)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, id, conversationId, role, content, traceId, sequence);
        jdbcTemplate.update("UPDATE tutor_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", conversationId);
    }

    public List<Map<String, Object>> recentMessages(String conversationId, int limit) {
        return jdbcTemplate.queryForList("""
                        SELECT role, content, trace_id, created_at
                        FROM (
                            SELECT role, content, trace_id, created_at, message_seq
                            FROM tutor_message
                            WHERE conversation_id = ?
                            ORDER BY message_seq DESC
                            LIMIT ?
                        ) recent
                        ORDER BY message_seq
                        """, conversationId, Math.min(Math.max(limit, 1), 20));
    }

    public boolean acquire(String id, String owner, int seconds) {
        return jdbcTemplate.update("""
                UPDATE tutor_conversation SET generation_owner = ?, generation_expires_at = ?
                WHERE id = ? AND (generation_owner IS NULL OR generation_expires_at < CURRENT_TIMESTAMP)
                """, owner, java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(seconds)), id) == 1;
    }

    public void release(String id, String owner) {
        jdbcTemplate.update("UPDATE tutor_conversation SET generation_owner = NULL, generation_expires_at = NULL WHERE id = ? AND generation_owner = ?", id, owner);
    }

    public void assertLease(String id, String owner) {
        var count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tutor_conversation WHERE id = ? AND generation_owner = ? AND generation_expires_at > CURRENT_TIMESTAMP", Integer.class, id, owner);
        if (count == null || count != 1) throw new com.xiaomi.education.common.ApiException(org.springframework.http.HttpStatus.CONFLICT, "CONVERSATION_LEASE_LOST", "会话生成已过期，请重新提问");
    }

    public List<Message> messages(String id) {
        return jdbcTemplate.query("SELECT message_seq, role, content FROM tutor_message WHERE conversation_id = ? ORDER BY message_seq",
                (rs, row) -> new Message(rs.getLong(1), rs.getString(2), rs.getString(3)), id);
    }

    public Memory memory(String id) {
        var rows = jdbcTemplate.query("SELECT summary_content, covered_through_seq, version FROM tutor_conversation_memory WHERE conversation_id = ?",
                (rs, row) -> new Memory(rs.getString(1), rs.getLong(2), rs.getLong(3)), id);
        return rows.isEmpty() ? new Memory("", 0, -1) : rows.getFirst();
    }

    @Transactional
    public boolean saveMemory(String id, String owner, Memory previous, String content, long through, int tokens, String model) {
        // Lock only for the short persistence transaction, never while waiting for a model.
        jdbcTemplate.queryForObject("SELECT next_message_seq FROM tutor_conversation WHERE id = ? FOR UPDATE", Long.class, id);
        assertLease(id, owner);
        if (previous.version() == -1) {
            if (memory(id).version() != -1) return false;
            jdbcTemplate.update("INSERT INTO tutor_conversation_memory (conversation_id, summary_content, covered_through_seq, estimated_tokens, version, summary_model, summary_version) VALUES (?, ?, ?, ?, 0, ?, '1.0.0')", id, content, through, tokens, model);
            return true;
        }
        return jdbcTemplate.update("UPDATE tutor_conversation_memory SET summary_content = ?, covered_through_seq = ?, estimated_tokens = ?, version = version + 1, summary_model = ?, summary_version = '1.0.0', updated_at = CURRENT_TIMESTAMP WHERE conversation_id = ? AND version = ?",
                content, through, tokens, model, id, previous.version()) == 1;
    }

    @Transactional
    public void saveLeasedMessage(String id, String owner, String role, String content, String trace) {
        jdbcTemplate.queryForObject("SELECT next_message_seq FROM tutor_conversation WHERE id = ? FOR UPDATE", Long.class, id);
        assertLease(id, owner);
        saveMessage(java.util.UUID.randomUUID().toString(), id, role, content, trace);
    }

    public void recordAttempt(String requestId, String id, int attempt, TutorContextBuilder.Built built, String status) {
        jdbcTemplate.update("INSERT INTO tutor_context_attempt (id, request_id, conversation_id, attempt, input_tokens, input_budget, retrieved_chunks, history_messages, degraded, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                java.util.UUID.randomUUID().toString(), requestId, id, attempt, built.tokens(), built.budget(), built.hits().size(), built.historyCount(), built.degraded(), status);
    }

    public record Message(long sequence, String role, String content) { }
    public record Memory(String content, long through, long version) { }

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
