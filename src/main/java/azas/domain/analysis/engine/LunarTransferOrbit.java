package azas.domain.analysis.engine;

import java.util.Arrays;

/** Earth-only two-body propagation and the short-way, zero-revolution Lambert branch from LLSN ver2. */
final class LunarTransferOrbit {
    static final double EARTH_MU = 398600.4418; // km^3/s^2
    private static final double RESIDUAL_LIMIT_KM = 0.01;

    record State(double[] position, double[] velocity) {}
    record Solution(String status, String reason, double[] departureVelocity, Double residualKm) {}

    private LunarTransferOrbit() {}

    static double norm(double[] vector) {
        return Math.sqrt(dot(vector, vector));
    }

    private static double dot(double[] first, double[] second) {
        return first[0] * second[0] + first[1] * second[1] + first[2] * second[2];
    }

    static double[] combine(double[] first, double factor, double[] second) {
        return new double[]{first[0] + factor * second[0], first[1] + factor * second[1], first[2] + factor * second[2]};
    }

    static double[] scale(double[] vector, double factor) {
        return new double[]{vector[0] * factor, vector[1] * factor, vector[2] * factor};
    }

    // Fehlberg 7(8), NASA TR R-287. Advance with the eighth-order weights.
    private static final double[][] STAGES = {
        {},
        {2.0/27},
        {1.0/36, 1.0/12},
        {1.0/24, 0, 1.0/8},
        {5.0/12, 0, -25.0/16, 25.0/16},
        {1.0/20, 0, 0, 1.0/4, 1.0/5},
        {-25.0/108, 0, 0, 125.0/108, -65.0/27, 125.0/54},
        {31.0/300, 0, 0, 0, 61.0/225, -2.0/9, 13.0/900},
        {2, 0, 0, -53.0/6, 704.0/45, -107.0/9, 67.0/90, 3},
        {-91.0/108, 0, 0, 23.0/108, -976.0/135, 311.0/54, -19.0/60, 17.0/6, -1.0/12},
        {2383.0/4100, 0, 0, -341.0/164, 4496.0/1025, -301.0/82, 2133.0/4100, 45.0/82, 45.0/164, 18.0/41},
        {3.0/205, 0, 0, 0, 0, -6.0/41, -3.0/205, -3.0/41, 3.0/41, 6.0/41, 0},
        {-1777.0/4100, 0, 0, -341.0/164, 4496.0/1025, -289.0/82, 2193.0/4100, 51.0/82, 33.0/164, 12.0/41, 0, 1},
    };
    private static final double[] WEIGHTS = {0, 0, 0, 0, 0, 34.0/105, 9.0/35, 9.0/35, 9.0/280, 9.0/280, 0, 41.0/840, 41.0/840};

    static State propagate(State initial, long elapsedSeconds) {
        double[] state = Arrays.copyOf(initial.position(), 6);
        System.arraycopy(initial.velocity(), 0, state, 3, 3);
        double remaining = elapsedSeconds;
        double step = Math.min(0.001, remaining);
        double[][] derivatives = new double[STAGES.length][6];
        double[] stage = new double[6];
        double[] next = new double[6];
        for (int iteration = 0; remaining > 0; iteration++) {
            if (iteration >= 2_000_000) {
                throw new ArithmeticException("PROPAGATION_STEP_LIMIT");
            }
            step = Math.min(step, remaining);
            for (int i = 0; i < STAGES.length; i++) {
                System.arraycopy(state, 0, stage, 0, 6);
                for (int j = 0; j < i; j++) {
                    double factor = step * STAGES[i][j];
                    for (int m = 0; m < 6; m++) {
                        stage[m] += factor * derivatives[j][m];
                    }
                }
                double radius = norm(stage);
                double gravity = -EARTH_MU / (radius * radius * radius);
                for (int axis = 0; axis < 3; axis++) {
                    derivatives[i][axis] = stage[axis + 3];
                    derivatives[i][axis + 3] = gravity * stage[axis];
                }
            }
            double error = 0;
            for (int m = 0; m < 6; m++) {
                next[m] = state[m];
                for (int i = 0; i < WEIGHTS.length; i++) {
                    next[m] += (step * WEIGHTS[i]) * derivatives[i][m];
                }
                double estimate = (step * (41.0 / 840)) *
                        (derivatives[0][m] + derivatives[10][m] - derivatives[11][m] - derivatives[12][m]);
                double tolerance = 1e-12 + 2e-12 * Math.max(Math.abs(state[m]), Math.abs(next[m]));
                error = Math.max(error, Math.abs(estimate) / tolerance);
            }
            if (!Double.isFinite(error) || (step < 1e-6 && error > 1)) {
                throw new ArithmeticException("PROPAGATION_DIVERGED");
            }
            if (error <= 1) {
                double[] previous = state;
                state = next;
                next = previous;
                remaining -= step;
            }
            step *= Math.max(0.2, Math.min(4, 0.9 * Math.pow(error, -1.0 / 8)));
        }
        return new State(Arrays.copyOfRange(state, 0, 3), Arrays.copyOfRange(state, 3, 6));
    }

