package com.mndk.tilemapper.util;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OklabColorTest {

    /**
     * Mixed-tolerance assertion for the chord decomposition invariant.
     *
     * <p>Plan §5.1 requires {@code |lhs - rhs| <= eps_abs + eps_rel * max(|lhs|, |rhs|)}.
     * A pure relative tolerance fails when the a-b distance itself is near zero
     * (both colours nearly identical): the denominator collapses and tiny
     * floating-point noise is amplified into a large relative error.</p>
     */
    private static void assertChordInvariant(double abDistanceSq, double polarDistanceSq) {
        double tol = 1.0e-14 + 1.0e-12 * Math.max(Math.abs(abDistanceSq), Math.abs(polarDistanceSq));
        assertEquals(abDistanceSq, polarDistanceSq, tol);
    }

    @Test
    void deltaH2DecomposesOklabAbPlaneDistance() {
        OklabColor first = new OklabColor(0.6, 0.09, -0.04);
        OklabColor second = new OklabColor(0.4, -0.03, 0.11);

        double da = first.a - second.a;
        double db = first.b - second.b;
        double abDistanceSq = da * da + db * db;

        double dc = first.chroma() - second.chroma();
        double polarDistanceSq = dc * dc + first.deltaH2(second);

        assertChordInvariant(abDistanceSq, polarDistanceSq);
    }

    /**
     * Plan §5.1 demands the chord decomposition invariant be verified over
     * many random Oklab pairs, not just one hand-picked pair. Uses a fixed seed
     * so the test is deterministic.
     */
    @Test
    void chordInvariantHoldsOverManyRandomPairs() {
        Random random = new Random(20260812L);
        for (int i = 0; i < 10_000; i++) {
            OklabColor first = randomColor(random);
            OklabColor second = randomColor(random);

            double da = first.a - second.a;
            double db = first.b - second.b;
            double abDistanceSq = da * da + db * db;

            double dc = first.chroma() - second.chroma();
            double polarDistanceSq = dc * dc + first.deltaH2(second);

            assertChordInvariant(abDistanceSq, polarDistanceSq);
        }
    }

    /** Random Oklab with a/b uniform in [-0.4, 0.4) and L uniform in [0, 1). */
    private static OklabColor randomColor(Random random) {
        double l = random.nextDouble();
        double a = random.nextDouble() * 0.8 - 0.4;
        double b = random.nextDouble() * 0.8 - 0.4;
        return new OklabColor(l, a, b);
    }

    @Test
    void deltaH2IsZeroWhenEitherColorIsNeutral() {
        assertEquals(0.0, OklabColor.deltaH2(0.0, 0.0, 0.2, Math.PI), 0.0);
        assertEquals(0.0, OklabColor.deltaH2(0.2, Math.PI, 0.0, 0.0), 0.0);
    }

    @Test
    void deltaH2ScalesWithChromaProduct() {
        // Same angular difference, larger chroma product -> larger (non-zero) deltaH2.
        double small = OklabColor.deltaH2(0.02, 0.0, 0.02, Math.PI / 2.0);
        double large = OklabColor.deltaH2(0.2, 0.0, 0.2, Math.PI / 2.0);
        assertTrue(large > small, "deltaH2 must grow with the chroma product");
    }
}
