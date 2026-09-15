package com.xiaomi.education.risk;

import com.xiaomi.education.ai.AiGateway;
import com.xiaomi.education.ai.audit.AiCallResult;
import com.xiaomi.education.ai.model.AiModelRequest;
import com.xiaomi.education.ai.model.AiScenario;
import com.xiaomi.education.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LearnerRiskService {

    private static final String SYSTEM_PROMPT = """
            你是学习运营风险解释助手。只能解释平台已经计算出的风险特征，不得推断健康、家庭或其他敏感属性。
            给出可执行且非惩罚性的干预建议，明确这是辅助判断，最终决策由教师或运营人员做出。
            """;

    private final LearnerRiskRepository repository;
    private final AiGateway aiGateway;

    public LearnerRiskService(LearnerRiskRepository repository, AiGateway aiGateway) {
        this.repository = repository;
        this.aiGateway = aiGateway;
    }

    public RiskAssessment assess(String courseId, boolean includeAiExplanation) {
        return assessLearner(TenantContext.current().userId(), courseId, includeAiExplanation);
    }

    public RiskAssessment assessLearner(String learnerId, String courseId, boolean includeAiExplanation) {
        var context = TenantContext.current();
        var features = repository.features(context.tenantId(), learnerId, courseId);
        var inactivityDays = features.lastActiveAt() == null ? 30
                : Math.max(0, Duration.between(features.lastActiveAt(), Instant.now()).toDays());
        var inactivity = Math.min(1.0, inactivityDays / 14.0);
        var progressGap = 1.0 - Math.min(1.0, features.progressPercent() / 100.0);
        var masteryGap = 1.0 - Math.min(1.0, features.averageMastery());
        var lowEngagement = features.activities14d() == 0 ? 1.0
                : Math.max(0, 1.0 - features.studySeconds14d() / 7200.0);
        var score = round(inactivity * 0.42 + progressGap * 0.25 + masteryGap * 0.23 + lowEngagement * 0.10);
        var level = score >= 0.70 ? "HIGH" : score >= 0.45 ? "MEDIUM" : "LOW";
        var drivers = drivers(inactivityDays, progressGap, masteryGap, lowEngagement);

        AiCallResult usage = null;
        var explanation = "风险评分由活跃度、课程进度、知识掌握度和近 14 天投入共同计算。";
        if (includeAiExplanation) {
            var prompt = "课程：" + courseId + "\n风险分：" + score + "\n风险等级：" + level
                    + "\n特征：" + features + "\n主要驱动：" + drivers;
            usage = aiGateway.execute(new AiModelRequest(AiScenario.RISK_EXPLANATION, SYSTEM_PROMPT, prompt,
                    Map.of("learnerId", learnerId, "courseId", courseId, "riskScore", score, "riskLevel", level)));
            explanation = usage.content();
        }
        return new RiskAssessment(courseId, score, level, drivers, features, explanation, usage,
                "仅用于教学干预优先级排序，不得作为退学或惩罚的自动决策依据");
    }

    private List<String> drivers(long inactivityDays, double progressGap, double masteryGap, double lowEngagement) {
        var drivers = new ArrayList<String>();
        if (inactivityDays >= 7) {
            drivers.add("连续 " + inactivityDays + " 天未活跃");
        }
        if (progressGap >= 0.60) {
            drivers.add("课程完成进度偏低");
        }
        if (masteryGap >= 0.55) {
            drivers.add("关键技能平均掌握度不足");
        }
        if (lowEngagement >= 0.60) {
            drivers.add("近 14 天有效学习投入不足");
        }
        if (drivers.isEmpty()) {
            drivers.add("未发现显著风险驱动");
        }
        return List.copyOf(drivers);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public record RiskAssessment(
            String courseId,
            double riskScore,
            String riskLevel,
            List<String> drivers,
            LearnerRiskRepository.RiskFeatures features,
            String explanation,
            AiCallResult usage,
            String decisionBoundary
    ) {
    }
}
