package com.mrfdev.walktheplank.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PlacementCommitPolicyTest {
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final BlockKey BLOCK = new BlockKey(UUID.randomUUID(), 8, 70, -12);
    private static final PlacementCommitPolicy.CapturedOwner CAPTURED =
            new PlacementCommitPolicy.CapturedOwner(
                    PLAYER_ID, RUN_ID, "summer", 11L, 4L, BLOCK);

    @Test
    void exactLiveOwnershipCommits() {
        assertEquals(
                PlacementCommitPolicy.Decision.COMMIT,
                PlacementCommitPolicy.decide(CAPTURED, validLive()));
    }

    @ParameterizedTest
    @MethodSource("rejections")
    void everyStaleOrUnownedContextRejects(
            UnaryOperator<PlacementCommitPolicy.LiveOwner> mutation,
            PlacementCommitPolicy.Decision expected) {
        assertEquals(expected, PlacementCommitPolicy.decide(CAPTURED, mutation.apply(validLive())));
    }

    private static Stream<Arguments> rejections() {
        return Stream.of(
                Arguments.of(
                        (UnaryOperator<PlacementCommitPolicy.LiveOwner>) live -> copy(
                                live, false, live.sessionCurrent(), live.playerOnline(),
                                live.arenaLeaseOwned(), live.blockLeaseOwned()),
                        PlacementCommitPolicy.Decision.RUNTIME_CLOSING),
                Arguments.of(
                        (UnaryOperator<PlacementCommitPolicy.LiveOwner>) live -> copy(
                                live, live.acceptingCompletions(), false, live.playerOnline(),
                                live.arenaLeaseOwned(), live.blockLeaseOwned()),
                        PlacementCommitPolicy.Decision.SESSION_REPLACED),
                Arguments.of(
                        (UnaryOperator<PlacementCommitPolicy.LiveOwner>) live -> copy(
                                live, live.acceptingCompletions(), live.sessionCurrent(), false,
                                live.arenaLeaseOwned(), live.blockLeaseOwned()),
                        PlacementCommitPolicy.Decision.PLAYER_OFFLINE),
                Arguments.of(
                        (UnaryOperator<PlacementCommitPolicy.LiveOwner>) live -> new PlacementCommitPolicy.LiveOwner(
                                UUID.randomUUID(), live.runId(), live.arenaId(), live.sessionGeneration(),
                                live.platformGeneration(), live.block(), true, true, true, true, true),
                        PlacementCommitPolicy.Decision.PLAYER_CHANGED),
                Arguments.of(
                        (UnaryOperator<PlacementCommitPolicy.LiveOwner>) live -> new PlacementCommitPolicy.LiveOwner(
                                live.playerId(), UUID.randomUUID(), live.arenaId(), live.sessionGeneration(),
                                live.platformGeneration(), live.block(), true, true, true, true, true),
                        PlacementCommitPolicy.Decision.RUN_CHANGED),
                Arguments.of(
                        (UnaryOperator<PlacementCommitPolicy.LiveOwner>) live -> new PlacementCommitPolicy.LiveOwner(
                                live.playerId(), live.runId(), "other", live.sessionGeneration(),
                                live.platformGeneration(), live.block(), true, true, true, true, true),
                        PlacementCommitPolicy.Decision.ARENA_CHANGED),
                Arguments.of(
                        (UnaryOperator<PlacementCommitPolicy.LiveOwner>) live -> new PlacementCommitPolicy.LiveOwner(
                                live.playerId(), live.runId(), live.arenaId(), live.sessionGeneration() + 1L,
                                live.platformGeneration(), live.block(), true, true, true, true, true),
                        PlacementCommitPolicy.Decision.SESSION_GENERATION_CHANGED),
                Arguments.of(
                        (UnaryOperator<PlacementCommitPolicy.LiveOwner>) live -> new PlacementCommitPolicy.LiveOwner(
                                live.playerId(), live.runId(), live.arenaId(), live.sessionGeneration(),
                                live.platformGeneration() + 1L, live.block(), true, true, true, true, true),
                        PlacementCommitPolicy.Decision.PLATFORM_GENERATION_CHANGED),
                Arguments.of(
                        (UnaryOperator<PlacementCommitPolicy.LiveOwner>) live -> new PlacementCommitPolicy.LiveOwner(
                                live.playerId(), live.runId(), live.arenaId(), live.sessionGeneration(),
                                live.platformGeneration(), new BlockKey(BLOCK.worldId(), 9, 70, -12),
                                true, true, true, true, true),
                        PlacementCommitPolicy.Decision.BLOCK_CHANGED),
                Arguments.of(
                        (UnaryOperator<PlacementCommitPolicy.LiveOwner>) live -> copy(
                                live, true, true, true, false, true),
                        PlacementCommitPolicy.Decision.ARENA_LEASE_LOST),
                Arguments.of(
                        (UnaryOperator<PlacementCommitPolicy.LiveOwner>) live -> copy(
                                live, true, true, true, true, false),
                        PlacementCommitPolicy.Decision.BLOCK_LEASE_LOST));
    }

    private static PlacementCommitPolicy.LiveOwner validLive() {
        return new PlacementCommitPolicy.LiveOwner(
                PLAYER_ID, RUN_ID, "summer", 11L, 4L, BLOCK,
                true, true, true, true, true);
    }

    private static PlacementCommitPolicy.LiveOwner copy(
            PlacementCommitPolicy.LiveOwner live,
            boolean accepting,
            boolean current,
            boolean online,
            boolean arenaOwned,
            boolean blockOwned) {
        return new PlacementCommitPolicy.LiveOwner(
                live.playerId(),
                live.runId(),
                live.arenaId(),
                live.sessionGeneration(),
                live.platformGeneration(),
                live.block(),
                accepting,
                current,
                online,
                arenaOwned,
                blockOwned);
    }
}
