package com.mrfdev.walktheplank.game;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Primary-thread ownership registry for configured arenas.
 *
 * <p>An arena ID alone is not an ownership token: a late asynchronous completion from an older
 * run must never release an arena that has already been assigned to a newer run.</p>
 */
final class ArenaLeaseRegistry {
    private final Map<String, ArenaLease> owners = new HashMap<>();

    boolean reserve(String arenaId, ArenaLease lease) {
        String checkedArenaId = requireArenaId(arenaId);
        ArenaLease checkedLease = Objects.requireNonNull(lease, "lease");
        ArenaLease existing = owners.putIfAbsent(checkedArenaId, checkedLease);
        return existing == null || existing.equals(checkedLease);
    }

    boolean owns(String arenaId, ArenaLease lease) {
        return Objects.equals(owners.get(requireArenaId(arenaId)), Objects.requireNonNull(lease, "lease"));
    }

    boolean release(String arenaId, ArenaLease lease) {
        return owners.remove(
                requireArenaId(arenaId),
                Objects.requireNonNull(lease, "lease"));
    }

    boolean isReserved(String arenaId) {
        return owners.containsKey(requireArenaId(arenaId));
    }

    int size() {
        return owners.size();
    }

    /**
     * Returns every run that still owns an arena, including pending starts, active sessions, and
     * quarantined cleanup. Recovery must exclude this authoritative snapshot rather than looking
     * only at activated sessions.
     */
    Set<UUID> ownedRunIds() {
        Set<UUID> result = new HashSet<>();
        for (ArenaLease lease : owners.values()) {
            result.add(lease.runId());
        }
        return Set.copyOf(result);
    }

    private static String requireArenaId(String arenaId) {
        Objects.requireNonNull(arenaId, "arenaId");
        String checked = arenaId.strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("arenaId must not be blank");
        }
        return checked;
    }

    record ArenaLease(UUID runId, long sessionGeneration) {
        ArenaLease {
            Objects.requireNonNull(runId, "runId");
            if (sessionGeneration <= 0L) {
                throw new IllegalArgumentException("sessionGeneration must be positive");
            }
        }
    }
}
