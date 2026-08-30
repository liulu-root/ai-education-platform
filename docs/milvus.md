# Milvus 对接约定

本项目没有安装或启动 Milvus。本文件仅约定未来由基础设施团队创建的集合结构。

## 集合

- 数据库：`default`，可由 `MILVUS_DATABASE` 覆盖
- 集合：`education_knowledge_chunks`，可由 `MILVUS_COLLECTION` 覆盖
- 相似度：建议 `COSINE`
- 向量维度：必须与 `EMBEDDING_MODEL` 的输出一致

字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `chunk_id` | VarChar，主键 | 知识片段 ID |
| `tenant_id` | VarChar | 必须建立标量过滤能力 |
| `document_id` | VarChar | 来源文档 ID |
| `course_id` | VarChar | 课程隔离字段 |
| `content` | VarChar | 片段正文；长度按集合配置 |
| `vector` | FloatVector | Embedding |

## REST 调用

适配器使用 Milvus REST API v2：

```text
POST /v2/vectordb/entities/upsert
POST /v2/vectordb/entities/search
Authorization: Bearer user:password
```

搜索表达式同时包含 tenantId 与可选 courseId，避免跨租户和跨课程召回。集合创建、索引参数、备份和扩缩容不属于应用启动职责。

## 启用前检查

1. 集合已经创建且字段名完全一致。
2. `vector` 维度等于真实 Embedding 输出维度。
3. 应用到 Milvus 的网络和凭据已经配置。
4. 对同租户的测试文档完成 Upsert 与 Search 冒烟测试。
5. 验证其他租户的文档不会被检索到。
