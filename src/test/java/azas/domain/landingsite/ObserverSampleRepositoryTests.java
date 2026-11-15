package azas.domain.landingsite;

import static org.assertj.core.api.Assertions.assertThat;

import azas.domain.landingsite.entity.ObserverSample;
import azas.domain.landingsite.entity.ObserverSampleId;
import azas.domain.landingsite.repository.ObserverSampleRepository;
import azas.support.PostgresIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ObserverSampleRepositoryTests extends PostgresIntegrationTest {

    @Autowired ObserverSampleRepository samples;
    @Autowired JdbcTemplate jdbc;

    @Test
    void readsOnlyTheSelectedSiteAndHalfOpenPeriodInTimeOrderAcrossPages() {
        long site = siteId("Site01");
        long otherSite = siteId("DM1");
        // Insert out of order, including both boundaries and a second site at the same UTC.
        jdbc.update("""
                insert into observer_sample (landing_site_id, observed_at, sun_azimuth_degrees,
                    sun_elevation_degrees, earth_azimuth_degrees, earth_elevation_degrees) values
                (?, '2027-01-01T00:20:00Z', 32.915265, 1.323692, 137.115949, -6.528197),
                (?, '2027-01-01T00:00:00Z', 33.084224, 1.323327, 137.123011, -6.518908),
                (?, '2027-01-01T00:10:00Z', 32.999745, 1.32351, 137.119487, -6.52356),
                (?, '2027-01-01T00:00:00Z', 192.363316, -4.456822, 296.812773, -3.739614)
                """, site, site, site, otherSite);

        Instant start = Instant.parse("2027-01-01T00:00:00Z");
        Instant end = start.plusSeconds(1200);
        var first = samples.findInPeriod(site, start, end, PageRequest.of(0, 1));
        assertThat(first.hasNext()).isTrue();
        ObserverSample row = first.getContent().getFirst();
        assertThat(row.getId()).isEqualTo(new ObserverSampleId(site, start));
        assertThat(row.getSunAzimuthDegrees()).isEqualTo(33.084224);
        assertThat(row.getSunElevationDegrees()).isEqualTo(1.323327);
        assertThat(row.getEarthAzimuthDegrees()).isEqualTo(137.123011);
        assertThat(row.getEarthElevationDegrees()).isEqualTo(-6.518908);

        var next = samples.findInPeriod(site, start, end, first.nextPageable());
        assertThat(next.hasNext()).isFalse();
        assertThat(next.getContent()).extracting(ObserverSample::getId)
                .containsExactly(new ObserverSampleId(site, start.plusSeconds(600)));
        var later = samples.findInPeriod(site, start.plusSeconds(1), end, PageRequest.of(0, 10));
        assertThat(later.getContent()).extracting(ObserverSample::getId)
                .containsExactly(new ObserverSampleId(site, start.plusSeconds(600)));
        assertThat(samples.findInPeriod(site, start, start, PageRequest.of(0, 10))).isEmpty();
    }

    private long siteId(String code) {
        return jdbc.queryForObject("""
                select s.landing_site_id from landing_site s join terrain_dataset d using (terrain_dataset_id)
                where d.dataset_code = 'landing-screen' and d.dataset_version = '2026-11-14-v1' and s.site_code = ?
                """, Long.class, code);
    }
}
