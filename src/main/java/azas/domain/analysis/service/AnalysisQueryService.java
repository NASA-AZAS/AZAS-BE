package azas.domain.analysis.service;

import azas.domain.analysis.dto.AnalysisResponses;
import azas.domain.analysis.engine.DateOpportunityEngine;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.analysis.repository.AnalysisRunRepository;
import azas.domain.analysis.repository.DailyAnalysisResultRepository;
import azas.domain.analysis.repository.VisibilityWindowRepository;
import azas.domain.analysis.repository.LandingOpportunityResultRepository;
import azas.domain.analysis.model.AnalysisType;
import azas.domain.analysis.model.ExecutionStatus;
import azas.global.exception.BusinessException;
import java.time.LocalDate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisQueryService {
    private final AnalysisRunRepository runs;
    private final DailyAnalysisResultRepository days;
    private final VisibilityWindowRepository windows;
    private final LandingOpportunityResultRepository opportunities;
    public AnalysisResponses.Run get(UUID id) {
        return AnalysisResponses.Run.from(runs.findById(id)
                .orElseThrow(() -> new BusinessException(AnalysisErrorCode.RUN_NOT_FOUND)));
    }

    public List<AnalysisResponses.Day> days(UUID id, LocalDate date) {
        if (!runs.existsById(id)) {
            throw new BusinessException(AnalysisErrorCode.RUN_NOT_FOUND);
        }

        var grouped = windows.findByDailyAnalysisResultAnalysisRunIdOrderByWindowStartAt(id).stream()
                .collect(Collectors.groupingBy(window -> window.getDailyAnalysisResult().getId()));

        return days.findByAnalysisRunIdOrderByAnalysisDate(id).stream()
                .filter(day -> date == null || date.equals(day.getAnalysisDate()))
                .map(day -> AnalysisResponses.Day.from(day, grouped.getOrDefault(day.getId(), List.of()))).toList();
    }

    public AnalysisResponses.Opportunities opportunities(UUID id) {
        var run = runs.findById(id).orElseThrow(() -> new BusinessException(AnalysisErrorCode.RUN_NOT_FOUND));
        if (run.getAnalysisType() != AnalysisType.DATE_OPPORTUNITIES) {
            throw new BusinessException(AnalysisErrorCode.TYPE_MISMATCH);
        }
        if (run.getExecutionStatus() != ExecutionStatus.SUCCEEDED) {
            return new AnalysisResponses.Opportunities(id, null, List.of(), List.of());
        }
        var intervals = new ArrayList<AnalysisResponses.TouchdownWindow>();
        var candidates = new ArrayList<AnalysisResponses.SurfaceCandidate>();
        for (var row : opportunities.findByAnalysisRunIdOrderByLandingWindowStartAt(id)) {
            Instant start = row.getLandingWindowStartAt(), end = row.getLandingWindowEndAt();
            Duration activity = Duration.between(row.getProposedLandingAt(), row.getSurfaceActivityFinishedAt());
            String surfaceStatus = row.getSurfaceConditionStatus();
            intervals.add(new AnalysisResponses.TouchdownWindow(start, end, end.plus(activity), surfaceStatus));
            if (!"SATISFIED".equals(surfaceStatus)) continue;

            Instant time = start;
            while (candidates.size() < DateOpportunityEngine.MAX_CANDIDATES) {
                String code = String.format(Locale.ROOT, "%s-%05d", id, candidates.size() + 1);
                candidates.add(new AnalysisResponses.SurfaceCandidate(code, time, time.plus(activity)));
                if (time.equals(end)) break;
                Instant next = time.plusSeconds(DateOpportunityEngine.STEP_SECONDS);
                time = next.isAfter(end) ? end : next;
            }
        }
        Instant earliestTouchdown = candidates.isEmpty() ? null : candidates.getFirst().proposedLandingUtc();
        return new AnalysisResponses.Opportunities(id, earliestTouchdown, intervals, candidates);
    }
}
