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
| 干预审批 | AI 通知草稿、人工批准/拒绝、幂等发送 | 输出后 Middleware + 敏感工具暂停/恢复 |
| 知识中心 | 文档切分、版本、Chunk、租户/课程过滤 | Embedding、相似度检索 |
| AI 治理 | 注入/作弊阻断、PII 脱敏、Token 预算 | Guardrail、审计、成本估算 |
| 可观测性 | traceId、模型、延迟、状态、场景指标 | Micrometer、Prometheus |

## 快速运行

环境要求：Java 21+、Maven 3.6.3+。当前电脑已发现 `D:\soft\jdk25-2` 与 `D:\soft\maven\apache-maven-3.8.1`，可直接执行：

```powershell
cd D:\new-code\AI\ai-education-platform
powershell -ExecutionPolicy Bypass -File .\scripts\run-local.ps1
```

打开 `http://localhost:8080`，先登录再体验相应角色被授权的功能。

运行测试：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test.ps1
```

### 演示账号

| 角色 | 邮箱 | 可访问场景 |
| --- | --- | --- |
| 学习者（LEARNER） | `linxiao@example.com` | 智能助教、学习路径、提交作业批改 |
| 教师（INSTRUCTOR） | `wang@example.com` | 运营总览、风险预警、干预审批、知识中心、提交列表 |
| 审计员（AUDITOR） | `audit@example.com` | 运营总览、AI 审计、受保护的 Actuator 指标 |

三个账号的本地演示密码均为 `Demo123!`。密码只以 BCrypt 哈希保存；演示密码不得用于生产环境。

### 浏览器查看数据

登录后点击左侧「数据查看」，即可直接读取当前应用数据库中的记录，无需安装数据库客户端。默认 H2 文件位于 `data/edu-ai.mv.db`。

页面支持选择数据表、按字段内容搜索、每页 25/50/100 条分页、刷新和记录详情；长文本与 JSON 可在详情中完整查看。页面仅提供只读操作。

- 学习者：自己的助教会话、消息、摘要、上下文记录、学习计划、活动、掌握度和作业提交。
- 教师：当前租户的课程、用户、报名、知识资料、作业、学习活动与干预审批数据。
- 审计员：当前租户的课程、AI 调用审计、每日 Token 用量和策略违规记录。

接口为 `GET /api/data/tables` 与 `GET /api/data/tables/{name}?page=1&size=25&search=关键词`。服务端限制数据表和字段范围，按登录身份过滤租户及学习者，用户表不会返回密码哈希。

认证与授权流程：

1. 浏览器先调用 `GET /api/auth/session` 获取 HttpSession 和 CSRF Token。
2. `POST /api/auth/login` 校验数据库账号和 BCrypt 密码，并在成功后更换 Session ID、轮换 CSRF Token。
3. 后续请求只从服务端认证主体读取 `tenantId / userId / role`；客户端传入的 `X-Tenant-Id`、`X-User-Id`、`X-User-Role` 不再被信任。
4. Spring Security 在服务端执行 RBAC；前端按角色隐藏菜单只是交互优化，不是权限边界。
5. `POST /api/auth/logout` 使会话失效并清除 Session Cookie。

`X-Trace-Id` 仍可选，仅用于链路追踪；值会经过格式和长度校验，不能改变用户身份。

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
GET  /api/ai/risk/assessment?learnerId=learner-001&courseId=course-data
POST /api/ai/risk/explain?learnerId=learner-001&courseId=course-data
POST /api/ai/interventions
GET  /api/ai/interventions/approvals
POST /api/ai/interventions/approvals/{id}/approve
POST /api/ai/interventions/approvals/{id}/reject
GET  /api/ai/interventions/notifications
POST /api/knowledge/documents
GET  /api/knowledge/search?courseId=course-java&query=RAG
GET  /api/dashboard/overview
```

具体请求示例见 `docs/api-examples.md`。

## 项目结构

```text
src/main/java/com/xiaomi/education
├── ai             # 模型网关、Guardrail、Token 预算、调用审计
├── security       # 数据库账号、会话认证、CSRF、RBAC
├── knowledge      # 切分、Embedding、内存/Milvus 向量适配
├── tutor          # RAG、多轮会话、学习画像工具
├── grading        # 规则评分、AI 反馈、人工复核
├── learning       # 掌握度驱动的学习路径
├── risk           # 可解释学习风险与干预边界
├── intervention   # 输出后检查、工具审批、通知执行
├── dashboard      # 业务和 AI 运营指标
└── tenant         # 已认证用户的租户请求上下文
```

架构、数据流和生产化边界见 `docs/architecture.md`。

助教的输入预算、增量摘要、超限缩减重试、配置与验证见 [上下文管理说明](docs/tutor-context-management.md)。

## 当前边界

- 默认本地向量算法用于可复现演示，不代表生产语义检索质量；生产请启用真实 Embedding。
- 演示费用单价写在 `AiGateway` 中，正式环境应建设按供应商、模型和生效时间维护的价格表。
- 当前是模块化单体，便于本地运行。高并发生产环境可按 AI 网关、知识索引、学习分析拆分服务。
- 干预场景把数据库通知记录作为可验证的工具副作用，并允许发起教师做显式确认；生产环境应按需启用四眼审批，并通过 outbox、供应商幂等键、回执、重试、频控和退订治理接入真实消息服务。
- 外部 OIDC/SSO、MFA、账号生命周期、课程资源级 ABAC，以及对象存储、消息队列、异步索引、模型评测集和数据保留策略属于下一阶段生产化工作。
