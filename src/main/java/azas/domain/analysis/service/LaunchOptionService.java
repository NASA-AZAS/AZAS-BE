package azas.domain.analysis.service;

import azas.domain.analysis.dto.LaunchOptionsResponse;
import azas.domain.analysis.engine.LaunchOptionCalculator;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.analysis.model.AnalysisType;
import azas.domain.analysis.model.ExecutionStatus;
import azas.domain.analysis.repository.AnalysisRunRepository;
import azas.domain.analysis.repository.LaunchSiteRepository;
import azas.global.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LaunchOptionService {
    private final AnalysisRunRepository runs;
    private final LaunchSiteRepository sites;

    public LaunchOptionsResponse get(UUID id) {
        var run = runs.findById(id).orElseThrow(() -> new BusinessException(AnalysisErrorCode.RUN_NOT_FOUND));
        if (run.getAnalysisType() != AnalysisType.DATE_OPPORTUNITIES) {
            throw new BusinessException(AnalysisErrorCode.TYPE_MISMATCH);
        }

        Instant touchdown = run.getExecutionStatus() == ExecutionStatus.SUCCEEDED
                ? runs.findEarliestSatisfiedTouchdown(id).orElse(null) : null;
        var reports = touchdown == null ? List.<LaunchOptionsResponse.Site>of() : options(run.getAnalysisStartAt(), touchdown);
        return new LaunchOptionsResponse(id, run.getLandingSite().getId(), run.getExecutionStatus(), touchdown,
                LaunchOptionCalculator.MODEL, false, LaunchOptionCalculator.ASSUMPTIONS, reports);
    }

    private List<LaunchOptionsResponse.Site> options(Instant start, Instant touchdown) {
        var catalog = sites.findAllByOrderByIdAsc();
        if (catalog.isEmpty()) {
            throw new BusinessException(AnalysisErrorCode.LAUNCH_SITES_INCOMPLETE);
        }
        return catalog.stream().map(site -> new LaunchOptionsResponse.Site(site.getId(), site.getSiteCode(),
                site.getLatitudeDegrees(), site.getLongitudeDegreesEast(),
                LaunchOptionCalculator.candidates(site.getSiteCode(), site.getLatitudeDegrees(),
                        site.getLongitudeDegreesEast(), start, touchdown))).toList();
    }
}
