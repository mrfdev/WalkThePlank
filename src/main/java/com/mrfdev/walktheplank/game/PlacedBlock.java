package com.mrfdev.walktheplank.game;

import com.mrfdev.walktheplank.recovery.RestorationCoordinator;
import com.mrfdev.walktheplank.recovery.RestorationOutcome;
import com.mrfdev.walktheplank.recovery.RestorationRecord;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.block.Block;

final class PlacedBlock {
    private final Block block;
    private final BlockKey key;
    private final BlockLeaseRegistry.BlockLease lease;
    private final RestorationCoordinator restoration;
    private final RestorationRecord record;
    private boolean restored;

    private PlacedBlock(
            Block block,
            BlockLeaseRegistry.BlockLease lease,
            RestorationCoordinator restoration,
            RestorationRecord record) {
        this.block = block;
        this.lease = Objects.requireNonNull(lease, "lease");
        this.restoration = restoration;
        this.record = record;
        key = BlockKey.from(block);
    }

    static PlacedBlock claimed(
            Block block,
            BlockLeaseRegistry.BlockLease lease,
            RestorationCoordinator restoration,
            RestorationRecord record) {
        return new PlacedBlock(
                Objects.requireNonNull(block, "block"),
                Objects.requireNonNull(lease, "lease"),
                Objects.requireNonNull(restoration, "restoration"),
                Objects.requireNonNull(record, "record"));
    }

    void place() {
        restoration.place(record);
    }

    BlockKey key() {
        return key;
    }

    BlockLeaseRegistry.BlockLease lease() {
        return lease;
    }

    Location location() {
        return block.getLocation();
    }

    boolean isIntact() {
        return !restored
                && block.getType().name().equals(record.expectedState().material())
                && block.getBlockData().getAsString().equals(record.expectedState().blockData());
    }

    RestorationCoordinator.DeferredRestoration restoreDeferred() {
        if (restored) {
            return new RestorationCoordinator.DeferredRestoration(
                    RestorationOutcome.NO_LONGER_PENDING,
                    java.util.concurrent.CompletableFuture.completedFuture(
                            RestorationOutcome.NO_LONGER_PENDING));
        }
        RestorationCoordinator.DeferredRestoration deferred =
                restoration.restoreDeferred(record);
        if (deferred.worldSettled()) {
            restored = true;
        }
        return deferred;
    }
}
