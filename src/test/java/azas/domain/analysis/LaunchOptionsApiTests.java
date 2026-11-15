package azas.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import azas.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LaunchOptionsApiTests extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/analyses/{runId}/launch-options";
    private static final String START = "2027-01-01T00:00:00Z";
    private static final String TOUCHDOWN = "2027-01-19T04:10:00Z";
    private final JsonMapper json = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void returnsTheFirstSatisfiedWindowOfThisRunAndMatchesEveryReferenceLaunchSite() throws Exception {
        JsonNode golden;
        try (var input = getClass().getResourceAsStream("/analysis/launch-options-golden.json")) {
            golden = json.readTree(input);
        }
        long landingSite = jdbc.queryForObject("select max(landing_site_id) from landing_site", Long.class);
        UUID id = run("DATE_OPPORTUNITIES", "SUCCEEDED", START, landingSite);
        window(id, "SATISFIED", "2027-01-29T00:00:00Z");
        window(id, "UNKNOWN", "2027-01-05T00:00:00Z");
        window(id, "SATISFIED", TOUCHDOWN);
        window(run("DATE_OPPORTUNITIES", "SUCCEEDED", START, landingSite), "SATISFIED", "2027-01-02T00:00:00Z");

        var expectedSites = golden.path("launchSites");
        for (int i = expectedSites.size() - 1; i >= 0; i--) {
            var site = expectedSites.get(i);
            jdbc.update("insert into launch_site values (?, ?, ?, ?)", i + 1, site.path("launchSiteId").asString(),
                    site.path("latitudeDeg").asDouble(), site.path("longitudeDegEast").asDouble());
        }

        var report = report(id);
        assertThat(report.path("analysisRunId").asString()).isEqualTo(id.toString());
        assertThat(report.path("landingSiteId").asLong()).isEqualTo(landingSite);
        assertThat(report.path("executionStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(report.path("touchdownUtc")).isEqualTo(golden.path("touchdownUtc"));
        assertThat(report.path("model")).isEqualTo(golden.path("model"));
        assertThat(report.path("launchVerified")).isEqualTo(golden.path("launchVerified"));
        assertThat(report.path("assumptions")).isEqualTo(golden.path("assumptions"));
        assertThat(report.path("launchSites").size()).isEqualTo(13);
        for (int i = 0; i < expectedSites.size(); i++) {
            var actual = report.path("launchSites").get(i);
            var expected = expectedSites.get(i);
            assertThat(actual.path("launchSiteId").asLong()).isEqualTo(i + 1L);
            assertThat(actual.path("launchSiteCode")).isEqualTo(expected.path("launchSiteId"));
            assertThat(actual.path("latitudeDeg")).isEqualTo(expected.path("latitudeDeg"));
            assertThat(actual.path("longitudeDegEast")).isEqualTo(expected.path("longitudeDegEast"));
            assertThat(actual.path("candidates")).isEqualTo(expected.path("candidates"));
        }
        assertThat(report(id)).isEqualTo(report);
    }

    @Test
    void distinguishesNoTouchdownFromAnEmptyCatalogAndUsesTheUploadedSites() throws Exception {
        UUID id = dateRun("SUCCEEDED");
        window(id, "UNKNOWN", TOUCHDOWN);
        mvc.perform(get(PATH, id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.touchdownUtc").isEmpty()).andExpect(jsonPath("$.data.launchSites").isEmpty());
        window(id, "SATISFIED", TOUCHDOWN);
        mvc.perform(get(PATH, id)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LAUNCH_SITE_DATA_INCOMPLETE"));
        jdbc.update("insert into launch_site values (1, 'Kennedy', 28.61, -80.60)");
        var sites = report(id).path("launchSites");
        assertThat(sites.size()).isOne();
        assertThat(sites.get(0).path("candidates").get(0).path("candidateId").asString()).isEqualTo("Kennedy-1799883120");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "FAILED"})
    void exposesExecutionStatusWithoutUsingUnpublishedWindows(String state) throws Exception {
        UUID id = dateRun(state);
        window(id, "SATISFIED", TOUCHDOWN);
        mvc.perform(get(PATH, id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executionStatus").value(state))
                .andExpect(jsonPath("$.data.touchdownUtc").isEmpty()).andExpect(jsonPath("$.data.launchSites").isEmpty());
    }

    @Test
    void reportsUnknownRunAndWrongAnalysisType() throws Exception {
        mvc.perform(get(PATH, UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
        mvc.perform(get(PATH, run("VISIBILITY", "SUCCEEDED", START, 1))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_TYPE_MISMATCH"));
    }

    private JsonNode report(UUID id) throws Exception {
        return json.readTree(mvc.perform(get(PATH, id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    private UUID dateRun(String state) {
        return run("DATE_OPPORTUNITIES", state, START, 1);
    }

    private UUID run(String type, String state, String start, long landingSite) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into analysis_run (analysis_run_id, landing_site_id, analysis_type, execution_status,
                    analysis_start_at, analysis_end_at, mission_due_date, mission_deadline_at, sample_interval_minutes,
                    analysis_parameters, calculation_parameters_sha256, engine_version, engine_sha256)
                values (?, ?, ?::analysis_type, ?::execution_status, ?::timestamptz, '2027-02-01T00:00:00Z',
                    '2027-01-31', '2027-02-01T00:00:00Z', 10, '{}', repeat('a', 64), 'fixture', repeat('b', 64))
                """, id, landingSite, type, state, start);
        return id;
    }

    private void window(UUID run, String status, String start) {
        var time = Instant.parse(start);
        jdbc.update("""
                insert into landing_opportunity_result (analysis_run_id, opportunity_code, opportunity_status,
                    surface_condition_status, terrain_coverage_status, landing_window_start_at, landing_window_end_at,
                    proposed_landing_at, surface_activity_finished_at, deadline_satisfied, trajectory_solver_status,
                    trajectory_verified, calculation_model_level, provenance_id)
                values (?, ?, 'ENVIRONMENT_ONLY', ?, 'PARTIAL', ?::timestamptz, ?::timestamptz, ?::timestamptz,
                    ?::timestamptz, true, 'NOT_RUN', false, 'ENVIRONMENT_ONLY', 'fixture')
                """, run, UUID.randomUUID().toString(), status, start, time.plusSeconds(3600).toString(),
                time.plusSeconds(60).toString(), time.plusSeconds(7200).toString());
    }
}
