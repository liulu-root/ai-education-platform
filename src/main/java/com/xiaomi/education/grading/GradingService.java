package com.xiaomi.education.grading;

import com.xiaomi.education.ai.AiGateway;
import com.xiaomi.education.ai.model.AiModelRequest;
import com.xiaomi.education.ai.model.AiScenario;
import com.xiaomi.education.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GradingService {

    private static final String SYSTEM_PROMPT = """
            你是教育平台的作业反馈助手。评分数值由平台规则引擎计算，你只提供形成性反馈。
            反馈需要指出已覆盖内容、缺失内容、改进建议，不得虚构课程要求，不使用羞辱性表达。
            """;

    private final GradingRepository repository;
    private final AiGateway aiGateway;

    public GradingService(GradingRepository repository, AiGateway aiGateway) {
        this.repository = repository;
        this.aiGateway = aiGateway;
    }

    public GradingResult grade(String assignmentId, String answer) {
        var context = TenantContext.current();
        var assignment = repository.assignment(context.tenantId(), assignmentId);
        var heuristic = evaluate(answer);
        var score = assignment.maxScore().multiply(BigDecimal.valueOf(heuristic.scoreRatio()))
                .setScale(2, RoundingMode.HALF_UP);
        var prompt = "作业：" + assignment.title() + "\n要求：" + assignment.instructions()
                + "\n评分量规：" + assignment.rubricJson() + "\n学生答案：" + answer
                + "\n规则引擎得分：" + score + "/" + assignment.maxScore();
        var aiResult = aiGateway.execute(new AiModelRequest(AiScenario.ASSIGNMENT_GRADING, SYSTEM_PROMPT, prompt,
                Map.of("assignmentId", assignmentId, "deterministicScore", score)));
        var reviewStatus = heuristic.confidence() < 0.72 ? "HUMAN_REVIEW" : "AI_GRADED";
        var submissionId = UUID.randomUUID().toString();
        repository.saveSubmission(submissionId, context.tenantId(), assignmentId, context.userId(), answer,
                score, aiResult.content(), heuristic.confidence(), reviewStatus);
        return new GradingResult(submissionId, score, assignment.maxScore(), heuristic.confidence(), reviewStatus,
                heuristic.dimensions(), aiResult.content(), aiResult);
    }

    public List<Map<String, Object>> submissions() {
        var context = TenantContext.current();
        return repository.submissions(context.tenantId(), context.userId());
    }

    private HeuristicScore evaluate(String answer) {
        var length = answer.codePointCount(0, answer.length());
        var completeness = Math.min(1.0, length / 600.0);
        var architecture = keywordCoverage(answer, List.of("切分", "向量", "检索", "提示词", "引用"));
        var governance = keywordCoverage(answer, List.of("审计", "token", "脱敏", "注入", "复核", "降级"));
        var clarity = Math.min(1.0, 0.45 + countParagraphs(answer) * 0.1);
        var ratio = completeness * 0.20 + architecture * 0.40 + governance * 0.25 + clarity * 0.15;
        var evidence = Math.min(1.0, length / 400.0);
        var confidence = Math.min(0.95, 0.55 + evidence * 0.30 + (architecture + governance) * 0.05);
        return new HeuristicScore(ratio, confidence, Map.of(
                "completeness", round(completeness),
                "architecture", round(architecture),
                "governance", round(governance),
                "clarity", round(clarity)
        ));
    }

    private double keywordCoverage(String answer, List<String> keywords) {
        var matched = keywords.stream().filter(answer.toLowerCase()::contains).count();
        return matched / (double) keywords.size();
    }

    private long countParagraphs(String answer) {
        return answer.lines().filter(line -> !line.isBlank()).count();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record HeuristicScore(double scoreRatio, double confidence, Map<String, Double> dimensions) {
    }

    public record GradingResult(
            String submissionId,
            BigDecimal score,
            BigDecimal maxScore,
            double confidence,
            String reviewStatus,
            Map<String, Double> dimensions,
            String feedback,
            com.xiaomi.education.ai.audit.AiCallResult usage
    ) {
    }
}
