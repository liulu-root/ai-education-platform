package com.xiaomi.education.ai;

import com.xiaomi.education.ai.model.AiModelClient;
import com.xiaomi.education.ai.model.AiModelRequest;
import com.xiaomi.education.ai.model.AiModelResponse;
import com.xiaomi.education.ai.model.AiModelStream;
import com.xiaomi.education.ai.model.AiScenario;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(AiGatewayPostModelAuditIntegrationTest.PolicyOutputModelConfiguration.class)
class AiGatewayPostModelAuditIntegrationTest {

    private static final String TRACE_ID = "post-model-policy-audit-test";

    private final AiGateway gateway;
    private final JdbcTemplate jdbcTemplate;
    private TenantContext.Scope tenantScope;

    @Autowired
    AiGatewayPostModelAuditIntegrationTest(AiGateway gateway, JdbcTemplate jdbcTemplate) {
        this.gateway = gateway;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void openContext() {
        tenantScope = TenantContext.open(new TenantContext("tenant-demo", "instructor-001", "INSTRUCTOR"));
        MDC.put("traceId", TRACE_ID);
    }

    @AfterEach
    void closeContext() {
        MDC.remove("traceId");
        tenantScope.close();
    }

    @Test
    void outputPolicyBlockAuditsProviderUsageAndViolation() {
        var request = new AiModelRequest(
                AiScenario.LEARNER_INTERVENTION,
                "生成友善的学习支持通知",
                "请给出下一步学习建议",
                Map.of()
        );

        assertThatThrownBy(() -> gateway.execute(request))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).code())
                .isEqualTo("MODEL_OUTPUT_POLICY_BLOCKED");

        var audit = jdbcTemplate.queryForMap("""
                SELECT status, risk_level, risk_codes, error_code,
                       input_tokens, output_tokens, estimated_cost
                FROM ai_call_audit
                WHERE trace_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, TRACE_ID);
        assertThat(audit.get("status")).isEqualTo("BLOCKED");
        assertThat(audit.get("risk_level")).isEqualTo("HIGH");
        assertThat(String.valueOf(audit.get("risk_codes"))).contains("PUNITIVE_LANGUAGE");
        assertThat(audit.get("error_code")).isEqualTo("MODEL_OUTPUT_POLICY_BLOCKED");
        assertThat(((Number) audit.get("input_tokens")).intValue()).isEqualTo(41);
        assertThat(((Number) audit.get("output_tokens")).intValue()).isEqualTo(9);
        assertThat(((Number) audit.get("estimated_cost")).doubleValue()).isPositive();

        var violationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM policy_violation
                WHERE trace_id = ? AND violation_type = 'OUTPUT_PUNITIVE_LANGUAGE' AND action = 'BLOCK'
                """, Integer.class, TRACE_ID);
        assertThat(violationCount).isEqualTo(1);

        var usage = jdbcTemplate.queryForMap("""
                SELECT input_tokens, output_tokens, request_count
                FROM ai_token_usage_daily
                WHERE usage_date = ? AND tenant_id = ? AND user_id = ?
                  AND scenario = ? AND model = ?
                """,
                LocalDate.now(),
                "tenant-demo",
                "instructor-001",
                AiScenario.LEARNER_INTERVENTION.name(),
                "policy-output-test-model");
        assertThat(((Number) usage.get("input_tokens")).longValue()).isEqualTo(41);
        assertThat(((Number) usage.get("output_tokens")).longValue()).isEqualTo(9);
        assertThat(((Number) usage.get("request_count")).longValue()).isEqualTo(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PolicyOutputModelConfiguration {

        @Bean
        @Primary
        AiModelClient policyOutputModelClient() {
            return new AiModelClient() {
                @Override
                public AiModelResponse generate(AiModelRequest request) {
                    return new AiModelResponse(
                            "如果今天没有完成练习，就扣分并取消课程资格。",
                            provider(),
                            model(),
                            41,
                            9,
                            0,
                            "policy-output-test-request"
                    );
                }

                @Override
                public AiModelStream generateStream(AiModelRequest request, Consumer<String> onDelta) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String provider() {
                    return "policy-output-test";
                }

                @Override
                public String model() {
                    return "policy-output-test-model";
                }
            };
        }
    }
}
