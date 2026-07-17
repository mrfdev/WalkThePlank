package com.mrfdev.walktheplank.game;

import com.mrfdev.walktheplank.recovery.RestorationCoordinator;
import com.mrfdev.walktheplank.recovery.RestorationOutcome;
import com.mrfdev.walktheplank.recovery.RestorationRecord;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;

final class PlacedBlock {
    private final Block block;
    private final BlockKey key;
    private final RestorationCoordinator restoration;
    private final RestorationRecord record;
    private boolean restored;

    private PlacedBlock(
            Block block,
            RestorationCoordinator restoration,
            RestorationRecord record) {
        this.block = block;
        this.restoration = restoration;
        this.record = record;
        key = BlockKey.from(block);
    }

    static PlacedBlock prepare(
            Block block,
            Material material,
            UUID sessionId,
            String arenaId,
            RestorationCoordinator restoration) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(arenaId, "arenaId");
        Objects.requireNonNull(restoration, "restoration");
        RestorationRecord record = restoration.prepare(sessionId, arenaId, block, material);
        return new PlacedBlock(block, restoration, record);
    }

    void place() {
        restoration.place(record);
    }

    BlockKey key() {
        return key;
    }

    boolean isIntact() {
        return !restored
                && block.getType().name().equals(record.expectedState().material())
                && block.getBlockData().getAsString().equals(record.expectedState().blockData());
    }

    void restore() {
        if (restored) {
            return;
        }
        RestorationOutcome outcome = restoration.restore(record);
        if (!outcome.completed()) {
            throw new IllegalStateException(
                    "Parkour block restoration remains pending for " + key + ": " + outcome);
        }
        restored = true;
    }
}
