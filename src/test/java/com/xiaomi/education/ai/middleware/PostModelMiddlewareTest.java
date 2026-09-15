package com.xiaomi.education.ai.middleware;

import com.xiaomi.education.ai.guardrail.GuardrailService;
import com.xiaomi.education.ai.model.AiScenario;
import com.xiaomi.education.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostModelMiddlewareTest {

    private final PostModelMiddleware middleware = new PostModelMiddleware(new GuardrailService());

    @Test
    void formatsInterventionAndRedactsPiiAfterModelGeneration() {
        var result = middleware.process(
                AiScenario.LEARNER_INTERVENTION,
                "建议今天完成一个 20 分钟练习，如需帮助可联系 13812345678。"
        );

        assertThat(result.content())
                .startsWith(PostModelMiddleware.INTERVENTION_PREFIX)
                .endsWith(PostModelMiddleware.INTERVENTION_SUFFIX)
                .contains("[PHONE_REDACTED]")
                .doesNotContain("13812345678");
        assertThat(result.checks()).contains(
                "NON_EMPTY_VALIDATED",
                "LENGTH_VALIDATED",
                "SAFETY_LANGUAGE_VALIDATED",
                "PII_REDACTED",
                "STANDARD_FORMAT_APPLIED"
        );
        assertThat(result.riskCodes()).contains("PII_REDACTED");
    }

    @Test
    void blocksPunitiveModelOutputBeforeItCanBecomeAToolArgument() {
        assertThatThrownBy(() -> middleware.process(
                AiScenario.LEARNER_INTERVENTION,
                "如果今天没有完成练习，就扣分并取消课程资格。"
        ))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("MODEL_OUTPUT_POLICY_BLOCKED");
    }

    @Test
    void doesNotTreatAvoidanceAdviceAsNegationOfAPunitiveThreat() {
        assertThatThrownBy(() -> middleware.process(
                AiScenario.LEARNER_INTERVENTION,
                "请避免缺课，否则将退学。"
        ))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("MODEL_OUTPUT_POLICY_BLOCKED");
    }

    @Test
    void leavesUnrelatedScenariosUntouched() {
        var result = middleware.process(AiScenario.TUTOR_CHAT, "普通助教回答");

        assertThat(result.content()).isEqualTo("普通助教回答");
        assertThat(result.checks()).isEmpty();
        assertThat(result.riskCodes()).isEmpty();
    }
}
