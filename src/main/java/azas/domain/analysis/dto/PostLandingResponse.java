package azas.domain.analysis.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PostLandingResponse(String siteId, Instant touchdownUtc, Instant surfaceEndUtc, int sampleStepSeconds,
        Occultations nextTerrainOccultationUtc, MeanDailyVisibility meanDailyVisibility, String model, List<Sample> samples) {
    public record Occultations(Instant earth, Instant sun) {}
    public record MeanDailyVisibility(LocalDate startDateUtc, LocalDate endDateUtc, int days,
            double sunMeanDailyVisiblePct, double earthMeanDailyVisiblePct) {}
    public record Sample(Instant utc, long elapsedSeconds, Body sun, Body earth, boolean jointVisible) {}
    public record Body(double azimuthDeg, double elevationDeg, double terrainHorizonDeg, boolean visible) {}
}
