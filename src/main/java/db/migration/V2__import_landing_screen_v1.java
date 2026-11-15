package db.migration;

import azas.domain.landingsite.importer.LandingScreenDatasetV1;
import azas.domain.landingsite.importer.LandingScreenV1Importer;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2__import_landing_screen_v1 extends BaseJavaMigration {

    @Override
    public Integer getChecksum() {
        return LandingScreenDatasetV1.checksum();
    }

    @Override
    public void migrate(Context context) throws Exception {
        LandingScreenV1Importer.importInto(context.getConnection(), LandingScreenDatasetV1.load());
    }
}
