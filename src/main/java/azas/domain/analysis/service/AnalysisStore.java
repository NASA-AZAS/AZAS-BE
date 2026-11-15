package azas.domain.analysis.service;

import azas.domain.analysis.dto.AnalysisRequest;
import azas.domain.analysis.engine.AnalysisEngine;
import azas.domain.analysis.engine.AnalysisEngineException;
import azas.domain.analysis.engine.HorizonsClient;
import azas.domain.analysis.entity.AnalysisRun;
import azas.domain.analysis.entity.DailyAnalysisResult;
import azas.domain.analysis.entity.VisibilityWindow;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.analysis.model.AnalysisType;
import azas.domain.analysis.model.CelestialBody;
import azas.domain.analysis.model.ExecutionStatus;
import azas.domain.analysis.model.ResultStatus;
import azas.domain.analysis.repository.AnalysisRunRepository;
import azas.domain.analysis.repository.DailyAnalysisResultRepository;
import azas.domain.analysis.repository.VisibilityWindowRepository;
import azas.domain.landingsite.repository.HorizonSampleRepository;
import azas.domain.landingsite.repository.LandingSiteRepository;
import azas.global.exception.BusinessException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Short DB transactions only. Network and calculation run outside this service's transactions. */
@Service
@RequiredArgsConstructor
public class AnalysisStore {
    public static final int TIMEOUT_SECONDS = 120;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final LandingSiteRepository sites;
    private final HorizonSampleRepository samples;
    private final AnalysisRunRepository runs;
    private final DailyAnalysisResultRepository dailyResults;
    private final VisibilityWindowRepository windows;

    public record Prepared(UUID id, UUID owner, Instant expiresAt, AnalysisEngine.Input input) {}

