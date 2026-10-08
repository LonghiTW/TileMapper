package com.mndk.tilemapper.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockColorRegistryScoreTest {

    @Test
    void baseScoreIsOklabChordDecomposition() {
        double dL2 = 0.001;
        double dC2 = 0.002;
        double dH2 = 0.003;

        assertEquals(dL2 + dC2 + dH2, BlockColorRegistry.computeBaseScore(dL2, dC2, dH2), 0.0);
    }

    @Test
    void neutralTargetsDoNotActivateHueTerm() {
        double targetChroma = 0.014; // < antiGrayStart=0.015, and < hueOnset=0.04

        assertEquals(0.0, BlockColorRegistry.computeHueWeight(targetChroma), 0.0);
    }

    @Test
    void hueWeightRampsFromHueOnsetToFullWeight() {
        // HUE_WEIGHT = 2.0 is the *total* ΔH² coefficient at full chroma;
        // computeHueWeight returns the extra boost above the 1.0 baseline,
        // so it ramps 0 -> 1.0.
        assertEquals(0.0, BlockColorRegistry.computeHueWeight(0.04), 0.0);
        assertEquals(0.5, BlockColorRegistry.computeHueWeight(0.05), 1.0e-12);
        assertEquals(1.0, BlockColorRegistry.computeHueWeight(0.06), 0.0);
        assertEquals(1.0, BlockColorRegistry.computeHueWeight(0.08), 0.0);
    }

    @Test
    void computeScoreIsBasePlusHueWeightedTerm() {
        double dL2 = 0.001;
        double dC2 = 0.002;
        double dH2 = 0.003;
        // At targetChroma above c1 the total ΔH² coefficient is 1 + 1 = 2.0.
        assertEquals(dL2 + dC2 + 2.0 * dH2, BlockColorRegistry.computeScore(dL2, dC2, dH2, 0.08), 0.0);
        // Low-chroma target -> boost 0 -> ΔH² keeps its 1.0 baseline, so the
        // score equals pure Oklab chord decomposition dL2 + dC2 + dH2.
        assertEquals(dL2 + dC2 + dH2, BlockColorRegistry.computeScore(dL2, dC2, dH2, 0.0), 0.0);
    }

    @Test
    void betterCandidateUsesScoreBeforeTieBreak() {
        assertTrue(BlockColorRegistry.isBetterCandidate(
                0.999999999999, "minecraft:z_block",
                1.0, "minecraft:a_block"));
    }

    @Test
    void nearTieUsesDeterministicKeyOrdering() {
        assertTrue(BlockColorRegistry.isBetterCandidate(
                1.0, "minecraft:a_block",
                1.0, "minecraft:z_block"));
    }

    @Test
    void nonTieDoesNotUseKeyOrdering() {
        assertFalse(BlockColorRegistry.isBetterCandidate(
                1.0000000001, "minecraft:a_block",
                1.0, "minecraft:z_block"));
    }
}
