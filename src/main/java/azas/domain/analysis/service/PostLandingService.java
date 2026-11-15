package azas.domain.analysis.service;

import static azas.domain.analysis.engine.DateOpportunityEngine.STEP_SECONDS;
import static azas.domain.analysis.engine.DateOpportunityEngine.terrainHorizonDegrees;

import azas.domain.analysis.dto.DateOpportunityRequest;
import azas.domain.analysis.dto.PostLandingResponse;
import azas.domain.analysis.dto.PostLandingResponse.Body;
import azas.domain.analysis.dto.PostLandingResponse.MeanDailyVisibility;
import azas.domain.analysis.dto.PostLandingResponse.Occultations;
import azas.domain.analysis.dto.PostLandingResponse.Sample;
import azas.domain.analysis.entity.AnalysisRun;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.analysis.model.AnalysisType;
import azas.domain.analysis.model.ExecutionStatus;
import azas.domain.analysis.repository.AnalysisRunRepository;
import azas.domain.analysis.repository.ObserverArchiveReader;
import azas.domain.analysis.service.CandidateHistoryStore.SurfacePeriod;
import azas.global.exception.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
    private static final LocalDate MEAN_START = LocalDate.of(2027, 1, 1);
    private static final LocalDate MEAN_END = LocalDate.of(2028, 12, 31);
    private static final int DAYS = (int) ChronoUnit.DAYS.between(MEAN_START, MEAN_END) + 1;
    private static final int DAY_SECONDS = 86400;
    private final AnalysisRunRepository runs;
    private final CandidateHistoryStore history;
    private final AnalysisQueryService legacyResults;
    private final LandingSiteHorizon horizons;
    private final ObserverArchiveReader archive;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PostLandingResponse get(UUID runId, String candidateId) {
        var run = runs.findById(runId).orElseThrow(() -> new BusinessException(AnalysisErrorCode.RUN_NOT_FOUND));
        if (run.getAnalysisType() != AnalysisType.DATE_OPPORTUNITIES) throw new BusinessException(AnalysisErrorCode.TYPE_MISMATCH);
        if (run.getExecutionStatus() != ExecutionStatus.SUCCEEDED) throw new BusinessException(AnalysisErrorCode.RUN_NOT_SUCCEEDED);
        var period = period(run, candidateId);
        if (period.touchdown().isBefore(DateOpportunityRequest.SEARCH_START) || period.end().isAfter(DateOpportunityRequest.ARCHIVE_END)) {
            throw new BusinessException(AnalysisErrorCode.OBSERVER_DATA_INCOMPLETE);
        }
        var site = run.getLandingSite();
        var parameters = CandidateHistoryStore.JSON.readTree(run.getAnalysisParameters());
        double referenceHeight = parameters.path("referenceObserverHeightAboveSurfaceMeters")
                .asDouble(site.getObserverHeightAboveSurfaceMeters());
        double observerHeight = parameters.path("site").path("observerHeightAboveSurfaceMeters").asDouble(referenceHeight);
        var horizon = horizons.elevations(site.getId(), observerHeight - referenceHeight);
        var track = new SurfaceTrack(period, horizon);
        long start = period.touchdown().getEpochSecond() + (period.touchdown().getNano() == 0 ? 0 : 1);
        long first = start + Math.floorMod(-start, STEP_SECONDS);
        archive.scan(site.getId(), first, DateOpportunityRequest.ARCHIVE_END.getEpochSecond(), track::add);
        return new PostLandingResponse(site.getSiteCode(), period.touchdown(), period.end(), STEP_SECONDS,
                new Occultations(track.earthOcculted, track.sunOcculted), meanVisibility(site.getId(), horizon),
                observerHeight == referenceHeight ? "BODY_CENTER_LOCAL_TERRAIN" : LandingSiteHorizon.FIXED_BLOCKER_MODEL,
                track.samples);
    }

    private SurfacePeriod period(AnalysisRun run, String candidateId) {
        if (CandidateHistoryStore.JSON.readTree(run.getAnalysisParameters()).has("request")) {
            return history.findPeriod(run.getId(), candidateId)
                    .orElseThrow(() -> new BusinessException(AnalysisErrorCode.SURFACE_CANDIDATE_NOT_FOUND));
        }
        // Pre-V6 runs expose candidates reconstructed from their original landing windows.
        return legacyResults.opportunities(run.getId()).candidates().stream().filter(c -> c.candidateId().equals(candidateId))
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

    private MeanDailyVisibility meanVisibility(long siteId, List<Double> horizon) {
        long first = MEAN_START.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        long deadline = MEAN_END.atTime(23, 59).toEpochSecond(ZoneOffset.UTC);
        int[] sunSeconds = new int[DAYS], earthSeconds = new int[DAYS];
        archive.scan(siteId, first, deadline - Math.floorMod(deadline, STEP_SECONDS), (utc, sunAz, sunEl, earthAz, earthEl) -> {
            int day = (int) ((utc - first) / DAY_SECONDS);
            int seconds = (int) Math.min(STEP_SECONDS, deadline - utc);
            if (sunEl > terrainHorizonDegrees(horizon, sunAz)) sunSeconds[day] += seconds;
            if (earthEl > terrainHorizonDegrees(horizon, earthAz)) earthSeconds[day] += seconds;
            return true;
        });
        return new MeanDailyVisibility(MEAN_START, MEAN_END, DAYS, meanPercent(sunSeconds), meanPercent(earthSeconds));
    }

    private static double meanPercent(int[] visibleSeconds) {
        double sum = 0;
        for (int i = 0; i < DAYS; i++) {
            // The reference deadline is 23:59 UTC: its final day contributes 86,340 evaluated seconds.
            int evaluated = i == DAYS - 1 ? DAY_SECONDS - 60 : DAY_SECONDS;
            sum += (double) visibleSeconds[i] / evaluated;
        }
        return Math.round(sum / DAYS * 100 * 1_000_000) / 1_000_000.0;
    }
}
