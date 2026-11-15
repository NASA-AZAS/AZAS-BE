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
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LaunchTrajectoryService.class);
    private final LaunchOptionService launchOptions;
    private final LunarTrajectoryCalculator calculator;
    private final TrajectoryHistoryStore history;
    private final AnalysisJobs jobs;

    public LaunchTrajectoryResponse get(UUID runId, UUID trajectoryId) { return history.get(runId, trajectoryId); }
    public java.util.List<LaunchTrajectoryResponse.Summary> list(UUID runId) { return history.list(runId); }

    public LaunchTrajectoryResponse calculate(UUID runId, String candidateId) {
        var options = launchOptions.get(runId);
        if (options.executionStatus() != ExecutionStatus.SUCCEEDED) {
            throw new BusinessException(AnalysisErrorCode.RUN_NOT_SUCCEEDED);
        }
        for (var site : options.launchSites()) {
            for (var candidate : site.candidates()) {
                if (candidate.candidateId().equals(candidateId)) {
                    var prepared = new java.util.concurrent.atomic.AtomicReference<TrajectoryHistoryStore.Prepared>();
                    try {
                        jobs.compute(() -> {
                            var work = history.create(options, site, candidate);
                            prepared.set(work);
                            LOG.atInfo().addKeyValue("analysisRunId", runId).addKeyValue("trajectoryRunId", work.id())
                                    .addKeyValue("candidateId", candidateId).log("Trajectory accepted");
                            return new AnalysisJobs.Work<>(work.expiresAt(), () -> {
                                if (history.start(work)) history.complete(work, calculator.calculate(options, site, candidate));
                                else history.fail(work.id(), "ANALYSIS_TIMEOUT");
                                return true;
                            }, reason -> history.fail(work.id(), reason));
                        });
                    } catch (azas.domain.analysis.engine.AnalysisEngineException exception) {
                        if (prepared.get() == null || Thread.currentThread().isInterrupted()) throw exception;
                    }
                    var result = history.get(runId, prepared.get().id());
                    LOG.atInfo().addKeyValue("analysisRunId", runId).addKeyValue("trajectoryRunId", result.trajectoryRunId())
                            .addKeyValue("executionStatus", result.executionStatus()).addKeyValue("reason", result.reason()).log("Trajectory finished");
                    return result;
                }
            }
        }
        throw new BusinessException(AnalysisErrorCode.LAUNCH_CANDIDATE_NOT_FOUND);
    }
}
