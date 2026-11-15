package azas.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import azas.domain.analysis.engine.AnalysisEngine;
import azas.domain.analysis.engine.HorizonsClient;
import azas.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class TouchdownSelectionApiTests extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean HorizonsClient horizons;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void twoDatesOnOneSiteKeepTheirOwnLaunchTrajectoryAndSurfaceObservation() throws Exception {
        long site = jdbc.queryForObject("select landing_site_id from landing_site where site_code='Site01'", Long.class);
        jdbc.update("insert into launch_site values (42, 'SelectionTest', 28.61, -80.60)");
        try {
            // Two separate visibility windows. The first alone exceeds the default 50 stored candidates.
            jdbc.update("""
                    insert into observer_sample
                    select ?, t, 0, elevation, 0, elevation
                    from generate_series('2027-01-01 00:00Z'::timestamptz, '2029-12-31 23:50Z', interval '10 minutes') t
                    cross join lateral (select case when t between '2027-01-10 00:00Z' and '2027-01-15 00:00Z'
                        or t between '2027-01-19 00:00Z' and '2027-01-25 00:00Z' then 80 else -80 end as elevation) angles
                    """, site);
            when(horizons.position(anyInt(), any())).thenAnswer(call -> {
                int target = call.getArgument(0);
                Instant utc = call.getArgument(1);
                return new HorizonsClient.Position(target == 399 ? new double[3] : new double[]{310000, 180000, -45000},
                        new AnalysisEngine.Source(target, "fixture/" + target + "/" + utc, "a".repeat(64), utc));
            });
            var accepted = data(post("/api/v1/landing-sites/{id}/date-opportunities", site)
                    .contentType("application/json").content("{\"missionDeadlineUtc\":\"2029-12-31\"}"), 202);
            String base = "/api/v1/analyses/" + UUID.fromString(accepted.path("analysisRunId").asString());
            Instant limit = Instant.now().plusSeconds(125);
            JsonNode state;
            do {
                state = data(get(base), 200);
                if (!java.util.Set.of("PENDING", "RUNNING").contains(state.path("executionStatus").asString())) break;
                Thread.sleep(100);
            } while (Instant.now().isBefore(limit));
            assertThat(state.path("executionStatus").asString()).as(state.toString()).isEqualTo("SUCCEEDED");
            var opportunities = data(get(base + "/date-opportunities"), 200);
            assertThat(opportunities.path("windows").size()).isEqualTo(2);
            assertThat(opportunities.path("arrivalWindows")).isEqualTo(opportunities.path("windows"));
            assertThat(opportunities.path("candidates").size()).isEqualTo(50);
            var options = base + "/launch-options";
            var original = data(get(options), 200);
            var a = data(get(options).param("touchdownDateUtc", "2027-01-10"), 200);
            assertThat(a).isEqualTo(original);
            assertThat(a.path("surfaceCandidateId")).isEqualTo(opportunities.path("candidates").get(0).path("candidateId"));
            var flightA = flight(base, a);
            // Later dates must use the catalog frozen with the analysis.
            jdbc.update("delete from launch_site where launch_site_id = 42");
            var b = data(get(options).param("touchdownDateUtc", "2027-01-20"), 200);
            assertThat(b.path("touchdownUtc").asString()).isEqualTo("2027-01-20T00:00:00Z");
            assertThat(b.path("surfaceCandidateId")).isNotEqualTo(a.path("surfaceCandidateId"));
            assertThat(opportunities.path("candidates")).noneMatch(c -> c.path("candidateId").equals(b.path("surfaceCandidateId")));
            var flightB = flight(base, b);
            var secondWindow = opportunities.path("arrivalWindows").get(1).path("startUtc").asString();
            var screenChoice = data(get(options).param("touchdownUtc", secondWindow), 200);
            assertThat(screenChoice.path("touchdownUtc").asString()).isEqualTo(secondWindow);
            assertThat(flight(base, screenChoice).path("surfaceCandidateId")).isEqualTo(screenChoice.path("surfaceCandidateId"));
            var screenSky = data(get(base + "/surface-candidates/{id}/post-landing", screenChoice.path("surfaceCandidateId").asString()), 200);
            assertThat(screenSky.path("touchdownUtc")).isEqualTo(screenChoice.path("touchdownUtc"));
            assertThat(data(get(options).param("touchdownUtc", b.path("touchdownUtc").asString()), 200)).isEqualTo(b);
            assertThat(flightB.path("trajectoryRunId")).isNotEqualTo(flightA.path("trajectoryRunId"));
            assertThat(data(get(options).param("surfaceCandidateId", b.path("surfaceCandidateId").asString()), 200)).isEqualTo(b);
            assertThat(data(get(options).param("touchdownDateUtc", "2027-01-10"), 200)).isEqualTo(a);
            assertThat(data(get(options), 200)).isEqualTo(original);

            for (var selection : java.util.List.of(a, b)) {
                var trajectory = selection == a ? flightA : flightB;
                assertThat(data(get(base + "/trajectories/{id}", trajectory.path("trajectoryRunId").asString()), 200))
                        .isEqualTo(trajectory);
                var sky = data(get(base + "/surface-candidates/{id}/post-landing", selection.path("surfaceCandidateId").asString()), 200);
                assertThat(sky.path("surfaceCandidateId")).isEqualTo(selection.path("surfaceCandidateId"));
                assertThat(sky.path("touchdownUtc")).isEqualTo(selection.path("touchdownUtc"));
                assertThat(sky.path("samples").get(0).path("utc")).isEqualTo(selection.path("touchdownUtc"));
                assertThat(sky.path("surfaceEndUtc").asString()).isEqualTo(
                        Instant.parse(selection.path("touchdownUtc").asString()).plusSeconds(48 * 3600).toString());
            }
            var neighbor = opportunities.path("candidates").get(1);
            var exact = data(get(options).param("surfaceCandidateId", neighbor.path("candidateId").asString()), 200);
            assertThat(exact.path("touchdownUtc")).isEqualTo(neighbor.path("proposedLandingUtc"));
            assertThat(launch(exact).path("launchUtc")).isEqualTo(launch(a).path("launchUtc"));
            assertThat(launch(exact).path("candidateId")).isNotEqualTo(launch(a).path("candidateId"));
            assertThat(flight(base, exact).path("proposedLandingUtc")).isEqualTo(neighbor.path("proposedLandingUtc"));
        } finally {
            jdbc.update("delete from observer_sample where landing_site_id = ?", site);
            jdbc.update("delete from launch_site where launch_site_id = 42");
        }
    }

    private JsonNode launch(JsonNode selection) {
        return selection.path("launchSites").get(0).path("candidates").get(0);
    }

    private JsonNode flight(String base, JsonNode selection) throws Exception {
        var candidate = launch(selection);
        var flight = data(post(base + "/launch-options/{id}/trajectory", candidate.path("candidateId").asString()), 200);
        assertThat(flight.path("status").asString()).isEqualTo("PRELIMINARY");
        assertThat(flight.path("candidateId")).isEqualTo(candidate.path("candidateId"));
        assertThat(flight.path("surfaceCandidateId")).isEqualTo(selection.path("surfaceCandidateId"));
        assertThat(flight.path("proposedLandingUtc")).isEqualTo(selection.path("touchdownUtc"));
        assertThat(flight.path("samples").get(0).path("utc")).isEqualTo(candidate.path("tliUtc"));
        return flight;
    }

    private JsonNode data(RequestBuilder request, int expectedStatus) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString()).path("data");
    }
}
