package com.mrfdev.walktheplank.game;

final class LandingPolicy {
    private LandingPolicy() {
    }

    static boolean isGroundedAndNotAscending(boolean hasGroundSupport, double verticalVelocity) {
        return hasGroundSupport && Double.isFinite(verticalVelocity) && verticalVelocity <= 0.0;
    }
}
