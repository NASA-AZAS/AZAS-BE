package azas.domain.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;

import azas.domain.analysis.dto.LaunchOptionsResponse;
import azas.domain.analysis.model.ExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in; normal check/CI never depends on JPL availability. */
@EnabledIfEnvironmentVariable(named = "AZAS_LIVE_HORIZONS_TEST", matches = "true")
class HorizonsLiveTests {
    @Test void computesTheKennedyExampleWithFreshEarthAndMoonVectors() {
        var touchdown = Instant.parse("2027-01-19T04:10:00Z");
        var candidates = LaunchOptionCalculator.candidates("Kennedy", 28.61, -80.6, Instant.parse("2027-01-01T00:00:00Z"), touchdown);
        var site = new LaunchOptionsResponse.Site(1, "Kennedy", 28.61, -80.6, candidates);
        var options = new LaunchOptionsResponse(UUID.randomUUID(), 1, ExecutionStatus.SUCCEEDED, touchdown,
                LaunchOptionCalculator.MODEL, false, LaunchOptionCalculator.ASSUMPTIONS, List.of(site));
        var trajectory = new LunarTrajectoryCalculator(new HorizonsClient()).calculate(options, site, candidates.getFirst());
        assertThat(trajectory.status()).as("reason: %s", trajectory.reason()).isEqualTo("PRELIMINARY");
        assertThat(trajectory.samples()).hasSize(701);
        assertThat(trajectory.sourceRecords()).extracting(AnalysisEngine.Source::target).containsExactly(399, 301);
    }

    @Test void fetchesAndValidatesFreshSunAndEarthResponsesForSite01() throws Exception {
        var input = new AnalysisEngine.Input("Site01", -89.6307206, -129.9644390, 863.783, 1,
                "MOON_ME_DE421_PLANETOCENTRIC_EAST", 1737400, false, "a".repeat(64),
                HorizonFixture.site01(),
                Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-12-01T01:00:00Z"), 3600);
        var client = new HorizonsClient();

        for (int target : new int[]{10, 399}) {
            var response = client.angles(target, input);
            assertThat(response.angles()).hasSize(2);
            assertThat(response.source().responseSha256()).matches("[0-9a-f]{64}");
        }
    }
}