    private record Stumpff(double c, double s) {}

    private static Stumpff stumpff(double z) {
        if (Math.abs(z) < 1e-5) {
            return new Stumpff(0.5 - z/24 + z*z/720 - z*z*z/40320, 1.0/6 - z/120 + z*z/5040 - z*z*z/362880);
        }
        double q = Math.sqrt(Math.abs(z));
        return z > 0 ? new Stumpff((1 - Math.cos(q))/z, (q - Math.sin(q))/(q*q*q))
                : new Stumpff((Math.cosh(q) - 1)/(-z), (Math.sinh(q) - q)/(q*q*q));
    }

    private record Geometry(double startRadius, double endRadius, double chordFactor, double transferTimeScaled) {
        double y(double z, Stumpff functions) {
            return startRadius + endRadius + chordFactor * (z * functions.s - 1) / Math.sqrt(functions.c);
        }

        double residual(double z) {
            var functions = stumpff(z);
            double y = y(z, functions);
            if (y < 0 || functions.c <= 0) return Double.NaN;
            return Math.pow(y / functions.c, 1.5) * functions.s + chordFactor * Math.sqrt(y) - transferTimeScaled;
        }
    }

    private static double grid(int index) {
        if (index < 600) return -Math.pow(10, 3 - 8 * index / 599.0);
        return (4 * Math.PI * Math.PI - 1e-6) * (index - 600) / 599.0;
    }

    private static double findRoot(Geometry geometry) {
        double left = grid(0);
        double previous = geometry.residual(left);
        for (int i = 1; i < 1200; i++) {
            double right = grid(i);
            double current = geometry.residual(right);
            if (Double.isFinite(previous) && Double.isFinite(current) && previous * current <= 0) {
                for (int k = 0; right - left > 1e-11 && k < 100; k++) {
                    double mid = (left + right) / 2;
                    double value = geometry.residual(mid);
                    if (previous * value <= 0) {
                        right = mid;
                    } else {
                        left = mid;
                        previous = value;
                    }
                }
                return (left + right) / 2;
            }
            left = right;
            previous = current;
        }
        return Double.NaN;
    }

    static Solution solve(double[] departure, double[] target, long transferSeconds) {
        double startRadius = norm(departure);
        double endRadius = norm(target);
        double cosine = Math.clamp(dot(departure, target) / (startRadius * endRadius), -1, 1);
        if (!Double.isFinite(cosine) || 1 - Math.abs(cosine) <= 1e-12) {
            return new Solution("NO_SOLUTION_IN_SUPPORTED_DOMAIN", "UNSUPPORTED_GEOMETRY", null, null);
        }
        double chordFactor = Math.sqrt(startRadius * endRadius * (1 + cosine));
        var geometry = new Geometry(startRadius, endRadius, chordFactor, Math.sqrt(EARTH_MU) * transferSeconds);
        double z = findRoot(geometry);
        if (!Double.isFinite(z)) {
            return new Solution("NO_SOLUTION_IN_SUPPORTED_DOMAIN", "NO_ZERO_REVOLUTION_SOLUTION", null, null);
        }
        double y = geometry.y(z, stumpff(z));
        double f = 1 - y / startRadius;
        double g = chordFactor * Math.sqrt(y / EARTH_MU);
        double[] velocity = scale(combine(target, -f, departure), 1/g);
        State endpoint;
        try {
            endpoint = propagate(new State(departure, velocity), transferSeconds);
        } catch (ArithmeticException ex) {
            return new Solution("NO_SOLUTION_IN_SUPPORTED_DOMAIN", "PROPAGATION_FAILED", null, null);
        }
        double residualKm = norm(combine(endpoint.position(), -1, target));
        if (!Double.isFinite(residualKm)) {
            return new Solution("NO_SOLUTION_IN_SUPPORTED_DOMAIN", "PROPAGATION_FAILED", null, null);
        }
        if (residualKm > RESIDUAL_LIMIT_KM) {
            return new Solution("RESIDUAL_TOO_LARGE", "POSITION_RESIDUAL_TOO_LARGE", null, residualKm);
        }
        return new Solution("CONVERGED", null, velocity, residualKm);
    }
}
