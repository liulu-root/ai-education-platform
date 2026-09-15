CREATE TABLE learner_intervention (
    id VARCHAR(36) PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    learner_id VARCHAR(36) NOT NULL,
    course_id VARCHAR(36) NOT NULL,
    objective TEXT NOT NULL,
    message TEXT NOT NULL,
    post_model_checks VARCHAR(512) NOT NULL,
    requested_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_intervention_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_intervention_learner FOREIGN KEY (learner_id) REFERENCES app_user(id),
    CONSTRAINT fk_intervention_course FOREIGN KEY (course_id) REFERENCES course(id),
    CONSTRAINT fk_intervention_requester FOREIGN KEY (requested_by) REFERENCES app_user(id)
);

CREATE INDEX idx_intervention_tenant_time ON learner_intervention(tenant_id, created_at);
CREATE INDEX idx_intervention_learner_course ON learner_intervention(tenant_id, learner_id, course_id);
CREATE INDEX idx_intervention_trace ON learner_intervention(trace_id);

CREATE TABLE ai_tool_approval (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    scenario VARCHAR(48) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(36) NOT NULL,
    arguments_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_by VARCHAR(36) NOT NULL,
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by VARCHAR(36),
    decided_at TIMESTAMP,
    decision_comment VARCHAR(1000),
    CONSTRAINT fk_tool_approval_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_tool_approval_requester FOREIGN KEY (requested_by) REFERENCES app_user(id),
    CONSTRAINT fk_tool_approval_decider FOREIGN KEY (decided_by) REFERENCES app_user(id)
);

CREATE INDEX idx_tool_approval_tenant_status ON ai_tool_approval(tenant_id, status, requested_at);
CREATE INDEX idx_tool_approval_resource ON ai_tool_approval(tenant_id, resource_type, resource_id);

CREATE TABLE learner_notification (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    approval_id VARCHAR(36) NOT NULL,
    intervention_id VARCHAR(36) NOT NULL,
    learner_id VARCHAR(36) NOT NULL,
    course_id VARCHAR(36) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    sent_by VARCHAR(36) NOT NULL,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_notification_approval UNIQUE (approval_id),
    CONSTRAINT fk_notification_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_notification_approval FOREIGN KEY (approval_id) REFERENCES ai_tool_approval(id),
    CONSTRAINT fk_notification_intervention FOREIGN KEY (intervention_id) REFERENCES learner_intervention(id),
    CONSTRAINT fk_notification_learner FOREIGN KEY (learner_id) REFERENCES app_user(id),
    CONSTRAINT fk_notification_sender FOREIGN KEY (sent_by) REFERENCES app_user(id),
    CONSTRAINT fk_notification_course FOREIGN KEY (course_id) REFERENCES course(id)
);

CREATE INDEX idx_notification_tenant_time ON learner_notification(tenant_id, sent_at);
CREATE INDEX idx_notification_learner ON learner_notification(tenant_id, learner_id, sent_at);
