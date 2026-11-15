package azas.domain.analysis.entity;

import azas.domain.analysis.model.AnalysisType;
import azas.domain.analysis.model.ExecutionStatus;
import azas.domain.analysis.model.ResultStatus;
import azas.domain.landingsite.entity.LandingSite;
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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analysis_run")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AnalysisRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "analysis_run_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "landing_site_id", nullable = false)
    private LandingSite landingSite;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_type", nullable = false, columnDefinition = "analysis_type")
    private AnalysisType analysisType;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "execution_status", nullable = false, columnDefinition = "execution_status")
    private ExecutionStatus executionStatus = ExecutionStatus.PENDING;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "overall_result_status", columnDefinition = "result_status")
    private ResultStatus overallResultStatus;

    @Column(name = "result_schema_version", length = 30)
    private String resultSchemaVersion;

    @Column(name = "analysis_start_at", nullable = false)
    private Instant analysisStartAt;

    @Column(name = "analysis_end_at", nullable = false)
    private Instant analysisEndAt;

    @Column(name = "sample_interval_minutes", nullable = false)
    private Integer sampleIntervalMinutes;

    @Column(name = "mission_due_date")
    private LocalDate missionDueDate;

    @Column(name = "mission_deadline_at")
    private Instant missionDeadlineAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_parameters", nullable = false, columnDefinition = "jsonb")
    private String analysisParameters;

    @Column(name = "calculation_parameters_sha256", nullable = false, length = 64)
    private String calculationParametersSha256;

    @Column(name = "engine_version", nullable = false, length = 100)
    private String engineVersion;

    @Column(name = "engine_sha256", nullable = false, length = 64)
    private String engineSha256;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "diagnostic_messages", columnDefinition = "text[]")
    private String[] diagnosticMessages;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_provenance", columnDefinition = "jsonb")
    private String sourceProvenance;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "execution_started_at")
    private Instant executionStartedAt;

    @Column(name = "execution_finished_at")
    private Instant executionFinishedAt;
}
