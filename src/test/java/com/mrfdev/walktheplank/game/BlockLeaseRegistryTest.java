package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlockLeaseRegistryTest {
    @Test
    void stalePlatformCannotReleaseNewerBlockOwner() {
        BlockLeaseRegistry registry = new BlockLeaseRegistry();
        UUID runId = UUID.randomUUID();
        BlockKey key = new BlockKey(UUID.randomUUID(), 10, 64, -4);
        BlockLeaseRegistry.BlockLease first =
                new BlockLeaseRegistry.BlockLease(runId, 7L, 1L);
        BlockLeaseRegistry.BlockLease second =
                new BlockLeaseRegistry.BlockLease(runId, 7L, 2L);

        assertTrue(registry.reserve(key, first));
        assertFalse(registry.reserve(key, second));
        assertTrue(registry.release(key, first));
        assertTrue(registry.reserve(key, second));
        assertFalse(registry.release(key, first));
        assertTrue(registry.owns(key, second));
    }
}
