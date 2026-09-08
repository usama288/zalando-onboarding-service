package com.zalando.onboarding.api;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zalando.onboarding.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Resume, demonstrated the way it actually has to work: the link is never sent, so the only
 * way back into a draft is the one the outbox recorded (A18).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class DevOutboxEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void theRecordedResumeLinkLeadsBackIntoTheDraft() throws Exception {
        MvcResult created = mvc.perform(post("/api/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new CreateApplicationRequest("resume@example.com", "NL"))))
                .andExpect(status().isCreated())
                .andReturn();
        String applicationId = body(created).get("applicationId").toString();

        MvcResult outbox = mvc.perform(get("/api/dev/outbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("RESUME_LINK"))
                .andExpect(jsonPath("$[0].payload.resumeUrl").value(notNullValue()))
                .andReturn();

        String resumeUrl = outbox.getResponse().getContentAsString()
                .replaceAll("(?s).*\"resumeUrl\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        String token = resumeUrl.substring(resumeUrl.lastIndexOf('/') + 1);

        mvc.perform(get("/api/applications/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.country").value("NL"))
                .andExpect(jsonPath("$.resumeStep").value("PERSONAL_DETAILS"));

        // The outbox row is the one written for this application, in the same transaction.
        mvc.perform(get("/api/dev/outbox"))
                .andExpect(jsonPath("$[0].applicationId").value(applicationId));
    }

    private Map<?, ?> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsString(), Map.class);
    }
}
