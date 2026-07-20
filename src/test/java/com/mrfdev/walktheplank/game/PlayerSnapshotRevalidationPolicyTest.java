package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PlayerSnapshotRevalidationPolicyTest {
    private static final UUID WORLD =
            UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void acceptsNormalMovementWithinTheCapturedBlock() {
        assertEquals(
                PlayerSnapshotRevalidationPolicy.Difference.NONE,
                PlayerSnapshotRevalidationPolicy.compare(state(), state()));
    }

    @Test
    void rejectsCrossBlockAndSecurityRelevantStateChanges() {
        assertEquals(
                PlayerSnapshotRevalidationPolicy.Difference.BLOCK_POSITION_CHANGED,
                PlayerSnapshotRevalidationPolicy.compare(
                        state(),
                        new PlayerSnapshotRevalidationPolicy.State(
                                WORLD, 11, 64, 10, 20.0, 20, 0.2F, false, false, true)));
        assertEquals(
                PlayerSnapshotRevalidationPolicy.Difference.WALK_SPEED_CHANGED,
                PlayerSnapshotRevalidationPolicy.compare(
                        state(),
                        new PlayerSnapshotRevalidationPolicy.State(
                                WORLD, 10, 64, 10, 20.0, 20, 0.3F, false, false, true)));
        assertEquals(
                PlayerSnapshotRevalidationPolicy.Difference.ALLOW_FLIGHT_CHANGED,
                PlayerSnapshotRevalidationPolicy.compare(
                        state(),
                        new PlayerSnapshotRevalidationPolicy.State(
                                WORLD, 10, 64, 10, 20.0, 20, 0.2F, true, false, true)));
    }

    private static PlayerSnapshotRevalidationPolicy.State state() {
        return new PlayerSnapshotRevalidationPolicy.State(
                WORLD, 10, 64, 10, 20.0, 20, 0.2F, false, false, true);
    }
}
