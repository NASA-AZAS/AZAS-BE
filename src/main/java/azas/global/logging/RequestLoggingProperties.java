package azas.global.logging;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "azas.request-logging")
public record RequestLoggingProperties(@Min(1) long slowRequestMs) {
}
