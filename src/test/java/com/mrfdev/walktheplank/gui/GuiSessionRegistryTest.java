package com.mrfdev.walktheplank.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class GuiSessionRegistryTest {
    @Test
    void requiresOwnerNonceGenerationAndExactInventoryIdentity() {
        GuiSessionRegistry<Object, String> registry = new GuiSessionRegistry<>();
        UUID owner = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        Object inventory = new EqualInventory("same");
        Object equalButDifferentInventory = new EqualInventory("same");

        GuiSessionRegistry.Session<Object, String> session =
                registry.activate(owner, nonce, inventory, "page");

        assertTrue(registry.isCurrent(session));
        assertTrue(registry.isCurrent(
                owner,
                nonce,
                session.generation(),
                inventory));
        assertFalse(registry.isCurrent(
                UUID.randomUUID(),
                nonce,
                session.generation(),
                inventory));
        assertFalse(registry.isCurrent(
                owner,
                UUID.randomUUID(),
                session.generation(),
                inventory));
        assertFalse(registry.isCurrent(
                owner,
                nonce,
                session.generation() + 1L,
                inventory));
        assertFalse(registry.isCurrent(
                owner,
                nonce,
                session.generation(),
                equalButDifferentInventory));
    }

    @Test
    void replacingAMenuAdvancesGenerationAndMakesOldCallbacksStale() {
        GuiSessionRegistry<Object, String> registry = new GuiSessionRegistry<>();
        UUID owner = UUID.randomUUID();
        Object oldInventory = new Object();
        Object newInventory = new Object();

        GuiSessionRegistry.Session<Object, String> oldSession =
                registry.activate(owner, UUID.randomUUID(), oldInventory, "old");
        GuiSessionRegistry.Session<Object, String> newSession =
                registry.activate(owner, UUID.randomUUID(), newInventory, "new");

        assertEquals(oldSession.generation() + 1L, newSession.generation());
        assertFalse(registry.isCurrent(oldSession));
        assertFalse(registry.removeIfCurrent(oldSession));
        assertFalse(registry.removeIfCurrent(
                owner,
                oldSession.nonce(),
                oldSession.generation(),
                oldInventory));
        assertTrue(registry.isCurrent(newSession));
        assertSame(newSession, registry.current(owner));
    }

    @Test
    void exactCloseRemovesCurrentSessionWhileStaleCloseCannot() {
        GuiSessionRegistry<Object, String> registry = new GuiSessionRegistry<>();
        UUID owner = UUID.randomUUID();
        Object inventory = new Object();
        GuiSessionRegistry.Session<Object, String> session =
                registry.activate(owner, UUID.randomUUID(), inventory, "current");

        assertFalse(registry.removeIfCurrent(
                owner,
                session.nonce(),
                session.generation(),
                new Object()));
        assertTrue(registry.isCurrent(session));
        assertTrue(registry.removeIfCurrent(
                owner,
                session.nonce(),
                session.generation(),
                inventory));
        assertNull(registry.current(owner));
    }

    @Test
    void quitRemovalOnlyInvalidatesThatOwner() {
        GuiSessionRegistry<Object, String> registry = new GuiSessionRegistry<>();
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        GuiSessionRegistry.Session<Object, String> first =
                registry.activate(firstOwner, UUID.randomUUID(), new Object(), "first");
        GuiSessionRegistry.Session<Object, String> second =
                registry.activate(secondOwner, UUID.randomUUID(), new Object(), "second");

        assertSame(first, registry.remove(firstOwner));

        assertNull(registry.current(firstOwner));
        assertTrue(registry.isCurrent(second));
    }

    private record EqualInventory(String value) {
    }
}
