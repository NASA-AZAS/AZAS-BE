package azas.domain.landingsite.importer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** JDBC batches keep the 97,200 samples out of the JPA persistence context. */
public final class LandingScreenV1Importer {

    private static final int BATCH_SIZE = 500;

    private LandingScreenV1Importer() {
    }

    public static void importInto(Connection connection, LandingScreenDatasetV1.Dataset dataset) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new IllegalStateException("Terrain import requires a caller-owned transaction");
        }
        Long existingId = existingDataset(connection, dataset.sha256());
        if (existingId != null) {
            verifyCounts(connection, existingId);
            return;
        }

        long datasetId;
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into terrain_dataset
                    (dataset_code, dataset_version, dataset_sha256, coordinate_reference_frame,
                     reference_sphere_radius_meters, distant_terrain_verified, source_metadata)
                values (?, ?, ?, 'MOON_ME_DE421_PLANETOCENTRIC_EAST', 1737400, false, cast(? as jsonb))
                returning terrain_dataset_id
                """)) {
            statement.setString(1, LandingScreenDatasetV1.CODE);
            statement.setString(2, LandingScreenDatasetV1.VERSION);
            statement.setString(3, dataset.sha256());
            statement.setString(4, dataset.sourceMetadata());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                datasetId = result.getLong(1);
            }
        }

        try (PreparedStatement site = connection.prepareStatement("""
                insert into landing_site
                    (terrain_dataset_id, site_code, latitude_degrees, longitude_degrees, surface_elevation_meters,
                     observer_height_above_surface_meters, low_slope_square_side_meters, terrain_screening_status)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning landing_site_id
                """);
                PreparedStatement sample = connection.prepareStatement("""
                insert into horizon_sample
                    (landing_site_id, azimuth_index, azimuth_degrees, terrain_horizon_degrees,
                     blocking_terrain_horizontal_distance_meters, searched_surface_arc_distance_meters)
                values (?, ?, ?, ?, ?, ?)
                """)) {
            int pending = 0;
            for (LandingScreenDatasetV1.Site input : dataset.sites()) {
                site.setLong(1, datasetId);
                site.setString(2, input.code());
                site.setDouble(3, input.latitude());
                site.setDouble(4, input.longitude());
                site.setDouble(5, input.elevation());
                site.setDouble(6, input.observerHeight());
                site.setDouble(7, input.squareSide());
                site.setString(8, input.screeningStatus());
                long siteId;
                try (ResultSet result = site.executeQuery()) {
                    result.next();
                    siteId = result.getLong(1);
                }
                for (int index = 0; index < input.samples().length; index++) {
                    sample.setLong(1, siteId);
                    sample.setShort(2, (short) index);
                    for (int column = 0; column < 4; column++) {
                        sample.setDouble(column + 3, input.samples()[index][column]);
                    }
                    sample.addBatch();
                    if (++pending == BATCH_SIZE) {
                        sample.executeBatch();
                        pending = 0;
                    }
                }
            }
            if (pending > 0) {
                sample.executeBatch();
            }
        }
        verifyCounts(connection, datasetId);
    }

    private static Long existingDataset(Connection connection, String expectedHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select terrain_dataset_id, dataset_sha256 from terrain_dataset
                where dataset_code = ? and dataset_version = ?
                """)) {
            statement.setString(1, LandingScreenDatasetV1.CODE);
            statement.setString(2, LandingScreenDatasetV1.VERSION);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                if (!expectedHash.equals(result.getString(2))) {
                    throw new IllegalStateException("Existing terrain version has a different hash; do not overwrite it");
                }
                return result.getLong(1);
            }
        }
    }

    private static void verifyCounts(Connection connection, long datasetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select s.landing_site_id, count(h.azimuth_index)
                from landing_site s left join horizon_sample h using (landing_site_id)
                where s.terrain_dataset_id = ? group by s.landing_site_id
                """)) {
            statement.setLong(1, datasetId);
            int sites = 0;
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    sites++;
                    if (result.getInt(2) != LandingScreenDatasetV1.SAMPLES_PER_SITE) {
                        throw new IllegalStateException("Incomplete existing horizon samples");
                    }
                }
            }
            if (sites != LandingScreenDatasetV1.SITE_COUNT) {
                throw new IllegalStateException("Incomplete existing candidate sites");
            }
        }
    }
}
