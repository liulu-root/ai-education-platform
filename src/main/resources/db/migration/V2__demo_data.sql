INSERT INTO tenant (id, name, status, token_budget) VALUES
('tenant-demo', '未来技能培训中心', 'ACTIVE', 1000000);

INSERT INTO app_user (id, tenant_id, display_name, role, email) VALUES
('learner-001', 'tenant-demo', '林晓', 'LEARNER', 'linxiao@example.com'),
('instructor-001', 'tenant-demo', '王老师', 'INSTRUCTOR', 'wang@example.com'),
('auditor-001', 'tenant-demo', '审计员', 'AUDITOR', 'audit@example.com');

INSERT INTO course (id, tenant_id, code, title, description, level, status) VALUES
('course-java', 'tenant-demo', 'JAVA-AI-101', 'Java 与 AI 应用开发', '面向企业开发者的 AI 工程实践课程', 'INTERMEDIATE', 'PUBLISHED'),
('course-data', 'tenant-demo', 'DATA-201', '数据分析实战', '从业务问题到可复现分析报告', 'INTERMEDIATE', 'PUBLISHED');

INSERT INTO enrollment (id, tenant_id, course_id, learner_id, progress_percent, status, last_active_at) VALUES
('enroll-001', 'tenant-demo', 'course-java', 'learner-001', 42.50, 'ACTIVE', CURRENT_TIMESTAMP),
('enroll-002', 'tenant-demo', 'course-data', 'learner-001', 18.00, 'AT_RISK', DATEADD('DAY', -8, CURRENT_TIMESTAMP));

INSERT INTO assignment (id, tenant_id, course_id, title, instructions, rubric_json, max_score, due_at) VALUES
('assignment-001', 'tenant-demo', 'course-java', '设计一个 RAG 问答服务',
 '说明数据切分、向量检索、提示词组装、答案引用和审计方案。',
 '{"criteria":[{"name":"架构完整性","weight":0.35},{"name":"检索质量","weight":0.25},{"name":"安全审计","weight":0.25},{"name":"表达清晰","weight":0.15}]}',
 100, DATEADD('DAY', 7, CURRENT_TIMESTAMP));

INSERT INTO mastery_record (id, tenant_id, learner_id, course_id, skill_code, skill_name, mastery, evidence_count) VALUES
('mastery-001', 'tenant-demo', 'learner-001', 'course-java', 'java-core', 'Java 核心', 0.82, 12),
('mastery-002', 'tenant-demo', 'learner-001', 'course-java', 'prompt-engineering', '提示词工程', 0.46, 5),
('mastery-003', 'tenant-demo', 'learner-001', 'course-java', 'rag', 'RAG 检索增强生成', 0.31, 3),
('mastery-004', 'tenant-demo', 'learner-001', 'course-java', 'ai-governance', 'AI 安全与治理', 0.22, 2);

INSERT INTO learner_activity (id, tenant_id, learner_id, course_id, activity_type, duration_seconds, occurred_at) VALUES
('activity-001', 'tenant-demo', 'learner-001', 'course-java', 'LESSON_VIEW', 2400, DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
('activity-002', 'tenant-demo', 'learner-001', 'course-java', 'QUIZ_ATTEMPT', 900, DATEADD('DAY', -2, CURRENT_TIMESTAMP)),
('activity-003', 'tenant-demo', 'learner-001', 'course-data', 'LESSON_VIEW', 600, DATEADD('DAY', -8, CURRENT_TIMESTAMP));
