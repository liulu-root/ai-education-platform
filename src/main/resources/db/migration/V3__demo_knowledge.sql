INSERT INTO knowledge_document (
    id, tenant_id, course_id, title, source_type, source_uri, content_hash, status, version, created_by
) VALUES
('doc-rag', 'tenant-demo', 'course-java', 'RAG 工程实践指南', 'COURSEWARE', 'demo://rag-guide',
 'f2f7618a6dbbb01ba1cf9fdff90611f589b23fc0714643cb5d0f78648eac0152', 'INDEXED', 1, 'instructor-001'),
('doc-audit', 'tenant-demo', 'course-java', '生成式 AI 审计规范', 'POLICY', 'demo://ai-audit-policy',
 '2375e8a7724da22e91b600d0b21df394ad308c72906a251365dd9289e36c27a7', 'INDEXED', 1, 'auditor-001'),
('doc-learning', 'tenant-demo', 'course-data', '自适应学习与掌握度评估', 'COURSEWARE', 'demo://adaptive-learning',
 'd81c64c3f838e9996778aa66ec38544f236566bc14252001841206f9967677ec', 'INDEXED', 1, 'instructor-001');

INSERT INTO knowledge_chunk (
    id, tenant_id, document_id, course_id, chunk_index, content, token_count, metadata_json
) VALUES
('chunk-rag-1', 'tenant-demo', 'doc-rag', 'course-java', 0,
 'RAG 系统先将课程资料按语义段落切分并生成向量。查询时需要进行租户和课程过滤，再执行相似度检索。生产系统还应使用重排、最低相关度阈值和无答案降级，避免模型脱离资料作答。',
 78, '{"chapter":"检索链路"}'),
('chunk-rag-2', 'tenant-demo', 'doc-rag', 'course-java', 1,
 '答案必须保留资料引用，包括文档标识、片段标识和相似度。离线评估可使用 Recall@K、MRR 和人工相关性标注；在线评估关注有帮助率、引用点击率、拒答率以及事实错误率。',
 75, '{"chapter":"质量评估"}'),
('chunk-audit-1', 'tenant-demo', 'doc-audit', 'course-java', 0,
 '每次模型调用应记录 traceId、租户、用户、业务场景、模型、提示词模板版本、输入输出 Token、延迟、估算费用和调用状态。提示词原文默认不进入日志，只保留脱敏预览和不可逆哈希。',
 82, '{"chapter":"调用审计"}'),
('chunk-audit-2', 'tenant-demo', 'doc-audit', 'course-java', 1,
 '安全治理需要覆盖提示词注入、个人信息、学术作弊、越权访问和预算超限。高风险请求应在模型调用前阻断，记录策略命中证据，并允许审计员根据 traceId 追溯。',
 71, '{"chapter":"安全策略"}'),
('chunk-learning-1', 'tenant-demo', 'doc-learning', 'course-data', 0,
 '掌握度不能只看一次测验成绩，应融合练习正确率、知识点难度、遗忘时间和证据数量。推荐学习路径时先补齐低掌握度的前置技能，再安排综合任务，并在每个阶段设置形成性测验。',
 79, '{"chapter":"掌握度模型"}');
