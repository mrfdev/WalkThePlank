package com.mrfdev.walktheplank.reward;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrfdev.walktheplank.reward.RewardService.PreparedRewardPlan;
import com.mrfdev.walktheplank.reward.RewardService.RewardPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class RewardServiceTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("61219285-43f3-4693-869d-45d578587ca1");

    @Test
    void preflightsEveryRootAndAbortsEntirePlanWhenOneIsMissing() {
        FakeCommandGateway gateway = new FakeCommandGateway(Set.of("first", "third"));
        RecordingHandler logs = new RecordingHandler();
        RewardService service = service(gateway, logs);
        RewardPlan plan = new RewardPlan(false, List.of(
                "/first {{playerName}}",
                "missing {{score}}",
                "third {{playerUuid}}"));

        service.execute(plan, "Alice", PLAYER_ID, 73, true);

        assertEquals(List.of("first", "missing", "third"), gateway.checkedRoots);
        assertTrue(gateway.dispatchedCommands.isEmpty());
        assertTrue(logs.records.stream().anyMatch(record ->
                record.getLevel() == Level.SEVERE
                        && record.getMessage().contains("unavailable command roots: missing")));
    }

    @Test
    void expandsAndDispatchesAValidPlanInConfiguredOrder() {
        FakeCommandGateway gateway = new FakeCommandGateway(Set.of("give", "cmi"));
        RewardService service = service(gateway, new RecordingHandler());
        RewardPlan plan = new RewardPlan(false, List.of(
                "  /give {{playerName}} diamond {{score}}  ",
                "cmi msg {{playerName}} UUID={{playerUuid}}",
                "   "));

        service.execute(plan, "Alice", PLAYER_ID, 73, false);

        assertEquals(List.of("give", "cmi"), gateway.checkedRoots);
        assertEquals(List.of(
                "give Alice diamond 73",
                "cmi msg Alice UUID=" + PLAYER_ID), gateway.dispatchedCommands);
    }

    @Test
    void personalBestGateSkipsPreflightAndExecution() {
        FakeCommandGateway gateway = new FakeCommandGateway(Set.of());
        RewardService service = service(gateway, new RecordingHandler());

        service.execute(
                new RewardPlan(true, List.of("missing {{playerName}}")),
                "Alice",
                PLAYER_ID,
                73,
                false);

        assertTrue(gateway.checkedRoots.isEmpty());
        assertTrue(gateway.dispatchedCommands.isEmpty());
    }

    @Test
    void lineBreakAbortsBeforeAnyRootLookupOrDispatch() {
        FakeCommandGateway gateway = new FakeCommandGateway(Set.of("first", "second"));
        RecordingHandler logs = new RecordingHandler();
        RewardService service = service(gateway, logs);

        service.execute(
                new RewardPlan(false, List.of("first Alice", "second Alice\nthird Alice")),
                "Alice",
                PLAYER_ID,
                73,
                true);

        assertTrue(gateway.checkedRoots.isEmpty());
        assertTrue(gateway.dispatchedCommands.isEmpty());
        assertTrue(logs.records.stream().anyMatch(record ->
                record.getLevel() == Level.SEVERE
                        && record.getMessage().contains("line break")));
    }

    @Test
    void configuredAllowListRejectsAnOtherwiseAvailableRootBeforeLookup() {
        FakeCommandGateway gateway = new FakeCommandGateway(Set.of("say", "op"));
        RecordingHandler logs = new RecordingHandler();
        RewardService service = service(gateway, logs);
        RewardPlan plan = new RewardPlan(
                false,
                List.of("say event started", "op Alice"),
                Set.of("say"));

        PreparedRewardPlan prepared = service.prepare(
                plan, "Alice", PLAYER_ID, 73, true);

        assertAll(
                () -> assertFalse(prepared.executable()),
                () -> assertEquals(
                        RewardService.RewardPreparationStatus.ABORTED_DISALLOWED_ROOT,
                        prepared.status()),
                () -> assertEquals(Set.of("op"), prepared.unavailableRoots()),
                () -> assertTrue(gateway.checkedRoots.isEmpty()),
                () -> assertTrue(gateway.dispatchedCommands.isEmpty()));
    }

    @Test
    void preparationExposesOnlyRootsAndHashesToTheDurableLedger() {
        FakeCommandGateway gateway = new FakeCommandGateway(Set.of("give"));
        RewardService service = service(gateway, new RecordingHandler());

        PreparedRewardPlan prepared = service.prepare(
                new RewardPlan(false, List.of("give {{playerName}} diamond {{score}}")),
                "Alice",
                PLAYER_ID,
                73,
                true);

        assertAll(
                () -> assertTrue(prepared.executable()),
                () -> assertEquals("give", prepared.ledgerSteps().get(0).commandRoot()),
                () -> assertEquals(64, prepared.ledgerSteps().get(0).commandHash().length()),
                () -> assertTrue(prepared.steps().get(0).command().contains("Alice")),
                () -> assertFalse(prepared.steps().get(0).toString().contains("Alice")));
    }

    private static RewardService service(
            FakeCommandGateway gateway,
            RecordingHandler handler) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        return new RewardService(
                () -> {
                    throw new AssertionError("Runtime settings were not expected");
                },
                logger,
                gateway);
    }

    private static final class FakeCommandGateway implements RewardService.CommandGateway {
        private final Set<String> availableRoots;
        private final List<String> checkedRoots = new ArrayList<>();
        private final List<String> dispatchedCommands = new ArrayList<>();

        private FakeCommandGateway(Set<String> availableRoots) {
            this.availableRoots = Set.copyOf(availableRoots);
        }

        @Override
        public boolean isPrimaryThread() {
            return true;
        }

        @Override
        public boolean commandExists(String root) {
            checkedRoots.add(root);
            return availableRoots.contains(root);
        }

        @Override
        public boolean dispatch(String command) {
            dispatchedCommands.add(command);
            return true;
        }
    }

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
