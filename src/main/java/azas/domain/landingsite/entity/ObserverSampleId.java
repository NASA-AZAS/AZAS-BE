package azas.domain.landingsite.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ObserverSampleId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "landing_site_id", nullable = false)
    private Long landingSiteId;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;
}
