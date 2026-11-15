package azas.domain.analysis.engine;

import azas.domain.analysis.dto.LaunchOptionsResponse.Assumptions;
import azas.domain.analysis.dto.LaunchOptionsResponse.Candidate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** LLSN ver2 launch-options, using the example fixed Earth parking plane. */
public final class LaunchOptionCalculator {
    public static final String MODEL = "ILLUSTRATIVE_FIXED_PARKING_PLANE";
    public static final Assumptions ASSUMPTIONS = new Assumptions(51.6, 0, 300, 2 * 3600, 48 * 3600, 120 * 3600, 6 * 3600);
    private static final Instant J2000_NOON = Instant.parse("2000-01-01T12:00:00Z");
    private static final double ROTATION_AT_J2000 = 280.46061837;
    private static final double ROTATION_PER_DAY = 360.98564736629;
    private static final double SECONDS_PER_DAY = 86400;

    private LaunchOptionCalculator() {}

    public static List<Candidate> candidates(String code, double latitude, double longitudeEast,
            Instant analysisStart, Instant touchdown) {
        Instant arrival = touchdown.minusSeconds(ASSUMPTIONS.arrivalToTouchdownSeconds());
        Instant start = arrival.minusSeconds(ASSUMPTIONS.launchToTliSeconds() + ASSUMPTIONS.maxTransferSeconds());
        Instant stop = arrival.minusSeconds(ASSUMPTIONS.launchToTliSeconds() + ASSUMPTIONS.minTransferSeconds());
        if (analysisStart.isAfter(start)) {
            start = analysisStart;
        }

        double ratio = Math.tan(Math.toRadians(latitude)) / Math.tan(Math.toRadians(ASSUMPTIONS.inclinationDeg()));
        if (stop.isBefore(start) || Math.abs(ratio) > 1) {
            return List.of();
        }

        // Evaluate both plane crossings and round to seconds as in the reference CLI.
        double angle = Math.toDegrees(Math.asin(ratio));
        double firstRotation = rotation(start);
        double lastRotation = rotation(stop);
        Instant earliest = null;
        for (double target : new double[]{angle - longitudeEast, 180 - angle - longitudeEast}) {
            double firstPhase = target + 360 * Math.ceil((firstRotation - target) / 360);
            for (double phase = firstPhase; phase <= lastRotation; phase += 360) {
                long seconds = Math.round((phase - ROTATION_AT_J2000) * SECONDS_PER_DAY / ROTATION_PER_DAY);
                Instant crossing = J2000_NOON.plusSeconds(seconds);
                if (!crossing.isBefore(start) && !crossing.isAfter(stop)) {
                    if (earliest == null || crossing.isBefore(earliest)) {
                        earliest = crossing;
                    }
                    break;
                }
            }
        }
        if (earliest == null) {
            return List.of();
        }
        return List.of(new Candidate(code + "-" + earliest.getEpochSecond(), "GEOMETRIC_ONLY", earliest,
                earliest.plusSeconds(ASSUMPTIONS.launchToTliSeconds()), arrival, touchdown));
    }

    static double rotation(Instant instant) {
        return ROTATION_AT_J2000 + ROTATION_PER_DAY * Duration.between(J2000_NOON, instant).toSeconds() / SECONDS_PER_DAY;
    }
}
