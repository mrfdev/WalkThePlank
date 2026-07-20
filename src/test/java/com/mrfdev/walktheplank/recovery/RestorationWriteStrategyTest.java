package com.mrfdev.walktheplank.recovery;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class RestorationWriteStrategyTest {
    @Test
    void directlyRestoresEveryAirVariantThatCanLeaveAClientGhostBlock() {
        assertAll(
                () -> assertEquals(
                        RestorationWriteStrategy.DIRECT_BLOCK_DATA,
                        strategy("AIR", "minecraft:air")),
                () -> assertEquals(
                        RestorationWriteStrategy.DIRECT_BLOCK_DATA,
                        strategy("CAVE_AIR", "minecraft:cave_air")),
                () -> assertEquals(
                        RestorationWriteStrategy.DIRECT_BLOCK_DATA,
                        strategy("VOID_AIR", "minecraft:void_air")));
    }

    @Test
    void retainsStructureSnapshotsForSolidFluidAndTileCapableBlocks() {
        assertAll(
                () -> assertEquals(
                        RestorationWriteStrategy.STRUCTURE_SNAPSHOT,
                        strategy("STONE", "minecraft:stone")),
                () -> assertEquals(
                        RestorationWriteStrategy.STRUCTURE_SNAPSHOT,
                        strategy("WATER", "minecraft:water[level=0]")),
                () -> assertEquals(
                        RestorationWriteStrategy.STRUCTURE_SNAPSHOT,
                        strategy("CHEST", "minecraft:chest[facing=north,type=single,waterlogged=false]")));
    }

    private static RestorationWriteStrategy strategy(String material, String blockData) {
        return RestorationWriteStrategy.forState(new SerializedBlockState(material, blockData));
    }
}
