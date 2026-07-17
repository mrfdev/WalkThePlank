package com.mrfdev.walktheplank.game;

import com.mrfdev.walktheplank.recovery.RecoveryDurabilityService;
import com.mrfdev.walktheplank.recovery.RestorationCoordinator;
import com.mrfdev.walktheplank.recovery.RestorationRecord;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.block.Block;

/**
 * Main-thread block reference paired with a worker-owned immutable durability preparation.
 */
final class PreparedBlock {
    private final Block block;
    private final BlockKey key;
    private final BlockLeaseRegistry.BlockLease lease;
    private final BlockLeaseRegistry leases;
    private final RestorationCoordinator restoration;
    private final RecoveryDurabilityService.RestorationPreparation preparation;
    private boolean claimed;
    private boolean abandoned;
    private CompletableFuture<Void> abandonment;

    PreparedBlock(
            Block block,
            BlockLeaseRegistry.BlockLease lease,
            BlockLeaseRegistry leases,
            RestorationCoordinator restoration,
            RecoveryDurabilityService.RestorationPreparation preparation) {
        this.block = Objects.requireNonNull(block, "block");
        this.key = BlockKey.from(block);
        this.lease = Objects.requireNonNull(lease, "lease");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.restoration = Objects.requireNonNull(restoration, "restoration");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
    }

    CompletableFuture<RestorationRecord> durableRecord() {
        return preparation.durableRecord();
    }

    BlockKey key() {
        return key;
    }

    BlockLeaseRegistry.BlockLease lease() {
        return lease;
    }

    PlacementCommitPolicy.CapturedOwner owner(UUIDOwner owner) {
        Objects.requireNonNull(owner, "owner");
        return new PlacementCommitPolicy.CapturedOwner(
                owner.playerId(),
                lease.runId(),
                owner.arenaId(),
                lease.sessionGeneration(),
                lease.platformGeneration(),
                key);
    }

    PlacedBlock claim(RestorationRecord record) {
        if (claimed || abandoned) {
            throw new IllegalStateException("Prepared block is no longer claimable");
        }
        if (!leases.owns(key, lease)) {
            throw new IllegalStateException("Prepared block no longer owns its exact lease");
        }
        if (!preparation.claim(Objects.requireNonNull(record, "record"))) {
            throw new IllegalStateException("Durable restoration preparation could not be claimed");
        }
        claimed = true;
        return PlacedBlock.claimed(block, lease, restoration, record);
    }

    synchronized CompletableFuture<Void> abandon() {
        if (claimed) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Claimed blocks must be restored"));
        }
        abandoned = true;
        if (abandonment == null || abandonment.isCompletedExceptionally()) {
            abandonment = preparation.discard().thenRun(() -> {
                if (!leases.release(key, lease)) {
                    throw new IllegalStateException(
                            "Discarded block no longer owns its exact lease");
                }
            });
        }
        return abandonment;
    }

    record UUIDOwner(java.util.UUID playerId, String arenaId) {
        UUIDOwner {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(arenaId, "arenaId");
        }
    }
}
