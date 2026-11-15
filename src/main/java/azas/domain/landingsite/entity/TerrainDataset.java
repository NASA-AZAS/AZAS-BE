package azas.domain.landingsite.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "terrain_dataset")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TerrainDataset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "terrain_dataset_id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "dataset_code", nullable = false, length = 100)
    private String datasetCode;

    @Column(name = "dataset_version", nullable = false, length = 100)
    private String datasetVersion;

    @Column(name = "dataset_sha256", nullable = false, length = 64)
    private String datasetSha256;

    @Column(name = "coordinate_reference_frame", nullable = false, length = 100)
    private String coordinateReferenceFrame;

    @Column(name = "reference_sphere_radius_meters", nullable = false)
    private Integer referenceSphereRadiusMeters;

    @Column(name = "distant_terrain_verified", nullable = false)
    private boolean distantTerrainVerified;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_metadata", nullable = false, columnDefinition = "jsonb")
    private String sourceMetadata;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
