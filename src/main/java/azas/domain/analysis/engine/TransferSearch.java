package azas.domain.analysis.engine;

import azas.domain.analysis.dto.DateOpportunityRequest;
import azas.domain.analysis.engine.LunarTransferOrbit.State;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The individual-candidate search from LLSN ver2 Deadline, independent of persistence. */
@Component
public class TransferSearch {
    private final HorizonsClient horizons;
    public TransferSearch(HorizonsClient horizons) { this.horizons = horizons; }

    public record Candidate(Instant landingUtc, Instant surfaceFinishUtc, Instant windowStartUtc, Instant windowEndUtc,
            String status, String surfaceStatus, Instant launchUtc, Instant tliUtc, Instant lunarArrivalUtc,
            Double transferDurationHours, String lambertStatus, Double positionResidualKm, String modelLevel,
            List<String> reasons, Map<String, Object> lambert) {}
    public record Result(List<Candidate> candidates, List<AnalysisEngine.Source> sources, boolean truncated) {}

    public Result search(List<DateOpportunityEngine.Window> windows, DateOpportunityRequest request) {
        var scan = new Search(request);
        outer: for (var window : windows)
            for (long travel : request.transfersEnabled() ? request.transferDurationHours().seconds() : List.of(0L))
                for (long delay : request.transfersEnabled() ? request.preLandingDelayHours().seconds() : List.of(0L))
                    for (long launch : request.launchToTliDelayHours() == null ? List.of(-1L) : request.launchToTliDelayHours().seconds())
                        if (!scan.grid(window, travel, delay, launch)) break outer;
        return new Result(List.copyOf(scan.candidates), List.copyOf(scan.sources.values()), scan.truncated);
    }

    private final class Search {
        final DateOpportunityRequest request;
        final List<Candidate> candidates = new ArrayList<>();
        final Map<String, AnalysisEngine.Source> sources = new LinkedHashMap<>();
        boolean truncated;
        Search(DateOpportunityRequest request) { this.request = request; }

        boolean grid(DateOpportunityEngine.Window w, long travel, long delay, long launch) {
            Instant previous = null;
            Candidate last = null;
            for (Instant t = w.startUtc();;) {
                Candidate current = emit(w, t, travel, delay, launch);
                if (current == null) return false;
                if (last != null) refine(w, previous, t, last, current, 0, travel, delay, launch);
                if (truncated) return false;
                if (t.equals(w.endUtc())) return true;
                previous = t; last = current;
                t = t.plusSeconds(Math.min(request.timeStepMinutes() * 60L, Duration.between(t, w.endUtc()).toSeconds()));
            }
        }

        void refine(DateOpportunityEngine.Window w, Instant a, Instant b, Candidate x, Candidate y,
                int level, long travel, long delay, long launch) {
            if (truncated || level >= request.refinementPolicy().maxDepth()
                    || Duration.between(a, b).toSeconds() <= request.refinementPolicy().minStepMinutes() * 60L
                    || x.status().equals(y.status()) && x.lambertStatus().equals(y.lambertStatus())) return;
            Instant mid = a.plusSeconds(Duration.between(a, b).toMinutes() / 2 * 60);
            if (mid.equals(a)) return;
            Candidate c = emit(w, mid, travel, delay, launch);
            if (c == null) return;
            refine(w, a, mid, x, c, level + 1, travel, delay, launch);
            refine(w, mid, b, c, y, level + 1, travel, delay, launch);
        }

