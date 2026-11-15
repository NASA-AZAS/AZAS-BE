package azas.domain.analysis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "launch_site")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LaunchSite {
    @Id
    @Column(name = "launch_site_id")
    private Long id;

    @Column(name = "site_code", nullable = false, length = 50)
    private String siteCode;

    @Column(name = "latitude_degrees", nullable = false)
    private double latitudeDegrees;

    @Column(name = "longitude_degrees_east", nullable = false)
    private double longitudeDegreesEast;
}
