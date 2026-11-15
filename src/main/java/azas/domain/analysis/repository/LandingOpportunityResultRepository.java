package azas.domain.analysis.repository;

import azas.domain.analysis.entity.LandingOpportunityResult;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LandingOpportunityResultRepository extends JpaRepository<LandingOpportunityResult, Long> {
    List<LandingOpportunityResult> findByAnalysisRunIdOrderByLandingWindowStartAt(UUID id);
}
