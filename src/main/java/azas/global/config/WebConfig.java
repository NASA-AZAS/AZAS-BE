package azas.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final String[] corsOrigins;

    public WebConfig(AzasProperties properties, JsonMapper jsonMapper) {
        // Keep the existing AZAS_CORS_ORIGINS JSON-array format on EC2.
        corsOrigins = jsonMapper.readValue(properties.corsOrigins(), String[].class);
        if (corsOrigins == null) {
            throw new IllegalArgumentException("AZAS_CORS_ORIGINS must be a JSON array of origins");
        }
        for (String origin : corsOrigins) {
            if (origin == null || origin.isBlank()) {
                throw new IllegalArgumentException("CORS origins must not be blank");
            }
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(corsOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .allowCredentials(false);
    }

    @Bean
    OpenAPI openAPI(AzasProperties properties) {
        return new OpenAPI().info(new Info().title(properties.appName()).version("0.1.0"));
    }
}
