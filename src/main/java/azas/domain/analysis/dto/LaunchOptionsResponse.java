package azas.domain.analysis.dto;

import azas.domain.analysis.model.ExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LaunchOptionsResponse(UUID analysisRunId, long landingSiteId, ExecutionStatus executionStatus,
        Instant touchdownUtc, String model, boolean launchVerified, Assumptions assumptions, List<Site> launchSites,
        Instant calculatedAt, String engineSha256, String surfaceCandidateId) {

    public LaunchOptionsResponse(UUID id, long siteId, ExecutionStatus state, Instant touchdown, String model,
            boolean verified, Assumptions assumptions, List<Site> sites) {
        this(id, siteId, state, touchdown, model, verified, assumptions, sites, null, null, null);
    }

    public record Assumptions(double inclinationDeg, int raanDeg, int parkingAltitudeKm, long launchToTliSeconds,
            long minTransferSeconds, long maxTransferSeconds, long arrivalToTouchdownSeconds) {}

    public record Site(long launchSiteId, String launchSiteCode, double latitudeDeg, double longitudeDegEast,
            List<Candidate> candidates) {}

    public record Candidate(String candidateId, String status, Instant launchUtc, Instant tliUtc,
            Instant lunarArrivalUtc, Instant proposedLandingUtc) {}
}
