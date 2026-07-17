package com.mrfdev.walktheplank.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ParkourMaterialPolicyTest {
    @Test
    void rejectsKnownStatefulAndWorkstationMaterials() {
        List.of(
                        "FURNACE",
                        "BLAST_FURNACE",
                        "BARREL",
                        "CHEST",
                        "TRAPPED_CHEST",
                        "NOTE_BLOCK",
                        "TRIAL_SPAWNER",
                        "PISTON",
                        "STICKY_PISTON",
                        "OBSERVER",
                        "TARGET",
                        "REDSTONE_ORE",
                        "DEEPSLATE_REDSTONE_ORE",
                        "MAGMA_BLOCK",
                        "COPPER_BLOCK",
                        "WAXED_COPPER_BLOCK",
                        "EXPOSED_CUT_COPPER",
                        "COPPER_GRATE",
                        "WAXED_OXIDIZED_COPPER_GRATE")
                .forEach(name -> assertTrue(
                        ParkourMaterialPolicy.isStatefulOrWorkstation(name), name));
    }

    @Test
    void rejectsCopperChestAndShelfFamiliesWithoutEnumeratingEveryVariant() {
        List.of(
                        "COPPER_CHEST",
                        "EXPOSED_COPPER_CHEST",
                        "WAXED_OXIDIZED_COPPER_CHEST",
                        "OAK_SHELF",
                        "COPPER_SHELF",
                        "WAXED_COPPER_SHELF")
                .forEach(name -> assertTrue(
                        ParkourMaterialPolicy.isStatefulOrWorkstation(name), name));
    }

    @Test
    void keepsLegacyAndCurrentLiveMaterialsEligibleAtTheNameLayer() {
        List.of("STONE", "JACK_O_LANTERN")
                .forEach(name -> assertFalse(
                        ParkourMaterialPolicy.isStatefulOrWorkstation(name), name));
    }
}