    @Transactional
    public Prepared create(long siteId, AnalysisRequest request, AnalysisEngine.Identity identity) {
        var site = sites.findById(siteId).orElseThrow(() -> new BusinessException(AnalysisErrorCode.SITE_NOT_FOUND));
        var dataset = site.getTerrainDataset();
        var profile = samples.findByIdLandingSiteIdOrderByIdAzimuthIndex(siteId);

        if (profile.size() != 3600) {
            throw new BusinessException(AnalysisErrorCode.INVALID_TERRAIN);
        }

        for (int i = 0; i < profile.size(); i++) {
            var sample = profile.get(i);

            if (sample.getId().getAzimuthIndex() != i || Math.abs(sample.getAzimuthDegrees() - i / 10.0) > 1e-8) {
                throw new BusinessException(AnalysisErrorCode.INVALID_TERRAIN);
            }
        }

        var input = new AnalysisEngine.Input(site.getSiteCode(), site.getLatitudeDegrees(), site.getLongitudeDegrees(),
                site.getSurfaceElevationMeters(), site.getObserverHeightAboveSurfaceMeters(), dataset.getCoordinateReferenceFrame(),
                dataset.getReferenceSphereRadiusMeters(), dataset.isDistantTerrainVerified(), dataset.getDatasetSha256(),
                profile.stream().map(sample -> sample.getTerrainHorizonDegrees()).toList(), request.start(), request.endExclusive(), 3600);
        String parameters = JSON.writeValueAsString(new java.util.TreeMap<>(Map.ofEntries(
                Map.entry("startDate", request.startDate()), Map.entry("endDate", request.endDate()),
                Map.entry("timezone", "UTC"), Map.entry("sampleIntervalMinutes", 60), Map.entry("siteCode", input.siteCode()),
                Map.entry("datasetSha256", input.datasetSha256()), Map.entry("latitudeDegrees", input.latitude()),
                Map.entry("longitudeDegrees", input.longitude()), Map.entry("surfaceElevationMeters", input.surfaceElevation()),
                Map.entry("observerHeightAboveSurfaceMeters", input.observerHeight()))));
        Instant now = Instant.now(), expires = now.plusSeconds(TIMEOUT_SECONDS);
        UUID owner = UUID.randomUUID();
        var run = AnalysisRun.builder().landingSite(site).analysisType(AnalysisType.VISIBILITY)
                .analysisStartAt(input.start()).analysisEndAt(input.end()).sampleIntervalMinutes(60)
                .analysisParameters(parameters).calculationParametersSha256(HorizonsClient.sha256(
                        parameters.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .engineVersion(identity.version()).engineSha256(identity.sha256()).createdAt(now)
                .executionOwner(owner).executionExpiresAt(expires).build();
        runs.saveAndFlush(run);

        return new Prepared(run.getId(), owner, expires, input);
    }

    @Transactional
    public boolean start(Prepared prepared) {
        var run = runs.lockById(prepared.id()).orElseThrow();
        Instant now = Instant.now();

        if (!run.ownedAndActive(prepared.owner()) || run.getExecutionStatus() != ExecutionStatus.PENDING) {
            return false;
        }

        if (run.expired(now)) { run.fail("ANALYSIS_TIMEOUT", now); return false; }
        run.start(now);

        return true;
    }

    @Transactional
    public boolean complete(Prepared prepared, AnalysisEngine.Output output) {
        var run = runs.lockById(prepared.id()).orElseThrow();
        Instant now = Instant.now();

        if (!run.ownedAndActive(prepared.owner()) || run.getExecutionStatus() != ExecutionStatus.RUNNING) {
            return false;
        }

        if (run.expired(now)) { run.fail("ANALYSIS_TIMEOUT", now); return false; }
        long days = ChronoUnit.DAYS.between(run.getAnalysisStartAt().atOffset(ZoneOffset.UTC).toLocalDate(),
                run.getAnalysisEndAt().atOffset(ZoneOffset.UTC).toLocalDate());

        if (output.days().size() != days || output.resultStatus().equals("FAILED")) {
            throw new AnalysisEngineException("ANALYSIS_INVALID_RESULT", null);
        }

        for (int i = 0; i < output.days().size(); i++) {
            var day = output.days().get(i);
            Instant expectedStart = run.getAnalysisStartAt().plusSeconds(i * 86400L);

            if (!day.start().equals(expectedStart) || !day.end().equals(expectedStart.plusSeconds(86400))
                    || !day.date().equals(expectedStart.atOffset(ZoneOffset.UTC).toLocalDate())) {
                throw new AnalysisEngineException("ANALYSIS_INVALID_RESULT", null);
            }

            var daily = DailyAnalysisResult.builder().analysisRun(run).analysisDate(day.date())
                    .resultStatus(ResultStatus.valueOf(day.resultStatus()))
                    .evaluatedPeriodStartAt(day.start()).evaluatedPeriodEndAt(day.end())
                    .sunVisibleTimeRatio(day.sun().visibleRatio()).earthVisibleTimeRatio(day.earth().visibleRatio())
                    .jointVisibleTimeRatio(day.joint().visibleRatio()).sunKnownTimeRatio(day.sun().knownRatio())
                    .earthKnownTimeRatio(day.earth().knownRatio()).jointKnownTimeRatio(day.joint().knownRatio())
                    .jointUnknownTimeRatio(1 - day.joint().knownRatio())
                    .longestSunOccultationHours(day.sun().longestOccultationHours())
                    .longestEarthOccultationHours(day.earth().longestOccultationHours())
                    .terrainCoverageStatus(day.terrainCoverage()).qualityFlags(day.qualityFlags().toArray(String[]::new)).build();
            dailyResults.save(daily);

            for (var window : day.windows()) {
                if (window.start().isBefore(day.start()) || window.end().isAfter(day.end())) {
                    throw new AnalysisEngineException("ANALYSIS_INVALID_RESULT", null);
                }

                windows.save(VisibilityWindow.builder().dailyAnalysisResult(daily).celestialBody(CelestialBody.valueOf(window.body()))
                        .windowStartAt(window.start()).windowEndAt(window.end()).boundaryPrecisionSeconds(window.precisionSeconds()).build());
            }
        }

        String provenance = JSON.writeValueAsString(Map.of("terrainDatasetSha256", prepared.input().datasetSha256(),
                "sources", output.sources(), "integrationConvention", "LEFT_ENDPOINT_HOLD_FINAL_ENDPOINT_ZERO_WEIGHT"));
        run.complete(ResultStatus.valueOf(output.resultStatus()), provenance, output.diagnostics().toArray(String[]::new), now);

        return true;
    }

    @Transactional
    public void fail(Prepared prepared, String code) {
        var run = runs.lockById(prepared.id()).orElseThrow();

        if (run.ownedAndActive(prepared.owner())) {
            Instant now = Instant.now();
            run.fail(run.expired(now) ? "ANALYSIS_TIMEOUT" : code, now);
        }
    }

    @Transactional
    public int expire() { return runs.expire(Instant.now()); }
}
