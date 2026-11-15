package azas.domain.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import azas.domain.analysis.dto.LaunchOptionsResponse;
import azas.domain.analysis.model.ExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LunarTrajectoryCalculatorTests {
    @Test void unsupportedLambertGeometryReturnsTheReasonWithoutSamples() {
        var touchdown = Instant.parse("2027-01-19T04:10:00Z");
        var candidates = LaunchOptionCalculator.candidates("Kennedy", 28.61, -80.60,
                Instant.parse("2027-01-01T00:00:00Z"), touchdown);
        var site = new LaunchOptionsResponse.Site(1, "Kennedy", 28.61, -80.60, candidates);
        var options = new LaunchOptionsResponse(UUID.randomUUID(), 1, ExecutionStatus.SUCCEEDED, touchdown,
                LaunchOptionCalculator.MODEL, false, LaunchOptionCalculator.ASSUMPTIONS, List.of(site));
        var arrival = candidates.getFirst().lunarArrivalUtc();
        var horizons = mock(HorizonsClient.class);
        // Place the test Moon on the same ray as the reference Kennedy departure position.
        double[] moon = LunarTransferOrbit.scale(new double[]{-6047.92776330016, 1759.0751122892318, 2219.4004393416776}, 60);
        when(horizons.position(399, arrival)).thenReturn(new HorizonsClient.Position(new double[3],
                new AnalysisEngine.Source(399, "earth-fixture", "a".repeat(64), arrival)));
        when(horizons.position(301, arrival)).thenReturn(new HorizonsClient.Position(moon,
                new AnalysisEngine.Source(301, "moon-fixture", "b".repeat(64), arrival)));
        var response = new LunarTrajectoryCalculator(horizons).calculate(options, site, candidates.getFirst());
        assertThat(response.status()).isEqualTo("NO_PRELIMINARY_SOLUTION");
        assertThat(response.reason()).isEqualTo("UNSUPPORTED_GEOMETRY");
        assertThat(response.samples()).isEmpty();
        assertThat(response.tliDeltaVKmS()).isNull();
        assertThat(response.trajectoryVerified()).isFalse();
    }
}