        Candidate emit(DateOpportunityEngine.Window w, Instant landing, long travel, long delay, long launchDelay) {
            if (Thread.currentThread().isInterrupted()) throw new AnalysisEngineException("ANALYSIS_INTERRUPTED", null);
            if (candidates.size() >= request.maxCandidates()) { truncated = true; return null; }
            var reasons = new ArrayList<String>();
            String status = "ENVIRONMENT_ONLY", solver = "NOT_EVALUATED", model = "ENVIRONMENT_ONLY";
            Instant arrival = null, tli = null, launch = null;
            Double residual = null, hours = null;
            Map<String, Object> details = null;
            if (!request.transfersEnabled()) reasons.add("TRANSFER_INPUTS_MISSING");
            else {
                model = "PRELIMINARY_TWO_BODY";
                hours = travel / 3600.0;
                arrival = landing.minusSeconds(delay); tli = arrival.minusSeconds(travel);
                launch = launchDelay < 0 ? null : tli.minusSeconds(launchDelay);
                if (launch == null) reasons.add("LAUNCH_MODEL_MISSING");
                if ((launch == null ? tli : launch).isBefore(DateOpportunityRequest.SEARCH_START)) {
                    status = "EXCLUDED"; reasons.add("TIME_ORDER_OR_SEARCH_START_VIOLATION");
                } else try {
                    if (request.networkMode() == DateOpportunityRequest.NetworkMode.CACHE_ONLY)
                        throw new AnalysisEngineException("VECTOR_ARCHIVE_UNAVAILABLE", null);
                    vector(399, tli); vector(301, tli);
                    var earth = vector(399, arrival); var moon = vector(301, arrival);
                    var d = request.departureState();
                    double mu = d.earthMuKm3S2();
                    State parking = LunarTransferOrbit.propagate(new State(d.positionKm(), d.velocityKmS()),
                            Duration.between(Instant.parse(d.epochUtc()), tli).toSeconds(), mu);
                    double[] target = LunarTransferOrbit.combine(LunarTransferOrbit.combine(moon.km(), -1, earth.km()),
                            1, request.arrivalTargetRule().offsetKm());
                    var solution = LunarTransferOrbit.solve(parking.position(), target, travel, mu,
                            request.lambert().branch() == DateOpportunityRequest.Branch.LONG_WAY, request.lambert().positionToleranceKm());
                    solver = solution.status(); residual = solution.residualKm();
                    status = solver.equals("CONVERGED") ? "PRELIMINARY" : "UNKNOWN";
                    details = new LinkedHashMap<>();
                    details.put("departurePositionKm", parking.position());
                    details.put("initialParkingVelocityKmS", parking.velocity());
                    details.put("earthMuKm3S2", mu);
                    details.put("status", solution.status());
                    details.put("branch", request.lambert().branch());
                    details.put("positionToleranceKm", request.lambert().positionToleranceKm());
                    details.put("positionResidualKm", solution.residualKm());
                    details.put("reason", solution.reason());
                    details.put("algorithmVersion", "uv-zero-rev-1");
                    details.put("departureVelocityKmS", solution.departureVelocity());
                    details.put("arrivalVelocityKmS", solution.arrivalVelocity());
                    if (solution.arrivalVelocity() != null) details.put("arrivalMoonRelativeVelocityKmS",
                            LunarTransferOrbit.combine(solution.arrivalVelocity(), -1,
                                    LunarTransferOrbit.combine(moon.velocityKmS(), -1, earth.velocityKmS())));
                    reasons.add("EARTH_ONLY_MODEL_LAUNCH_CAPTURE_DESCENT_UNVERIFIED");
                } catch (AnalysisEngineException | ArithmeticException exception) {
                    if (Thread.currentThread().isInterrupted()) throw new AnalysisEngineException("ANALYSIS_INTERRUPTED", exception);
                    status = "UNKNOWN"; solver = "SOURCE_OR_NUMERICAL_ERROR";
                    reasons.add(exception instanceof AnalysisEngineException failure ? failure.code() : "PROPAGATION_FAILED");
                }
            }
            var candidate = new Candidate(landing, landing.plusSeconds(request.durationSeconds()), w.startUtc(), w.endUtc(),
                    status, "SATISFIED", launch, tli, arrival, hours, solver, residual, model, List.copyOf(reasons), details);
            candidates.add(candidate);
            return candidate;
        }

        HorizonsClient.Position vector(int target, Instant utc) {
            var result = horizons.position(target, utc);
            var source = result.source();
            sources.put(source.url() + source.responseSha256(), source);
            return result;
        }
    }
}
