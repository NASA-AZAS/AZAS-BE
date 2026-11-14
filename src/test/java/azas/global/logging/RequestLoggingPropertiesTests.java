package azas.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RequestLoggingPropertiesTests {

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveSlowThresholdPreventsStartup(long threshold) {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("azas.request-logging.slow-request-ms=" + threshold)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RequestLoggingProperties.class)
    static class PropertiesConfiguration {
    }
}
