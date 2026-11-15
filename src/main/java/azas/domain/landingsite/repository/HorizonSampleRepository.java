package azas.domain.landingsite.repository;

import azas.domain.landingsite.entity.HorizonSample;
import azas.domain.landingsite.entity.HorizonSampleId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HorizonSampleRepository extends JpaRepository<HorizonSample, HorizonSampleId> {
    List<HorizonSample> findByIdLandingSiteIdOrderByIdAzimuthIndex(Long siteId);
}
