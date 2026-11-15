package azas.domain.analysis.engine;

import azas.domain.analysis.dto.LaunchOptionsResponse;
import azas.domain.analysis.dto.LaunchOptionsResponse.Candidate;
import azas.domain.analysis.dto.LaunchOptionsResponse.Site;
import azas.domain.analysis.dto.LaunchTrajectoryResponse;
import azas.domain.analysis.dto.LaunchTrajectoryResponse.Sample;
import azas.domain.analysis.engine.LunarTransferOrbit.State;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LunarTrajectoryCalculator {
    private static final int STEP_SECONDS = 600;
    private static final double EARTH_RADIUS_KM = 6378.137;
    private static final double MOON_OFFSET_KM = 2000;
    private final HorizonsClient horizons;

    public LaunchTrajectoryResponse calculate(LaunchOptionsResponse options, Site site, Candidate candidate) {
        var sources = new ArrayList<AnalysisEngine.Source>(2);
        var response = LaunchTrajectoryResponse.builder()
                .analysisRunId(options.analysisRunId()).landingSiteId(options.landingSiteId())
                .launchSiteId(site.launchSiteId()).launchSiteCode(site.launchSiteCode()).candidateId(candidate.candidateId())
                .launchUtc(candidate.launchUtc()).tliUtc(candidate.tliUtc()).lunarArrivalUtc(candidate.lunarArrivalUtc())
                .proposedLandingUtc(candidate.proposedLandingUtc()).launchVerified(false).trajectoryVerified(false)
                .model("PRELIMINARY_EARTH_TWO_BODY").frame("ICRF").origin("EARTH").segment("TLI_TO_LUNAR_ARRIVAL")
                .arrivalTarget("MOON_EARTH_FACING_OFFSET_2000_KM_FROM_CENTER").assumptions(options.assumptions())
                .nominalSampleStepSeconds(STEP_SECONDS).samples(List.of());

        try {
            var earth = horizons.position(399, candidate.lunarArrivalUtc());
            sources.add(earth.source());
            var moon = horizons.position(301, candidate.lunarArrivalUtc());
            sources.add(moon.source());
            double[] moonFromEarth = LunarTransferOrbit.combine(moon.km(), -1, earth.km());
            double distance = LunarTransferOrbit.norm(moonFromEarth);
            if (!Double.isFinite(distance) || distance <= MOON_OFFSET_KM) {
                throw new AnalysisEngineException("HORIZONS_INVALID_RESPONSE", null);
            }
            double[] target = LunarTransferOrbit.scale(moonFromEarth, 1 - MOON_OFFSET_KM / distance);
            State parking = LunarTransferOrbit.propagate(parkingAtLaunch(site, candidate), options.assumptions().launchToTliSeconds());
            long transfer = Duration.between(candidate.tliUtc(), candidate.lunarArrivalUtc()).toSeconds();
            var solution = LunarTransferOrbit.solve(parking.position(), target, transfer);
            response.lambertStatus(solution.status()).positionResidualKm(solution.residualKm());
            if ("CONVERGED".equals(solution.status())) {
                var departure = new State(parking.position(), solution.departureVelocity());
                response.samples(samples(candidate, departure, transfer)).status("PRELIMINARY")
                        .tliDeltaVKmS(LunarTransferOrbit.norm(LunarTransferOrbit.combine(departure.velocity(), -1, parking.velocity())));
            } else {
                response.status("NO_PRELIMINARY_SOLUTION").reason(solution.reason());
            }
        } catch (AnalysisEngineException ex) {
            response.status("FAILED").reason(ex.code());
        } catch (ArithmeticException ex) {
            response.status("FAILED").reason("PROPAGATION_FAILED");
        }
        return response.sourceRecords(List.copyOf(sources)).build();
    }

    private static State parkingAtLaunch(Site site, Candidate candidate) {
        double latitude = Math.toRadians(site.latitudeDeg());
        double longitude = Math.toRadians((LaunchOptionCalculator.rotation(candidate.launchUtc()) + site.longitudeDegEast()) % 360);
        double inclination = Math.toRadians(LaunchOptionCalculator.ASSUMPTIONS.inclinationDeg());
        double sin = Math.sin(inclination);
        double cos = Math.cos(inclination);
        double alongPlane = Math.cos(latitude) * Math.sin(longitude) * cos + Math.sin(latitude) * sin;
        double angle = Math.atan2(alongPlane, Math.cos(latitude) * Math.cos(longitude));
        double[] radial = {Math.cos(angle), Math.sin(angle) * cos, Math.sin(angle) * sin};
        double[] tangent = {-Math.sin(angle), Math.cos(angle) * cos, Math.cos(angle) * sin};
        double radius = EARTH_RADIUS_KM + LaunchOptionCalculator.ASSUMPTIONS.parkingAltitudeKm();
        return new State(LunarTransferOrbit.scale(radial, radius),
                LunarTransferOrbit.scale(tangent, Math.sqrt(LunarTransferOrbit.EARTH_MU / radius)));
    }

    private static List<Sample> samples(Candidate candidate, State state, long transferSeconds) {
        var samples = new ArrayList<Sample>();
        long elapsed = 0;
        samples.add(new Sample(candidate.tliUtc(), elapsed, state.position(), state.velocity()));
        while (elapsed < transferSeconds) {
            long step = Math.min(STEP_SECONDS, transferSeconds - elapsed);
            state = LunarTransferOrbit.propagate(state, step);
            elapsed += step;
            samples.add(new Sample(candidate.tliUtc().plusSeconds(elapsed), elapsed, state.position(), state.velocity()));
        }
        return List.copyOf(samples);
    }
}
