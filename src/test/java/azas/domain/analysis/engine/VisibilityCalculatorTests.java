package azas.domain.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import azas.domain.landingsite.importer.LandingScreenDatasetV1;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class VisibilityCalculatorTests {
    private final VisibilityCalculator calculator = new VisibilityCalculator();

    @Test
    void matchesEveryObservationInBothUpstreamScenariosAndReaggregatesEachDay() throws Exception {
        var original = LandingScreenDatasetV1.load().sites().stream().filter(site -> site.code().equals("Site01"))
                .findFirst().orElseThrow();
        var profile = new VisibilityCalculator.Horizon(java.util.Arrays.stream(original.samples()).map(row -> row[1]).toList());
        var fixture = JsonMapper.builder().build().readTree(getClass().getResourceAsStream("/analysis/llsn-golden.json"));

        for (JsonNode scenario : fixture.path("scenarios")) {
            List<VisibilityCalculator.Epoch> epochs = new ArrayList<>();

            for (JsonNode row : scenario.path("rows")) {
                var sun = angle(row.path("sun"));
                var earth = angle(row.path("earth"));
                epochs.add(new VisibilityCalculator.Epoch(Instant.parse(row.path("utc").asString()), sun, earth));

                for (String body : List.of("sun", "earth")) {
                    JsonNode expected = row.path(body);
                    var actual = calculator.evaluate(profile, angle(expected));
                    assertThat(actual.horizon()).isCloseTo(expected.get(2).asDouble(), org.assertj.core.data.Offset.offset(1e-9));
                    assertThat(actual.clearance()).isCloseTo(expected.get(3).asDouble(), org.assertj.core.data.Offset.offset(1e-9));
                    assertThat(actual.state().name()).isEqualTo(expected.get(4).asString());
                }
            }

            var days = calculator.analyze(profile, epochs, 600);
            assertThat(days).hasSize(2);

            for (var day : days) {
                List<JsonNode> rows = new ArrayList<>();
                var all = scenario.path("rows");

                for (int i = 0; i < all.size() - 1; i++) {
                    if (all.get(i).path("utc").asString().startsWith(day.date().toString())) rows.add(all.get(i));
                }

                assertThat(rows).hasSize(144);
                assertMetrics(day.sun(), rows.stream().map(row -> row.path("sun").get(4).asString()).toList());
                assertMetrics(day.earth(), rows.stream().map(row -> row.path("earth").get(4).asString()).toList());
                assertMetrics(day.joint(), rows.stream().map(row ->
                        row.path("sun").get(4).asString().equals("OCCULTED") || row.path("earth").get(4).asString().equals("OCCULTED")
                                ? "OCCULTED" : "UNKNOWN").toList());
                assertThat(day.windows()).isEmpty();
            }
        }
    }

    @Test
    void interpolatesAcrossNorthAndPreservesNegativeHorizonsAndConservativeUnknown() {
        var elevations = new ArrayList<>(Collections.nCopies(3600, -5.0));
        elevations.set(3599, -10.0);
        elevations.set(0, 10.0);
        var horizon = new VisibilityCalculator.Horizon(elevations);
        assertThat(calculator.evaluate(horizon, new VisibilityCalculator.Angle(359.95, -1)).horizon())
                .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(calculator.evaluate(horizon, new VisibilityCalculator.Angle(360, 9)).state())
                .isEqualTo(VisibilityCalculator.State.OCCULTED);
        assertThat(calculator.evaluate(horizon, new VisibilityCalculator.Angle(90, -4)).state())
                .isEqualTo(VisibilityCalculator.State.UNKNOWN);
    }

    @Test
    void splitsIntervalsAtUtcMidnightAndGivesFinalEndpointZeroWeight() {
        var horizon = new VisibilityCalculator.Horizon(Collections.nCopies(3600, 0.0));
        Instant start = Instant.parse("2026-12-01T23:55:00Z");
        var occulted = new VisibilityCalculator.Angle(0, -1);
        var unknown = new VisibilityCalculator.Angle(0, 1);
        var days = calculator.analyze(horizon, List.of(new VisibilityCalculator.Epoch(start, occulted, unknown),
                new VisibilityCalculator.Epoch(start.plusSeconds(600), unknown, occulted)), 600);
        assertThat(days).hasSize(2);
        assertThat(days.getFirst().date()).isEqualTo(LocalDate.parse("2026-12-01"));

        for (var day : days) {
            assertThat(day.sun().knownRatio()).isEqualTo(1);
            assertThat(day.sun().longestOccultationHours()).isEqualTo(5.0 / 60);
            assertThat(day.earth().visibleRatio()).isNull();
            assertThat(day.earth().knownRatio()).isZero();
            assertThat(day.joint().knownRatio()).isEqualTo(1);
        }
    }

    @Test
    void rejectsIncompleteProfilesNonFiniteAnglesAndMissingEpochs() {
        assertThatThrownBy(() -> new VisibilityCalculator.Horizon(List.of(0.0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisibilityCalculator.Angle(Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
        var angle = new VisibilityCalculator.Angle(0, 0);
        assertThatThrownBy(() -> calculator.analyze(new VisibilityCalculator.Horizon(Collections.nCopies(3600, 0.0)),
                List.of(new VisibilityCalculator.Epoch(Instant.EPOCH, angle, angle),
                        new VisibilityCalculator.Epoch(Instant.EPOCH.plusSeconds(120), angle, angle)), 60))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private VisibilityCalculator.Angle angle(JsonNode values) {
        return new VisibilityCalculator.Angle(values.get(0).asDouble(), values.get(1).asDouble());
    }

    private void assertMetrics(VisibilityCalculator.Metrics actual, List<String> states) {
        long known = states.stream().filter(state -> !state.equals("UNKNOWN")).count();
        assertThat(actual.knownRatio()).isEqualTo(known / (double) states.size());

        if (known == 0) {
            assertThat(actual.visibleRatio()).isNull();
            assertThat(actual.longestOccultationHours()).isNull();
        } else {
            assertThat(actual.visibleRatio()).isEqualTo(0);
            int longest = 0, consecutive = 0;

            for (String state : states) {
                consecutive = state.equals("OCCULTED") ? consecutive + 1 : 0;
                longest = Math.max(longest, consecutive);
            }

            assertThat(actual.longestOccultationHours()).isEqualTo(longest / 6.0);
        }
    }
}
