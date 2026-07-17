package com.mrfdev.walktheplank.game;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Keeps crash-owned arenas unavailable until durable player evidence is resolved. */
final class RecoveryArenaPolicy {
    private RecoveryArenaPolicy() {
    }

    static Set<String> unavailableArenaIds(
            Collection<String> configuredArenaIds,
            Collection<String> pendingArenaIds,
            int invalidRecords) {
        Objects.requireNonNull(configuredArenaIds, "configuredArenaIds");
        Objects.requireNonNull(pendingArenaIds, "pendingArenaIds");
        if (invalidRecords < 0) {
            throw new IllegalArgumentException("invalidRecords must not be negative");
        }
        Collection<String> source = invalidRecords > 0 ? configuredArenaIds : pendingArenaIds;
        HashSet<String> unavailable = new HashSet<>();
        for (String arenaId : source) {
            unavailable.add(Objects.requireNonNull(arenaId, "arenaId"));
        }
        return Set.copyOf(unavailable);
    }
}
