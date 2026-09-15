package com.xiaomi.education.databrowser;

import com.xiaomi.education.security.AuthenticatedUser;
import com.xiaomi.education.security.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DataBrowserIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void requiresLoginAndNeverAllowsWritesOrRoleBypass() throws Exception {
        mvc.perform(get("/api/data/tables")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/data/tables/course").with(as(UserRole.INSTRUCTOR)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/data/tables/ai_call_audit").with(as(UserRole.INSTRUCTOR)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/data/tables/tutor_message").with(as(UserRole.AUDITOR)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/data/tables/app_user").with(as(UserRole.LEARNER)).header("X-User-Role", "INSTRUCTOR"))
                .andExpect(status().isForbidden());
    }

    @Test
    void everyAdvertisedTableSupportsListingAndSearching() throws Exception {
        for (var role : UserRole.values()) {
            var response = mvc.perform(get("/api/data/tables").with(as(role)))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse().getContentAsString();
            for (var table : mapper.readTree(response)) {
                for (String search : new String[]{"", "test"}) {
                    mvc.perform(get("/api/data/tables/" + table.get("name").asText()).with(as(role)).param("search", search))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.columns").isArray())
                            .andExpect(jsonPath("$.rows").isArray());
                }
            }
        }
    }

    @Test
    void paginatesSearchesAndRejectsInvalidInput() throws Exception {
        mvc.perform(get("/api/data/tables/course").with(as(UserRole.INSTRUCTOR)).param("size", "1").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.rows.length()").value(1)).andExpect(jsonPath("$.page").value(2));
        mvc.perform(get("/api/data/tables/course").with(as(UserRole.INSTRUCTOR)).param("search", "java").param("page", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.rows[0].id").value("course-java"));
        for (String search : new String[]{"%", "_", "' OR 1=1 --"}) {
            mvc.perform(get("/api/data/tables/course").with(as(UserRole.INSTRUCTOR)).param("search", search))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        }
        for (String page : new String[]{"0", "-1", "abc", "1000001"}) {
            mvc.perform(get("/api/data/tables/course").with(as(UserRole.INSTRUCTOR)).param("page", page))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/data/tables/course").with(as(UserRole.INSTRUCTOR)).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/data/tables/course").with(as(UserRole.INSTRUCTOR)).param("search", "a".repeat(201)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/data/tables/flyway_schema_history").with(as(UserRole.INSTRUCTOR)))
                .andExpect(status().isNotFound());
    }

    @Test
    void excludesCredentialsAndOtherTenantsFromRowsAndCounts() throws Exception {
        jdbc.update("INSERT INTO tenant(id,name,status,token_budget) VALUES('data-other','Other','ACTIVE',1000)");
        jdbc.update("INSERT INTO course(id,tenant_id,code,title,level,status) VALUES('data-secret','data-other','SECRET','Secret','BEGINNER','PUBLISHED')");
        mvc.perform(get("/api/data/tables/course").with(as(UserRole.INSTRUCTOR)).header("X-Tenant-Id", "data-other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/data/tables/course").with(as(UserRole.INSTRUCTOR)).param("search", "Secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        String users = mvc.perform(get("/api/data/tables/app_user").with(as(UserRole.INSTRUCTOR)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(users).doesNotContain("password_hash", "$2b$", "$2a$");
    }

    @Test
    void scopesChildTablesThroughConversationAndPlanOwners() throws Exception {
        jdbc.update("INSERT INTO tenant(id,name,status,token_budget) VALUES('data-other','Other','ACTIVE',1000)");
        for (String suffix : new String[]{"own", "peer", "foreign"}) {
            String tenant = suffix.equals("foreign") ? "data-other" : "tenant-demo";
            String learner = suffix.equals("peer") ? "learner-peer" : "learner-001";
            String id = "data-" + suffix;
            jdbc.update("INSERT INTO tutor_conversation(id,tenant_id,learner_id,title,status) VALUES(?,?,?,?, 'ACTIVE')", id, tenant, learner, suffix);
            jdbc.update("INSERT INTO tutor_message(id,conversation_id,role,content,message_seq) VALUES(?,?,'user',?,1)", id, id, suffix);
            jdbc.update("INSERT INTO tutor_conversation_memory(conversation_id,summary_content) VALUES(?,?)", id, suffix);
            jdbc.update("INSERT INTO tutor_context_attempt(id,request_id,conversation_id,attempt,input_tokens,input_budget,retrieved_chunks,history_messages,degraded,status) VALUES(?,?,?,1,1,100,0,0,FALSE,'SUCCESS')", id, id, id);
            jdbc.update("INSERT INTO learning_plan(id,tenant_id,learner_id,course_id,goal,status) VALUES(?,?,?,'course-java',?,'ACTIVE')", id, tenant, learner, suffix);
            jdbc.update("INSERT INTO learning_plan_item(id,plan_id,sequence_no,skill_code,activity,estimated_minutes,completion_status) VALUES(?,?,1,'test',?,10,'PENDING')", id, id, suffix);
        }
        for (String table : new String[]{"tutor_conversation", "tutor_message", "tutor_conversation_memory", "tutor_context_attempt", "learning_plan", "learning_plan_item"}) {
            mvc.perform(get("/api/data/tables/" + table).with(as(UserRole.LEARNER)).param("search", "data-"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        }
        mvc.perform(get("/api/data/tables/tutor_message").with(as(UserRole.LEARNER)).param("search", "peer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    private RequestPostProcessor as(UserRole role) {
        return user(new AuthenticatedUser(role == UserRole.LEARNER ? "learner-001" : "viewer",
                "tenant-demo", "Viewer", "viewer@example.com", role, "unused"));
    }
}
