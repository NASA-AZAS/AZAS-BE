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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
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
        mvc.perform(get("/api/v1/analyses/" + id + "/daily-results").param("date", "2026-12-03"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
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

    @Test void surfaceSearchUsesTheArchiveAndKeepsWindowsBeyondTheCandidateLimit() throws Exception {
        long site = siteId();
        // Two visible runs, with the second beginning after candidate 50 of the first.
        jdbc.update("""
                insert into observer_sample
                select ?, t, 0, case when t = timestamptz '2027-01-01 12:00Z' then -90 else 90 end, 0, 90
                from generate_series(timestamptz '2027-01-01 00:00Z', timestamptz '2027-01-02 00:30Z', interval '10 minutes') t
                """, site);
        try {
            UUID id = submitSurface(site, "2027-01-02T00:33:00Z", "0.5");
            var run = await(id);
            assertThat(run.path("overallResultStatus").asString()).isEqualTo("TRUNCATED");
            assertThat(run.path("analysisType").asString()).isEqualTo("DATE_OPPORTUNITIES");
            var report = surfaceReport(id);
            assertThat(report.path("earliestTouchdownUtc").asString()).isEqualTo("2027-01-01T00:00:00Z");
            assertThat(report.path("windows").size()).isEqualTo(2);
            assertThat(report.path("windows").get(1).path("endUtc").asString()).isEqualTo("2027-01-02T00:00:00Z");
            assertThat(report.path("candidates").size()).isEqualTo(50);
            var days = dailyResults(id);
            assertThat(days.size()).isEqualTo(2);
            assertThat(days.get(0).path("sunVisibleTimeRatio").asDouble()).isEqualTo(143.0 / 144);
            assertThat(days.get(1).path("evaluatedPeriodEndAt").asString()).isEqualTo("2027-01-02T00:33:00Z");
            mvc.perform(get("/api/v1/analyses/" + id + "/daily-results").param("date", "2027-01-02"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].analysisDate").value("2027-01-02"))
                    .andExpect(jsonPath("$.data[0].visibilityWindows.length()").value(3))
                    .andExpect(jsonPath("$.data[0].visibilityWindows[*].windowStartAt")
                            .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("2027-01-02T00:00:00Z"))));

            id = submitSurface(site, "2027-01-01T00:30:00Z", "0.5");
            assertThat(await(id).path("overallResultStatus").asString()).isEqualTo("PARTIAL");
            assertThat(surfaceReport(id).path("windows").get(0).path("endUtc").asString()).isEqualTo("2027-01-01T00:00:00Z");

            id = submitSurface(site, "2027-01-01T00:30:00Z", "0.25");
            await(id);
            var fractional = surfaceReport(id).path("candidates");
            assertThat(fractional.size()).isEqualTo(3);
            assertThat(fractional.get(2).path("proposedLandingUtc").asString()).isEqualTo("2027-01-01T00:15:00Z");

            id = submitSurface(site, "2027-01-01T08:40:00Z", "0.5");
            assertThat(await(id).path("overallResultStatus").asString()).isEqualTo("PARTIAL");
            assertThat(surfaceReport(id).path("candidates").size()).isEqualTo(50);

            id = submitSurface(site, "2027-01-01", "48");
            assertThat(await(id).path("executionStatus").asString()).isEqualTo("SUCCEEDED");
            assertThat(surfaceReport(id).path("earliestTouchdownUtc").isNull()).isTrue();

            jdbc.update("delete from observer_sample where landing_site_id = ? and observed_at = '2027-01-01T00:10Z'", site);
            id = submitSurface(site, "2027-01-01", "1");
            assertThat(await(id).path("failureCode").asString()).isEqualTo("OBSERVER_DATA_INCOMPLETE");
            assertThat(dailyResults(id).size()).isZero();
            verifyNoInteractions(horizons);
        } finally {
            jdbc.update("delete from observer_sample where landing_site_id = ?", site);
        }
    }

    @Test void rejectsInvalidSurfaceRequestsBeforeStartingAJob() throws Exception {
        for (String body : java.util.List.of(
                "{\"missionDeadlineUtc\":\"2030-01-01\"}",
                "{\"missionDeadlineUtc\":\"2027-01-01T00:00:00Z\"}",
                "{\"missionDeadlineUtc\":\"2027-01-01\",\"surfaceDurationHours\":0.001}")) {
            mvc.perform(post("/api/v1/landing-sites/" + siteId() + "/date-opportunities")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"VISIBLE", "ANY", "OCCULTED"})
    @EnabledIfEnvironmentVariable(named = "LLSN_VER2_HOME", matches = ".+")
    void surfaceSearchMatchesTheOriginalCliForSite01(String criterion, @TempDir Path scratch) throws Exception {
        Path module = Path.of(System.getenv("LLSN_VER2_HOME")).toAbsolutePath();
        var apiRequest = new java.util.LinkedHashMap<String, Object>();
        apiRequest.put("missionDeadlineUtc", "2027-01-31");
        apiRequest.put("surfaceDurationHours", 48);
        apiRequest.put("surfaceCriteria", java.util.Map.of("sun", criterion, "earth", criterion, "unknownPolicy", "RECORD_UNKNOWN"));
        apiRequest.put("timeStepMinutes", criterion.equals("VISIBLE") ? 10 : 60);
        apiRequest.put("maxCandidates", criterion.equals("VISIBLE") ? 50 : 7);
        apiRequest.put("refinementPolicy", java.util.Map.of("minStepMinutes", 10, "maxDepth", 2));
        var cliRequest = new java.util.LinkedHashMap<>(apiRequest);
        cliRequest.put("schemaVersion", "1.0");
        cliRequest.put("requestId", "parity");
        cliRequest.put("candidateSiteIds", java.util.List.of("Site01"));
        cliRequest.put("networkMode", "CACHE_ONLY");
        Path inputFile = scratch.resolve("request.json");
        Files.writeString(inputFile, json.writeValueAsString(cliRequest));
        var process = new ProcessBuilder("java", "-jar", module.resolve("llsn.jar").toString(), "date-opportunities",
                inputFile.toString(), scratch.toString(), "--offline", "--deadline", "2027-01-31")
                .redirectErrorStream(true).redirectOutput(scratch.resolve("cli.log").toFile()).start();
        try {
            assertThat(process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)).as("offline CLI completed").isTrue();
            assertThat(process.exitValue()).as("CLI exit code (log: %s)", scratch.resolve("cli.log")).isEqualTo(2);
        } finally {
            process.destroyForcibly();
        }
        var original = json.readTree(Files.readString(scratch.resolve("date_opportunities.json")));
        var expected = original.path("siteReports").get(0);
        long site = siteId();
        // Load only January's 4,464 source rows into this suite's disposable PostgreSQL.
        try (var lines = Files.lines(module.resolve("data/observer-2027-2029/Site01.csv"))) {
            var january = lines.skip(1).takeWhile(line -> line.startsWith("2027-01-")).toList();
            assertThat(january).hasSize(4464);
            jdbc.batchUpdate("insert into observer_sample values (?, ?::timestamptz, ?, ?, ?, ?)", january, 512,
                    (statement, line) -> {
                        String[] fields = line.split(",");
                        statement.setLong(1, site);
                        statement.setString(2, fields[0]);
                        for (int i = 1; i <= 4; i++) statement.setDouble(i + 2, Double.parseDouble(fields[i]));
                    });
            UUID id = submitSurfaceBody(site, json.writeValueAsString(apiRequest));
            assertThat(await(id).path("overallResultStatus")).isEqualTo(original.path("status"));
            var actual = surfaceReport(id);
            assertThat(actual.path("earliestTouchdownUtc")).isEqualTo(expected.path("earliestTouchdownUtc"));
            assertThat(actual.path("windows").size()).isEqualTo(expected.path("windows").size());
            for (int i = 0; i < actual.path("windows").size(); i++) {
                for (String field : java.util.List.of("startUtc", "endUtc", "supportEndUtc", "surfaceStatus")) {
                    assertThat(actual.path("windows").get(i).path(field)).isEqualTo(expected.path("windows").get(i).path(field));
                }
            }
            assertThat(actual.path("candidates").size()).isEqualTo(original.path("candidates").size());
            for (int i = 0; i < actual.path("candidates").size(); i++) {
                assertThat(actual.path("candidates").get(i).path("proposedLandingUtc"))
                        .isEqualTo(original.path("candidates").get(i).path("proposedLandingUtc"));
            }
            var days = dailyResults(id);
            assertThat(days.size()).isEqualTo(31);
            for (int i = 0; i < days.size(); i++) {
                var reference = expected.path("dailyVisibility").get(i);
                assertThat(days.get(i).path("analysisDate")).isEqualTo(reference.path("dateUtc"));
                for (String body : java.util.List.of("sun", "earth")) {
                    assertThat(days.get(i).path(body + "VisibleTimeRatio").asDouble())
                            .isEqualTo(reference.path(body).path("visibleFraction").asDouble());
                }
            }
            assertThat(jdbc.queryForObject("select count(*) from surface_candidate_result where analysis_run_id = ?", Integer.class, id))
                    .isEqualTo(original.path("candidates").size());
            verifyNoInteractions(horizons);
        } finally {
            jdbc.update("delete from observer_sample where landing_site_id = ?", site);
        }
    }

    @Test void rejectsUnknownAndInvalidOriginalOptionsInsteadOfSilentlyIgnoringThem() throws Exception {
        long count = jdbc.queryForObject("select count(*) from analysis_run", Long.class);
        for (String options : java.util.List.of(
                "\"typoOption\":1", "\"timeStepMinutes\":0", "\"maxCandidates\":10001",
                "\"surfaceCriteria\":{\"sun\":\"ANY\",\"earth\":\"VISIBLE\",\"unknownPolicy\":\"RECORD_UNKNOWN\",\"typo\":1}",
                "\"departureState\":{\"frame\":\"ICRF\"}", "\"networkMode\":\"INVALID\"",
                "\"refinementPolicy\":{\"minStepMinutes\":11,\"maxDepth\":2}",
                "\"launchToTliDelayHours\":{\"min\":2,\"max\":2,\"step\":1}")) {
            mvc.perform(post("/api/v1/landing-sites/" + siteId() + "/date-opportunities").contentType("application/json")
                    .content("{\"missionDeadlineUtc\":\"2027-01-31\"," + options + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
        }
        assertThat(jdbc.queryForObject("select count(*) from analysis_run", Long.class)).isEqualTo(count);
        verifyNoInteractions(horizons);
    }

    @Test void persistsOriginalTransferOptionsAndFetchesFreshVectorsForEachNewRun() throws Exception {
        long site = siteId();
        jdbc.update("""
                insert into observer_sample select ?, t, 0,
                case when t < timestamptz '2027-01-04 00:00Z' then -90 else 90 end, 0, 90
                from generate_series(timestamptz '2027-01-01 00:00Z', timestamptz '2027-01-04 02:00Z', interval '10 minutes') t
                """, site);
        when(horizons.position(anyInt(), any())).thenAnswer(call -> new HorizonsClient.Position(
                (int) call.getArgument(0) == 399 ? new double[]{0,0,0} : new double[]{308000,180000,-45000},
                new double[]{0,0,0}, new AnalysisEngine.Source(call.getArgument(0), "https://fixture/" + call.getArgument(0), "a".repeat(64), Instant.EPOCH)));
        String body = """
                {"missionDeadlineUtc":"2027-01-04T02:00:00Z", "surfaceDurationHours":1,"maxCandidates":1,
                 "departureState":{"epochUtc":"2027-01-01T00:00:00Z","frame":"ICRF","center":"EARTH",
                    "positionKm":[7000,500,-100],"velocityKmS":[0,7.5,0],"earthMuKm3S2":398601},
                 "arrivalTargetRule":{"type":"MOON_RELATIVE_ICRF_OFFSET","offsetKm":[2000,0,0],"minimumAltitudeKm":100},
                 "transferDurationHours":{"min":72,"max":72,"step":1},
                 "preLandingDelayHours":{"min":0,"max":0,"step":1},
                 "lambert":{"branch":"LONG_WAY","positionToleranceKm":0.01}}
                """;
        try {
            UUID previous = null;
            for (int n = 0; n < 2; n++) {
                UUID id = submitSurfaceBody(site, body);
                assertThat(id).isNotEqualTo(previous); previous = id;
                assertThat(await(id).path("executionStatus").asString()).isEqualTo("SUCCEEDED");
                var candidate = surfaceReport(id).path("candidates").get(0);
                assertThat(candidate.path("status").asString()).isEqualTo("PRELIMINARY");
                assertThat(candidate.path("lambertStatus").asString()).isEqualTo("CONVERGED");
                assertThat(candidate.path("lambert").path("earthMuKm3S2").asDouble()).isEqualTo(398601);
                assertThat(candidate.path("lambert").path("branch").asString()).isEqualTo("LONG_WAY");
                assertThat(jdbc.queryForList("select decision_reasons[1] from landing_opportunity_result where analysis_run_id = ?", String.class, id))
                        .containsExactly("SURFACE_CRITERIA_SATISFIED");
                assertThat(jdbc.queryForObject("select count(*) from surface_candidate_result where analysis_run_id = ? and status = 'PRELIMINARY'", Integer.class, id)).isOne();
                assertThat(jdbc.queryForObject("select count(*) from launch_option_batch where analysis_run_id = ?", Integer.class, id)).isOne();
                assertThat(jdbc.queryForObject("select analysis_parameters::text from analysis_run where analysis_run_id = ?", String.class, id)).contains("LONG_WAY", "departureState", "maxCandidates");
                assertThat(jdbc.queryForObject("select source_provenance::text from analysis_run where analysis_run_id = ?", String.class, id)).contains("https://fixture/399", "https://fixture/301");
            }
            verify(horizons, times(8)).position(anyInt(), any());
        } finally { jdbc.update("delete from observer_sample where landing_site_id = ?", site); }
    }

    private UUID submitSurface(long site, String deadline, String hours) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("missionDeadlineUtc", deadline,
                "surfaceDurationHours", new java.math.BigDecimal(hours)));
        return submitSurfaceBody(site, body);
    }

    private UUID submitSurfaceBody(long site, String body) throws Exception {
        var response = mvc.perform(post("/api/v1/landing-sites/" + site + "/date-opportunities")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        return UUID.fromString(json.readTree(response.getContentAsString()).path("data").path("analysisRunId").asString());
    }

    private JsonNode surfaceReport(UUID id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/analyses/" + id + "/date-opportunities"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private JsonNode dailyResults(UUID id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/analyses/" + id + "/daily-results"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
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
