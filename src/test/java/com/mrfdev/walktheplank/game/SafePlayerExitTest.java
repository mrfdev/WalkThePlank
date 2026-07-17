package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class SafePlayerExitTest {
    @Test
    void rejectsLiquidsDamageBlocksAndPortalsByName() {
        List.of(
                        "WATER",
                        "LAVA",
                        "FIRE",
                        "SOUL_FIRE",
                        "MAGMA_BLOCK",
                        "CACTUS",
                        "POINTED_DRIPSTONE",
                        "POWDER_SNOW",
                        "NETHER_PORTAL",
                        "END_PORTAL")
                .forEach(name -> assertTrue(SafePlayerExit.isUnsafeMaterialName(name), name));
    }

    @Test
    void rejectsEveryCampfireVariantButKeepsOrdinarySupportBlocks() {
        assertTrue(SafePlayerExit.isUnsafeMaterialName("CAMPFIRE"));
        assertTrue(SafePlayerExit.isUnsafeMaterialName("SOUL_CAMPFIRE"));
        List.of("STONE", "GRASS_BLOCK", "OAK_PLANKS")
                .forEach(name -> assertFalse(SafePlayerExit.isUnsafeMaterialName(name), name));
    }

    @Test
    void inspectionRequiresConsistentSafetyReason() {
        assertThrows(IllegalArgumentException.class, () ->
                new SafePlayerExit.Inspection(true, SafePlayerExit.Reason.HAZARD));
        assertThrows(IllegalArgumentException.class, () ->
                new SafePlayerExit.Inspection(false, SafePlayerExit.Reason.SAFE));
    }
}
