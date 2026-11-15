package azas.domain.landingsite.repository;

import azas.domain.landingsite.entity.TerrainDataset;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TerrainDatasetRepository extends JpaRepository<TerrainDataset, Long> {
    Optional<TerrainDataset> findByDatasetCodeAndDatasetVersion(String code, String version);
}
