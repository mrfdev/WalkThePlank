package com.mrfdev.walktheplank.game;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.Block;

public record BlockKey(UUID worldId, int x, int y, int z) {
    public BlockKey {
        Objects.requireNonNull(worldId, "worldId");
    }

    public static BlockKey from(Block block) {
        Objects.requireNonNull(block, "block");
        return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockKey from(Location location) {
        Objects.requireNonNull(location, "location");
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Location must have a world");
        }
        return new BlockKey(
                location.getWorld().getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());
    }
}
