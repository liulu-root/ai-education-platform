# 智培云 AI 教育培训平台

一个可直接运行的教育培训行业 AI 项目。它不是单接口聊天示例，而是把课程、学习者、知识库、作业、学习路径、风险干预与 AI 治理放在同一个多租户业务模型中。

默认配置完全离线可运行：H2 保存业务数据，本地 Mock 模型产生稳定响应，本地哈希向量用于演示检索。无需 API Key，也不会安装或连接 Milvus。需要真实模型时，可通过环境变量切换到 OpenAI 兼容的 Chat Completions 与 Embeddings API。

## 已实现业务

| 领域 | 真实业务处理 | AI 技术 |
| --- | --- | --- |
| 智能助教 | 多轮会话、课程隔离、学习画像工具、资料引用 | RAG、向量检索、上下文编排 |
| 作业批改 | 量规、规则得分、置信度、人工复核状态 | 规则 + LLM 形成性反馈 |
| 学习路径 | 掌握度、证据量、时间预算、检查点 | 自适应推荐 + LLM 解释 |
| 风险预警 | 活跃度、进度、掌握度、学习投入 | 可解释风险评分 + 干预建议 |
| 知识中心 | 文档切分、版本、Chunk、租户/课程过滤 | Embedding、相似度检索 |
| AI 治理 | 注入/作弊阻断、PII 脱敏、Token 预算 | Guardrail、审计、成本估算 |
| 可观测性 | traceId、模型、延迟、状态、场景指标 | Micrometer、Prometheus |

## 快速运行

环境要求：Java 21+、Maven 3.6.3+。当前电脑已发现 `D:\soft\jdk25-2` 与 `D:\soft\maven\apache-maven-3.8.1`，可直接执行：

```powershell
cd D:\new-code\AI\ai-education-platform
powershell -ExecutionPolicy Bypass -File .\scripts\run-local.ps1
```

打开 `http://localhost:8080`。页面内可以直接体验助教、学习路径、批改、风险评估和审计看板。

运行测试：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test.ps1
```

默认演示身份由请求头提供：

- `X-Tenant-Id: tenant-demo`
- `X-User-Id: learner-001`
- `X-User-Role: LEARNER`
- `X-Trace-Id` 可选；不传时自动生成并通过响应头返回

这些 Header 只用于本地演示身份网关。生产环境必须接入 OAuth2/OIDC、JWT 校验和权限策略，不能信任客户端直接传入的身份。

## 接入真实模型

项目使用供应商无关的 `AiModelClient`。任何支持 OpenAI Chat Completions API 的服务都可接入：

```powershell
$env:EDU_AI_PROVIDER='openai-compatible'
$env:AI_BASE_URL='https://api.openai.com'
$env:AI_API_KEY='replace-me'
$env:AI_CHAT_MODEL='replace-with-your-model'
powershell -ExecutionPolicy Bypass -File .\scripts\run-local.ps1
```

如需真实 Embedding，再增加：

```powershell
$env:EMBEDDING_PROVIDER='openai-compatible'
$env:EMBEDDING_MODEL='replace-with-your-embedding-model'
```

模型返回的 `prompt_tokens`、`completion_tokens` 和缓存 Token 会写入调用审计与每日汇总。默认 Mock 模型则使用本地估算器，确保无外部服务时也能完整演示 Token 计量链路。

## Milvus 说明

Milvus 是向量数据库，不是传统图数据库。本项目当前不需要图数据库；课程、报名、掌握度与学习计划使用关系数据库，知识检索可选择 Milvus。

本次没有安装、下载或启动 Milvus，只提供了：

- `MilvusVectorStore` REST v2 适配器；
- `application-milvus.yml` 配置模板；
- 租户和课程级标量过滤；
- 向量 Upsert 与 Search 调用。

未来由运维创建好 Milvus 集合后，才启用：

```powershell
$env:VECTOR_STORE='milvus'
$env:MILVUS_BASE_URL='http://your-milvus:19530'
$env:MILVUS_TOKEN='user:password'
$env:MILVUS_DATABASE='default'
$env:MILVUS_COLLECTION='education_knowledge_chunks'
$env:EMBEDDING_PROVIDER='openai-compatible'
```

集合字段约定见 `docs/milvus.md`。Embedding 维度必须与 Milvus 集合的向量维度一致。

## AI 审计设计

每次调用记录：

- traceId、tenantId、userId、业务场景；
- provider、model、提示词模板与版本；
- 提示词 SHA-256、脱敏预览、响应哈希；
- 输入、输出、缓存 Token；
- 延迟、状态、风险等级、策略代码；
- 演示单价下的估算费用。

原始系统提示词和完整模型响应不会写入 AI 审计表。教育业务自身的助教消息仍会保存在会话表中，生产环境应再配置数据分级、保留周期、加密与删除策略。

审计接口：

```text
GET /api/ai-audit/overview?days=30
GET /api/ai-audit/events?limit=50
GET /api/ai-audit/token-usage?days=30
GET /actuator/prometheus
```

## 主要 API

```text
POST /api/ai/tutor/chat
POST /api/ai/grading
POST /api/ai/learning-paths
GET  /api/ai/risk/assessment?courseId=course-data
POST /api/ai/risk/explain?courseId=course-data
POST /api/knowledge/documents
GET  /api/knowledge/search?courseId=course-java&query=RAG
GET  /api/dashboard/overview
```

具体请求示例见 `docs/api-examples.md`。

## 项目结构

```text
src/main/java/com/xiaomi/education
├── ai             # 模型网关、Guardrail、Token 预算、调用审计
├── knowledge      # 切分、Embedding、内存/Milvus 向量适配
├── tutor          # RAG、多轮会话、学习画像工具
├── grading        # 规则评分、AI 反馈、人工复核
├── learning       # 掌握度驱动的学习路径
├── risk           # 可解释学习风险与干预边界
├── dashboard      # 业务和 AI 运营指标
└── tenant         # 演示租户请求上下文
```

架构、数据流和生产化边界见 `docs/architecture.md`。

## 当前边界

- 默认本地向量算法用于可复现演示，不代表生产语义检索质量；生产请启用真实 Embedding。
- 演示费用单价写在 `AiGateway` 中，正式环境应建设按供应商、模型和生效时间维护的价格表。
- 当前是模块化单体，便于本地运行。高并发生产环境可按 AI 网关、知识索引、学习分析拆分服务。
- 身份与权限、对象存储、消息队列、异步索引、模型评测集、数据保留策略属于下一阶段生产化工作。
