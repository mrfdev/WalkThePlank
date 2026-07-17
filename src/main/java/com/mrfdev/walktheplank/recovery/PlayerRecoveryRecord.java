package com.mrfdev.walktheplank.recovery;

import com.mrfdev.walktheplank.config.ArenaId;
import java.util.Objects;
import java.util.UUID;

/**
 * Privacy-bounded player state captured before a run mutates or teleports the player.
 *
 * <p>No name, inventory, IP address, command, or world name is persisted.</p>
 */
public record PlayerRecoveryRecord(
        UUID playerId,
        UUID runId,
        String arenaId,
        UUID returnWorldId,
        double returnX,
        double returnY,
        double returnZ,
        float returnYaw,
        float returnPitch,
        double health,
        int foodLevel,
        float saturation,
        float exhaustion,
        float walkSpeed,
        boolean allowFlight,
        boolean flying,
        boolean collidable) {
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final double MAX_HORIZONTAL_COORDINATE = 30_000_000.0;
    private static final double MAX_ABSOLUTE_Y = 4_096.0;
    private static final double MAX_HEALTH = 2_048.0;
    private static final float MAX_SATURATION = 1_000.0F;
    private static final float MAX_EXHAUSTION = 1_000.0F;

    public PlayerRecoveryRecord {
        requireOwnedUuid(playerId, "playerId");
        requireOwnedUuid(runId, "runId");
        arenaId = ArenaId.requireValid(arenaId);
        requireOwnedUuid(returnWorldId, "returnWorldId");
        requireFiniteRange(returnX, -MAX_HORIZONTAL_COORDINATE, MAX_HORIZONTAL_COORDINATE, "returnX");
        requireFiniteRange(returnY, -MAX_ABSOLUTE_Y, MAX_ABSOLUTE_Y, "returnY");
        requireFiniteRange(returnZ, -MAX_HORIZONTAL_COORDINATE, MAX_HORIZONTAL_COORDINATE, "returnZ");
        requireFiniteRange(returnYaw, -180.0F, 180.0F, "returnYaw");
        requireFiniteRange(returnPitch, -90.0F, 90.0F, "returnPitch");
        requireFiniteRange(health, 0.0, MAX_HEALTH, "health");
        if (foodLevel < 0 || foodLevel > 20) {
            throw new IllegalArgumentException("foodLevel must be between 0 and 20");
        }
        requireFiniteRange(saturation, 0.0F, MAX_SATURATION, "saturation");
        requireFiniteRange(exhaustion, 0.0F, MAX_EXHAUSTION, "exhaustion");
        requireFiniteRange(walkSpeed, -1.0F, 1.0F, "walkSpeed");
        if (flying && !allowFlight) {
            throw new IllegalArgumentException("flying requires allowFlight");
        }
    }

    public boolean owns(UUID expectedPlayerId, UUID expectedRunId, String expectedArenaId) {
        return playerId.equals(Objects.requireNonNull(expectedPlayerId, "expectedPlayerId"))
                && runId.equals(Objects.requireNonNull(expectedRunId, "expectedRunId"))
                && arenaId.equals(Objects.requireNonNull(expectedArenaId, "expectedArenaId"));
    }

    private static void requireOwnedUuid(UUID value, String field) {
        Objects.requireNonNull(value, field);
        if (NIL_UUID.equals(value)) {
            throw new IllegalArgumentException(field + " must not be the nil UUID");
        }
    }

    private static void requireFiniteRange(double value, double minimum, double maximum, String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is outside its safe finite range");
        }
    }

    private static void requireFiniteRange(float value, float minimum, float maximum, String field) {
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is outside its safe finite range");
        }
    }
}
