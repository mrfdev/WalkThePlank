package com.mrfdev.walktheplank.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerQueueTest {
    private static final Instant START = Instant.parse("2026-07-14T12:00:00Z");

    @Test
    void preservesFifoAndRequiresExplicitReadinessConsumption() {
        PlayerQueue queue = queue();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        assertEquals(PlayerQueue.JoinState.JOINED, queue.join(first, START).state());
        assertEquals(PlayerQueue.JoinState.JOINED, queue.join(second, START).state());
        assertEquals(PlayerQueue.JoinState.JOINED, queue.join(third, START).state());

        PlayerQueue.QueueUpdate update = queue.refresh(2, ignored -> true, START);
        assertEquals(List.of(first, second), update.assigned().stream()
                .map(PlayerQueue.ReadyClaim::playerId)
                .toList());
        assertEquals(1, queue.position(first));
        assertEquals(3, queue.position(third));
        assertTrue(queue.consumeReady(first, START.plusSeconds(1)));

        PlayerQueue.QueueUpdate next = queue.refresh(1, ignored -> true, START.plusSeconds(1));
        assertTrue(next.assigned().isEmpty());
        assertEquals(List.of(second, third), queue.orderedPlayers());
    }

    @Test
    void expiresReadinessAndAppliesJoinCooldown() {
        PlayerQueue queue = queue();
        UUID player = UUID.randomUUID();
        queue.join(player, START);
        queue.refresh(1, ignored -> true, START);

        PlayerQueue.QueueUpdate update = queue.refresh(1, ignored -> true, START.plusSeconds(31));
        assertEquals(List.of(player), update.expired());
        assertFalse(queue.consumeReady(player, START.plusSeconds(31)));
        assertEquals(PlayerQueue.JoinState.COOLDOWN, queue.join(player, START.plusSeconds(31)).state());
        assertEquals(PlayerQueue.JoinState.JOINED, queue.join(player, START.plusSeconds(42)).state());
    }

    @Test
    void dropsOfflinePlayersAndNeverAssignsWhilePaused() {
        PlayerQueue queue = queue();
        UUID offline = UUID.randomUUID();
        UUID online = UUID.randomUUID();
        queue.join(offline, START);
        queue.join(online, START);
        queue.setPaused(true);

        PlayerQueue.QueueUpdate paused = queue.refresh(1, online::equals, START);
        assertEquals(List.of(offline), paused.removed());
        assertTrue(paused.assigned().isEmpty());
        assertEquals(List.of(online), queue.orderedPlayers());
        assertEquals(PlayerQueue.JoinState.COOLDOWN, queue.join(offline, START).state());

        queue.setPaused(false);
        assertEquals(online, queue.refresh(1, ignored -> true, START).assigned().getFirst().playerId());
    }

    @Test
    void pauseFreezesExistingReadinessWindowAndBlocksConsumption() {
        PlayerQueue queue = queue();
        UUID player = UUID.randomUUID();
        queue.join(player, START);
        queue.refresh(1, ignored -> true, START);

        queue.setPaused(true, START.plusSeconds(10));
        PlayerQueue.QueueUpdate whilePaused =
                queue.refresh(1, ignored -> true, START.plusSeconds(100));
        assertAll(
                () -> assertTrue(whilePaused.expired().isEmpty()),
                () -> assertEquals(1, queue.readyCount()),
                () -> assertFalse(queue.consumeReady(player, START.plusSeconds(100))));

        queue.setPaused(false, START.plusSeconds(100));
        assertAll(
                () -> assertTrue(queue.refresh(
                                1, ignored -> true, START.plusSeconds(119))
                        .expired()
                        .isEmpty()),
                () -> assertTrue(queue.consumeReady(player, START.plusSeconds(119))));
    }

    @Test
    void drainAndQuitRemovalAreDeterministic() {
        PlayerQueue queue = queue();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        queue.join(first, START);
        queue.join(second, START);
        queue.refresh(1, ignored -> true, START);

        assertTrue(queue.remove(first, START));
        assertEquals(List.of(second), queue.drain());
        assertEquals(0, queue.size());
        assertFalse(queue.remove(second, START));
    }

    private static PlayerQueue queue() {
        return new PlayerQueue(Duration.ofSeconds(10), Duration.ofSeconds(30));
    }
}
