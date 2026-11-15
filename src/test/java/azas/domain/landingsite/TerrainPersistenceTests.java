package azas.domain.landingsite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import azas.domain.landingsite.importer.LandingScreenDatasetV1;
import azas.domain.landingsite.importer.LandingScreenV1Importer;
import azas.domain.landingsite.repository.HorizonSampleRepository;
import azas.domain.landingsite.repository.LandingSiteRepository;
import azas.domain.landingsite.repository.TerrainDatasetRepository;
import azas.support.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class TerrainPersistenceTests extends PostgresIntegrationTest {

    @Autowired TerrainDatasetRepository datasets;
    @Autowired LandingSiteRepository sites;
    @Autowired HorizonSampleRepository samples;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired Flyway flyway;

    @Test
    void freshDatabaseHasTheCompleteVersionedDataset() {
        var dataset = datasets.findByDatasetCodeAndDatasetVersion(
                LandingScreenDatasetV1.CODE, LandingScreenDatasetV1.VERSION).orElseThrow();
        assertThat(datasets.count()).isEqualTo(1);
        assertThat(sites.findByTerrainDatasetIdOrderBySiteCode(dataset.getId())).hasSize(27);
        assertThat(samples.count()).isEqualTo(97200);
        assertThat(dataset.getCoordinateReferenceFrame()).isEqualTo("MOON_ME_DE421_PLANETOCENTRIC_EAST");
        assertThat(dataset.getReferenceSphereRadiusMeters()).isEqualTo(1737400);
        assertThat(dataset.isDistantTerrainVerified()).isFalse();
        assertThat(dataset.getSourceMetadata()).contains("qualityDescription", "Finite NoData sentinels");
        assertThat(jdbc.queryForObject("""
                select count(*) from (select landing_site_id from horizon_sample
                group by landing_site_id having count(*) = 3600) complete_sites
                """, Integer.class)).isEqualTo(27);
    }

    @Test
    void repositoryReadsOrderedSamplesAndPreservesSite01ScientificValues() {
        var dataset = datasets.findByDatasetCodeAndDatasetVersion(
                LandingScreenDatasetV1.CODE, LandingScreenDatasetV1.VERSION).orElseThrow();
        var site = sites.findByTerrainDatasetIdAndSiteCode(dataset.getId(), "Site01").orElseThrow();
        assertThat(site.getLongitudeDegrees()).isEqualTo(-129.9644390);
        assertThat(site.getLatitudeDegrees()).isEqualTo(-89.6307206);
        assertThat(site.getSurfaceElevationMeters()).isEqualTo(863.783);
        assertThat(site.getObserverHeightAboveSurfaceMeters()).isEqualTo(1);
        var profile = samples.findByIdLandingSiteIdOrderByIdAzimuthIndex(site.getId());
        assertThat(profile).hasSize(3600);
        assertThat(profile.getFirst().getId().getAzimuthIndex()).isZero();
        assertThat(profile.getLast().getAzimuthDegrees()).isEqualTo(359.9);
        assertThat(profile.get(2200).getTerrainHorizonDegrees()).isEqualTo(8.1972760);
        assertThat(profile.get(2200).getBlockingTerrainHorizontalDistanceMeters()).isEqualTo(5730.6);
        assertThat(profile.get(2200).getSearchedSurfaceArcDistanceMeters()).isEqualTo(311785);
        assertThat(jdbc.queryForObject("select count(*) from horizon_sample where terrain_horizon_degrees < 0", Integer.class))
                .isEqualTo(11341);
    }

    @Test
    void repeatingFlywayAndTheSameImporterDoesNotDuplicateRows() throws Exception {
        var input = LandingScreenDatasetV1.load();
        transactions.executeWithoutResult(status -> jdbc.execute((ConnectionCallback<Void>) connection -> {
            LandingScreenV1Importer.importInto(connection, input);
            return null;
        }));
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version in ('1', '2') and success", Integer.class))
                .isEqualTo(2);
        assertThat(sites.count()).isEqualTo(27);
        assertThat(samples.count()).isEqualTo(97200);
    }

    @Test
    void sameVersionWithDifferentContentIsNeverOverwritten() throws Exception {
        var original = LandingScreenDatasetV1.load();
        var changed = new LandingScreenDatasetV1.Dataset("0".repeat(64), original.sourceMetadata(), original.sites());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    LandingScreenV1Importer.importInto(connection, changed);
                    return null;
                }))).isInstanceOf(IllegalStateException.class).hasMessageContaining("different hash");
        assertThat(datasets.findByDatasetCodeAndDatasetVersion(LandingScreenDatasetV1.CODE, LandingScreenDatasetV1.VERSION)
                .orElseThrow().getDatasetSha256()).isEqualTo(original.sha256());
    }

    @Test
    void importerRejectsAutocommitToPreventPartialImports() throws Exception {
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            connection.setAutoCommit(true);
            assertThatThrownBy(() -> LandingScreenV1Importer.importInto(connection, LandingScreenDatasetV1.load()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        }
    }

    @Test
    void aFailureAfterSuccessfulBatchesRollsBackEveryImportedRow() throws Exception {
        String schema = "terrain_import_rollback_test";
        Flyway.configure().dataSource(flyway.getConfiguration().getDataSource())
                .schemas(schema).locations("classpath:db/migration").target("1").load().migrate();
        var input = LandingScreenDatasetV1.load();
        input.sites().getFirst().samples()[1000][1] = Double.NaN;

        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            connection.setSchema(schema);
            connection.setAutoCommit(false);

            try {
                assertThatThrownBy(() -> LandingScreenV1Importer.importInto(connection, input))
                        .isInstanceOf(SQLException.class);
            } finally {
                connection.rollback();
                connection.setSchema("public");
            }
        }

        for (String table : new String[] {"terrain_dataset", "landing_site", "horizon_sample"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + schema + "." + table, Integer.class)).isZero();
        }
    }

    @Test
    void databaseRejectsDuplicateGridSamplesAndNonFiniteAngles() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> jdbc.update("""
                insert into horizon_sample select * from horizon_sample limit 1
                """))).isInstanceOf(DataIntegrityViolationException.class);

        for (String value : new String[] {"NaN", "Infinity", "-Infinity"}) {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> jdbc.update("""
                    update horizon_sample set terrain_horizon_degrees = cast(? as double precision)
                    where landing_site_id = (select min(landing_site_id) from landing_site) and azimuth_index = 0
                    """, value))).isInstanceOf(DataIntegrityViolationException.class);
        }

        assertThat(samples.count()).isEqualTo(97200);
    }
}
