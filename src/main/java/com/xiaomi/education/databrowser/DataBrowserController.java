package com.xiaomi.education.databrowser;

import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Clob;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/data")
public class DataBrowserController {
    private final JdbcTemplate jdbc;

    public DataBrowserController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // SQL identifiers and access predicates come exclusively from this allowlist.
    private static final List<Table> TABLES = List.of(
            table("course", "课程", "INSTRUCTOR,AUDITOR", "id,code,title,description,level,status,created_at"),
            table("app_user", "用户", "INSTRUCTOR", "id,display_name,role,email,status,created_at"),
            table("enrollment", "课程报名", "INSTRUCTOR", "id,course_id,learner_id,progress_percent,status,last_active_at,enrolled_at"),
            table("knowledge_document", "知识文档", "INSTRUCTOR", "id,course_id,title,source_type,source_uri,content_hash,status,version,created_by,created_at"),
            table("knowledge_chunk", "知识片段", "INSTRUCTOR", "id,document_id,course_id,chunk_index,content,token_count,metadata_json,created_at"),
            table("assignment", "作业", "INSTRUCTOR", "id,course_id,title,instructions,rubric_json,max_score,due_at,created_at"),
            owned("submission", "作业提交", "INSTRUCTOR,LEARNER", "id,assignment_id,learner_id,answer_text,ai_score,ai_feedback,confidence,review_status,submitted_at,graded_at"),
            owned("mastery_record", "技能掌握度", "INSTRUCTOR,LEARNER", "id,learner_id,course_id,skill_code,skill_name,mastery,evidence_count,updated_at"),
            owned("learner_activity", "学习活动", "INSTRUCTOR,LEARNER", "id,learner_id,course_id,activity_type,duration_seconds,occurred_at,metadata_json"),
            owned("learning_plan", "学习计划", "LEARNER", "id,learner_id,course_id,goal,rationale,status,created_at"),
            child("learning_plan_item", "计划明细", "id,plan_id,sequence_no,skill_code,activity,estimated_minutes,completion_status", "learning_plan", "plan_id"),
            owned("tutor_conversation", "助教会话", "LEARNER", "id,learner_id,course_id,title,status,created_at,updated_at,next_message_seq"),
            child("tutor_message", "助教消息", "id,conversation_id,role,content,trace_id,created_at,message_seq", "tutor_conversation", "conversation_id"),
            child("tutor_conversation_memory", "会话摘要", "conversation_id,summary_content,covered_through_seq,estimated_tokens,version,summary_model,summary_version,updated_at", "tutor_conversation", "conversation_id"),
            child("tutor_context_attempt", "上下文记录", "id,request_id,conversation_id,attempt,input_tokens,input_budget,retrieved_chunks,history_messages,degraded,status,created_at", "tutor_conversation", "conversation_id"),
            table("learner_intervention", "学习干预", "INSTRUCTOR", "id,trace_id,learner_id,course_id,objective,message,post_model_checks,requested_by,created_at"),
            table("ai_tool_approval", "工具审批", "INSTRUCTOR", "id,scenario,tool_name,resource_type,resource_id,arguments_json,status,requested_by,requested_at,decided_by,decided_at,decision_comment"),
            table("learner_notification", "通知记录", "INSTRUCTOR", "id,approval_id,intervention_id,learner_id,course_id,channel,content,sent_by,sent_at"),
            table("ai_call_audit", "AI 调用审计", "AUDITOR", "id,trace_id,user_id,scenario,provider,model,prompt_template,prompt_version,prompt_hash,prompt_preview,response_hash,input_tokens,output_tokens,cached_tokens,estimated_cost,latency_ms,status,risk_level,risk_codes,error_code,created_at"),
            new Table("ai_token_usage_daily", "每日 Token 用量", Set.of("AUDITOR"), columns("usage_date,user_id,scenario,model,input_tokens,output_tokens,cached_tokens,request_count,estimated_cost"), "t.tenant_id = ?", null, "t.usage_date DESC,t.user_id,t.scenario,t.model"),
            table("policy_violation", "策略违规", "AUDITOR", "id,trace_id,user_id,violation_type,severity,action,evidence_hash,created_at")
    );

