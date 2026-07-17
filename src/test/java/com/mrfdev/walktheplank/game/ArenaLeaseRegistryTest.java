package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArenaLeaseRegistryTest {
    @Test
    void staleRunCannotReleaseNewerArenaOwner() {
        ArenaLeaseRegistry registry = new ArenaLeaseRegistry();
        ArenaLeaseRegistry.ArenaLease oldLease =
                new ArenaLeaseRegistry.ArenaLease(UUID.randomUUID(), 1L);
        ArenaLeaseRegistry.ArenaLease newLease =
                new ArenaLeaseRegistry.ArenaLease(UUID.randomUUID(), 2L);

        assertTrue(registry.reserve("summer", oldLease));
        assertFalse(registry.reserve("summer", newLease));
        assertTrue(registry.release("summer", oldLease));
        assertTrue(registry.reserve("summer", newLease));
        assertFalse(registry.release("summer", oldLease));
        assertTrue(registry.owns("summer", newLease));
    }

    @Test
    void recoverySnapshotIncludesEveryPendingActiveOrQuarantinedLeaseOwner() {
        ArenaLeaseRegistry registry = new ArenaLeaseRegistry();
        UUID pendingRun = UUID.randomUUID();
        UUID activeRun = UUID.randomUUID();
        UUID quarantinedRun = UUID.randomUUID();
        ArenaLeaseRegistry.ArenaLease pending =
                new ArenaLeaseRegistry.ArenaLease(pendingRun, 1L);
        ArenaLeaseRegistry.ArenaLease active =
                new ArenaLeaseRegistry.ArenaLease(activeRun, 2L);
        ArenaLeaseRegistry.ArenaLease quarantined =
                new ArenaLeaseRegistry.ArenaLease(quarantinedRun, 3L);

        assertTrue(registry.reserve("pending", pending));
        assertTrue(registry.reserve("active", active));
        assertTrue(registry.reserve("quarantined", quarantined));
        assertEquals(
                java.util.Set.of(pendingRun, activeRun, quarantinedRun),
                registry.ownedRunIds());

        assertTrue(registry.release("pending", pending));
        assertEquals(
                java.util.Set.of(activeRun, quarantinedRun),
                registry.ownedRunIds());
    }
}
