package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void asynchronousCompletionIsIdempotentAndPreservesANewerOwner() {
        BlockLeaseRegistry registry = new BlockLeaseRegistry();
        BlockKey key = new BlockKey(UUID.randomUUID(), 3, 80, 9);
        BlockLeaseRegistry.BlockLease old =
                new BlockLeaseRegistry.BlockLease(UUID.randomUUID(), 1L, 1L);
        BlockLeaseRegistry.BlockLease newer =
                new BlockLeaseRegistry.BlockLease(UUID.randomUUID(), 2L, 1L);

        assertTrue(registry.reserve(key, old));
        assertEquals(
                BlockLeaseRegistry.CompletionRelease.RELEASED,
                registry.completeRelease(key, old));
        assertEquals(
                BlockLeaseRegistry.CompletionRelease.ALREADY_RELEASED,
                registry.completeRelease(key, old));
        assertTrue(registry.reserve(key, newer));
        assertEquals(
                BlockLeaseRegistry.CompletionRelease.NEWER_OWNER_PRESERVED,
                registry.completeRelease(key, old));
        assertTrue(registry.owns(key, newer));
    }
}
