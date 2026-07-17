package com.mrfdev.walktheplank.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Main-thread registry for authoritative GUI sessions.
 *
 * <p>A session only matches when its owner, random nonce, generation and exact
 * inventory object all match. Inventory equality is deliberately insufficient.
 */
final class GuiSessionRegistry<I, P> {
    private final Map<UUID, Session<I, P>> sessions = new HashMap<>();
    private long generation;

    Session<I, P> activate(UUID ownerId, UUID nonce, I inventory, P page) {
        long nextGeneration = Math.incrementExact(generation);
        generation = nextGeneration;
        Session<I, P> session = new Session<>(
                ownerId,
                nonce,
                nextGeneration,
                inventory,
                page);
        sessions.put(ownerId, session);
        return session;
    }

    Session<I, P> current(UUID ownerId) {
        return sessions.get(Objects.requireNonNull(ownerId, "ownerId"));
    }

    boolean isCurrent(Session<I, P> expected) {
        Objects.requireNonNull(expected, "expected");
        return sessions.get(expected.ownerId()) == expected;
    }

    boolean isCurrent(
            UUID ownerId,
            UUID nonce,
            long expectedGeneration,
            I inventory) {
        Session<I, P> current = sessions.get(Objects.requireNonNull(ownerId, "ownerId"));
        return current != null
                && current.nonce().equals(Objects.requireNonNull(nonce, "nonce"))
                && current.generation() == expectedGeneration
                && current.inventory() == Objects.requireNonNull(inventory, "inventory");
    }

    boolean removeIfCurrent(Session<I, P> expected) {
        Objects.requireNonNull(expected, "expected");
        if (sessions.get(expected.ownerId()) != expected) {
            return false;
        }
        sessions.remove(expected.ownerId());
        return true;
    }

    boolean removeIfCurrent(
            UUID ownerId,
            UUID nonce,
            long expectedGeneration,
            I inventory) {
        Session<I, P> current = sessions.get(Objects.requireNonNull(ownerId, "ownerId"));
        if (current == null
                || !current.nonce().equals(Objects.requireNonNull(nonce, "nonce"))
                || current.generation() != expectedGeneration
                || current.inventory() != Objects.requireNonNull(inventory, "inventory")) {
            return false;
        }
        sessions.remove(ownerId);
        return true;
    }

    Session<I, P> remove(UUID ownerId) {
        return sessions.remove(Objects.requireNonNull(ownerId, "ownerId"));
    }

    List<Session<I, P>> snapshot() {
        return List.copyOf(sessions.values());
    }

    int size() {
        return sessions.size();
    }

    void clear() {
        sessions.clear();
    }

    record Session<I, P>(
            UUID ownerId,
            UUID nonce,
            long generation,
            I inventory,
            P page) {
        Session {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(nonce, "nonce");
            if (generation <= 0L) {
                throw new IllegalArgumentException("generation must be positive");
            }
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(page, "page");
        }

        SessionKey key() {
            return new SessionKey(ownerId, nonce, generation);
        }
    }

    record SessionKey(UUID ownerId, UUID nonce, long generation) {
        SessionKey {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(nonce, "nonce");
            if (generation <= 0L) {
                throw new IllegalArgumentException("generation must be positive");
            }
        }
    }
}
