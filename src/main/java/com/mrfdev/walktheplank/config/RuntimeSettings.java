package com.mrfdev.walktheplank.config;

import com.mrfdev.walktheplank.game.Arena;
import com.mrfdev.walktheplank.game.ArenaBounds;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Particle;

public record RuntimeSettings(
        int configVersion,
        List<Arena> arenas,
        List<Material> parkourBlocks,
        boolean particlesEnabled,
        Particle particle,
        int particleCount,
        double fallDistance,
        int horizontalRadius,
        int maximumRunSeconds,
        int idleTimeoutSeconds,
        boolean onlyReplaceAir,
        boolean finishCommandsEnabled,
        boolean rewardsOnlyOnPersonalBest,
        Set<String> allowedRewardCommandRoots,
        List<RewardTier> rewardTiers,
        QueueSettings queue,
        ArenaSelectionSettings arenaSelection,
        PermissionSettings permissions) {
    public RuntimeSettings {
        arenas = List.copyOf(arenas);
        parkourBlocks = List.copyOf(parkourBlocks);
        allowedRewardCommandRoots = Set.copyOf(
                Objects.requireNonNull(allowedRewardCommandRoots, "allowedRewardCommandRoots"));
        rewardTiers = List.copyOf(rewardTiers);
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(arenaSelection, "arenaSelection");
        Objects.requireNonNull(particle, "particle");
        Objects.requireNonNull(permissions, "permissions");
        if (configVersion != 2) {
            throw new IllegalArgumentException("configVersion must be 2");
        }
        if (arenas.isEmpty()) {
            throw new IllegalArgumentException("At least one valid arena is required");
        }
        if (parkourBlocks.isEmpty()) {
            throw new IllegalArgumentException("At least one parkour block is required");
        }
        if (allowedRewardCommandRoots.isEmpty()
                || allowedRewardCommandRoots.size() > RewardCommandRootPolicy.MAXIMUM_ROOTS) {
            throw new IllegalArgumentException(
                    "allowedRewardCommandRoots must contain 1 through "
                            + RewardCommandRootPolicy.MAXIMUM_ROOTS + " roots");
        }
        if (particleCount < 0 || particleCount > 1000) {
            throw new IllegalArgumentException("particleCount must be between 0 and 1000");
        }
        if (fallDistance < ArenaBounds.MINIMUM_FALL_DISTANCE || fallDistance > 64.0) {
            throw new IllegalArgumentException("fallDistance must be between 6 and 64");
        }
        if (horizontalRadius < 3 || horizontalRadius > 64) {
            throw new IllegalArgumentException("horizontalRadius must be between 3 and 64");
        }
        if (maximumRunSeconds < 30 || maximumRunSeconds > 86_400) {
            throw new IllegalArgumentException("maximumRunSeconds must be between 30 and 86400");
        }
        if (idleTimeoutSeconds < 15 || idleTimeoutSeconds > maximumRunSeconds) {
            throw new IllegalArgumentException(
                    "idleTimeoutSeconds must be between 15 and maximumRunSeconds");
        }
        if (arenaSelection.pinnedArenaId().isPresent()) {
            String pinned = arenaSelection.pinnedArenaId().orElseThrow();
            boolean configured = false;
            for (Arena arena : arenas) {
                if (arena.id().equals(pinned)) {
                    configured = true;
                    break;
                }
            }
            if (!configured) {
                throw new IllegalArgumentException(
                        "arenaSelection.pinnedArena must name a configured arena");
            }
        }
    }
}
