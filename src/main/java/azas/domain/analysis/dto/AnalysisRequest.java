package azas.domain.analysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public record AnalysisRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate) {
    @JsonIgnore
    @AssertTrue(message = "기간은 시작일~종료일 포함 1~31일이며 유효한 UTC 날짜여야 합니다.")
    public boolean isValidPeriod() {
        return startDate == null || endDate == null || (startDate.getYear() > 0 && endDate.getYear() < 9999
                && ChronoUnit.DAYS.between(startDate, endDate) >= 0 && ChronoUnit.DAYS.between(startDate, endDate) < 31);
    }

    public Instant start() { return startDate.atStartOfDay().toInstant(ZoneOffset.UTC); }
    public Instant endExclusive() { return endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC); }
}
