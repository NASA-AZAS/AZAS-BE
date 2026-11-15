package azas.domain.analysis.entity;

import azas.domain.analysis.model.CelestialBody;
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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "visibility_window")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class VisibilityWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "visibility_window_id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "daily_analysis_result_id", nullable = false)
    private DailyAnalysisResult dailyAnalysisResult;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "celestial_body", nullable = false, columnDefinition = "celestial_body")
    private CelestialBody celestialBody;

    @Column(name = "window_start_at", nullable = false)
    private Instant windowStartAt;

    @Column(name = "window_end_at", nullable = false)
    private Instant windowEndAt;

    @Column(name = "boundary_precision_seconds", nullable = false)
    private Integer boundaryPrecisionSeconds;
}
