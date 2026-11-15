package azas.support;

import java.nio.file.Path;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Test-only equivalent of importing the three files through DataGrip. */
public final class TerrainTestData {

    private TerrainTestData() {
    }

    public static void copyInto(PostgreSQLContainer postgres, String database) throws Exception {
        for (String name : new String[] {"01_terrain_dataset", "02_landing_site", "03_horizon_sample"}) {
            Path file = Path.of("data/import", name + ".csv");
            String columns;

            try (var lines = java.nio.file.Files.lines(file)) {
                columns = lines.findFirst().orElseThrow();
            }

            String table = name.substring(3);
            String target = "/tmp/" + name + ".csv";
            postgres.copyFileToContainer(MountableFile.forHostPath(file), target);
            var result = postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d", database,
                    "--set", "ON_ERROR_STOP=1", "--command",
                    "\\copy public." + table + " (" + columns + ") FROM '" + target + "' WITH (FORMAT csv, HEADER true)");

            if (result.getExitCode() != 0) {
                throw new IllegalStateException(result.getStderr());
            }
        }

        postgres.copyFileToContainer(MountableFile.forHostPath(Path.of("data/import/after-import.sql")),
                "/tmp/after-import.sql");
        var result = postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d", database,
                "--set", "ON_ERROR_STOP=1", "--file", "/tmp/after-import.sql");

        if (result.getExitCode() != 0) {
            throw new IllegalStateException(result.getStderr());
        }
    }
}
