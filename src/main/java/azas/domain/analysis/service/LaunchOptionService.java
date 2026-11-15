package azas.domain.analysis.service;

import azas.domain.analysis.dto.LaunchOptionsResponse;
import azas.domain.analysis.engine.LaunchOptionCalculator;
import azas.domain.analysis.entity.AnalysisRun;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.analysis.model.AnalysisType;
import azas.domain.analysis.model.ExecutionStatus;
import azas.domain.analysis.repository.AnalysisRunRepository;
import azas.domain.analysis.repository.LaunchSiteRepository;
import azas.domain.analysis.service.TouchdownSelections.Surface;
import azas.global.exception.BusinessException;
import azas.global.exception.CommonErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static azas.domain.analysis.service.LaunchOptionStore.DEFAULT;

@Service
@RequiredArgsConstructor
@Transactional
public class LaunchOptionService {
    private final AnalysisRunRepository runs;
    private final LaunchSiteRepository sites;
    private final LaunchOptionStore store;
    private final TouchdownSelections selections;

    public LaunchOptionsResponse get(UUID id) {
        return get(id, null, null, null);
    }

    public LaunchOptionsResponse get(UUID id, LocalDate date, Instant time, String surfaceId) {
        int selectors = (date == null ? 0 : 1) + (time == null ? 0 : 1) + (surfaceId == null ? 0 : 1);
        if (selectors > 1 || (surfaceId != null && surfaceId.isBlank())) throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        var run = lockRun(id);
        if (run.getExecutionStatus() != ExecutionStatus.SUCCEEDED) {
            if (selectors > 0) throw new BusinessException(AnalysisErrorCode.RUN_NOT_SUCCEEDED);
            return new LaunchOptionsResponse(id, run.getLandingSite().getId(), run.getExecutionStatus(), null,
                    LaunchOptionCalculator.MODEL, false, LaunchOptionCalculator.ASSUMPTIONS, List.of());
        }

        if (date != null) {
            var cached = store.keyForDate(id, date);
            if (cached.isPresent()) return store.read(run, cached.get(), true);
        }
        var original = defaultBatch(run);
        if (selectors == 0) return original;

        Surface surface;
        if (date != null) {
            surface = selections.onDate(run, date, original.launchSites());
        } else if (time != null) {
            surface = selections.onGrid(run, time);
        } else {
            boolean stored = CandidateHistoryStore.JSON.readTree(run.getAnalysisParameters()).has("request");
            surface = selections.byId(id, surfaceId, stored);
            selections.windowAt(id, surface.touchdown());
        }

        String key = surface.id().equals(original.surfaceCandidateId()) ? DEFAULT : surface.id();
        if (!store.exists(id, key)) {
            store.save(id, key, run.getAnalysisStartAt(), surface.touchdown(), surface, original.launchSites());
        }
        if (date != null) store.rememberDate(id, date, key);
        return store.read(run, key, true);
    }

    public LaunchOptionsResponse forLaunchCandidate(UUID id, String candidateId) {
        var run = lockRun(id);
        if (run.getExecutionStatus() != ExecutionStatus.SUCCEEDED) throw new BusinessException(AnalysisErrorCode.RUN_NOT_SUCCEEDED);
        var key = store.keyForLaunch(id, candidateId);
        if (key.isPresent() && !key.get().isEmpty()) return store.read(run, key.get(), true);
        return defaultBatch(run);
    }

    private AnalysisRun lockRun(UUID id) {
        var run = runs.lockById(id).orElseThrow(() -> new BusinessException(AnalysisErrorCode.RUN_NOT_FOUND));
        if (run.getAnalysisType() != AnalysisType.DATE_OPPORTUNITIES) throw new BusinessException(AnalysisErrorCode.TYPE_MISMATCH);
        return run;
    }

    private LaunchOptionsResponse defaultBatch(AnalysisRun run) {
        UUID id = run.getId();
        if (!store.exists(id, DEFAULT)) {
            capture(id, run.getAnalysisStartAt(), runs.findEarliestSatisfiedTouchdown(id).orElse(null));
        }
        var batch = store.read(run, DEFAULT, false);
        if (batch.touchdownUtc() != null && batch.surfaceCandidateId() == null) {
            var window = selections.windowAt(id, batch.touchdownUtc());
            store.linkDefault(id, selections.at(run, batch.touchdownUtc(), window));
            return store.read(run, DEFAULT, false);
        }
        return batch;
    }

    /** Called under the analysis row lock. Later date selections reuse this frozen catalog. */
    public void capture(UUID id, Instant start, Instant touchdown) {
        var catalog = touchdown == null ? List.<LaunchOptionsResponse.Site>of() : sites.findAllByOrderByIdAsc().stream()
                .map(site -> new LaunchOptionsResponse.Site(site.getId(), site.getSiteCode(), site.getLatitudeDegrees(),
                        site.getLongitudeDegreesEast(), List.of())).toList();
        store.save(id, DEFAULT, start, touchdown, null, catalog);
    }
}
