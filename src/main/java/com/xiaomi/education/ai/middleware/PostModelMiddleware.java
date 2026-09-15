package com.xiaomi.education.ai.middleware;

import com.xiaomi.education.ai.guardrail.GuardrailService;
import com.xiaomi.education.ai.model.AiScenario;
import com.xiaomi.education.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class PostModelMiddleware {

    public static final int MAX_INTERVENTION_CODE_POINTS = 800;
    public static final String INTERVENTION_PREFIX = "【学习支持提醒】\n";
    public static final String INTERVENTION_SUFFIX =
            "\n\n—— 此消息由 AI 辅助生成并经安全检查，仅用于学习支持。";

    private static final Pattern PUNITIVE_LANGUAGE = Pattern.compile(
            "(扣分|处分|惩罚|退学|开除|停学|取消.{0,4}资格|降低.{0,4}成绩)"
    );
    private static final Pattern NON_PUNITIVE_CONTEXT = Pattern.compile(
            ".*(不会|不得|不应|不能|禁止|不用于|无须|无需|非)[^，。；！？\\n]{0,10}"
    );

    private final GuardrailService guardrailService;

    public PostModelMiddleware(GuardrailService guardrailService) {
        this.guardrailService = guardrailService;
    }

    public ProcessedOutput process(AiScenario scenario, String content) {
        if (scenario != AiScenario.LEARNER_INTERVENTION) {
            return new ProcessedOutput(content, List.of(), List.of());
        }

        var value = content == null ? "" : content.strip();
        if (value.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "MODEL_OUTPUT_EMPTY",
                    "模型未生成可用的干预消息",
                    List.of("OUTPUT_EMPTY")
            );
        }
        if (value.codePointCount(0, value.length()) > MAX_INTERVENTION_CODE_POINTS) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "MODEL_OUTPUT_TOO_LONG",
                    "模型生成的干预消息超过长度限制",
                    List.of("OUTPUT_TOO_LONG")
            );
        }
        if (containsPunitiveLanguage(value)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MODEL_OUTPUT_POLICY_BLOCKED",
                    "模型输出包含惩罚性措辞，已阻断",
                    List.of("PUNITIVE_LANGUAGE")
            );
        }

        var guarded = guardrailService.inspect(value);
        if (!guarded.allowed()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MODEL_OUTPUT_POLICY_BLOCKED",
                    "模型输出触发安全策略，已阻断",
                    guarded.riskCodes()
            );
        }

        var checks = new ArrayList<String>();
        checks.add("NON_EMPTY_VALIDATED");
        checks.add("LENGTH_VALIDATED");
        checks.add("SAFETY_LANGUAGE_VALIDATED");
        checks.add(guarded.riskCodes().contains("PII_REDACTED") ? "PII_REDACTED" : "PII_CLEAR");
        checks.add("STANDARD_FORMAT_APPLIED");
        var riskCodes = guarded.riskCodes().stream()
                .filter("PII_REDACTED"::equals)
                .toList();
        return new ProcessedOutput(
                INTERVENTION_PREFIX + guarded.sanitizedText().strip() + INTERVENTION_SUFFIX,
                checks,
                riskCodes
        );
    }

    public boolean requiresBufferedOutput(AiScenario scenario) {
        return scenario == AiScenario.LEARNER_INTERVENTION;
    }

    private boolean containsPunitiveLanguage(String content) {
        var matcher = PUNITIVE_LANGUAGE.matcher(content);
        while (matcher.find()) {
            var contextStart = Math.max(0, matcher.start() - 24);
            var context = content.substring(contextStart, matcher.start());
            if (!NON_PUNITIVE_CONTEXT.matcher(context).matches()) {
                return true;
            }
        }
        return false;
    }

    public record ProcessedOutput(String content, List<String> checks, List<String> riskCodes) {
        public ProcessedOutput {
            checks = List.copyOf(checks);
            riskCodes = List.copyOf(riskCodes);
        }
    }
}
