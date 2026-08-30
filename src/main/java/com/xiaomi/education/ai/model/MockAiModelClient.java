package com.xiaomi.education.ai.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "edu.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockAiModelClient implements AiModelClient {

    private final TokenEstimator tokenEstimator;

    public MockAiModelClient(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public AiModelResponse generate(AiModelRequest request) {
        var content = switch (request.scenario()) {
            case TUTOR_CHAT -> "我先给出学习提示：先明确问题中的核心概念，再结合检索到的课程资料逐步推导。"
                    + "不要只记结论，建议你用自己的话复述，并完成一个最小示例来验证理解。";
            case ASSIGNMENT_GRADING -> "答案已覆盖主要思路。建议补充可量化的检索评估指标、失败降级策略，以及人工复核触发条件。";
            case LEARNING_PATH -> "建议按前置知识、核心练习、综合项目和复盘测验四个阶段推进，每完成一阶段更新掌握度。";
            case RISK_EXPLANATION -> "风险主要由近期活跃度下降、课程进度偏低和练习中断共同造成，建议安排一次短周期学习干预。";
            case CONTENT_SUMMARY -> "内容已提炼为学习目标、关键概念、实践步骤和自测问题四部分。";
        };
        return new AiModelResponse(
                content,
                provider(),
                model(),
                tokenEstimator.estimate(request.systemPrompt() + request.userPrompt()),
                tokenEstimator.estimate(content),
                0,
                "mock-" + UUID.randomUUID()
        );
    }

    @Override
    public String provider() {
        return "local-mock";
    }

    @Override
    public String model() {
        return "education-simulator-v1";
    }
}
