package azas.domain.analysis.entity;

import azas.domain.analysis.model.ResultStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "daily_analysis_result")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DailyAnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "daily_analysis_result_id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_run_id", nullable = false)
    private AnalysisRun analysisRun;

    @Column(name = "analysis_date", nullable = false)
    private LocalDate analysisDate;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, columnDefinition = "result_status")
    private ResultStatus resultStatus;

    @Column(name = "evaluated_period_start_at", nullable = false)
    private Instant evaluatedPeriodStartAt;

    @Column(name = "evaluated_period_end_at", nullable = false)
    private Instant evaluatedPeriodEndAt;

    @Column(name = "sun_visible_time_ratio")
    private Double sunVisibleTimeRatio;

    @Column(name = "earth_visible_time_ratio")
    private Double earthVisibleTimeRatio;

    @Column(name = "joint_visible_time_ratio")
    private Double jointVisibleTimeRatio;

    @Column(name = "sun_known_time_ratio", nullable = false)
    private Double sunKnownTimeRatio;

    @Column(name = "earth_known_time_ratio", nullable = false)
    private Double earthKnownTimeRatio;

    @Column(name = "joint_known_time_ratio", nullable = false)
    private Double jointKnownTimeRatio;

    @Column(name = "joint_unknown_time_ratio", nullable = false)
    private Double jointUnknownTimeRatio;

    @Column(name = "longest_sun_occultation_hours")
    private Double longestSunOccultationHours;

    @Column(name = "longest_earth_occultation_hours")
    private Double longestEarthOccultationHours;

    @Column(name = "terrain_coverage_status", nullable = false, length = 40)
    private String terrainCoverageStatus;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "quality_flags", columnDefinition = "text[]")
    private String[] qualityFlags;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
