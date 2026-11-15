package azas.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import azas.domain.analysis.dto.DateOpportunityRequest;
import azas.domain.analysis.engine.DateOpportunityEngine;
import azas.domain.analysis.service.AnalysisStore;
import azas.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostLandingApiTests extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/analyses/{runId}/surface-candidates/{candidateId}/post-landing";
    private static final String TOUCHDOWN = "2027-01-19T04:10:00Z";
    private static final String FINISH = "2027-01-21T04:10:00Z";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AnalysisStore store;
    @Autowired DateOpportunityEngine engine;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void matchesEveryFieldOfTheSuppliedSite01Report() throws Exception {
        long site = site("Site01");
        loadOriginalObserverData(site);
        var detail = data("/api/v1/landing-sites/{id}", site);
        assertThat(detail.path("siteCode").asString()).isEqualTo("Site01");
        assertThat(detail.path("landingSiteId").asLong()).isEqualTo(site);
        var request = new DateOpportunityRequest("2027-01-31", null);
        var run = store.createOpportunities(site, request, engine.identity());
        assertThat(store.start(run)).isTrue();
        assertThat(store.completeOpportunities(run, engine.analyze(site, run.input(), request))).isTrue();
        var first = data("/api/v1/analyses/{runId}/date-opportunities", run.id()).path("candidates").get(0);
        assertThat(first.path("proposedLandingUtc").asString()).isEqualTo(TOUCHDOWN);
        var actual = data(PATH, run.id(), first.path("candidateId").asString());
        try (var file = getClass().getResourceAsStream("/analysis/post_landing.json")) {
            var original = (tools.jackson.databind.node.ObjectNode) json.readTree(file);
            original.put("surfaceCandidateId", first.path("candidateId").asString());
            assertThat(actual.path("samples").size()).isEqualTo(289);
            assertThat(actual).isEqualTo(original);
            assertThat(detail.path("meanDailyVisibility")).isEqualTo(original.path("meanDailyVisibility"));
        }
    }

    @Test
    void legacyCandidateUsesItsOwnSiteAndUtcGridEvenWhenTouchdownIsOffGrid() throws Exception {
        long site = site("DM1");
        // A full reference period and the final archive epoch are sufficient for this candidate.
        jdbc.update("""
                insert into observer_sample select ?, t, 360, -90, 0, 90
                from generate_series('2027-01-01T00:00Z'::timestamptz, '2028-12-31T23:50Z', interval '10 minutes') t
                """, site);
        jdbc.update("insert into observer_sample values (?, '2030-01-01T00:00Z', 360, -90, 0, 90)", site);
        UUID run = run(site, "DATE_OPPORTUNITIES", "SUCCEEDED", false);
        jdbc.update("""
                insert into landing_opportunity_result (analysis_run_id, opportunity_code, opportunity_status,
                    surface_condition_status, terrain_coverage_status, landing_window_start_at, landing_window_end_at,
                    proposed_landing_at, surface_activity_finished_at, deadline_satisfied, trajectory_solver_status,
                    trajectory_verified, calculation_model_level, provenance_id)
                values (?, 'legacy-window', 'ENVIRONMENT_ONLY', 'SATISFIED', 'LOCAL_HORIZON_ONLY',
                    '2029-12-31T23:57Z','2029-12-31T23:57Z','2029-12-31T23:57Z','2030-01-01T00:00Z',
                    true,'NOT_EVALUATED',false,'ENVIRONMENT_ONLY','fixture')
                """, run);
        String id = data("/api/v1/analyses/{runId}/date-opportunities", run).path("candidates").get(0).path("candidateId").asString();
        var result = data(PATH, run, id);
        assertThat(result.path("siteId").asString()).isEqualTo("DM1");
        assertThat(result.path("samples").size()).isOne();
        var sample = result.path("samples").get(0);
        assertThat(sample.path("elapsedSeconds").asLong()).isEqualTo(180);
        assertThat(sample.path("sun").path("terrainHorizonDeg")).isEqualTo(sample.path("earth").path("terrainHorizonDeg"));
        assertThat(sample.path("jointVisible").asBoolean()).isFalse();
        assertThat(result.path("nextTerrainOccultationUtc").path("sun").asString()).isEqualTo("2030-01-01T00:00:00Z");
        assertThat(result.path("nextTerrainOccultationUtc").path("earth").isNull()).isTrue();
        assertThat(result.path("meanDailyVisibility").path("earthMeanDailyVisiblePct").asDouble()).isEqualTo(100);
        assertThat(result.path("meanDailyVisibility").path("sunMeanDailyVisiblePct").asDouble()).isZero();
        assertThat(data("/api/v1/landing-sites/{id}", site).path("meanDailyVisibility")).isEqualTo(result.path("meanDailyVisibility"));
        jdbc.update("delete from observer_sample where landing_site_id=? and observed_at='2028-02-29T12:00Z'", site);
        assertThat(data("/api/v1/landing-sites/{id}", site).path("meanDailyVisibility").isNull()).isTrue();
        mvc.perform(get(PATH, run, id)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OBSERVER_DATA_INCOMPLETE"));
    }

    @Test
    void usesTheSavedObserverHeightForTheSkyTrackAndDailyMeans() throws Exception {
        long site = site("Site01");
        jdbc.update("""
                insert into observer_sample select ?, t, 0, 7.24, 0, 7.24
                from generate_series('2027-01-01T00:00Z'::timestamptz, '2028-12-31T23:50Z', interval '10 minutes') t
                """, site);
        jdbc.update("insert into observer_sample values (?, '2030-01-01T00:00Z', 0, 7.24, 0, 7.24)", site);
        UUID run = run(site, "DATE_OPPORTUNITIES", "SUCCEEDED", true);
        jdbc.update("""
                update analysis_run set analysis_parameters = '{"request":{},"referenceObserverHeightAboveSurfaceMeters":1,
                    "site":{"observerHeightAboveSurfaceMeters":2}}'::jsonb where analysis_run_id = ?
                """, run);
        var id = candidate(run, "2029-12-31T23:59:00Z", "2030-01-01T00:00:00Z");
        var result = data(PATH, run, id);
        assertThat(result.path("model").asString()).isEqualTo("FIXED_BLOCKER_HEIGHT_APPROXIMATION");
        var sample = result.path("samples").get(0);
        assertThat(sample.path("sun").path("terrainHorizonDeg").asDouble()).isBetween(7.232, 7.235);
        assertThat(sample.path("jointVisible").asBoolean()).isTrue();
        assertThat(result.path("meanDailyVisibility").path("sunMeanDailyVisiblePct").asDouble()).isEqualTo(100);
    }

    @Test
    void rejectsWrongParentAndUnavailableOrIncompleteInputs() throws Exception {
        long site = site("Site01");
        UUID run = run(site, "DATE_OPPORTUNITIES", "SUCCEEDED", true);
        String id = candidate(run, TOUCHDOWN, FINISH);
        assertThat(data("/api/v1/analyses/{runId}/date-opportunities", run).path("candidates").get(0)
                .path("candidateId").asString()).isEqualTo(id);
        mvc.perform(get(PATH, UUID.randomUUID(), id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
        mvc.perform(get(PATH, run(site, "DATE_OPPORTUNITIES", "SUCCEEDED", true), id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURFACE_CANDIDATE_NOT_FOUND"));
        mvc.perform(get(PATH, run(site, "VISIBILITY", "SUCCEEDED", false), id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_TYPE_MISMATCH"));
        mvc.perform(get(PATH, run(site, "DATE_OPPORTUNITIES", "RUNNING", true), id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_SUCCEEDED"));
        jdbc.update("insert into observer_sample values (?, '2027-01-19T04:20Z', 0, 0, 0, 0)", site);
        mvc.perform(get(PATH, run, id)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OBSERVER_DATA_INCOMPLETE"));
    }

    private void loadOriginalObserverData(long site) {
        POSTGRES.copyFileToContainer(MountableFile.forClasspathResource("analysis/site01-observers-2027-2028.csv.gz"),
                "/tmp/post-landing-site01.csv.gz");
        jdbc.execute("""
                create temporary table post_landing_input (utc timestamptz, sun_az float8, sun_el float8,
                    earth_az float8, earth_el float8) on commit drop
                """);
        jdbc.execute("copy post_landing_input from program 'gzip -dc /tmp/post-landing-site01.csv.gz' with (format csv, header)");
        assertThat(jdbc.update("insert into observer_sample select ?, i.* from post_landing_input i", site)).isEqualTo(105264);
    }

    private long site(String code) {
        return jdbc.queryForObject("select landing_site_id from landing_site where site_code=?", Long.class, code);
    }

    private UUID run(long site, String type, String status, boolean persisted) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into analysis_run (analysis_run_id, landing_site_id, analysis_type, execution_status, analysis_start_at,
                    analysis_end_at, mission_due_date, mission_deadline_at, sample_interval_minutes, analysis_parameters,
                    calculation_parameters_sha256, engine_version, engine_sha256)
                values (?, ?, ?::analysis_type, ?::execution_status, '2027-01-01T00:00Z','2030-01-01T00:00Z',
                    '2029-12-31','2030-01-01T00:00Z',10,?::jsonb,repeat('a',64),'fixture',repeat('b',64))
                """, id, site, type, status, persisted ? "{\"request\":{}}" : "{}");
        return id;
    }

    private String candidate(UUID run, String start, String end) {
        String id = run + "-00001";
        jdbc.update("""
                insert into surface_candidate_result (analysis_run_id, candidate_index, candidate_code, proposed_landing_at,
                    surface_finished_at, window_start_at, window_end_at, status, surface_status, solver_status, model, reasons)
                values (?,1,?,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,
                    'ENVIRONMENT_ONLY','SATISFIED','NOT_EVALUATED','ENVIRONMENT_ONLY','[]')
                """, run, id, start, end, start, start);
        return id;
    }

    private JsonNode data(String path, Object... ids) throws Exception {
        var response = mvc.perform(get(path, ids)).andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("data");
    }
}
