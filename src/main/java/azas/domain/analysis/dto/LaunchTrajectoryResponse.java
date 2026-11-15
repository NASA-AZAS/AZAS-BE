package azas.domain.analysis.dto;

import azas.domain.analysis.engine.AnalysisEngine.Source;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder(toBuilder = true)
public record LaunchTrajectoryResponse(UUID analysisRunId, long landingSiteId, long launchSiteId, String launchSiteCode,
        String candidateId,
        @Schema(description = "궤적 계산의 판정(PRELIMINARY, NO_PRELIMINARY_SOLUTION, FAILED). 대기·실행 중에는 null입니다.",
                types = {"string", "null"}, nullable = true)
        String status, String reason, String lambertStatus, boolean launchVerified,
        boolean trajectoryVerified, String model, String frame, String origin, String segment, String arrivalTarget,
        LaunchOptionsResponse.Assumptions assumptions, Instant launchUtc, Instant tliUtc, Instant lunarArrivalUtc,
        Instant proposedLandingUtc, int nominalSampleStepSeconds, Double positionResidualKm, Double tliDeltaVKmS,
        List<Sample> samples, List<Source> sourceRecords,
        @Schema(description = "궤적 계산 한 건의 UUID. analysisRunId와 함께 상세 조회에 사용합니다.")
        UUID trajectoryRunId,
        @Schema(description = "궤적 작업의 상태. SUCCEEDED이면 status로 예비 궤적의 산출 여부를 확인합니다.",
                allowableValues = {"PENDING", "RUNNING", "SUCCEEDED", "FAILED"})
        String executionStatus,
        Instant createdAt, Instant startedAt, Instant finishedAt, String engineSha256) {

    @Schema(description = "UTC 시각의 지구 중심 ICRF 표본. elapsedSeconds는 TLI 이후 경과 초이며 positionKm와 velocityKmS는 각각 XYZ 순서의 km, km/s입니다.")
    public record Sample(Instant utc, long elapsedSeconds, double[] positionKm, double[] velocityKmS) {}
    public record Summary(UUID trajectoryRunId, String candidateId, String executionStatus, String status,
            Instant createdAt, Instant startedAt, Instant finishedAt, String reason) {}
}
