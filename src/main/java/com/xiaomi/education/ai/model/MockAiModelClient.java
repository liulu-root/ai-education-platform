package com.xiaomi.education.ai.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
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
            case LEARNER_INTERVENTION -> "最近的学习节奏可能有些放缓。建议先安排一个 20 分钟的小任务，"
                    + "完成后记录遇到的一个问题；老师会根据你的反馈提供下一步支持。";
            case CONTENT_SUMMARY -> "内容已提炼为学习目标、关键概念、实践步骤和自测问题四部分。";
            case CONVERSATION_SUMMARY -> "{\"summary\":\"模拟摘要：学习者正在学习课程，需要结合练习理解知识点。\"}";
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
    public AiModelStream generateStream(AiModelRequest request, Consumer<String> onDelta) {
        var response = generate(request);
        var cancelled = new AtomicBoolean();
        var completion = new CompletableFuture<AiModelResponse>();
        var worker = Thread.ofVirtual().name("mock-ai-stream").start(() -> {
            try {
                for (var delta : chunks(response.content(), 12)) {
                    if (cancelled.get()) {
                        throw new CancellationException("Model stream was cancelled");
                    }
                    onDelta.accept(delta);
                    Thread.sleep(35);
                }
                if (!cancelled.get()) {
                    completion.complete(response);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                completion.cancel(false);
            } catch (CancellationException exception) {
                completion.cancel(false);
            } catch (RuntimeException exception) {
                completion.completeExceptionally(exception);
            }
        });

        return new AiModelStream() {
            @Override
            public CompletableFuture<AiModelResponse> completion() {
                return completion;
            }

            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    worker.interrupt();
                    completion.cancel(false);
                }
            }
        };
    }

    private java.util.List<String> chunks(String content, int chunkSize) {
        var chunks = new ArrayList<String>();
        for (int start = 0; start < content.length(); start += chunkSize) {
            chunks.add(content.substring(start, Math.min(content.length(), start + chunkSize)));
        }
        return chunks;
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
