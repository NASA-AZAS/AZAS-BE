package azas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "azas")
public record AzasProperties(String appName, String environment, String corsOrigins) {
}
