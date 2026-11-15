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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "horizon_sample")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class HorizonSample {

    @EmbeddedId
    private HorizonSampleId id;

    @MapsId("landingSiteId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "landing_site_id", nullable = false)
    private LandingSite landingSite;

    @Column(name = "azimuth_degrees", nullable = false)
    private Double azimuthDegrees;

    @Column(name = "terrain_horizon_degrees", nullable = false)
    private Double terrainHorizonDegrees;

    @Column(name = "blocking_terrain_horizontal_distance_meters", nullable = false)
    private Double blockingTerrainHorizontalDistanceMeters;

    @Column(name = "searched_surface_arc_distance_meters", nullable = false)
    private Double searchedSurfaceArcDistanceMeters;
}