    @GetMapping("/tables")
    public ResponseEntity<List<TableInfo>> tables() {
        var context = TenantContext.current();
        var result = TABLES.stream().filter(t -> t.roles().contains(context.role())).map(t -> {
            var scope = scope(t, context);
            return new TableInfo(t.name(), t.label(), t.columns(), count(t, scope));
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/tables/{name}")
    public ResponseEntity<Rows> rows(@PathVariable String name,
                                    @RequestParam(defaultValue = "1") String page,
                                    @RequestParam(defaultValue = "25") String size,
                                    @RequestParam(defaultValue = "") String search) {
        var context = TenantContext.current();
        var table = TABLES.stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TABLE_NOT_FOUND", "数据表不存在"));
        if (!table.roles().contains(context.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "当前用户无权查看此数据表");
        }
        int requestedPage = positiveInt(page, 1_000_000);
        int pageSize = positiveInt(size, 100);
        if (search.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "搜索内容不能超过 200 个字符");
        }
        var scope = scope(table, context);
        String keyword = search.strip();
        if (!keyword.isEmpty()) {
            String pattern = "%" + keyword.toLowerCase(java.util.Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            String filter = table.columns().stream()
                    .map(c -> "LOWER(CAST(t." + c + " AS VARCHAR)) LIKE ? ESCAPE '!'")
                    .collect(Collectors.joining(" OR ", " AND (", ")"));
            for (String column : table.columns()) scope.args().add(pattern);
            scope = new Scope(scope.where() + filter, scope.args());
        }
        long total = count(table, scope);
        int actualPage = (int) Math.min(requestedPage, Math.max(1, (total + pageSize - 1) / pageSize));
        String selection = table.columns().stream().map(c -> "t." + c).collect(Collectors.joining(","));
        var args = new ArrayList<>(scope.args());
        args.add(pageSize);
        args.add((long) (actualPage - 1) * pageSize);
        var records = jdbc.query("SELECT " + selection + " FROM " + table.name() + " t WHERE "
                + scope.where() + " ORDER BY " + table.order() + " LIMIT ? OFFSET ?", (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String column : table.columns()) {
                Object value = rs.getObject(column);
                if (value instanceof Clob || value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor) {
                    value = rs.getString(column);
                }
                row.put(column, value);
            }
            return row;
        }, args.toArray());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Rows(table.name(), table.label(), table.columns(), records, total, actualPage, pageSize));
    }

    private long count(Table table, Scope scope) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table.name() + " t WHERE " + scope.where(),
                Long.class, scope.args().toArray());
    }

    private static Scope scope(Table table, TenantContext context) {
        var args = new ArrayList<Object>();
        args.add(context.tenantId());
        String where = table.scope();
        if ("LEARNER".equals(context.role()) && table.owner() != null) {
            where += " AND " + table.owner() + " = ?";
            args.add(context.userId());
        }
        return new Scope(where, args);
    }

    private static int positiveInt(String value, int max) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= 1 && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) {
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "分页参数超出有效范围");
    }

    private static List<String> columns(String columns) {
        return List.copyOf(Arrays.asList(columns.split(",")));
    }

    private static Table table(String name, String label, String roles, String columns) {
        return new Table(name, label, Set.of(roles.split(",")), columns(columns), "t.tenant_id = ?", null, "t.id");
    }

    private static Table owned(String name, String label, String roles, String columns) {
        return new Table(name, label, Set.of(roles.split(",")), columns(columns), "t.tenant_id = ?", "t.learner_id", "t.id");
    }

    private static Table child(String name, String label, String columns, String parent, String foreignKey) {
        return new Table(name, label, Set.of("LEARNER"), columns(columns),
                "EXISTS (SELECT 1 FROM " + parent + " p WHERE p.id = t." + foreignKey + " AND p.tenant_id = ?)",
                "(SELECT p.learner_id FROM " + parent + " p WHERE p.id = t." + foreignKey + ")", "t." + columns.split(",")[0]);
    }

    private record Table(String name, String label, Set<String> roles, List<String> columns,
                         String scope, String owner, String order) { }
    private record Scope(String where, ArrayList<Object> args) { }
    public record TableInfo(String name, String label, List<String> columns, long total) { }
    public record Rows(String name, String label, List<String> columns, List<Map<String, Object>> rows,
                       long total, int page, int size) { }
}
