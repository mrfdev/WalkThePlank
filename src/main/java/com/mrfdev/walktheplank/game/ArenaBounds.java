package com.mrfdev.walktheplank.game;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.Block;

/** The complete protected course and fall volume for one arena. */
public record ArenaBounds(
        UUID worldId,
        int minX,
        int maxX,
        int minY,
        int maxY,
        int minZ,
        int maxZ) {
    public static final double MINIMUM_FALL_DISTANCE = JumpPlanner.VERTICAL_RADIUS;

    private static final int HORIZONTAL_SAFETY_MARGIN = 2;
    private static final int PLAYER_HEADROOM_BLOCKS = 2;

    public ArenaBounds {
        Objects.requireNonNull(worldId, "worldId");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Arena bounds minimums must not exceed maximums");
        }
    }

    public static ArenaBounds around(Arena arena, int horizontalRadius, double fallDistance) {
        Objects.requireNonNull(arena, "arena");
        if (horizontalRadius < 0) {
            throw new IllegalArgumentException("horizontalRadius must not be negative");
        }
        if (!Double.isFinite(fallDistance) || fallDistance < 0.0) {
            throw new IllegalArgumentException("fallDistance must be finite and nonnegative");
        }

        Location start = arena.start();
        Location base = arena.baseBlock();
        int horizontalExtent = Math.addExact(horizontalRadius, HORIZONTAL_SAFETY_MARGIN);
        int courseMinimumY = Math.subtractExact(base.getBlockY(), JumpPlanner.VERTICAL_RADIUS);
        int fallMinimumY = Math.subtractExact((int) Math.floor(start.getY() - fallDistance), 1);
        int protectedMinimumY = Math.min(courseMinimumY, fallMinimumY);
        int protectedMaximumY = Math.addExact(
                base.getBlockY(),
                JumpPlanner.VERTICAL_RADIUS + PLAYER_HEADROOM_BLOCKS);

        return new ArenaBounds(
                Objects.requireNonNull(start.getWorld(), "Arena world").getUID(),
                Math.subtractExact(base.getBlockX(), horizontalExtent),
                Math.addExact(base.getBlockX(), horizontalExtent),
                protectedMinimumY,
                protectedMaximumY,
                Math.subtractExact(base.getBlockZ(), horizontalExtent),
                Math.addExact(base.getBlockZ(), horizontalExtent));
    }

    public boolean contains(Location location) {
        Objects.requireNonNull(location, "location");
        return location.getWorld() != null
                && worldId.equals(location.getWorld().getUID())
                && location.getBlockX() >= minX
                && location.getBlockX() <= maxX
                && location.getBlockY() >= minY
                && location.getBlockY() <= maxY
                && location.getBlockZ() >= minZ
                && location.getBlockZ() <= maxZ;
    }

    public boolean overlaps(ArenaBounds other) {
        Objects.requireNonNull(other, "other");
        return worldId.equals(other.worldId)
                && minX <= other.maxX
                && maxX >= other.minX
                && minY <= other.maxY
                && maxY >= other.minY
                && minZ <= other.maxZ
                && maxZ >= other.minZ;
    }

    public static boolean hasClearHeadroom(Block supportingBlock) {
        Objects.requireNonNull(supportingBlock, "supportingBlock");
        return supportingBlock.getRelative(0, 1, 0).getType().isAir()
                && supportingBlock.getRelative(0, 2, 0).getType().isAir();
    }
}
