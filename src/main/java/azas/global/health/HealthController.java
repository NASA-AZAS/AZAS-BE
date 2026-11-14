package azas.global.health;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/health/")
    public ResponseEntity<Void> redirectHealth() {
        return ResponseEntity.status(307)
                .location(ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/health").build().toUri())
                .build();
    }
}
