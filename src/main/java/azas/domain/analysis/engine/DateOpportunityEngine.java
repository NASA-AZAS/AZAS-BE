package azas.domain.analysis.engine;

import azas.domain.analysis.engine.VisibilityCalculator.State;
import azas.domain.landingsite.entity.ObserverSample;
import azas.domain.landingsite.repository.ObserverSampleRepository;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
public class DateOpportunityEngine {
    public static final int STEP_SECONDS = 600;
    public static final int MAX_CANDIDATES = 50;
    private final ObserverSampleRepository observations;
    private final AnalysisEngine.Identity identity = new AnalysisEngine.Identity("llsn-ver2-date-opportunities",
            EngineFingerprint.of(DateOpportunityEngine.class, VisibilityCalculator.class, AnalysisEngine.class));

    public DateOpportunityEngine(ObserverSampleRepository observations) { this.observations = observations; }
    public AnalysisEngine.Identity identity() { return identity; }

    public record Window(Instant startUtc, Instant endUtc, Instant supportEndUtc) {
        public long candidateCount() {
            return 1 + Math.ceilDiv(Duration.between(startUtc, endUtc).getSeconds(), STEP_SECONDS);
        }
    }

    public record Result(AnalysisEngine.Output visibility, List<Window> windows, String observerSha256, int sampleCount) {}

    public Result analyze(long siteId, AnalysisEngine.Input input, long surfaceSeconds) {
        var horizon = new VisibilityCalculator.Horizon(input.horizon());
        Scan scan = new Scan(horizon, input.end(), surfaceSeconds);
        Instant expected = input.start();
        Instant endExclusive = input.end().plusSeconds(1);
        var pageSize = Pageable.ofSize(2048);
        while (!expected.isAfter(input.end())) {
            if (Thread.currentThread().isInterrupted()) throw new AnalysisEngineException("ANALYSIS_INTERRUPTED", null);
            var rows = observations.findInPeriod(siteId, expected, endExclusive, pageSize).getContent();
            if (rows.isEmpty()) throw new AnalysisEngineException("OBSERVER_DATA_INCOMPLETE", null);
            for (ObserverSample row : rows) {
                if (!expected.equals(row.getId().getObservedAt())) throw new AnalysisEngineException("OBSERVER_DATA_INCOMPLETE", null);
                scan.add(expected, row);
                expected = expected.plusSeconds(STEP_SECONDS);
            }
        }
        return scan.finish();
    }

    private static final class Scan {
        final VisibilityCalculator.Horizon horizon;
        final Instant deadline;
        final long surfaceSeconds;
        final List<AnalysisEngine.Day> days = new ArrayList<>();
        final List<Window> windows = new ArrayList<>();
        final MessageDigest digest;
        final ByteBuffer sampleBytes = ByteBuffer.allocate(5 * Long.BYTES);
        VisibilityCalculator.Accumulator day;
        Instant firstVisible;
        Instant lastVisible;
        int sampleCount;

        Scan(VisibilityCalculator.Horizon horizon, Instant deadline, long surfaceSeconds) {
            this.horizon = horizon;
            this.deadline = deadline;
            this.surfaceSeconds = surfaceSeconds;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        }

        State localState(double azimuth, double elevation) {
            double position = azimuth * 10;
            int lower = (int) position;
            double fraction = position - lower;
            var profile = horizon.elevations();
            double left = profile.get(lower % 3600), right = profile.get((lower + 1) % 3600);
            double cutoff = fraction == 0 ? left : left * (1 - fraction) + right * fraction;
            return elevation > cutoff ? State.VISIBLE : State.OCCULTED;
        }

        void add(Instant utc, ObserverSample row) {
            sampleBytes.clear();
            sampleBytes.putLong(utc.getEpochSecond()).putDouble(row.getSunAzimuthDegrees()).putDouble(row.getSunElevationDegrees())
                    .putDouble(row.getEarthAzimuthDegrees()).putDouble(row.getEarthElevationDegrees());
            digest.update(sampleBytes.array());
            sampleCount++;
            State sun = localState(row.getSunAzimuthDegrees(), row.getSunElevationDegrees());
            State earth = localState(row.getEarthAzimuthDegrees(), row.getEarthElevationDegrees());
            State joint = sun == State.VISIBLE && earth == State.VISIBLE ? State.VISIBLE : State.OCCULTED;
            if (joint == State.VISIBLE) {
                if (firstVisible == null) firstVisible = utc;
                lastVisible = utc;
            } else {
                closeWindow();
            }

            // The final grid point may support a touchdown but carries no weight at an exact deadline.
            if (!utc.isBefore(deadline)) return;
            var date = utc.atOffset(ZoneOffset.UTC).toLocalDate();
            if (day == null || !day.date.equals(date)) {
                if (day != null) days.add(AnalysisEngine.Day.localHorizon(day.finish(STEP_SECONDS)));
                day = new VisibilityCalculator.Accumulator(date, utc);
            }
            Instant next = utc.plusSeconds(STEP_SECONDS);
            day.add(utc, next.isAfter(deadline) ? deadline : next, new State[]{sun, earth, joint});
        }

        void closeWindow() {
            if (firstVisible == null) return;
            if (Duration.between(firstVisible, lastVisible).getSeconds() >= surfaceSeconds) {
                windows.add(new Window(firstVisible, lastVisible.minusSeconds(surfaceSeconds), lastVisible));
            }
            firstVisible = null;
        }

        Result finish() {
            closeWindow();
            days.add(AnalysisEngine.Day.localHorizon(day.finish(STEP_SECONDS)));
            long candidates = windows.stream().mapToLong(Window::candidateCount).sum();
            boolean truncated = candidates > MAX_CANDIDATES;
            List<String> diagnostics = new ArrayList<>(List.of("LOCAL_HORIZON_ONLY", "DISTANT_TERRAIN_NOT_EVALUATED"));
            if (truncated) diagnostics.add("MAX_CANDIDATES_REACHED");
            if (windows.isEmpty()) diagnostics.add("NO_SAMPLE_SUPPORTED_WINDOW");
            var output = new AnalysisEngine.Output(truncated ? "TRUNCATED" : "PARTIAL", days, List.of(), diagnostics);
            return new Result(output, List.copyOf(windows), HexFormat.of().formatHex(digest.digest()), sampleCount);
        }
    }
}
