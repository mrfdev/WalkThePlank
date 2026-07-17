package com.mrfdev.walktheplank.config;

import java.util.Locale;
import java.util.Set;

/** Name-level deny policy for stateful blocks and administrative workstations. */
final class ParkourMaterialPolicy {
    private static final Set<String> DENIED_EXACT_NAMES = Set.of(
            "BEACON",
            "BELL",
            "BREWING_STAND",
            "BUDDING_AMETHYST",
            "CHORUS_FLOWER",
            "COMPOSTER",
            "CONDUIT",
            "CRAFTER",
            "DAYLIGHT_DETECTOR",
            "DECORATED_POT",
            "DISPENSER",
            "DROPPER",
            "END_PORTAL_FRAME",
            "FROSTED_ICE",
            "GRINDSTONE",
            "HOPPER",
            "JIGSAW",
            "JUKEBOX",
            "LECTERN",
            "LOOM",
            "MAGMA_BLOCK",
            "OBSERVER",
            "REDSTONE_LAMP",
            "RESPAWN_ANCHOR",
            "SCULK_CATALYST",
            "SCULK_SHRIEKER",
            "SMOKER",
            "SPAWNER",
            "STONECUTTER",
            "STRUCTURE_BLOCK",
            "TARGET",
            "TNT",
            "VAULT");

    /*
     * Match complete underscore-delimited suffix families so future wood, color,
     * oxidation, waxed, and similar variants inherit the same conservative rule.
     */
    private static final Set<String> DENIED_SUFFIX_FAMILIES = Set.of(
            "ANVIL",
            "BARREL",
            "BEEHIVE",
            "BEE_NEST",
            "BOOKSHELF",
            "CAMPFIRE",
            "CAULDRON",
            "CHEST",
            "COMMAND_BLOCK",
            "COPPER",
            "COPPER_BULB",
            "COPPER_BLOCK",
            "FURNACE",
            "GOLEM_STATUE",
            "NOTE_BLOCK",
            "PISTON",
            "REDSTONE_ORE",
            "SCULK_SENSOR",
            "SHELF",
            "SHULKER_BOX",
            "SPAWNER",
            "TABLE",
            "TEST_BLOCK");

    private ParkourMaterialPolicy() {
    }

    static boolean isStatefulOrWorkstation(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return true;
        }
        String normalized = materialName.strip().toUpperCase(Locale.ROOT);
        if (DENIED_EXACT_NAMES.contains(normalized) || normalized.contains("COPPER")) {
            return true;
        }
        return DENIED_SUFFIX_FAMILIES.stream()
                .anyMatch(family -> normalized.equals(family)
                        || normalized.endsWith('_' + family));
    }
}
