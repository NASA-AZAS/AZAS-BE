package azas.domain.analysis.dto;

import azas.domain.analysis.engine.AnalysisEngine.Source;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder(toBuilder = true)
public record LaunchTrajectoryResponse(UUID analysisRunId, long landingSiteId, long launchSiteId, String launchSiteCode,
        String candidateId, String status, String reason, String lambertStatus, boolean launchVerified,
        boolean trajectoryVerified, String model, String frame, String origin, String segment, String arrivalTarget,
        LaunchOptionsResponse.Assumptions assumptions, Instant launchUtc, Instant tliUtc, Instant lunarArrivalUtc,
        Instant proposedLandingUtc, int nominalSampleStepSeconds, Double positionResidualKm, Double tliDeltaVKmS,
        List<Sample> samples, List<Source> sourceRecords, UUID trajectoryRunId, String executionStatus,
        Instant createdAt, Instant finishedAt, String engineSha256) {

    public record Sample(Instant utc, long elapsedSeconds, double[] positionKm, double[] velocityKmS) {}
    public record Summary(UUID trajectoryRunId, String candidateId, String executionStatus, String status,
            Instant createdAt, Instant finishedAt, String reason) {}
}
