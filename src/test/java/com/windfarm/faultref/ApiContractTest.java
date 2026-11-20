package com.windfarm.faultref;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.windfarm.faultref.service.DiagnosisService;
import com.windfarm.faultref.web.dto.DiagnosisDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP contract tests with security enabled: auth, role gates, OpenAPI exposure
 * and the public read paths used by the demo.
 */
@AutoConfigureMockMvc
class ApiContractTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestFixtures fx;
    @Autowired
    private DiagnosisService diagnosis;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void seedSmallSet() {
        fx.manufacturer("NW", "NordWind");
        fx.model("NW", "NW-8");
        fx.firmware("NW", "NW-8", "V1.0", 1);
        fx.unit("A01", "NW", "NW-8");
        fx.install("A01", "V1.0", "2023-01-01T00:00:00Z", null);
        fx.controller("NW_CTRL");
        fx.concept("HYD_LOW", "Low pressure", "HYDRAULIC", "HIGH", "pressure below 120 bar");
        var code = fx.sourceCode("NW_CTRL", "HYD-201");
        fx.assertion(code, "HYD_LOW", 1, null, null, null, null, "MOC-1");
        fx.alarm("ALT-1", "A01", "NW_CTRL", "HYD-201", "2025-04-01T00:00:00Z",
                "2025-04-01T00:00:10Z", "REALTIME");
        diagnosis.run(new DiagnosisDtos.RunRequest("ASSERTION", "LATEST", null, null, null, null,
                null, null, false, null, null, null), "tester");
    }

    @Test
    void unauthenticatedReadIsRejected() throws Exception {
        mvc.perform(get("/api/reference/units"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCanRead() throws Exception {
        mvc.perform(get("/api/reference/units").with(httpBasic("viewer", "viewer123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("A01"));
    }

    @Test
    void viewerCannotCreate() throws Exception {
        mvc.perform(post("/api/reference/manufacturers")
                        .with(httpBasic("viewer", "viewer123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X\",\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void engineerCanCreate() throws Exception {
        mvc.perform(post("/api/reference/manufacturers")
                        .with(httpBasic("engineer", "engineer123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"VG\",\"name\":\"Vestas\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("VG"));
    }

    @Test
    void validationErrorsAreStructured() throws Exception {
        mvc.perform(post("/api/reference/manufacturers")
                        .with(httpBasic("engineer", "engineer123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void openApiDocumentIsPublic() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").exists());
    }

    @Test
    void diagnosisRunIsExecutableOverHttp() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "mode", "ASSERTION", "basis", "LATEST", "alarmIds", List.of()));
        String response = mvc.perform(post("/api/diagnosis/runs")
                        .with(httpBasic("engineer", "engineer123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(response);
        JsonNode result = node.get("results").get(0);
        org.assertj.core.api.Assertions.assertThat(result.get("primaryConceptCode").asText())
                .isEqualTo("HYD_LOW");
        org.assertj.core.api.Assertions.assertThat(result.get("occurredAt").asText())
                .isEqualTo("2025-04-01T00:00:00Z");
    }

    @Test
    void alarmBackfillValidationRejectsReceivedBeforeOccurred() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "externalId", "ALT-BAD", "unitCode", "A01", "sourceSystemCode", "NW_CTRL",
                "sourceCode", "HYD-201", "occurredAt", "2025-04-02T00:00:00Z",
                "receivedAt", "2025-04-01T00:00:00Z"));
        mvc.perform(post("/api/evidence/alarms")
                        .with(httpBasic("engineer", "engineer123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECEIVED_BEFORE_OCCURRED"));
    }
}
