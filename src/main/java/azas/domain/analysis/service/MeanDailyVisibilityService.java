package azas.domain.analysis.service;

import static azas.domain.analysis.engine.DateOpportunityEngine.STEP_SECONDS;
import static azas.domain.analysis.engine.DateOpportunityEngine.terrainHorizonDegrees;

import azas.domain.analysis.repository.ObserverArchiveReader;
import azas.domain.landingsite.dto.MeanDailyVisibility;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MeanDailyVisibilityService {
    private static final LocalDate MEAN_START = LocalDate.of(2027, 1, 1);
    private static final LocalDate MEAN_END = LocalDate.of(2028, 12, 31);
    private static final int DAYS = (int) ChronoUnit.DAYS.between(MEAN_START, MEAN_END) + 1;
    private static final int DAY_SECONDS = 86400;
    private final LandingSiteHorizon horizons;
    private final ObserverArchiveReader archive;

    public MeanDailyVisibility atReferenceHeight(long siteId) {
        return calculate(siteId, horizons.elevations(siteId, 0));
    }

    MeanDailyVisibility calculate(long siteId, List<Double> horizon) {
        long first = MEAN_START.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        long deadline = MEAN_END.atTime(23, 59).toEpochSecond(ZoneOffset.UTC);
        int[] sunSeconds = new int[DAYS], earthSeconds = new int[DAYS];
        archive.scan(siteId, first, deadline - Math.floorMod(deadline, STEP_SECONDS), (utc, sunAz, sunEl, earthAz, earthEl) -> {
            int day = (int) ((utc - first) / DAY_SECONDS);
            int seconds = (int) Math.min(STEP_SECONDS, deadline - utc);
            if (sunEl > terrainHorizonDegrees(horizon, sunAz)) sunSeconds[day] += seconds;
            if (earthEl > terrainHorizonDegrees(horizon, earthAz)) earthSeconds[day] += seconds;
            return true;
        });
        return new MeanDailyVisibility(MEAN_START, MEAN_END, DAYS, meanPercent(sunSeconds), meanPercent(earthSeconds));
    }

    private static double meanPercent(int[] visibleSeconds) {
        double sum = 0;
        for (int i = 0; i < DAYS; i++) {
            // The reference deadline is 23:59 UTC: its final day contributes 86,340 evaluated seconds.
            int evaluated = i == DAYS - 1 ? DAY_SECONDS - 60 : DAY_SECONDS;
            sum += (double) visibleSeconds[i] / evaluated;
        }
        return Math.round(sum / DAYS * 100 * 1_000_000) / 1_000_000.0;
    }
}
