package com.mrfdev.walktheplank.game;

import java.util.Objects;
import java.util.UUID;

/**
 * Critical-state comparison used after the asynchronous player-recovery write.
 *
 * <p>Sub-block movement, look direction, saturation, and exhaustion may change naturally while
 * the player remains in the captured block. They are intentionally not used as activation
 * blockers. Cross-block/world movement and security-relevant state still fail closed.</p>
 */
final class PlayerSnapshotRevalidationPolicy {
    private PlayerSnapshotRevalidationPolicy() {
    }

    static Difference compare(State captured, State current) {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(current, "current");
        if (!captured.worldId().equals(current.worldId())) {
            return Difference.WORLD_CHANGED;
        }
        if (captured.blockX() != current.blockX()
                || captured.blockY() != current.blockY()
                || captured.blockZ() != current.blockZ()) {
            return Difference.BLOCK_POSITION_CHANGED;
        }
        if (Double.compare(captured.health(), current.health()) != 0) {
            return Difference.HEALTH_CHANGED;
        }
        if (captured.foodLevel() != current.foodLevel()) {
            return Difference.FOOD_LEVEL_CHANGED;
        }
        if (Float.compare(captured.walkSpeed(), current.walkSpeed()) != 0) {
            return Difference.WALK_SPEED_CHANGED;
        }
        if (captured.allowFlight() != current.allowFlight()) {
            return Difference.ALLOW_FLIGHT_CHANGED;
        }
        if (captured.flying() != current.flying()) {
            return Difference.FLYING_CHANGED;
        }
        if (captured.collidable() != current.collidable()) {
            return Difference.COLLIDABLE_CHANGED;
        }
        return Difference.NONE;
    }

    enum Difference {
        NONE,
        WORLD_CHANGED,
        BLOCK_POSITION_CHANGED,
        HEALTH_CHANGED,
        FOOD_LEVEL_CHANGED,
        WALK_SPEED_CHANGED,
        ALLOW_FLIGHT_CHANGED,
        FLYING_CHANGED,
        COLLIDABLE_CHANGED
    }

    record State(
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            double health,
            int foodLevel,
            float walkSpeed,
            boolean allowFlight,
            boolean flying,
            boolean collidable) {
        State {
            Objects.requireNonNull(worldId, "worldId");
        }
    }
}
