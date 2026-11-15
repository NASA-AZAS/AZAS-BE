package azas.domain.analysis.repository;

import azas.domain.analysis.entity.VisibilityWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface VisibilityWindowRepository extends JpaRepository<VisibilityWindow, Long> {
    List<VisibilityWindow> findByDailyAnalysisResultAnalysisRunIdOrderByWindowStartAt(UUID runId);
}
