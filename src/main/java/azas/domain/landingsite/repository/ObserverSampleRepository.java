package azas.domain.landingsite.repository;

import azas.domain.landingsite.entity.ObserverSample;
import azas.domain.landingsite.entity.ObserverSampleId;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ObserverSampleRepository extends Repository<ObserverSample, ObserverSampleId> {

    @Query("""
            select sample from ObserverSample sample
            where sample.id.landingSiteId = :landingSiteId
              and sample.id.observedAt >= :startInclusive
              and sample.id.observedAt < :endExclusive
            order by sample.id.observedAt
            """)
    Slice<ObserverSample> findInPeriod(@Param("landingSiteId") long landingSiteId,
            @Param("startInclusive") Instant startInclusive, @Param("endExclusive") Instant endExclusive,
            Pageable pageable);
}
