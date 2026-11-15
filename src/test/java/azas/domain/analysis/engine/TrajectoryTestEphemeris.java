package azas.domain.analysis.engine;

import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TrajectoryTestEphemeris {
    private HttpServer server;

    @Bean
    @Primary
    HorizonsClient localEphemeris() throws IOException {
        byte[] earth = resource("earth-vector.json");
        byte[] moon = resource("moon-vector.json");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ephemeris", exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            byte[] body = query.contains("COMMAND='399'") ? earth : moon;
            int status = query.contains("TLIST='2027-01-18 22:10:00'") ? 200 : 503;
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        return new HorizonsClient(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ephemeris"));
    }

    private byte[] resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/analysis/" + name)) {
            return input.readAllBytes();
        }
    }

    @PreDestroy
    void stop() { server.stop(0); }
}
