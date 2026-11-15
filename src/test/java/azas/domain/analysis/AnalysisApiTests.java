package azas.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import azas.domain.analysis.engine.AnalysisEngine;
import azas.domain.analysis.engine.AnalysisEngineException;
import azas.domain.analysis.engine.HorizonsClient;
import azas.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class AnalysisApiTests extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean HorizonsClient horizons;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> workerRequestId = new AtomicReference<>();

    @BeforeEach void prepare() {
        reset(horizons);
        workerRequestId.set(null);
        when(horizons.angles(anyInt(), any())).thenAnswer(call -> {
            int target = call.getArgument(0);
            AnalysisEngine.Input input = call.getArgument(1);
            workerRequestId.set(MDC.get("requestId"));
            int size = Math.toIntExact(Duration.between(input.start(), input.end()).toSeconds() / input.stepSeconds() + 1);

            return new HorizonsClient.Series(Collections.nCopies(size, new HorizonsClient.Angle(90, target == 10 ? -90 : 90)),
                    new AnalysisEngine.Source(target, "https://example.test/horizons", "a".repeat(64), Instant.now()));
        });
    }

    @Test void listsOnlySiteSummariesAndReturnsDetailsByDatabasePrimaryKey() throws Exception {
        mvc.perform(get("/openapi.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/landing-sites/{id}/analyses'].post.responses['202']").exists());
        mvc.perform(get("/api/v1/landing-sites")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(27)).andExpect(jsonPath("$.data[0].landingSiteId").isNumber())
                .andExpect(jsonPath("$.data[0].horizon").doesNotExist());
        mvc.perform(get("/api/v1/landing-sites/" + siteId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteCode").value("Site01"))
                .andExpect(jsonPath("$.data.longitudeDegrees").value(-129.964439))
                .andExpect(jsonPath("$.data.observerHeightAboveSurfaceMeters").value(1));
        mvc.perform(get("/api/v1/landing-sites").param("terrainDatasetId", "9223372036854775807"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TERRAIN_DATASET_NOT_FOUND"));
        verifyNoInteractions(horizons);
    }

    @Test void runsTheRealAdapterAndCalculatorFromDbInputAndPersistsPerDayResults() throws Exception {
        var response = mvc.perform(post("/api/v1/landing-sites/" + siteId() + "/analyses")
                        .contentType("application/json").content(request("2026-12-01", "2026-12-02")))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location"))
                .andExpect(header().exists("X-Request-ID")).andReturn().getResponse();
        UUID id = UUID.fromString(json.readTree(response.getContentAsString()).path("data").path("analysisRunId").asString());
        var run = await(id);
        assertThat(run.path("executionStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.path("overallResultStatus").asString()).isEqualTo("PARTIAL");
        assertThat(run.path("analysisEndAt").asString()).isEqualTo("2026-12-03T00:00:00Z");
        assertThat(run.path("sampleIntervalMinutes").asInt()).isEqualTo(60);
        assertThat(run.path("engineSha256").asString()).matches("[0-9a-f]{64}");
        assertThat(workerRequestId.get()).isEqualTo(response.getHeader("X-Request-ID"));
        mvc.perform(get("/api/v1/analyses/" + id + "/daily-results")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].longestSunOccultationHours").value(24))
                .andExpect(jsonPath("$.data[0].earthKnownTimeRatio").value(0))
                .andExpect(jsonPath("$.data[0].earthVisibleTimeRatio").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].visibilityWindows.length()").value(0));
        mvc.perform(get("/api/v1/analyses/" + id + "/daily-results").param("date", "2026-12-02"))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].analysisDate").value("2026-12-02"));
        assertThat(jdbc.queryForObject("select source_provenance::text from analysis_run where analysis_run_id = ?", String.class, id))
                .contains("responseSha256", "retrievedAt", "terrainDatasetSha256");
        verify(horizons, times(2)).angles(anyInt(), any());
    }

    @Test void identicalRequestsCreateNewExecutionsAndFetchFreshSources() throws Exception {
        UUID first = submit("2026-12-01", "2026-12-01");
        await(first);
        UUID second = submit("2026-12-01", "2026-12-01");
        await(second);
        assertThat(first).isNotEqualTo(second);
        assertThat(jdbc.queryForObject("select calculation_parameters_sha256 from analysis_run where analysis_run_id = ?", String.class, first))
                .isEqualTo(jdbc.queryForObject("select calculation_parameters_sha256 from analysis_run where analysis_run_id = ?", String.class, second));
        verify(horizons, times(4)).angles(anyInt(), any());
    }

    @Test void recordsSafeSourceFailureAndDoesNotLeavePartialDailyResults() throws Exception {
        doThrow(new AnalysisEngineException("HORIZONS_UNAVAILABLE", new IllegalArgumentException("private upstream response")))
                .when(horizons).angles(eq(399), any());
        UUID id = submit("2026-12-01", "2026-12-01");
        var result = await(id);
        assertThat(result.path("executionStatus").asString()).isEqualTo("FAILED");
        assertThat(result.path("failureCode").asString()).isEqualTo("HORIZONS_UNAVAILABLE");
        assertThat(result.toString()).doesNotContain("private upstream response");
        mvc.perform(get("/api/v1/analyses/" + id + "/daily-results"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test void rejectsInvalidRangesMissingFieldsAndUnknownPrimaryKeysBeforeAnalysis() throws Exception {
        for (String body : new String[]{request("2026-12-03", "2026-12-01"), request("2026-12-01", "2027-01-01"),
                "{}", "{\"startDate\":\"not-a-date\",\"endDate\":\"2026-12-01\"}"}) {
            mvc.perform(post("/api/v1/landing-sites/" + siteId() + "/analyses").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
        }

        mvc.perform(post("/api/v1/landing-sites/9223372036854775807/analyses").contentType("application/json")
                        .content(request("2026-12-01", "2026-12-01")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LANDING_SITE_NOT_FOUND"));
        mvc.perform(get("/api/v1/landing-sites/-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/analyses/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/analyses/not-a-uuid")).andExpect(status().isBadRequest());
        verifyNoInteractions(horizons);
    }

    @Test void acceptsTheFull31DayBoundary() throws Exception {
        var id = submit("2026-12-01", "2026-12-31");
        assertThat(await(id).path("executionStatus").asString()).isEqualTo("SUCCEEDED");
        mvc.perform(get("/api/v1/analyses/" + id + "/daily-results"))
                .andExpect(jsonPath("$.data.length()").value(31));
    }

    private long siteId() { return jdbc.queryForObject("select landing_site_id from landing_site where site_code = 'Site01'", Long.class); }
    private String request(String start, String end) { return "{\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}"; }
    private UUID submit(String start, String end) throws Exception {
        String body = mvc.perform(post("/api/v1/landing-sites/" + siteId() + "/analyses")
                        .contentType("application/json").content(request(start, end)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        return UUID.fromString(json.readTree(body).path("data").path("analysisRunId").asString());
    }

    private JsonNode await(UUID id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();

        while (System.nanoTime() < deadline) {
            var run = json.readTree(mvc.perform(get("/api/v1/analyses/" + id)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).path("data");

            if (run.path("executionStatus").asString().equals("SUCCEEDED") || run.path("executionStatus").asString().equals("FAILED")) {
                return run;
            }

            Thread.sleep(25);
        }

        throw new AssertionError("Analysis did not finish: " + id);
    }
}
