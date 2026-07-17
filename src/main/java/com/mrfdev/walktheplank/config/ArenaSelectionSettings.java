package com.mrfdev.walktheplank.config;

import com.mrfdev.walktheplank.game.ArenaSelectionPolicy;
import java.util.Objects;
import java.util.Optional;

/** Validated arena-allocation policy. */
public record ArenaSelectionSettings(
        ArenaSelectionPolicy policy,
        Optional<String> pinnedArenaId) {
    public ArenaSelectionSettings {
        Objects.requireNonNull(policy, "policy");
        pinnedArenaId = Objects.requireNonNull(pinnedArenaId, "pinnedArenaId")
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        if (policy == ArenaSelectionPolicy.PINNED && pinnedArenaId.isEmpty()) {
            throw new IllegalArgumentException("PINNED arena selection requires an arena ID");
        }
    }

    public static ArenaSelectionSettings defaults() {
        return new ArenaSelectionSettings(ArenaSelectionPolicy.RANDOM, Optional.empty());
    }
}
