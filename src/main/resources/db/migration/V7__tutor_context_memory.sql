ALTER TABLE tutor_message ADD COLUMN message_seq BIGINT;
UPDATE tutor_message SET message_seq = (
    SELECT COUNT(*) FROM tutor_message older
    WHERE older.conversation_id = tutor_message.conversation_id
      AND (older.created_at < tutor_message.created_at
           OR (older.created_at = tutor_message.created_at AND older.id <= tutor_message.id))
);
ALTER TABLE tutor_message ALTER COLUMN message_seq SET NOT NULL;
CREATE UNIQUE INDEX idx_tutor_message_seq ON tutor_message(conversation_id, message_seq);

ALTER TABLE tutor_conversation ADD COLUMN next_message_seq BIGINT NOT NULL DEFAULT 0;
UPDATE tutor_conversation SET next_message_seq = (
    SELECT COALESCE(MAX(message_seq), 0) FROM tutor_message WHERE conversation_id = tutor_conversation.id
);
ALTER TABLE tutor_conversation ADD COLUMN generation_owner VARCHAR(36);
ALTER TABLE tutor_conversation ADD COLUMN generation_expires_at TIMESTAMP;

CREATE TABLE tutor_conversation_memory (
    conversation_id VARCHAR(36) PRIMARY KEY REFERENCES tutor_conversation(id),
    summary_content TEXT NOT NULL,
    covered_through_seq BIGINT NOT NULL DEFAULT 0,
    estimated_tokens INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    summary_model VARCHAR(128),
    summary_version VARCHAR(32),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tutor_context_attempt (
    id VARCHAR(36) PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL REFERENCES tutor_conversation(id),
    attempt INTEGER NOT NULL,
    input_tokens INTEGER NOT NULL,
    input_budget INTEGER NOT NULL,
    retrieved_chunks INTEGER NOT NULL,
    history_messages INTEGER NOT NULL,
    degraded BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
