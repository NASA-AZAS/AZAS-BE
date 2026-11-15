package azas.domain.analysis.engine;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** The single mapping point to change when the analysis implementation or return shape changes. */
@Component
public class LlsnAnalysisAdapter implements AnalysisEngine {
    private final HorizonsClient horizons;
    private final VisibilityCalculator calculator = new VisibilityCalculator();
    private final Identity identity;

    public LlsnAnalysisAdapter(HorizonsClient horizons) {
        this.horizons = horizons;
        this.identity = new Identity("llsn-visibility-1",
                EngineFingerprint.of(VisibilityCalculator.class, LlsnAnalysisAdapter.class, HorizonsClient.class, AnalysisEngine.class));
    }

    @Override public Identity identity() { return identity; }

    @Override public Output analyze(Input input) {
        if (!input.frame().equals("MOON_ME_DE421_PLANETOCENTRIC_EAST") || input.sphereRadius() != 1737400
                || input.distantTerrainVerified() || input.observerHeight() != 1) {
            throw new AnalysisEngineException("METADATA_INCOMPLETE", null);
        }

        var profile = new VisibilityCalculator.Horizon(input.horizon());
        var sun = horizons.angles(10, input);
        var earth = horizons.angles(399, input);

        if (sun.angles().size() != earth.angles().size()) {
            throw new AnalysisEngineException("HORIZONS_INVALID_RESPONSE", null);
        }

        List<VisibilityCalculator.Epoch> epochs = new ArrayList<>();

        for (int i = 0; i < sun.angles().size(); i++) {
            var a = sun.angles().get(i);
            var b = earth.angles().get(i);
            epochs.add(new VisibilityCalculator.Epoch(input.start().plusSeconds((long) i * input.stepSeconds()),
                    new VisibilityCalculator.Angle(a.azimuth(), a.elevation()),
                    new VisibilityCalculator.Angle(b.azimuth(), b.elevation())));
        }

        var days = calculator.analyze(profile, epochs, input.stepSeconds()).stream().map(Day::localHorizon).toList();

        return new Output("PARTIAL", days, List.of(sun.source(), earth.source()),
                List.of("LOCAL_HORIZON_ONLY", "DISTANT_TERRAIN_NOT_EVALUATED", "BODY_CENTER_AIRLESS"));
    }
}
