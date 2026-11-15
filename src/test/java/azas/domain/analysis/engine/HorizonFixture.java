package azas.domain.analysis.engine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class HorizonFixture {

    private HorizonFixture() {
    }

    static List<Double> site01() throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(
                HorizonFixture.class.getResourceAsStream("/analysis/Site01_horizon.csv"), StandardCharsets.UTF_8))) {
            return reader.lines().skip(1).map(line -> Double.parseDouble(line.split(",")[1])).toList();
        }
    }
}
