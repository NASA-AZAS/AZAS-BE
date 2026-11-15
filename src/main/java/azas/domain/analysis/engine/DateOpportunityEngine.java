package azas.domain.analysis.engine;

import azas.domain.analysis.engine.VisibilityCalculator.State;
import azas.domain.analysis.dto.DateOpportunityRequest;
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
    private final TransferSearch search;
    private final AnalysisEngine.Identity identity = new AnalysisEngine.Identity("llsn-ver2-date-opportunities",
            EngineFingerprint.of(DateOpportunityEngine.class, VisibilityCalculator.class, AnalysisEngine.class,
                    TransferSearch.class, LunarTransferOrbit.class, HorizonsClient.class, DateOpportunityRequest.class));

    public DateOpportunityEngine(ObserverSampleRepository observations, TransferSearch search) { this.observations = observations; this.search = search; }
    public AnalysisEngine.Identity identity() { return identity; }

    public record Window(Instant startUtc, Instant endUtc, Instant supportEndUtc) {}

    public static double terrainHorizonDegrees(List<Double> profile, double azimuth) {
        double position = azimuth * 10;
        int lower = (int) position;
        double fraction = position - lower;
        double left = profile.get(lower % 3600), right = profile.get((lower + 1) % 3600);
        return fraction == 0 ? left : left * (1 - fraction) + right * fraction;
    }

    public record Result(AnalysisEngine.Output visibility, List<Window> windows, String observerSha256, int sampleCount, List<TransferSearch.Candidate> candidates) {}

    public Result analyze(long siteId, AnalysisEngine.Input input, DateOpportunityRequest request) {
        var horizon = new VisibilityCalculator.Horizon(input.horizon());
        Scan scan = new Scan(horizon, input.end(), request);
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
        var environment = scan.finish();
        var evaluated = search.search(environment.windows(), request);
        var diagnostics = new ArrayList<>(environment.visibility().diagnostics());
        if (evaluated.truncated()) diagnostics.add("MAX_CANDIDATES_REACHED");
        var output = new AnalysisEngine.Output(evaluated.truncated() ? "TRUNCATED" : "PARTIAL",
                environment.visibility().days(), evaluated.sources(), diagnostics);
        return new Result(output, environment.windows(), environment.observerSha256(), environment.sampleCount(), evaluated.candidates());
    }

    private static final class Scan {
        final VisibilityCalculator.Horizon horizon;
        final Instant deadline;
        final long surfaceSeconds;
        final DateOpportunityRequest request;
        final List<AnalysisEngine.Day> days = new ArrayList<>();
        final List<Window> windows = new ArrayList<>();
        final MessageDigest digest;
        final ByteBuffer sampleBytes = ByteBuffer.allocate(5 * Long.BYTES);
        VisibilityCalculator.Accumulator day;
        Instant firstVisible;
        Instant lastVisible;
        int sampleCount;

        Scan(VisibilityCalculator.Horizon horizon, Instant deadline, DateOpportunityRequest request) {
            this.horizon = horizon;
            this.deadline = deadline;
            this.surfaceSeconds = request.durationSeconds();
            this.request = request;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        }

        State localState(double azimuth, double elevation) {
            double cutoff = terrainHorizonDegrees(horizon.elevations(), azimuth);
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
            if (matches(sun, request.surfaceCriteria().sun()) && matches(earth, request.surfaceCriteria().earth())) {
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

        boolean matches(State state, DateOpportunityRequest.Criterion criterion) {
            return criterion == DateOpportunityRequest.Criterion.ANY || state.name().equals(criterion.name());
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
            List<String> diagnostics = new ArrayList<>(List.of("LOCAL_HORIZON_ONLY", "DISTANT_TERRAIN_NOT_EVALUATED"));
            if (windows.isEmpty()) diagnostics.add("NO_SAMPLE_SUPPORTED_WINDOW");
            var output = new AnalysisEngine.Output("PARTIAL", days, List.of(), diagnostics);
            return new Result(output, List.copyOf(windows), HexFormat.of().formatHex(digest.digest()), sampleCount, List.of());
        }
    }
}
