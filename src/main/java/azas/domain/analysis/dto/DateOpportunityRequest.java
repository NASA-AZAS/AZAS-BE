package azas.domain.analysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

public record DateOpportunityRequest(@NotBlank String missionDeadlineUtc, BigDecimal surfaceDurationHours) {
    public static final Instant SEARCH_START = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant ARCHIVE_END = Instant.parse("2030-01-01T00:00:00Z");

    public DateOpportunityRequest {
        if (surfaceDurationHours == null) surfaceDurationHours = BigDecimal.valueOf(48);
    }

    public Instant deadline() {
        return missionDeadlineUtc.length() == 10
                ? LocalDate.parse(missionDeadlineUtc).atTime(23, 59).toInstant(ZoneOffset.UTC)
                : Instant.parse(missionDeadlineUtc);
    }

    public long durationSeconds() {
        long minutes = surfaceDurationHours.multiply(BigDecimal.valueOf(60)).longValueExact();
        return Math.multiplyExact(minutes, 60);
    }

    @JsonIgnore
    @AssertTrue(message = "마감일은 2027~2029년 UTC 날짜 또는 초 단위 시각이어야 하며 탐색 시작보다 늦어야 합니다.")
    public boolean isValidDeadline() {
        if (missionDeadlineUtc == null || missionDeadlineUtc.isBlank()) return true;
        try {
            Instant end = deadline();
            return end.getNano() == 0 && end.isAfter(SEARCH_START) && end.isBefore(ARCHIVE_END);
        } catch (DateTimeException exception) { return false; }
    }

    @JsonIgnore
    @AssertTrue(message = "표면활동 시간은 양수이며 분 단위로 표현할 수 있어야 합니다.")
    public boolean isValidDuration() {
        try { return durationSeconds() > 0; }
        catch (ArithmeticException exception) { return false; }
    }
}
