package azas.domain.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;

import azas.domain.landingsite.importer.LandingScreenDatasetV1;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in; normal check/CI never depends on JPL availability. */
@EnabledIfEnvironmentVariable(named = "AZAS_LIVE_HORIZONS_TEST", matches = "true")
class HorizonsLiveTests {
    @Test void fetchesAndValidatesFreshSunAndEarthResponsesForSite01() throws Exception {
        var site = LandingScreenDatasetV1.load().sites().stream().filter(value -> value.code().equals("Site01"))
                .findFirst().orElseThrow();
        var input = new AnalysisEngine.Input(site.code(), site.latitude(), site.longitude(), site.elevation(), site.observerHeight(),
                "MOON_ME_DE421_PLANETOCENTRIC_EAST", 1737400, false, "a".repeat(64),
                java.util.Arrays.stream(site.samples()).map(sample -> sample[1]).toList(),
                Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-12-01T01:00:00Z"), 3600);
        var client = new HorizonsClient();

        for (int target : new int[]{10, 399}) {
            var response = client.angles(target, input);
            assertThat(response.angles()).hasSize(2);
            assertThat(response.source().responseSha256()).matches("[0-9a-f]{64}");
        }
    }
}
