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
    private final CandidateHistoryStore history;
    public AnalysisResponses.Run get(UUID id) {
        return AnalysisResponses.Run.from(runs.findById(id)
                .orElseThrow(() -> new BusinessException(AnalysisErrorCode.RUN_NOT_FOUND)));
    }

    public List<AnalysisResponses.Day> days(UUID id, LocalDate date) {
        if (!runs.existsById(id)) {
            throw new BusinessException(AnalysisErrorCode.RUN_NOT_FOUND);
        }

        if (date != null) {
            return days.findByAnalysisRunIdAndAnalysisDate(id, date).stream()
                    .map(day -> AnalysisResponses.Day.from(day,
                            windows.findByDailyAnalysisResultIdOrderByWindowStartAt(day.getId())))
                    .toList();
        }

        var grouped = windows.findByDailyAnalysisResultAnalysisRunIdOrderByWindowStartAt(id).stream()
                .collect(Collectors.groupingBy(window -> window.getDailyAnalysisResult().getId()));

        return days.findByAnalysisRunIdOrderByAnalysisDate(id).stream()
                .map(day -> AnalysisResponses.Day.from(day, grouped.getOrDefault(day.getId(), List.of()))).toList();
    }

    public AnalysisResponses.Opportunities opportunities(UUID id) {
        var run = runs.findById(id).orElseThrow(() -> new BusinessException(AnalysisErrorCode.RUN_NOT_FOUND));
        if (run.getAnalysisType() != AnalysisType.DATE_OPPORTUNITIES) {
            throw new BusinessException(AnalysisErrorCode.TYPE_MISMATCH);
        }
        if (run.getExecutionStatus() != ExecutionStatus.SUCCEEDED) {
            return new AnalysisResponses.Opportunities(id, null, List.of(), List.of(), List.of());
        }

        var intervals = touchdownWindows(id);
        var earliest = intervals.stream().filter(window -> "SATISFIED".equals(window.surfaceStatus()))
                .map(AnalysisResponses.TouchdownWindow::startUtc).min(Instant::compareTo).orElse(null);
        boolean hasStoredCandidates = CandidateHistoryStore.JSON.readTree(run.getAnalysisParameters()).has("request");
        var candidates = hasStoredCandidates ? history.get(id) : reconstructLegacyCandidates(id, intervals);
        var arrivalWindows = intervals.stream()
                .filter(window -> "SATISFIED".equals(window.surfaceStatus()))
                .filter(window -> Duration.between(window.startUtc(), window.endUtc())
                        .compareTo(Duration.between(window.endUtc(), window.supportEndUtc())) >= 0)
                .toList();
        return new AnalysisResponses.Opportunities(id, earliest, intervals, candidates, arrivalWindows);
    }

    List<AnalysisResponses.TouchdownWindow> touchdownWindows(UUID id) {
        var intervals = new ArrayList<AnalysisResponses.TouchdownWindow>();
        for (var row : opportunities.findByAnalysisRunIdOrderByLandingWindowStartAt(id)) {
            Instant start = row.getLandingWindowStartAt(), end = row.getLandingWindowEndAt();
            Duration activity = Duration.between(row.getProposedLandingAt(), row.getSurfaceActivityFinishedAt());
            intervals.add(new AnalysisResponses.TouchdownWindow(start, end, end.plus(activity), row.getSurfaceConditionStatus()));
        }
        return intervals;
    }

    List<AnalysisResponses.SurfaceCandidate> legacyCandidates(UUID id) {
        return reconstructLegacyCandidates(id, touchdownWindows(id));
    }

    private static List<AnalysisResponses.SurfaceCandidate> reconstructLegacyCandidates(
            UUID id, List<AnalysisResponses.TouchdownWindow> intervals) {
        var candidates = new ArrayList<AnalysisResponses.SurfaceCandidate>();
        for (var window : intervals) {
            if (!"SATISFIED".equals(window.surfaceStatus())) continue;

            Instant end = window.endUtc();
            Duration activity = Duration.between(end, window.supportEndUtc());
            Instant time = window.startUtc();
            while (candidates.size() < DateOpportunityEngine.MAX_CANDIDATES) {
                String code = String.format(Locale.ROOT, "%s-%05d", id, candidates.size() + 1);
                candidates.add(new AnalysisResponses.SurfaceCandidate(code, time, time.plus(activity)));
                if (time.equals(end)) break;
                Instant next = time.plusSeconds(DateOpportunityEngine.STEP_SECONDS);
                time = next.isAfter(end) ? end : next;
            }
        }
        return candidates;
    }
}
