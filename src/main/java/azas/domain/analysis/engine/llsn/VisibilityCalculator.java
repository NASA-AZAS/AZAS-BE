package azas.domain.analysis.engine.llsn;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** LLSN Terrain.Profile.body / Analysis.scenario semantics, with UTC daily interval aggregation. */
public final class VisibilityCalculator {
    public enum State { VISIBLE, OCCULTED, UNKNOWN }
    public enum Body { SUN, EARTH, JOINT }
    public record Angle(double azimuth, double elevation) {
        public Angle {
            if (!Double.isFinite(azimuth) || azimuth < 0 || azimuth > 360
                    || !Double.isFinite(elevation) || Math.abs(elevation) > 90) {
                throw new IllegalArgumentException("Invalid body angle");
            }

            azimuth %= 360;
        }
    }

    public record Horizon(List<Double> elevations) {
        public Horizon {
            elevations = List.copyOf(elevations);

            if (elevations.size() != 3600 || elevations.stream().anyMatch(
                    value -> !Double.isFinite(value) || Math.abs(value) > 90)) {
                throw new IllegalArgumentException("Invalid 0.1 degree horizon");
            }
        }
    }

    public record Epoch(Instant time, Angle sun, Angle earth) {}
    public record Observation(double horizon, double clearance, State state) {}
    public record Metrics(Double visibleRatio, double knownRatio, Double longestOccultationHours) {}
    public record Window(Body body, Instant start, Instant end, int precisionSeconds) {}
    public record Day(LocalDate date, Instant start, Instant end, Metrics sun, Metrics earth,
                      Metrics joint, List<Window> windows) {
        public Day { windows = List.copyOf(windows); }
    }

    public Observation evaluate(Horizon profile, Angle angle) {
        double position = angle.azimuth() / 0.1;
        int index = Math.min(3599, (int) position);
        double a = profile.elevations().get(index), b = profile.elevations().get((index + 1) % 3600);
        double horizon = position == index ? a : a + (b - a) * (position - index);
        double clearance = angle.elevation() - horizon;
        // Preserve upstream's conservative result above unverified distant terrain.
        return new Observation(horizon, clearance, clearance > 0 ? State.UNKNOWN : State.OCCULTED);
    }

    public List<Day> analyze(Horizon profile, List<Epoch> epochs, int stepSeconds) {
        epochs = List.copyOf(epochs);

        if (epochs.size() < 2 || epochs.size() > 10001 || stepSeconds < 60 || stepSeconds % 60 != 0) {
            throw new IllegalArgumentException("Invalid analysis grid");
        }

        for (int i = 1; i < epochs.size(); i++) {
            if (!epochs.get(i).time().equals(epochs.get(i - 1).time().plusSeconds(stepSeconds))) {
                throw new IllegalArgumentException("Nonuniform analysis epochs");
            }
        }

        List<Day> days = new ArrayList<>();
        Accumulator day = null;

        for (int i = 0; i < epochs.size() - 1; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Analysis interrupted");
            }

            Epoch epoch = epochs.get(i);
            State sun = evaluate(profile, epoch.sun()).state(), earth = evaluate(profile, epoch.earth()).state();
            State joint = sun == State.OCCULTED || earth == State.OCCULTED ? State.OCCULTED : State.UNKNOWN;
            Instant cursor = epoch.time(), end = epochs.get(i + 1).time();

            while (cursor.isBefore(end)) {
                LocalDate date = cursor.atOffset(ZoneOffset.UTC).toLocalDate();

                if (day == null || !day.date.equals(date)) {
                    if (day != null) days.add(day.finish(stepSeconds));
                    day = new Accumulator(date, cursor);
                }

                Instant midnight = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                Instant until = end.isBefore(midnight) ? end : midnight;
                day.add(cursor, until, new State[]{sun, earth, joint});
                cursor = until;
            }
        }

        days.add(day.finish(stepSeconds));

        return List.copyOf(days);
    }

    private static final class Accumulator {
        final LocalDate date;
        final Instant start;
        Instant end;
        final double[] known = new double[3], visible = new double[3], dark = new double[3], longest = new double[3];
        final Instant[] open = new Instant[3];
        final List<Window> windows = new ArrayList<>();
        Accumulator(LocalDate date, Instant start) { this.date = date; this.start = start; }
        void add(Instant from, Instant until, State[] states) {
            double duration = Duration.between(from, until).toNanos() / 1e9;

            for (int body = 0; body < 3; body++) {
                State state = states[body];

                if (state != State.UNKNOWN) known[body] += duration;

                if (state == State.VISIBLE) {
                    visible[body] += duration;

                    if (open[body] == null) open[body] = from;
                } else if (open[body] != null) {
                    windows.add(new Window(Body.values()[body], open[body], from, 0));
                    open[body] = null;
                }

                dark[body] = state == State.OCCULTED ? dark[body] + duration : 0;
                longest[body] = Math.max(longest[body], dark[body]);
            }

            end = until;
        }

        Metrics metrics(int body) {
            double total = Duration.between(start, end).toNanos() / 1e9;

            return new Metrics(known[body] == 0 ? null : visible[body] / known[body], known[body] / total,
                    known[body] == 0 ? null : longest[body] / 3600.0);
        }

        Day finish(int precision) {
            for (int body = 0; body < 3; body++) {
                if (open[body] != null) windows.add(new Window(Body.values()[body], open[body], end, precision));
            }

            List<Window> precise = windows.stream().map(window -> new Window(
                    window.body(), window.start(), window.end(), precision)).toList();

            return new Day(date, start, end, metrics(0), metrics(1), metrics(2), precise);
        }
    }
}
