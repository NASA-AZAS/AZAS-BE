package azas.domain.analysis.service;

import static azas.domain.analysis.engine.DateOpportunityEngine.STEP_SECONDS;
import static azas.domain.analysis.engine.DateOpportunityEngine.terrainHorizonDegrees;

import azas.domain.analysis.dto.DateOpportunityRequest;
import azas.domain.analysis.dto.PostLandingResponse;
import azas.domain.analysis.dto.PostLandingResponse.Body;
import azas.domain.analysis.dto.PostLandingResponse.Occultations;
import azas.domain.analysis.dto.PostLandingResponse.Sample;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.analysis.model.AnalysisType;
import azas.domain.analysis.model.ExecutionStatus;
import azas.domain.analysis.repository.AnalysisRunRepository;
import azas.domain.analysis.repository.ObserverArchiveReader;
import azas.domain.analysis.service.CandidateHistoryStore.SurfacePeriod;
import azas.global.exception.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostLandingService {
    private final AnalysisRunRepository runs;
    private final CandidateHistoryStore history;
    private final AnalysisQueryService legacyResults;
    private final LandingSiteHorizon horizons;
    private final ObserverArchiveReader archive;
    private final MeanDailyVisibilityService visibility;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PostLandingResponse get(UUID runId, String candidateId) {
        var run = runs.findById(runId).orElseThrow(() -> new BusinessException(AnalysisErrorCode.RUN_NOT_FOUND));
        if (run.getAnalysisType() != AnalysisType.DATE_OPPORTUNITIES) throw new BusinessException(AnalysisErrorCode.TYPE_MISMATCH);
        if (run.getExecutionStatus() != ExecutionStatus.SUCCEEDED) throw new BusinessException(AnalysisErrorCode.RUN_NOT_SUCCEEDED);
        var parameters = CandidateHistoryStore.JSON.readTree(run.getAnalysisParameters());
        var period = candidatePeriod(runId, candidateId, parameters.has("request"));
        if (period.touchdown().isBefore(DateOpportunityRequest.SEARCH_START) || period.end().isAfter(DateOpportunityRequest.ARCHIVE_END)) {
            throw new BusinessException(AnalysisErrorCode.OBSERVER_DATA_INCOMPLETE);
        }
        var site = run.getLandingSite();
        double referenceHeight = parameters.path("referenceObserverHeightAboveSurfaceMeters")
                .asDouble(site.getObserverHeightAboveSurfaceMeters());
        double observerHeight = parameters.path("site").path("observerHeightAboveSurfaceMeters").asDouble(referenceHeight);
        var horizon = horizons.elevations(site.getId(), observerHeight - referenceHeight);
        var track = new SurfaceTrack(period, horizon);
        long start = period.touchdown().getEpochSecond() + (period.touchdown().getNano() == 0 ? 0 : 1);
        long first = start + Math.floorMod(-start, STEP_SECONDS);
        archive.scan(site.getId(), first, DateOpportunityRequest.ARCHIVE_END.getEpochSecond(), track::add);
        return new PostLandingResponse(site.getSiteCode(), period.touchdown(), period.end(), STEP_SECONDS,
                new Occultations(track.earthOcculted, track.sunOcculted), visibility.calculate(site.getId(), horizon),
                observerHeight == referenceHeight ? "BODY_CENTER_LOCAL_TERRAIN" : LandingSiteHorizon.FIXED_BLOCKER_MODEL,
                track.samples);
    }

    private SurfacePeriod candidatePeriod(UUID runId, String candidateId, boolean hasStoredCandidates) {
        if (hasStoredCandidates) {
            return history.findPeriod(runId, candidateId)
                    .orElseThrow(() -> new BusinessException(AnalysisErrorCode.SURFACE_CANDIDATE_NOT_FOUND));
        }
        // Pre-V6 runs expose candidates reconstructed from their original landing windows.
        return legacyResults.legacyCandidates(runId).stream().filter(c -> c.candidateId().equals(candidateId))
                .map(c -> new SurfacePeriod(c.proposedLandingUtc(), c.surfaceFinishUtc())).findFirst()
                .orElseThrow(() -> new BusinessException(AnalysisErrorCode.SURFACE_CANDIDATE_NOT_FOUND));
    }

    private static final class SurfaceTrack {
        final SurfacePeriod period;
        final List<Double> horizon;
        final List<Sample> samples = new ArrayList<>();
        Instant earthOcculted;
        Instant sunOcculted;

        SurfaceTrack(SurfacePeriod period, List<Double> horizon) {
            this.period = period;
            this.horizon = horizon;
        }

        boolean add(long utc, double sunAz, double sunEl, double earthAz, double earthEl) {
            var time = Instant.ofEpochSecond(utc);
            var sun = body(sunAz, sunEl);
            var earth = body(earthAz, earthEl);
            if (sunOcculted == null && !sun.visible()) sunOcculted = time;
            if (earthOcculted == null && !earth.visible()) earthOcculted = time;
            if (!time.isAfter(period.end())) {
                samples.add(new Sample(time, Duration.between(period.touchdown(), time).toSeconds(), sun, earth,
                        sun.visible() && earth.visible()));
            }
            return utc + STEP_SECONDS <= period.end().getEpochSecond() || sunOcculted == null || earthOcculted == null;
        }

        Body body(double azimuth, double elevation) {
            double cutoff = terrainHorizonDegrees(horizon, azimuth);
            return new Body(azimuth, elevation, cutoff, elevation > cutoff);
        }
    }
}
