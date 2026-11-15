package azas.domain.analysis.repository;

import azas.domain.analysis.entity.AnalysisRun;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {
}
