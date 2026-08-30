package com.xiaomi.education.learning;

import com.xiaomi.education.ai.AiGateway;
import com.xiaomi.education.ai.model.AiModelRequest;
import com.xiaomi.education.ai.model.AiScenario;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.learning.LearningPathRepository.LearningPlanItem;
import com.xiaomi.education.learning.LearningPathRepository.SkillMastery;
import com.xiaomi.education.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LearningPathService {

    private static final String SYSTEM_PROMPT = """
            你是自适应学习规划师。平台已根据掌握度和证据数量排好学习活动。
            请用简洁语言解释路径安排原因、复习节奏和检查点，不得修改平台给出的顺序。
            """;

    private final LearningPathRepository repository;
    private final AiGateway aiGateway;

    public LearningPathService(LearningPathRepository repository, AiGateway aiGateway) {
        this.repository = repository;
        this.aiGateway = aiGateway;
    }

    public LearningPathResult generate(String courseId, String goal, int weeklyMinutes) {
        var context = TenantContext.current();
        var mastery = repository.mastery(context.tenantId(), context.userId(), courseId);
        if (mastery.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "MASTERY_DATA_REQUIRED", "缺少掌握度数据，需先完成诊断测验");
        }
        var items = createItems(mastery, weeklyMinutes);
        var prompt = "学习目标：" + goal + "\n每周可用分钟：" + weeklyMinutes + "\n路径：" + items;
        var aiResult = aiGateway.execute(new AiModelRequest(AiScenario.LEARNING_PATH, SYSTEM_PROMPT, prompt,
                Map.of("courseId", courseId, "weeklyMinutes", weeklyMinutes, "itemCount", items.size())));
        var planId = UUID.randomUUID().toString();
        repository.savePlan(planId, context.tenantId(), context.userId(), courseId, goal, aiResult.content(), items);
        return new LearningPathResult(planId, goal, items, aiResult.content(), aiResult);
    }

    public List<Map<String, Object>> plans() {
        var context = TenantContext.current();
        return repository.plans(context.tenantId(), context.userId());
    }

    private List<LearningPlanItem> createItems(List<SkillMastery> mastery, int weeklyMinutes) {
        var selected = mastery.stream().limit(4).toList();
        var perSkill = Math.max(20, weeklyMinutes / Math.max(1, selected.size()));
        var items = new ArrayList<LearningPlanItem>();
        var sequence = 1;
        for (var skill : selected) {
            var activity = skill.mastery() < 0.35
                    ? "学习微课并完成带提示的基础练习"
                    : skill.mastery() < 0.60
                    ? "完成案例练习并订正错因"
                    : "完成综合任务并进行间隔复习";
            items.add(new LearningPlanItem(sequence++, skill.code(), skill.name(), skill.mastery(), activity, perSkill));
        }
        items.add(new LearningPlanItem(sequence, "checkpoint", "形成性测验", 0,
                "完成混合知识点测验并更新掌握度", Math.max(20, weeklyMinutes / 5)));
        return List.copyOf(items);
    }

    public record LearningPathResult(
            String planId,
            String goal,
            List<LearningPlanItem> items,
            String rationale,
            com.xiaomi.education.ai.audit.AiCallResult usage
    ) {
    }
}
