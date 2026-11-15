package azas.domain.analysis.dto;

import azas.domain.landingsite.dto.MeanDailyVisibility;
import java.time.Instant;
import java.util.List;

public record PostLandingResponse(String siteId, String surfaceCandidateId, Instant touchdownUtc, Instant surfaceEndUtc, int sampleStepSeconds,
        Occultations nextTerrainOccultationUtc, MeanDailyVisibility meanDailyVisibility, String model, List<Sample> samples) {
    public record Occultations(Instant earth, Instant sun) {}
    public record Sample(Instant utc, long elapsedSeconds, Body sun, Body earth, boolean jointVisible) {}
    public record Body(double azimuthDeg, double elevationDeg, double terrainHorizonDeg, boolean visible) {}
}
