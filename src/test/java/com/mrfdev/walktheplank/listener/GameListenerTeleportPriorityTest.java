package com.mrfdev.walktheplank.listener;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

class GameListenerTeleportPriorityTest {
    @Test
    void externalTeleportDecisionRunsAtHighestAndSkipsPriorCancellation() throws Exception {
        EventHandler handler = handler("onTeleportDecision");

        assertAll(
                () -> assertEquals(EventPriority.HIGHEST, handler.priority()),
                () -> assertTrue(handler.ignoreCancelled()));
    }

    @Test
    void externalTeleportOutcomeOnlyObservesAtMonitorIncludingCancellation() throws Exception {
        EventHandler handler = handler("onTeleportOutcome");

        assertAll(
                () -> assertEquals(EventPriority.MONITOR, handler.priority()),
                () -> assertFalse(handler.ignoreCancelled()));
    }

    private static EventHandler handler(String methodName) throws Exception {
        Method method = GameListener.class.getDeclaredMethod(methodName, PlayerTeleportEvent.class);
        return method.getAnnotation(EventHandler.class);
    }
}
