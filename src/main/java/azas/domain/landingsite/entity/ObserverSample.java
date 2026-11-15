package azas.domain.landingsite.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "observer_sample")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObserverSample {

    @EmbeddedId
    private ObserverSampleId id;

    @MapsId("landingSiteId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "landing_site_id", nullable = false)
    private LandingSite landingSite;

    @Column(name = "sun_azimuth_degrees", nullable = false)
    private double sunAzimuthDegrees;

    @Column(name = "sun_elevation_degrees", nullable = false)
    private double sunElevationDegrees;

    @Column(name = "earth_azimuth_degrees", nullable = false)
    private double earthAzimuthDegrees;

    @Column(name = "earth_elevation_degrees", nullable = false)
    private double earthElevationDegrees;
}
