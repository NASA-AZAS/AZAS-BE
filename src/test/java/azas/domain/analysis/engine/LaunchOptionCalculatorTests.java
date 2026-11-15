package azas.domain.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LaunchOptionCalculatorTests {
    @Test
    void permitsACrossingExactlyAtBothSearchBounds() {
        var crossing = Instant.parse("2000-01-01T12:00:00Z");
        var touchdown = crossing.plusSeconds((2 + 48 + 6) * 3600);
        double longitudeEast = 360 - 280.46061837;
        assertThat(LaunchOptionCalculator.candidates("Example", 0, longitudeEast, crossing, touchdown))
                .singleElement().satisfies(candidate -> assertThat(candidate.launchUtc()).isEqualTo(crossing));
        assertThat(LaunchOptionCalculator.candidates("Example", 0, longitudeEast, crossing.plusSeconds(1), touchdown))
                .isEmpty();
    }

    @Test
    void clippingToTheRunStartSelectsTheNextCrossing() {
        var start = Instant.parse("2027-01-14T12:00:00Z");
        var touchdown = Instant.parse("2027-01-19T04:10:00Z");
        assertThat(LaunchOptionCalculator.candidates("Kennedy", 28.61, -80.6, start, touchdown)).singleElement()
                .satisfies(candidate -> assertThat(candidate.launchUtc()).isBetween(start, start.plusSeconds(86400)));
    }
}
