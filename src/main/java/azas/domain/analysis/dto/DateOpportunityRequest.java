package azas.domain.analysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A database site selected by the URL; numerical options follow LLSN ver2. */
public record DateOpportunityRequest(@NotBlank String missionDeadlineUtc, BigDecimal surfaceDurationHours,
        SurfaceCriteria surfaceCriteria, Integer timeStepMinutes, RefinementPolicy refinementPolicy,
        Integer maxCandidates, Range transferDurationHours, Range preLandingDelayHours, Range launchToTliDelayHours,
        DepartureState departureState, ArrivalTargetRule arrivalTargetRule, Lambert lambert, NetworkMode networkMode,
        String searchStartUtc,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @Schema(description = "쿼리에서 지정한 달 표면 위 관측자 높이(m)", accessMode = Schema.AccessMode.READ_ONLY)
        Double observerHeightAboveSurfaceMeters) implements StrictAnalysisOptions {
    public static final Instant SEARCH_START = Instant.parse("2027-01-01T00:00:00Z");
    public static final Instant ARCHIVE_END = Instant.parse("2030-01-01T00:00:00Z");

    public enum Criterion { ANY, VISIBLE, OCCULTED }
    public enum UnknownPolicy { RECORD_UNKNOWN, EXCLUDE_WITH_RECORD }
    public enum NetworkMode { ONLINE, CACHE_ONLY }
    public enum Branch { SHORT_WAY, LONG_WAY }

    public DateOpportunityRequest(String deadline, BigDecimal hours) {
        this(deadline, hours, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public DateOpportunityRequest {
        if (surfaceDurationHours == null) surfaceDurationHours = BigDecimal.valueOf(48);
        if (observerHeightAboveSurfaceMeters == null) observerHeightAboveSurfaceMeters = 1.0;
        if (surfaceCriteria == null) surfaceCriteria = new SurfaceCriteria(Criterion.VISIBLE, Criterion.VISIBLE, UnknownPolicy.RECORD_UNKNOWN);
        if (timeStepMinutes == null) timeStepMinutes = 10;
        if (refinementPolicy == null) refinementPolicy = new RefinementPolicy(Math.min(10, timeStepMinutes), 0);
        if (maxCandidates == null) maxCandidates = 50;
        if (networkMode == null) networkMode = NetworkMode.ONLINE;
        if (searchStartUtc == null) searchStartUtc = SEARCH_START.toString();
    }

    public DateOpportunityRequest withSurfaceOptions(BigDecimal hours, double heightMeters) {
        return new DateOpportunityRequest(missionDeadlineUtc, hours == null ? surfaceDurationHours : hours,
                surfaceCriteria, timeStepMinutes, refinementPolicy, maxCandidates, transferDurationHours,
                preLandingDelayHours, launchToTliDelayHours, departureState, arrivalTargetRule, lambert,
                networkMode, searchStartUtc, heightMeters);
    }

    public record SurfaceCriteria(Criterion sun, Criterion earth, UnknownPolicy unknownPolicy) implements StrictAnalysisOptions {}
    public record RefinementPolicy(Integer minStepMinutes, Integer maxDepth) implements StrictAnalysisOptions {}
    public record Range(BigDecimal min, BigDecimal max, BigDecimal step) implements StrictAnalysisOptions {
        public List<Long> seconds() {
            long first = secondsOf(min), last = secondsOf(max), increment = secondsOf(step);
            if (first < 0 || last < first || increment <= 0 || (last - first) / (double) increment > 100) {
                throw new IllegalArgumentException("Invalid search range");
            }
            var result = new ArrayList<Long>();
            for (long value = first; value < last; value = Math.addExact(value, increment)) result.add(value);
            result.add(last);
            return List.copyOf(result);
        }
    }
    public record DepartureState(String epochUtc, String frame, String center, double[] positionKm,
            double[] velocityKmS, Double earthMuKm3S2) implements StrictAnalysisOptions {
        public DepartureState { positionKm = copy(positionKm); velocityKmS = copy(velocityKmS); }
        @Override public double[] positionKm() { return copy(positionKm); }
        @Override public double[] velocityKmS() { return copy(velocityKmS); }
    }
    public record ArrivalTargetRule(String type, double[] offsetKm, Double minimumAltitudeKm) implements StrictAnalysisOptions {
        public ArrivalTargetRule { offsetKm = copy(offsetKm); }
        @Override public double[] offsetKm() { return copy(offsetKm); }
    }
    public record Lambert(Branch branch, Double positionToleranceKm) implements StrictAnalysisOptions {}

    public Instant deadline() {
        return missionDeadlineUtc.length() == 10
                ? LocalDate.parse(missionDeadlineUtc).atTime(23, 59).toInstant(ZoneOffset.UTC)
                : Instant.parse(missionDeadlineUtc);
    }
    public long durationSeconds() { return secondsOf(surfaceDurationHours); }
    public boolean transfersEnabled() { return departureState != null; }

    @JsonIgnore
    @AssertTrue(message = "마감일은 2027~2029년 UTC 날짜 또는 초 단위 시각이어야 하며 탐색 시작보다 늦어야 합니다.")
    public boolean isValidDeadline() {
        if (missionDeadlineUtc == null || missionDeadlineUtc.isBlank()) return true;
        try {
            Instant end = deadline();
            return end.getNano() == 0 && end.isAfter(SEARCH_START) && end.isBefore(ARCHIVE_END)
                    && SEARCH_START.equals(Instant.parse(searchStartUtc));
        } catch (RuntimeException exception) { return false; }
    }

    @JsonIgnore
    @AssertTrue(message = "표면활동 시간은 양수이며 분 단위로 표현할 수 있어야 합니다.")
    public boolean isValidDuration() {
        try { return durationSeconds() > 0; } catch (RuntimeException exception) { return false; }
    }

    @JsonIgnore
    @AssertTrue(message = "분석 옵션의 범위·단위·전이 입력 조합을 확인해 주세요.")
    public boolean isValidOptions() {
        try {
            if (surfaceCriteria.sun() == null || surfaceCriteria.earth() == null || surfaceCriteria.unknownPolicy() == null
                    || timeStepMinutes < 1 || timeStepMinutes > 1440 || maxCandidates < 1 || maxCandidates > 10000
                    || refinementPolicy.minStepMinutes() == null || refinementPolicy.minStepMinutes() < 1
                    || refinementPolicy.minStepMinutes() > timeStepMinutes || refinementPolicy.maxDepth() == null
                    || refinementPolicy.maxDepth() < 0 || refinementPolicy.maxDepth() > 12) return false;
            boolean anyTransfer = departureState != null || arrivalTargetRule != null || transferDurationHours != null
                    || preLandingDelayHours != null || lambert != null;
            if (anyTransfer) {
                if (departureState == null || arrivalTargetRule == null || transferDurationHours == null
                        || preLandingDelayHours == null || lambert == null) return false;
                var d = departureState;
                if (!"ICRF".equals(d.frame()) || !"EARTH".equals(d.center()) || !vector(d.positionKm())
                        || !vector(d.velocityKmS()) || norm(d.positionKm()) <= 6378.137 || !positive(d.earthMuKm3S2())
                        || Instant.parse(d.epochUtc()).getNano() != 0
                        || Duration.between(SEARCH_START, Instant.parse(d.epochUtc())).abs().compareTo(Duration.ofDays(366)) > 0) return false;
                var a = arrivalTargetRule;
                if (!"MOON_RELATIVE_ICRF_OFFSET".equals(a.type()) || !vector(a.offsetKm())
                        || !positive(a.minimumAltitudeKm()) || norm(a.offsetKm()) <= 1737.4 + a.minimumAltitudeKm()) return false;
                if (lambert.branch() == null || !positive(lambert.positionToleranceKm()) || lambert.positionToleranceKm() > 10
                        || transferDurationHours.seconds().getFirst() <= 0) return false;
                preLandingDelayHours.seconds();
            }
            if (launchToTliDelayHours != null) {
                if (!anyTransfer) return false;
                launchToTliDelayHours.seconds();
            }
            return true;
        } catch (RuntimeException exception) { return false; }
    }

    private static long secondsOf(BigDecimal hours) { return Math.multiplyExact(hours.multiply(BigDecimal.valueOf(60)).longValueExact(), 60); }
    private static boolean positive(Double value) { return value != null && Double.isFinite(value) && value > 0; }
    private static boolean vector(double[] values) { return values != null && values.length == 3 && Arrays.stream(values).allMatch(Double::isFinite) && Double.isFinite(norm(values)); }
    private static double norm(double[] values) { return Math.sqrt(Arrays.stream(values).map(v -> v * v).sum()); }
    private static double[] copy(double[] values) { return values == null ? null : values.clone(); }
}
