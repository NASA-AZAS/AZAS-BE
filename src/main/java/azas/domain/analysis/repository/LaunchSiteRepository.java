package azas.domain.analysis.repository;

import azas.domain.analysis.entity.LaunchSite;
import java.util.List;
import org.springframework.data.repository.Repository;

public interface LaunchSiteRepository extends Repository<LaunchSite, Long> {
    List<LaunchSite> findAllByOrderByIdAsc();
}
