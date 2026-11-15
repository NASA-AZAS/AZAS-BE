package azas.domain.analysis.service;

import azas.domain.analysis.dto.LaunchTrajectoryResponse;
import azas.domain.analysis.engine.LunarTrajectoryCalculator;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.analysis.model.ExecutionStatus;
import azas.global.exception.BusinessException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LaunchTrajectoryService {
    private final LaunchOptionService launchOptions;
    private final LunarTrajectoryCalculator calculator;

    public LaunchTrajectoryResponse calculate(UUID runId, String candidateId) {
        var options = launchOptions.get(runId);
        if (options.executionStatus() != ExecutionStatus.SUCCEEDED) {
            throw new BusinessException(AnalysisErrorCode.RUN_NOT_SUCCEEDED);
        }
        for (var site : options.launchSites()) {
            for (var candidate : site.candidates()) {
                if (candidate.candidateId().equals(candidateId)) {
                    return calculator.calculate(options, site, candidate);
                }
            }
        }
        throw new BusinessException(AnalysisErrorCode.LAUNCH_CANDIDATE_NOT_FOUND);
    }
}
