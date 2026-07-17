package com.mrfdev.walktheplank.game;

/** Pure admission rule for activating the exact pending start after its durable write completes. */
final class StartActivationPolicy {
    private StartActivationPolicy() {
    }

    static boolean mayActivate(
            boolean shuttingDown,
            boolean exactPendingStart,
            boolean alreadyActive,
            boolean arenaReserved,
            boolean notCancelled,
            boolean online,
            boolean hasPlayPermission,
            boolean supportedGameMode,
            boolean movementStateEligible,
            boolean hasDisallowedMovementEffect) {
        return !shuttingDown
                && exactPendingStart
                && !alreadyActive
                && arenaReserved
                && notCancelled
                && online
                && hasPlayPermission
                && supportedGameMode
                && movementStateEligible
                && !hasDisallowedMovementEffect;
    }
}
