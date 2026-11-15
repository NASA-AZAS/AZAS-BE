package azas.domain.landingsite.repository;

import azas.domain.landingsite.entity.LandingSite;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LandingSiteRepository extends JpaRepository<LandingSite, Long> {
    List<LandingSite> findByTerrainDatasetIdOrderBySiteCode(Long datasetId);
    Optional<LandingSite> findByTerrainDatasetIdAndSiteCode(Long datasetId, String siteCode);
}
