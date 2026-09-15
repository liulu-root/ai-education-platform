package com.xiaomi.education.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.endpoints.web.exposure.include=health,info,metrics,prometheus")
@AutoConfigureMockMvc
class AuthenticationAuthorizationIntegrationTest {

    private static final String DEMO_PASSWORD = "Demo123!";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    AuthenticationAuthorizationIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void anonymousBusinessRequestIsRejectedAndSessionEndpointReturnsCsrfToken() throws Exception {
        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        var result = mockMvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.csrf.headerName").isNotEmpty())
                .andExpect(jsonPath("$.csrf.token").isNotEmpty())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isInstanceOf(MockHttpSession.class);
    }

    @Test
    void loginRequiresCsrfAndRejectsInvalidPasswordWithoutLeakingAccountState() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("linxiao@example.com", DEMO_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        var anonymous = anonymousSession();
        mockMvc.perform(post("/api/auth/login")
                        .session(anonymous.session())
                        .header(anonymous.csrfHeader(), anonymous.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("linxiao@example.com", "WrongPass123!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("邮箱或密码错误"));
    }

    @Test
    void learnerCanUseLearningFeaturesButCannotForgeAuditorOrInstructorRole() throws Exception {
        var learner = login("linxiao@example.com", "LEARNER");

        mockMvc.perform(get("/api/ai/tutor/conversations").session(learner.session()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ai/learning-paths").session(learner.session()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/ai-audit/overview")
                        .session(learner.session())
                        .header("X-User-Role", "AUDITOR")
                        .header("X-User-Id", "auditor-001")
                        .header("X-Tenant-Id", "another-tenant"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/dashboard/overview")
                        .session(learner.session())
                        .header("X-User-Role", "INSTRUCTOR"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/ai/interventions/approvals").session(learner.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void instructorCanUseTeachingFeaturesButNotLearnerOrAuditFeatures() throws Exception {
        var instructor = login("wang@example.com", "INSTRUCTOR");

        mockMvc.perform(get("/api/dashboard/overview")
                        .session(instructor.session())
                        .header("X-Tenant-Id", "forged-tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.id").value("tenant-demo"));
        mockMvc.perform(get("/api/knowledge/documents").session(instructor.session()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ai/risk/assessment")
                        .session(instructor.session())
                        .param("courseId", "course-data")
                        .param("learnerId", "learner-001"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ai/interventions/approvals").session(instructor.session()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/ai/learning-paths").session(instructor.session()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/ai-audit/overview").session(instructor.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditorCanReadAuditAndMetricsButCannotUseLearnerFeatures() throws Exception {
        var auditor = login("audit@example.com", "AUDITOR");

        mockMvc.perform(get("/api/dashboard/overview").session(auditor.session()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ai-audit/overview").session(auditor.session()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics").session(auditor.session()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/ai/tutor/conversations").session(auditor.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void mutationRequiresCurrentCsrfTokenAndLogoutInvalidatesSession() throws Exception {
        var learner = login("linxiao@example.com", "LEARNER");

        mockMvc.perform(post("/api/ai/learning-paths")
                        .session(learner.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "courseId": "course-java",
                                  "goal": "掌握企业级 RAG 设计",
                                  "weeklyMinutes": 240
                                }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/auth/logout")
                        .session(learner.session())
                        .header(learner.csrfHeader(), learner.csrfToken()))
                .andExpect(status().isNoContent());

        assertThat(learner.session().isInvalid()).isTrue();
        mockMvc.perform(get("/api/ai/tutor/conversations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void demoPasswordsAreStoredAsBcryptHashes() {
        var rows = jdbcTemplate.queryForList("""
                SELECT password_hash
                FROM app_user
                WHERE id IN ('learner-001', 'instructor-001', 'auditor-001')
                """);

        assertThat(rows).hasSize(3).allSatisfy(row -> {
            var hash = String.valueOf(row.get("password_hash"));
            assertThat(hash).startsWith("$2").doesNotContain(DEMO_PASSWORD);
        });
    }

    private LoggedInSession login(String email, String expectedRole) throws Exception {
        var anonymous = anonymousSession();
        var originalSessionId = anonymous.session().getId();
        var result = mockMvc.perform(post("/api/auth/login")
                        .session(anonymous.session())
                        .header(anonymous.csrfHeader(), anonymous.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, DEMO_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.role").value(expectedRole))
                .andExpect(jsonPath("$.user.tenantId").value("tenant-demo"))
                .andExpect(jsonPath("$.csrf.token").isNotEmpty())
                .andReturn();

        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        var rotatedCsrfToken = body.path("csrf").path("token").asText();
        assertThat(anonymous.session().getId()).isNotEqualTo(originalSessionId);
        assertThat(rotatedCsrfToken).isNotEqualTo(anonymous.csrfToken());

        return new LoggedInSession(
                anonymous.session(),
                body.path("csrf").path("headerName").asText(),
                rotatedCsrfToken
        );
    }

    private AnonymousSession anonymousSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new AnonymousSession(
                (MockHttpSession) result.getRequest().getSession(false),
                body.path("csrf").path("headerName").asText(),
                body.path("csrf").path("token").asText()
        );
    }

    private String loginJson(String email, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password);
    }

    private record AnonymousSession(MockHttpSession session, String csrfHeader, String csrfToken) {
    }

    private record LoggedInSession(MockHttpSession session, String csrfHeader, String csrfToken) {
    }
}
