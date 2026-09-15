package com.xiaomi.education.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("edu.ai.context")
public record TutorContextProperties(
        // 当前模型可用的总上下文窗口大小
        @DefaultValue("16384") int contextWindowTokens,
        // 主回答最多允许模型输出多少 token
        @DefaultValue("2048") int maxOutputTokens,
        // 安全余量比例，用来给 token 估算误差留空间
        @DefaultValue("0.10") double safetyMarginRatio,
        // 历史摘要模型的最大输出 token 数，同时也是摘要文本保存前的长度校验上限
        @DefaultValue("800") int summaryMaxTokens,
        // 做历史摘要时，优先保留最近多少轮完整问答不压缩。旧轮次才会被合并进摘要
        @DefaultValue("2") int recentTurnsToKeep,
        // 主回答遇到模型上下文超限 MODEL_CONTEXT_OVERFLOW 时，最多重试几次
        @DefaultValue("1") int maxOverflowRetries,
        // 发生上下文超限后，重试时把输入预算缩到原来的多少
        @DefaultValue("0.75") double retryInputRatio,
        // 助教一次请求的总超时时间。主回答、摘要、重试都受这个整体 deadline 约束；SSE emitter 也基于它设置超时
        @DefaultValue("60") int requestTimeoutSeconds,
        // 单次会话摘要调用的超时时间，但实际还会受 requestTimeoutSeconds 剩余时间限制
        @DefaultValue("15") int summaryTimeoutSeconds
) {
    public TutorContextProperties {
        if (contextWindowTokens < 128 || maxOutputTokens < 1 || summaryMaxTokens < 1
                || safetyMarginRatio < 0 || safetyMarginRatio >= 0.5
                || recentTurnsToKeep < 1 || maxOverflowRetries < 0 || maxOverflowRetries > 1
                || retryInputRatio <= 0 || retryInputRatio >= 1
                || requestTimeoutSeconds < 1 || summaryTimeoutSeconds < 1
                || contextWindowTokens * (1 - safetyMarginRatio) <= Math.max(maxOutputTokens, summaryMaxTokens)) {
            throw new IllegalArgumentException("Invalid tutor context budget configuration");
        }
    }

    public int inputBudget() {
        return (int) (contextWindowTokens * (1 - safetyMarginRatio)) - maxOutputTokens;
    }

    public int summaryInputBudget() {
        return (int) (contextWindowTokens * (1 - safetyMarginRatio)) - summaryMaxTokens;
    }
}
