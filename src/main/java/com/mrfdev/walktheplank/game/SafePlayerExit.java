package com.mrfdev.walktheplank.game;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;

/** Fail-closed support, clearance, border, liquid, and hazard inspection for player exits. */
public final class SafePlayerExit {
    private static final Set<String> UNSAFE_EXACT_NAMES = Set.of(
            "CACTUS",
            "COBWEB",
            "END_GATEWAY",
            "END_PORTAL",
            "FIRE",
            "LAVA",
            "MAGMA_BLOCK",
            "NETHER_PORTAL",
            "POINTED_DRIPSTONE",
            "POWDER_SNOW",
            "SOUL_FIRE",
            "SWEET_BERRY_BUSH",
            "WATER",
            "WITHER_ROSE");

    private static final Set<String> UNSAFE_SUFFIX_FAMILIES = Set.of("CAMPFIRE");

    private SafePlayerExit() {
    }

    public static Inspection inspect(Location location) {
        Objects.requireNonNull(location, "location");
        World world = location.getWorld();
        if (world == null) {
            return Inspection.rejected(Reason.NO_WORLD);
        }
        if (!Double.isFinite(location.getX())
                || !Double.isFinite(location.getY())
                || !Double.isFinite(location.getZ())
                || !Float.isFinite(location.getYaw())
                || !Float.isFinite(location.getPitch())) {
            return Inspection.rejected(Reason.NON_FINITE);
        }
        if (location.getBlockY() <= world.getMinHeight()
                || location.getBlockY() + 1 >= world.getMaxHeight()) {
            return Inspection.rejected(Reason.HEIGHT);
        }
        if (!world.getWorldBorder().isInside(location)) {
            return Inspection.rejected(Reason.WORLD_BORDER);
        }
        try {
            Block feet = location.getBlock();
            Block head = feet.getRelative(0, 1, 0);
            Block support = feet.getRelative(0, -1, 0);
            if (!feet.isPassable() || !head.isPassable()) {
                return Inspection.rejected(Reason.BLOCKED);
            }
            if (support.isPassable() || support.getCollisionShape().getBoundingBoxes().isEmpty()) {
                return Inspection.rejected(Reason.NO_SUPPORT);
            }
            if (isUnsafeBlock(feet) || isUnsafeBlock(head) || isUnsafeBlock(support)) {
                return Inspection.rejected(Reason.HAZARD);
            }
            return Inspection.accepted();
        } catch (RuntimeException failure) {
            return Inspection.rejected(Reason.INSPECTION_FAILED);
        }
    }

    static boolean isUnsafeMaterialName(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return true;
        }
        String normalized = materialName.strip().toUpperCase(Locale.ROOT);
        if (UNSAFE_EXACT_NAMES.contains(normalized)) {
            return true;
        }
        return UNSAFE_SUFFIX_FAMILIES.stream()
                .anyMatch(family -> normalized.equals(family)
                        || normalized.endsWith('_' + family));
    }

    private static boolean isUnsafeBlock(Block block) {
        return block.isLiquid()
                || block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()
                || isUnsafeMaterialName(block.getType().name());
    }

    public enum Reason {
        SAFE,
        NO_WORLD,
        NON_FINITE,
        HEIGHT,
        WORLD_BORDER,
        BLOCKED,
        NO_SUPPORT,
        HAZARD,
        INSPECTION_FAILED
    }

    public record Inspection(boolean safe, Reason reason) {
        public Inspection {
            Objects.requireNonNull(reason, "reason");
            if (safe != (reason == Reason.SAFE)) {
                throw new IllegalArgumentException("safe and reason must agree");
            }
        }

        private static Inspection accepted() {
            return new Inspection(true, Reason.SAFE);
        }

        private static Inspection rejected(Reason reason) {
            return new Inspection(false, reason);
        }
    }
}
