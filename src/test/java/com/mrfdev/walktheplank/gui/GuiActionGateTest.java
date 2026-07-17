package com.mrfdev.walktheplank.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

class GuiActionGateTest {
    @Test
    void permitsOnlyPlainLeftClickAndRejectsInventoryAbuseVariants() {
        GuiActionGate gate = new GuiActionGate();
        GuiSessionRegistry.SessionKey session = sessionKey(1L);

        for (ClickType clickType : ClickType.values()) {
            GuiActionGate.BeginResult expected = clickType == ClickType.LEFT
                    ? GuiActionGate.BeginResult.ACCEPTED
                    : GuiActionGate.BeginResult.DISALLOWED_CLICK;
            assertEquals(
                    expected,
                    gate.tryBegin(session, 13, clickType, 1_000_000L),
                    clickType.name());
            gate.complete(session);
            gate.invalidate(session);
        }
    }

    @Test
    void allowsAtMostOnePendingActionAcrossAllSlotsInASession() {
        GuiActionGate gate = new GuiActionGate();
        GuiSessionRegistry.SessionKey session = sessionKey(1L);

        assertEquals(
                GuiActionGate.BeginResult.ACCEPTED,
                gate.tryBegin(session, 13, ClickType.LEFT, 1_000_000L));
        assertEquals(
                GuiActionGate.BeginResult.ACTION_PENDING,
                gate.tryBegin(session, 16, ClickType.LEFT, 2_000_000L));
        assertEquals(1, gate.pendingCount());
    }

    @Test
    void suppressesDuplicateClicksUntilTheWindowExpires() {
        GuiActionGate gate = new GuiActionGate();
        GuiSessionRegistry.SessionKey session = sessionKey(1L);
        long start = 5_000_000_000L;

        assertEquals(
                GuiActionGate.BeginResult.ACCEPTED,
                gate.tryBegin(session, 13, ClickType.LEFT, start));
        gate.complete(session);
        assertEquals(
                GuiActionGate.BeginResult.DUPLICATE_CLICK,
                gate.tryBegin(
                        session,
                        13,
                        ClickType.LEFT,
                        start + GuiActionGate.DUPLICATE_WINDOW.toNanos() - 1L));
        assertEquals(
                GuiActionGate.BeginResult.ACCEPTED,
                gate.tryBegin(
                        session,
                        13,
                        ClickType.LEFT,
                        start + GuiActionGate.DUPLICATE_WINDOW.toNanos()));
    }

    @Test
    void newNonceOrGenerationCannotInheritStalePendingState() {
        GuiActionGate gate = new GuiActionGate();
        UUID owner = UUID.randomUUID();
        GuiSessionRegistry.SessionKey oldSession =
                new GuiSessionRegistry.SessionKey(owner, UUID.randomUUID(), 7L);
        GuiSessionRegistry.SessionKey newSession =
                new GuiSessionRegistry.SessionKey(owner, UUID.randomUUID(), 8L);

        assertEquals(
                GuiActionGate.BeginResult.ACCEPTED,
                gate.tryBegin(oldSession, 13, ClickType.LEFT, 1L));
        assertEquals(
                GuiActionGate.BeginResult.ACCEPTED,
                gate.tryBegin(newSession, 13, ClickType.LEFT, 2L));

        gate.complete(oldSession);

        assertEquals(1, gate.pendingCount());
        assertEquals(
                GuiActionGate.BeginResult.ACTION_PENDING,
                gate.tryBegin(newSession, 16, ClickType.LEFT, 3L));
    }

    @Test
    void closeAndQuitInvalidationReleasePendingActionsAndClickHistory() {
        GuiActionGate gate = new GuiActionGate();
        UUID owner = UUID.randomUUID();
        GuiSessionRegistry.SessionKey session =
                new GuiSessionRegistry.SessionKey(owner, UUID.randomUUID(), 1L);

        assertEquals(
                GuiActionGate.BeginResult.ACCEPTED,
                gate.tryBegin(session, 13, ClickType.LEFT, 1_000L));
        gate.invalidate(session);
        assertEquals(0, gate.pendingCount());
        assertEquals(
                GuiActionGate.BeginResult.ACCEPTED,
                gate.tryBegin(session, 13, ClickType.LEFT, 1_001L));

        gate.invalidateOwner(owner);

        assertEquals(0, gate.pendingCount());
        assertEquals(
                GuiActionGate.BeginResult.ACCEPTED,
                gate.tryBegin(session, 13, ClickType.LEFT, 1_002L));
    }

    @Test
    void negativeSlotsNeverStartActions() {
        GuiActionGate gate = new GuiActionGate();

        assertEquals(
                GuiActionGate.BeginResult.INVALID_SLOT,
                gate.tryBegin(sessionKey(1L), -1, ClickType.LEFT, 1L));
        assertEquals(0, gate.pendingCount());
    }

    private static GuiSessionRegistry.SessionKey sessionKey(long generation) {
        return new GuiSessionRegistry.SessionKey(
                UUID.randomUUID(),
                UUID.randomUUID(),
                generation);
    }
}
