package azas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsInAnyOrder;

import org.junit.jupiter.api.Test;
import azas.support.PostgresIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "azas.cors-origins=[\"https://frontend.example\"]",
        "azas.app-name=Test AZAS API"
})
@AutoConfigureMockMvc
class AzasApplicationTests extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthRemainsCompatible() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void healthRedirectUsesTrustedProxyScheme() throws Exception {
        mvc.perform(get("/health/")
                        .header("Host", "api.azas.n-e.kr")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string("Location", "https://api.azas.n-e.kr/health"));
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() throws Exception {
        mvc.perform(options("/health")
                        .header("Origin", "https://frontend.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://frontend.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        mvc.perform(options("/health")
                        .header("Origin", "https://unconfigured.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void apiDocsKeepTheirPublicPathsAndConfiguredTitle() throws Exception {
        mvc.perform(get("/docs"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Test AZAS API"))
                .andExpect(jsonPath("$.paths['/health']").exists());
    }

    @Test
    void analysisOpenApiPublishesRequestExamplesAndTrajectoryStates() throws Exception {
        String post = "$.paths['/api/v1/landing-sites/{id}/date-opportunities'].post";
        String examples = post + ".requestBody.content['application/json'].examples";
        String detail = "$.paths['/api/v1/analyses/{runId}/trajectories/{trajectoryId}'].get";
        mvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(post + ".requestBody.required").value(true))
                .andExpect(jsonPath(examples + "['Default landing conditions'].value.missionDeadlineUtc").value("2027-01-31"))
                .andExpect(jsonPath(examples + "['One hour with Earth below the horizon'].value.surfaceCriteria.earth")
                        .value("OCCULTED"))
                .andExpect(jsonPath(post + ".responses['202'].content.*.schema").isNotEmpty())
                .andExpect(jsonPath(detail + ".responses['200'].content.*.schema").isNotEmpty())
                .andExpect(jsonPath(detail + ".responses['404'].content['application/json'].examples['Trajectory not found'].value.code")
                        .value("TRAJECTORY_NOT_FOUND"))
                .andExpect(jsonPath("$.components.schemas.LaunchTrajectoryResponse.properties.executionStatus.enum")
                        .value(containsInAnyOrder("PENDING", "RUNNING", "SUCCEEDED", "FAILED")));
    }

    @Test
    void frontendCanReadTheServerGeneratedRequestId() throws Exception {
        mvc.perform(get("/health").header("Origin", "https://frontend.example"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(header().string("Access-Control-Expose-Headers", "X-Request-ID"));
    }

    @Test
    void onlyHealthActuatorEndpointIsExposed() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/env"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
