package azas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import azas.support.PersistenceFixture;
import azas.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class DatabaseIntegrationTests extends PostgresIntegrationTest {

    @Autowired
    EntityManager entityManager;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Flyway flyway;

    @Test
    void jpaCanPersistAndReadDataAgainstTheFlywaySchema() {
        transactions.executeWithoutResult(status -> {
            entityManager.persist(new PersistenceFixture(1L, "AZAS"));
            entityManager.flush();
            entityManager.clear();
            assertThat(entityManager.find(PersistenceFixture.class, 1L).label()).isEqualTo("AZAS");
            status.setRollbackOnly();
        });
    }

    @Test
    void appliedMigrationsAreValidatedAndNotAppliedAgain() {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '9000' and success", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aFailedMigrationRollsBackPostgresDdl() {
        Flyway invalid = Flyway.configure()
                .dataSource(flyway.getConfiguration().getDataSource())
                .schemas("failed_migration_test")
                .locations("classpath:db/invalidmigration")
                .load();
        assertThatThrownBy(invalid::migrate).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject(
                "select to_regclass('failed_migration_test.should_rollback')", String.class)).isNull();
    }
}
