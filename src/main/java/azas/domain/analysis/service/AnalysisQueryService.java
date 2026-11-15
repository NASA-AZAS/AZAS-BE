package azas.domain.analysis.service;

import azas.domain.analysis.dto.AnalysisResponses;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.analysis.repository.AnalysisRunRepository;
import azas.domain.analysis.repository.DailyAnalysisResultRepository;
import azas.domain.analysis.repository.VisibilityWindowRepository;
import azas.global.exception.BusinessException;
import java.time.LocalDate;
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
}
