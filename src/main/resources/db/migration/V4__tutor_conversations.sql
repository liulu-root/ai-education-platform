CREATE TABLE tutor_conversation (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    learner_id VARCHAR(36) NOT NULL,
    course_id VARCHAR(36),
    title VARCHAR(256) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tutor_message (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    role VARCHAR(24) NOT NULL,
    content TEXT NOT NULL,
    trace_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tutor_message_conversation FOREIGN KEY (conversation_id) REFERENCES tutor_conversation(id)
);

CREATE INDEX idx_tutor_message_conversation ON tutor_message(conversation_id, created_at);
