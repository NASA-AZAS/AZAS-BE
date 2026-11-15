package azas.domain.landingsite;

import static org.assertj.core.api.Assertions.assertThat;

import azas.support.PostgresIntegrationTest;
import azas.support.TerrainTestData;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class TerrainSchemaMigrationTests extends PostgresIntegrationTest {

    @Test
    void freshDatabaseStartsEmptyAndAcceptsTheDataGripFiles() throws Exception {
        String database = createDatabase();
        Flyway flyway = flyway(database);
        flyway.migrate();

        try (Connection connection = connect(database)) {
            for (String table : new String[] {"terrain_dataset", "landing_site", "horizon_sample", "observer_sample", "launch_site"}) {
                assertThat(count(connection, table)).isZero();
            }
        }

        TerrainTestData.copyInto(POSTGRES, database);

        try (Connection connection = connect(database)) {
            assertThat(count(connection, "terrain_dataset")).isEqualTo(1);
            assertThat(count(connection, "landing_site")).isEqualTo(27);
            assertThat(count(connection, "horizon_sample")).isEqualTo(97200);

            try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                    select nextval(pg_get_serial_sequence('landing_site', 'landing_site_id'))
                    """)) {
                result.next();
                assertThat(result.getLong(1)).isEqualTo(28);
            }
        }

        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void existingV2ChecksumAndDataRemainValidWithoutReimporting() throws Exception {
        String database = createDatabase();
        Flyway.configure().dataSource(url(database), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("2").load().migrate();
        TerrainTestData.copyInto(POSTGRES, database);

        try (Connection connection = connect(database); var statement = connection.createStatement()) {
            // Existing installations recorded this exact JDBC migration checksum.
            try (var result = statement.executeQuery("select checksum, type from flyway_schema_history where version = '2'")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(2091428245);
                assertThat(result.getString(2)).isEqualTo("JDBC");
            }

            statement.executeUpdate("update landing_site set low_slope_square_side_meters = 123.45 where landing_site_id = 1");
            statement.executeUpdate("""
                    insert into analysis_run (analysis_run_id, landing_site_id, analysis_type, execution_status,
                    analysis_start_at, analysis_end_at, sample_interval_minutes, analysis_parameters,
                    calculation_parameters_sha256, engine_version, engine_sha256)
                    values ('00000000-0000-0000-0000-000000000001', 1, 'VISIBILITY', 'SUCCEEDED',
                    '2026-12-01T00:00:00Z', '2026-12-02T00:00:00Z', 60, '{}', repeat('a', 64), 'original', repeat('b', 64))
                    """);
        }

        Flyway current = flyway(database);
        assertThat(current.migrate().migrationsExecuted).isEqualTo(3); // V3 lease, V4 observations, V5 launch sites
        assertThat(current.validateWithResult().validationSuccessful).isTrue();
        assertThat(current.migrate().migrationsExecuted).isZero();

        try (Connection connection = connect(database); var statement = connection.createStatement()) {
            assertThat(count(connection, "analysis_run")).isEqualTo(1);
            assertThat(count(connection, "observer_sample")).isZero();
            assertThat(count(connection, "launch_site")).isZero();
            assertThat(count(connection, "landing_site")).isEqualTo(27);
            assertThat(count(connection, "horizon_sample")).isEqualTo(97200);

            try (var result = statement.executeQuery("select low_slope_square_side_meters from landing_site where landing_site_id = 1")) {
                result.next();
                assertThat(result.getDouble(1)).isEqualTo(123.45);
            }
        }
    }

    private String createDatabase() throws Exception {
        String database = "terrain_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = connect(POSTGRES.getDatabaseName()); var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }

        return database;
    }

    private Flyway flyway(String database) {
        return Flyway.configure().dataSource(url(database), POSTGRES.getUsername(), POSTGRES.getPassword()).load();
    }

    private Connection connect(String database) throws Exception {
        return DriverManager.getConnection(url(database), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String url(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    private long count(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("select count(*) from " + table)) {
            result.next();
            return result.getLong(1);
        }
    }
}
