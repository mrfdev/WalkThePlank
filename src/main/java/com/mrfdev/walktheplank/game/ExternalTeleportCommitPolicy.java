package com.mrfdev.walktheplank.game;

import java.util.Objects;
import java.util.UUID;

/** Exact-enough post-event confirmation before durable external-teleport cleanup. */
final class ExternalTeleportCommitPolicy {
    private static final double MAXIMUM_DISTANCE_SQUARED = 0.0001D;

    private ExternalTeleportCommitPolicy() {
    }

    static boolean reached(
            UUID expectedWorld,
            double expectedX,
            double expectedY,
            double expectedZ,
            UUID actualWorld,
            double actualX,
            double actualY,
            double actualZ) {
        Objects.requireNonNull(expectedWorld, "expectedWorld");
        if (!expectedWorld.equals(actualWorld)
                || !Double.isFinite(expectedX)
                || !Double.isFinite(expectedY)
                || !Double.isFinite(expectedZ)
                || !Double.isFinite(actualX)
                || !Double.isFinite(actualY)
                || !Double.isFinite(actualZ)) {
            return false;
        }
        double x = expectedX - actualX;
        double y = expectedY - actualY;
        double z = expectedZ - actualZ;
        return x * x + y * y + z * z <= MAXIMUM_DISTANCE_SQUARED;
    }
}
