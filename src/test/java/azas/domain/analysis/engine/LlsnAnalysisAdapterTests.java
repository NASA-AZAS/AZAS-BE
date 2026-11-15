package azas.domain.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class LlsnAnalysisAdapterTests {
    @Test void mapsModuleResultsThroughTheStableContractAndFetchesBothBodiesForEachRun() {
        var client = mock(HorizonsClient.class);
        Instant start = Instant.parse("2026-12-01T00:00:00Z");
        var source = new AnalysisEngine.Source(10, "https://example.test", "a".repeat(64), start);
        when(client.angles(eq(10), any())).thenReturn(new HorizonsClient.Series(
                Collections.nCopies(25, new HorizonsClient.Angle(0, -1)), source));
        when(client.angles(eq(399), any())).thenReturn(new HorizonsClient.Series(
                Collections.nCopies(25, new HorizonsClient.Angle(90, 1)), source));
        AnalysisEngine engine = new LlsnAnalysisAdapter(client);
        var input = new AnalysisEngine.Input("Site01", -89, -129, 863, 1,
                "MOON_ME_DE421_PLANETOCENTRIC_EAST", 1737400, false, "a".repeat(64),
                Collections.nCopies(3600, 0.0), start, start.plusSeconds(86400), 3600);
        var result = engine.analyze(input);
        engine.analyze(input);
        assertThat(result.resultStatus()).isEqualTo("PARTIAL");
        assertThat(result.days()).hasSize(1);
        assertThat(result.days().getFirst().sun().longestOccultationHours()).isEqualTo(24);
        assertThat(result.days().getFirst().earth().visibleRatio()).isNull();
        assertThat(result.days().getFirst().earth().knownRatio()).isZero();
        assertThat(result.days().getFirst().qualityFlags()).contains("DISTANT_TERRAIN_NOT_EVALUATED");
        assertThat(engine.identity().sha256()).matches("[0-9a-f]{64}");
        verify(client, times(2)).angles(10, input);
        verify(client, times(2)).angles(399, input);
    }

    @Test void incompatibleMetadataFailsBeforeAnyExternalCall() {
        var client = mock(HorizonsClient.class);
        var input = new AnalysisEngine.Input("Site01", -89, -129, 863, 1,
                "UNKNOWN", 1737400, false, "a".repeat(64), Collections.nCopies(3600, 0.0),
                Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), 3600);
        assertThatThrownBy(() -> new LlsnAnalysisAdapter(client).analyze(input)).hasMessage("METADATA_INCOMPLETE");
        verifyNoInteractions(client);
    }
}
