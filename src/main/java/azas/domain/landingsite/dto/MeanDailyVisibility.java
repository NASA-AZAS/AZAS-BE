package azas.domain.landingsite.dto;

import java.time.LocalDate;

public record MeanDailyVisibility(LocalDate startDateUtc, LocalDate endDateUtc, int days,
        double sunMeanDailyVisiblePct, double earthMeanDailyVisiblePct) {}
