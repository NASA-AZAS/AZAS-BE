package azas.domain.analysis.engine;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import azas.domain.analysis.dto.DateOpportunityRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TransferSearchTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private double[] vector(JsonNode node) { return json.treeToValue(node, double[].class); }

    @Test void bothLambertBranchesAndCustomMuMatchTheOriginalJar() throws Exception {
        var golden = json.readTree(getClass().getResourceAsStream("/analysis/transfer-options-golden.json"));
        for (var reference : golden.path("cases")) {
            var result = LunarTransferOrbit.solve(vector(reference.path("position")), vector(reference.path("target")),
                    reference.path("seconds").asLong(), reference.path("mu").asDouble(),
                    reference.path("branch").asString().equals("LONG_WAY"), 0.01);
            var expected = reference.path("solution");
            assertThat(result.status()).isEqualTo(expected.path("status").asString());
            assertThat(result.residualKm()).isCloseTo(expected.path("positionResidualKm").asDouble(), within(1e-6));
            assertThat(result.departureVelocity()).containsExactly(vector(expected.path("departureVelocityKmS")), within(1e-8));
            assertThat(result.arrivalVelocity()).containsExactly(vector(expected.path("arrivalVelocityKmS")), within(1e-8));
        }
        var back = LunarTransferOrbit.propagate(new LunarTransferOrbit.State(new double[]{7000,0,0}, new double[]{0,7.54605329,0}), -3600, 398600.4418);
        assertThat(back.position()).containsExactly(vector(golden.path("backwardPositionKm")), within(1e-6));
        assertThat(back.velocity()).containsExactly(vector(golden.path("backwardVelocityKmS")), within(1e-9));
    }

    @Test void transferInputsReachTheSolverAndCacheOnlyNeverMakesNetworkCalls() {
        var client = mock(HorizonsClient.class);
        when(client.position(anyInt(), any())).thenAnswer(call -> new HorizonsClient.Position(
                (int) call.getArgument(0) == 399 ? new double[]{0,0,0} : new double[]{308000,180000,-45000},
                new double[]{0,0,0}, new AnalysisEngine.Source(call.getArgument(0), "https://fixture/" + call.getArgument(0), "a".repeat(64), Instant.EPOCH)));
        var request = json.readValue("""
                {"missionDeadlineUtc":"2027-01-05", "surfaceDurationHours":1,"maxCandidates":1,
                 "departureState":{"epochUtc":"2027-01-01T00:00:00Z","frame":"ICRF","center":"EARTH",
                    "positionKm":[7000,500,-100],"velocityKmS":[0,7.5,0],"earthMuKm3S2":398601},
                 "arrivalTargetRule":{"type":"MOON_RELATIVE_ICRF_OFFSET","offsetKm":[2000,0,0],"minimumAltitudeKm":100},
                 "transferDurationHours":{"min":72,"max":72,"step":1},
                 "preLandingDelayHours":{"min":0,"max":0,"step":1},
                 "lambert":{"branch":"LONG_WAY","positionToleranceKm":0.01}}
                """, DateOpportunityRequest.class);
        assertThat(request.isValidOptions()).isTrue();
        var landing = Instant.parse("2027-01-04T00:00:00Z");
        var windows = List.of(new DateOpportunityEngine.Window(landing, landing, landing.plusSeconds(3600)));
        var result = new TransferSearch(client).search(windows, request).candidates().getFirst();
        assertThat(result.status()).isEqualTo("PRELIMINARY");
        assertThat(result.tliUtc()).isEqualTo(DateOpportunityRequest.SEARCH_START);
        assertThat(result.launchUtc()).isNull();
        assertThat(result.lambert().get("earthMuKm3S2")).isEqualTo(398601.0);
        verify(client, times(4)).position(anyInt(), any());
        reset(client);
        var tree = (tools.jackson.databind.node.ObjectNode) json.valueToTree(request);
        tree.put("networkMode", "CACHE_ONLY");
        var offline = new TransferSearch(client).search(windows, json.treeToValue(tree, DateOpportunityRequest.class));
        assertThat(offline.candidates().getFirst().status()).isEqualTo("UNKNOWN");
        assertThat(offline.candidates().getFirst().reasons()).contains("VECTOR_ARCHIVE_UNAVAILABLE");
        verifyNoInteractions(client);
    }
}
