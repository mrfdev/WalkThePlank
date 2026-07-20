package com.mrfdev.walktheplank.recovery;

import java.util.Objects;

/**
 * Chooses the world-write mechanism for an exact captured block snapshot.
 *
 * <p>Air has no tile or component payload to preserve. Restoring it through the direct block API
 * also guarantees the real AIR transition is sent to clients on current Paper. Non-air snapshots
 * retain the structure path so tile data, PDC, and unknown metadata survive restoration.</p>
 */
enum RestorationWriteStrategy {
    DIRECT_BLOCK_DATA,
    STRUCTURE_SNAPSHOT;

    static RestorationWriteStrategy forState(SerializedBlockState state) {
        String material = Objects.requireNonNull(state, "state").material();
        return switch (material) {
            case "AIR", "CAVE_AIR", "VOID_AIR" -> DIRECT_BLOCK_DATA;
            default -> STRUCTURE_SNAPSHOT;
        };
    }
}
