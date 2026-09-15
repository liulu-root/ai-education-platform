package com.xiaomi.education.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class StaticResourceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void faviconIsAvailableWithoutLogin() throws Exception {
        var response = mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getContentAsByteArray()).startsWith(0, 0, 1, 0);
        assertThat(response.getContentType()).isIn("image/x-icon", "image/vnd.microsoft.icon");
    }

    @Test
    void missingResourceReturns404WithoutUnexpectedFailureLog(CapturedOutput output) throws Exception {
        // Use an authorized namespace so the request reaches the static resource handler.
        mockMvc.perform(get("/api/knowledge/__missing__/asset.js")
                        .with(user("instructor").roles("INSTRUCTOR"))
                        .header("X-Trace-Id", "missing-resource-test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").value("missing-resource-test"));

        assertThat(output.getAll()).doesNotContain("Unexpected request failure");
    }
}
