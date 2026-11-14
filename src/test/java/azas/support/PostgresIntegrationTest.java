package azas.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = createDatabase();

    private static PostgreSQLContainer createDatabase() {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(Path.of("deploy/postgres.env"))) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load the pinned PostgreSQL image", exception);
        }
        DockerImageName image = DockerImageName.parse(properties.getProperty("POSTGRES_IMAGE"))
                .asCompatibleSubstituteFor("postgres");
        PostgreSQLContainer postgres = new PostgreSQLContainer(image);
        postgres.withDatabaseName("azas_test").withUsername("azas_test").withPassword("test_only_password");
        // A single container per test JVM; Testcontainers cleans it up at JVM exit.
        postgres.start();
        return postgres;
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/testmigration");
    }
}
