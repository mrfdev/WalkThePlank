package com.mrfdev.walktheplank.game;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact ownership registry for captured, placed, and deletion-pending arena blocks.
 *
 * <p>Most access is on the primary thread. Durable-discard completion may release a lease on the
 * recovery writer after the journal deletion and directory sync succeed, so every operation is
 * synchronized.</p>
 */
final class BlockLeaseRegistry {
    private final Map<BlockKey, BlockLease> owners = new HashMap<>();

    synchronized boolean reserve(BlockKey key, BlockLease lease) {
        BlockKey checkedKey = Objects.requireNonNull(key, "key");
        BlockLease checkedLease = Objects.requireNonNull(lease, "lease");
        BlockLease existing = owners.putIfAbsent(checkedKey, checkedLease);
        return existing == null || existing.equals(checkedLease);
    }

    synchronized boolean owns(BlockKey key, BlockLease lease) {
        return Objects.equals(
                owners.get(Objects.requireNonNull(key, "key")),
                Objects.requireNonNull(lease, "lease"));
    }

    synchronized boolean release(BlockKey key, BlockLease lease) {
        return owners.remove(
                Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(lease, "lease"));
    }

    synchronized boolean isReserved(BlockKey key) {
        return owners.containsKey(Objects.requireNonNull(key, "key"));
    }

    synchronized int size() {
        return owners.size();
    }

    synchronized int releaseRun(UUID runId, long sessionGeneration) {
        Objects.requireNonNull(runId, "runId");
        int before = owners.size();
        owners.entrySet().removeIf(entry -> entry.getValue().runId().equals(runId)
                && entry.getValue().sessionGeneration() == sessionGeneration);
        return before - owners.size();
    }

    record BlockLease(UUID runId, long sessionGeneration, long platformGeneration) {
        BlockLease {
            Objects.requireNonNull(runId, "runId");
            if (sessionGeneration <= 0L) {
                throw new IllegalArgumentException("sessionGeneration must be positive");
            }
            if (platformGeneration <= 0L) {
                throw new IllegalArgumentException("platformGeneration must be positive");
            }
        }
    }
}
