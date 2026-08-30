CREATE TABLE tenant (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL,
    token_budget BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_user (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    email VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);

CREATE TABLE course (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL,
    title VARCHAR(256) NOT NULL,
    description TEXT,
    level VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_course_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_course_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);

CREATE TABLE enrollment (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    course_id VARCHAR(36) NOT NULL,
    learner_id VARCHAR(36) NOT NULL,
    progress_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    last_active_at TIMESTAMP,
    enrolled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_enrollment UNIQUE (course_id, learner_id),
    CONSTRAINT fk_enrollment_course FOREIGN KEY (course_id) REFERENCES course(id),
    CONSTRAINT fk_enrollment_user FOREIGN KEY (learner_id) REFERENCES app_user(id)
);

CREATE TABLE knowledge_document (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    course_id VARCHAR(36),
    title VARCHAR(256) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_uri VARCHAR(512),
    content_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_course FOREIGN KEY (course_id) REFERENCES course(id)
);

CREATE TABLE knowledge_chunk (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    document_id VARCHAR(36) NOT NULL,
    course_id VARCHAR(36),
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    token_count INTEGER NOT NULL,
    metadata_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_document_chunk UNIQUE (document_id, chunk_index),
    CONSTRAINT fk_chunk_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id)
);

CREATE TABLE assignment (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    course_id VARCHAR(36) NOT NULL,
    title VARCHAR(256) NOT NULL,
    instructions TEXT NOT NULL,
    rubric_json TEXT NOT NULL,
    max_score DECIMAL(7,2) NOT NULL,
    due_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_assignment_course FOREIGN KEY (course_id) REFERENCES course(id)
);

CREATE TABLE submission (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    assignment_id VARCHAR(36) NOT NULL,
    learner_id VARCHAR(36) NOT NULL,
    answer_text TEXT NOT NULL,
    ai_score DECIMAL(7,2),
    ai_feedback TEXT,
    confidence DECIMAL(5,4),
    review_status VARCHAR(24) NOT NULL,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    graded_at TIMESTAMP,
    CONSTRAINT fk_submission_assignment FOREIGN KEY (assignment_id) REFERENCES assignment(id),
    CONSTRAINT fk_submission_learner FOREIGN KEY (learner_id) REFERENCES app_user(id)
);

CREATE TABLE mastery_record (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    learner_id VARCHAR(36) NOT NULL,
    course_id VARCHAR(36) NOT NULL,
    skill_code VARCHAR(64) NOT NULL,
    skill_name VARCHAR(128) NOT NULL,
    mastery DECIMAL(5,4) NOT NULL,
    evidence_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mastery UNIQUE (learner_id, course_id, skill_code)
);

CREATE TABLE learner_activity (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    learner_id VARCHAR(36) NOT NULL,
    course_id VARCHAR(36) NOT NULL,
    activity_type VARCHAR(48) NOT NULL,
    duration_seconds INTEGER NOT NULL DEFAULT 0,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata_json TEXT
);

CREATE TABLE learning_plan (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    learner_id VARCHAR(36) NOT NULL,
    course_id VARCHAR(36) NOT NULL,
    goal TEXT NOT NULL,
    rationale TEXT,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE learning_plan_item (
    id VARCHAR(36) PRIMARY KEY,
    plan_id VARCHAR(36) NOT NULL,
    sequence_no INTEGER NOT NULL,
    skill_code VARCHAR(64) NOT NULL,
    activity VARCHAR(256) NOT NULL,
    estimated_minutes INTEGER NOT NULL,
    completion_status VARCHAR(24) NOT NULL,
    CONSTRAINT fk_plan_item_plan FOREIGN KEY (plan_id) REFERENCES learning_plan(id)
);

CREATE TABLE ai_call_audit (
    id VARCHAR(36) PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    scenario VARCHAR(48) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_template VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(24) NOT NULL,
    prompt_hash VARCHAR(64) NOT NULL,
    prompt_preview TEXT,
    response_hash VARCHAR(64),
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    cached_tokens INTEGER NOT NULL DEFAULT 0,
    estimated_cost DECIMAL(16,8) NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    risk_level VARCHAR(24) NOT NULL,
    risk_codes VARCHAR(512),
    error_code VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ai_audit_tenant_time ON ai_call_audit(tenant_id, created_at);
CREATE INDEX idx_ai_audit_trace ON ai_call_audit(trace_id);
CREATE INDEX idx_ai_audit_scenario ON ai_call_audit(tenant_id, scenario);

CREATE TABLE ai_token_usage_daily (
    usage_date DATE NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    scenario VARCHAR(48) NOT NULL,
    model VARCHAR(128) NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cached_tokens BIGINT NOT NULL DEFAULT 0,
    request_count BIGINT NOT NULL DEFAULT 0,
    estimated_cost DECIMAL(16,8) NOT NULL DEFAULT 0,
    PRIMARY KEY (usage_date, tenant_id, user_id, scenario, model)
);

CREATE TABLE policy_violation (
    id VARCHAR(36) PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    violation_type VARCHAR(64) NOT NULL,
    severity VARCHAR(24) NOT NULL,
    action VARCHAR(32) NOT NULL,
    evidence_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
