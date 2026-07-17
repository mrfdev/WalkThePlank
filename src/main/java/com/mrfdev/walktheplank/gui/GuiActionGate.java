package com.mrfdev.walktheplank.gui;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.event.inventory.ClickType;

/**
 * Main-thread gate for GUI actions.
 *
 * <p>Only a plain left click can start an action. Each session may have at most
 * one pending action and repeated clicks on the same slot are suppressed.
 */
final class GuiActionGate {
    static final Duration DUPLICATE_WINDOW = Duration.ofMillis(250);

    private final Set<GuiSessionRegistry.SessionKey> pending = new HashSet<>();
    private final Map<ClickKey, Long> recentClicks = new HashMap<>();

    BeginResult tryBegin(
            GuiSessionRegistry.SessionKey session,
            int slot,
            ClickType clickType,
            long nowNanos) {
        Objects.requireNonNull(session, "session");
        if (clickType != ClickType.LEFT) {
            return BeginResult.DISALLOWED_CLICK;
        }
        if (slot < 0) {
            return BeginResult.INVALID_SLOT;
        }
        if (pending.contains(session)) {
            return BeginResult.ACTION_PENDING;
        }

        ClickKey key = new ClickKey(session, slot);
        Long previous = recentClicks.get(key);
        if (previous != null) {
            long elapsed = nowNanos - previous;
            if (elapsed >= 0L && elapsed < DUPLICATE_WINDOW.toNanos()) {
                return BeginResult.DUPLICATE_CLICK;
            }
        }

        pending.add(session);
        recentClicks.put(key, nowNanos);
        return BeginResult.ACCEPTED;
    }

    void complete(GuiSessionRegistry.SessionKey session) {
        pending.remove(Objects.requireNonNull(session, "session"));
    }

    void invalidate(GuiSessionRegistry.SessionKey session) {
        Objects.requireNonNull(session, "session");
        pending.remove(session);
        recentClicks.keySet().removeIf(key -> key.session().equals(session));
    }

    void invalidateOwner(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        pending.removeIf(session -> session.ownerId().equals(ownerId));
        recentClicks.keySet().removeIf(key -> key.session().ownerId().equals(ownerId));
    }

    void clear() {
        pending.clear();
        recentClicks.clear();
    }

    int pendingCount() {
        return pending.size();
    }

    enum BeginResult {
        ACCEPTED,
        DISALLOWED_CLICK,
        INVALID_SLOT,
        ACTION_PENDING,
        DUPLICATE_CLICK
    }

    private record ClickKey(GuiSessionRegistry.SessionKey session, int slot) {
        private ClickKey {
            Objects.requireNonNull(session, "session");
        }
    }
}
