package azas.domain.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in; normal check/CI never depends on JPL availability. */
@EnabledIfEnvironmentVariable(named = "AZAS_LIVE_HORIZONS_TEST", matches = "true")
class HorizonsLiveTests {
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
