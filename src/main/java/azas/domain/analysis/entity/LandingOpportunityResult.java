package azas.domain.analysis.entity;

import azas.domain.analysis.model.OpportunityStatus;
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
@Table(name = "landing_opportunity_result")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LandingOpportunityResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "landing_opportunity_result_id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_run_id", nullable = false)
    private AnalysisRun analysisRun;

    @Column(name = "opportunity_code", nullable = false, length = 160)
    private String opportunityCode;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "opportunity_status", nullable = false, columnDefinition = "opportunity_status")
    private OpportunityStatus opportunityStatus;

    @Column(name = "surface_condition_status", nullable = false, length = 30)
    private String surfaceConditionStatus;

    @Column(name = "terrain_coverage_status", nullable = false, length = 40)
    private String terrainCoverageStatus;

    @Column(name = "landing_window_start_at", nullable = false)
    private Instant landingWindowStartAt;

    @Column(name = "landing_window_end_at", nullable = false)
    private Instant landingWindowEndAt;

    @Column(name = "proposed_landing_at", nullable = false)
    private Instant proposedLandingAt;

    @Column(name = "surface_activity_finished_at", nullable = false)
    private Instant surfaceActivityFinishedAt;

    @Column(name = "deadline_satisfied", nullable = false)
    private boolean deadlineSatisfied;

    @Column(name = "launch_at")
    private Instant launchAt;

    @Column(name = "translunar_injection_at")
    private Instant translunarInjectionAt;

    @Column(name = "lunar_arrival_at")
    private Instant lunarArrivalAt;

    @Column(name = "transfer_duration_hours")
    private Double transferDurationHours;

    @Column(name = "trajectory_solver_status", nullable = false, length = 60)
    private String trajectorySolverStatus;

    @Column(name = "arrival_position_error_kilometers")
    private Double arrivalPositionErrorKilometers;

    @Column(name = "trajectory_verified", nullable = false)
    private boolean trajectoryVerified;

    @Column(name = "calculation_model_level", nullable = false, length = 40)
    private String calculationModelLevel;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "decision_reasons", columnDefinition = "text[]")
    private String[] decisionReasons;

    @Column(name = "provenance_id", nullable = false, length = 64)
    private String provenanceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trajectory_calculation_details", columnDefinition = "jsonb")
    private String trajectoryCalculationDetails;
}
