# 助教上下文预算、摘要与超限恢复

适用接口：`POST /api/ai/tutor/chat`、`POST /api/ai/tutor/chat/stream`。
同步接口仍返回完整 JSON；内部与 SSE 接口共用可取消的模型流。真实模型供应商需要支持 Chat Completions SSE。

## 处理顺序

1. 校验会话归属，领取数据库会话租约。同一会话只允许一个在途生成请求；冲突返回 `CONVERSATION_BUSY`。
2. 获取有稳定序号的历史快照、已保存摘要、学习者画像和本次 Top 4 资料。
3. 输入预算 = `context-window-tokens * (1 - safety-margin-ratio) - max-output-tokens`。估算包括系统指令、渲染后的所有内容和消息格式余量。
4. 超预算时，最多调用一次 `CONVERSATION_SUMMARY`，合并旧摘要和较早的完整问答。优先留下最近两轮。摘要输入同样受预算控制，只处理能完整容纳的连续批次。
5. 校验摘要 JSON、长度和安全策略后，使用版本号条件更新摘要与覆盖序号。失败或超时保留旧摘要与游标；安全阻断、费用预算耗尽不绕过。
6. 仍超预算时，依次移除低相关资料、较早完整问答、画像、历史摘要。保留系统指令、完整当前问题，以及至少一个已有课程资料片段。最小输入仍放不下时返回 `CONTEXT_INPUT_TOO_LARGE`。
7. 明确的供应商上下文超限且尚未输出任何回答时，输入预算缩至原来的 75%，并确保实际估算输入比上次更短，最多重试一次。重试使用同一快照，不重新摘要、不重复保存用户问题。

摘要是有损的会话辅助记忆，不替代原始消息，也不作为课程事实来源。历史资料编号不应进入摘要；画像以当前数据库信息为准。
单条旧问答本身超过摘要输入预算时，不推进摘要游标，本轮通过历史裁剪降级。大量积压历史分请求渐进处理；未覆盖的历史可能在预算压力下暂时省略。

## 配置

位于 `edu.ai.context`：

| 配置 | 默认值 | 含义 |
|---|---:|---|
| context-window-tokens | 16384 | 应用允许使用的窗口；部署时按真实模型能力设置 |
| max-output-tokens | 2048 | 主回答生成上限，通过 `max_tokens` 发送 |
| safety-margin-ratio | 0.10 | 为估算误差预留空间 |
| summary-max-tokens | 800 | 摘要模型输出上限及摘要文本校验上限 |
| recent-turns-to-keep | 2 | 摘要时优先保留的完整问答轮数；紧急裁剪时可缩减 |
| max-overflow-retries | 1 | 主回答超限重试次数，只允许 0 或 1 |
| retry-input-ratio | 0.75 | 重试缩减比例 |
| request-timeout-seconds | 60 | 摘要、主回答和重试共用的截止时间 |
| summary-timeout-seconds | 15 | 单次摘要调用超时，受整体剩余时间约束 |

可用环境变量覆盖 `AI_CONTEXT_WINDOW_TOKENS`、`AI_MAX_OUTPUT_TOKENS`。
Token 计数沿用字符启发式估算，并非模型精确 tokenizer；因此同时保留安全余量与供应商超限恢复。
当前供应商适配发送 `max_tokens`；仅接受其他输出额度参数的模型需要扩展适配器。

## 持久化与审计

Flyway V7 为原有消息按 `(created_at, id)` 回填会话内序号；后续消息在短事务中分配序号。
迁移不会修改原始消息正文；已有同时间戳消息只能以 ID 确定稳定顺序，无法还原更精细的历史发生时间。

`tutor_conversation_memory` 保存摘要、覆盖序号、版本、模型和模板版本。
`tutor_conversation` 保存租约所有者及过期时间。模型调用期间不持有数据库事务；写入回复和摘要时重新校验租约。
`tutor_context_attempt` 保存业务请求 ID、尝试次数、预算、估算输入、实际资料数量、历史条数、降级标志和结果。
已有 `ai_call_audit` 记录每次实际模型调用与费用；首轮使用业务 trace，摘要为 `.summary`，重试为 `.attempt1` 后缀。
为了兼容 64 字符审计字段，助教业务 trace 最长使用传入 trace 的前 48 字符。

```sql
SELECT request_id, attempt, input_tokens, input_budget, retrieved_chunks,
       history_messages, degraded, status
FROM tutor_context_attempt
WHERE conversation_id = ?
ORDER BY created_at, attempt;
```

## SSE 与取消

事件顺序：可选 `status` → `start` → `citations` → 一个或多个 `delta` → `done`。
`start` 不包含最终资料引用；`citations` 在最终尝试首个回答片段前发出，`done` 再携带最终引用。
缩减重试可发送 `status`，已发出非空 `delta` 后不自动重试。
客户端断开会取消当前摘要或回答调用，并阻止后续重试。超时不会为每次尝试重新计时。

## 验证

`TutorContextBuilderTest`：预算裁剪、资料排序、完整问答删除、必要输入保护。
`TutorContextIntegrationTest`：摘要增量与降级、有限重试、去重、并发、取消、超时、事件顺序。
`ModelContextOverflowTest`：真实 HTTP 错误分类、SSE 内错误、输出额度参数。
原有浏览器断开集成测试继续验证上游连接被关闭。

本地 mock 只返回固定的模拟摘要，适合测试处理链路；摘要语义质量需使用真实模型和业务会话样本单独评估。
