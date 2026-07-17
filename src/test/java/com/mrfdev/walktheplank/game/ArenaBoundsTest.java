package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArenaBoundsTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void overlapRequiresIntersectionInAllAxesAndTheSameWorld() {
        ArenaBounds origin = new ArenaBounds(WORLD, -8, 8, 90, 110, -8, 8);

        assertTrue(origin.overlaps(new ArenaBounds(WORLD, 8, 20, 100, 120, 8, 20)));
        assertFalse(origin.overlaps(new ArenaBounds(WORLD, 9, 20, 100, 120, 0, 4)));
        assertFalse(origin.overlaps(new ArenaBounds(WORLD, 0, 4, 111, 120, 0, 4)));
        assertFalse(origin.overlaps(new ArenaBounds(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                -8,
                8,
                90,
                110,
                -8,
                8)));
    }
}
