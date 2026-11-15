package azas.domain.analysis.repository;

import azas.domain.analysis.entity.DailyAnalysisResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyAnalysisResultRepository extends JpaRepository<DailyAnalysisResult, Long> {
    List<DailyAnalysisResult> findByAnalysisRunIdOrderByAnalysisDate(UUID analysisRunId);
    Optional<DailyAnalysisResult> findByAnalysisRunIdAndAnalysisDate(UUID analysisRunId, LocalDate analysisDate);
}
