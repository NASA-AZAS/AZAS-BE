package azas.domain.landingsite.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "landing_site")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LandingSite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "landing_site_id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "terrain_dataset_id", nullable = false)
    private TerrainDataset terrainDataset;

    @Column(name = "site_code", nullable = false, length = 50)
    private String siteCode;

    @Column(name = "latitude_degrees", nullable = false)
    private Double latitudeDegrees;

    @Column(name = "longitude_degrees", nullable = false)
    private Double longitudeDegrees;

    @Column(name = "surface_elevation_meters", nullable = false)
    private Double surfaceElevationMeters;

    @Column(name = "observer_height_above_surface_meters", nullable = false)
    private Double observerHeightAboveSurfaceMeters;

    @Column(name = "low_slope_square_side_meters", nullable = false)
    private Double lowSlopeSquareSideMeters;

    @Column(name = "terrain_screening_status", nullable = false, length = 30)
    private String terrainScreeningStatus;
}
