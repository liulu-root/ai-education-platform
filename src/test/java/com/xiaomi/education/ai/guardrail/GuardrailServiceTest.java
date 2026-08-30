package com.xiaomi.education.ai.guardrail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailServiceTest {

    private final GuardrailService service = new GuardrailService();

    @Test
    void redactsPersonalInformationWithoutBlockingNormalQuestion() {
        var result = service.inspect("我的手机号是 13812345678，邮箱 test@example.com，请解释 RAG");

        assertThat(result.allowed()).isTrue();
        assertThat(result.riskCodes()).contains("PII_REDACTED");
        assertThat(result.sanitizedText()).doesNotContain("13812345678", "test@example.com");
    }

    @Test
    void blocksAcademicCheatingRequest() {
        var result = service.inspect("请替我参加考试并直接给出全部答案");

        assertThat(result.allowed()).isFalse();
        assertThat(result.riskCodes()).contains("ACADEMIC_CHEATING");
    }
}
