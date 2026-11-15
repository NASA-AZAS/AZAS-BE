package azas.domain.analysis.repository;

import azas.domain.analysis.entity.AnalysisRun;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from AnalysisRun run where run.id = :id")
    Optional<AnalysisRun> lockById(@Param("id") UUID id);

    @Query("""
            select min(result.landingWindowStartAt) from LandingOpportunityResult result
            where result.analysisRun.id = :id and result.surfaceConditionStatus = 'SATISFIED'
            """)
    Optional<Instant> findEarliestSatisfiedTouchdown(@Param("id") UUID id);

    @Modifying
    @Query(value = """
            update analysis_run set execution_status = 'FAILED', overall_result_status = 'FAILED',
                failure_code = 'ANALYSIS_TIMEOUT', diagnostic_messages = ARRAY['ANALYSIS_TIMEOUT'], execution_finished_at = :now
            where execution_expires_at <= :now and execution_status in ('PENDING', 'RUNNING')
            """, nativeQuery = true)
    int expire(@Param("now") Instant now);
}
