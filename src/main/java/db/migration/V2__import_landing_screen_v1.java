package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2__import_landing_screen_v1 extends BaseJavaMigration {

    @Override
    public Integer getChecksum() {
        // Preserve the checksum recorded by the former CSV migration in existing databases.
        return 2091428245;
    }

    @Override
    public void migrate(Context context) {
        // Historical marker only. New databases receive data through DataGrip, not Flyway.
    }
}
