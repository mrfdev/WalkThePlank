package com.mrfdev.walktheplank.listener;

/** Includes the piston base/head even when an event has no moved-block entries. */
final class PistonProtectionPolicy {
    private PistonProtectionPolicy() {
    }

    static boolean shouldCancel(
            boolean pistonBaseProtected,
            boolean pistonHeadProtected,
            boolean movedCellProtected) {
        return pistonBaseProtected || pistonHeadProtected || movedCellProtected;
    }
}
