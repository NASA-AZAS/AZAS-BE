package azas.domain.analysis.dto;

import azas.domain.analysis.engine.AnalysisEngine.Source;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder(toBuilder = true)
public record LaunchTrajectoryResponse(UUID analysisRunId, long landingSiteId, long launchSiteId, String launchSiteCode,
        String candidateId, String surfaceCandidateId,
        @Schema(description = "Flight-path outcome: PRELIMINARY, NO_PRELIMINARY_SOLUTION or FAILED. Null while queued or running.",
                types = {"string", "null"}, nullable = true)
        String status, String reason, String lambertStatus, boolean launchVerified,
        boolean trajectoryVerified, String model, String frame, String origin, String segment, String arrivalTarget,
        LaunchOptionsResponse.Assumptions assumptions, Instant launchUtc, Instant tliUtc, Instant lunarArrivalUtc,
        Instant proposedLandingUtc, int nominalSampleStepSeconds, Double positionResidualKm, Double tliDeltaVKmS,
        List<Sample> samples, List<Source> sourceRecords,
        @Schema(description = "Identifier for one trajectory calculation. Use it with the analysis ID to retrieve the saved result.")
        UUID trajectoryRunId,
        @Schema(description = "Progress of the calculation. Once it succeeds, check status to see whether it produced a flight path.",
                allowableValues = {"PENDING", "RUNNING", "SUCCEEDED", "FAILED"})
        String executionStatus,
        Instant createdAt, Instant startedAt, Instant finishedAt, String engineSha256) {

    @Schema(description = "One UTC sample in Earth-centred ICRF coordinates. elapsedSeconds counts from departure toward the Moon. Position and velocity are XYZ vectors in km and km/s.")
    public record Sample(Instant utc, long elapsedSeconds, double[] positionKm, double[] velocityKmS) {}
    public record Summary(UUID trajectoryRunId, String candidateId, String executionStatus, String status,
            Instant createdAt, Instant startedAt, Instant finishedAt, String reason) {}
}
