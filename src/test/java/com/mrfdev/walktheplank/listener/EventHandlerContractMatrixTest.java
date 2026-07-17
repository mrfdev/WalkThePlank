package com.mrfdev.walktheplank.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mrfdev.walktheplank.gui.MenuService;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

final class EventHandlerContractMatrixTest {
    @Test
    void locksEveryGameplayListenerPriorityAndCancellationContract() {
        Map<String, Contract> expected = new LinkedHashMap<>();
        add(expected, EventPriority.MONITOR, false,
                "onJoin",
                "onTeleportOutcome",
                "onQuit",
                "onDeath",
                "onRespawn");
        add(expected, EventPriority.MONITOR, true, "onGameModeChange");
        add(expected, EventPriority.HIGHEST, false, "onInteract");
        add(expected, EventPriority.HIGHEST, true,
                "onMove",
                "onTeleportDecision",
                "onBreak",
                "onPlace",
                "onBucketEmpty",
                "onBucketFill",
                "onFluidFlow",
                "onFluidLevelChange",
                "onSpongeAbsorb",
                "onIgnite",
                "onBurn",
                "onFade",
                "onForm",
                "onEntityForm",
                "onSpread",
                "onEntityChangeBlock",
                "onEntityExplode",
                "onBlockExplode",
                "onPistonExtend",
                "onPistonRetract",
                "onDamage",
                "onOutgoingDamage",
                "onDrop",
                "onPickup",
                "onInventoryClick",
                "onInventoryDrag",
                "onSwapHands",
                "onHeldItemChange",
                "onInteractEntity",
                "onInteractAtEntity",
                "onArmorStandManipulate",
                "onMount",
                "onHunger",
                "onFlight",
                "onGlide",
                "onPotionEffect",
                "onVelocity",
                "onVehicleEnter");

        assertContracts(GameListener.class, expected);
    }

    @Test
    void locksEveryMenuListenerPriorityAndCancellationContract() {
        Map<String, Contract> expected = new LinkedHashMap<>();
        add(expected, EventPriority.HIGHEST, false, "onClick", "onDrag");
        add(expected, EventPriority.MONITOR, false,
                "onClose",
                "onQuit",
                "onChangedWorld");
        add(expected, EventPriority.MONITOR, true, "onKick");

        assertContracts(MenuService.Listener.class, expected);
    }

    private static void add(
            Map<String, Contract> contracts,
            EventPriority priority,
            boolean ignoreCancelled,
            String... methodNames) {
        for (String methodName : methodNames) {
            assertNull(
                    contracts.put(methodName, new Contract(priority, ignoreCancelled)),
                    () -> "Duplicate expected handler " + methodName);
        }
    }

    private static void assertContracts(
            Class<?> listenerType,
            Map<String, Contract> expected) {
        Map<String, Contract> actual = new TreeMap<>();
        for (Method method : listenerType.getDeclaredMethods()) {
            EventHandler annotation = method.getAnnotation(EventHandler.class);
            if (annotation == null) {
                continue;
            }
            assertNull(
                    actual.put(
                            method.getName(),
                            new Contract(annotation.priority(), annotation.ignoreCancelled())),
                    () -> "Overloaded event handler needs an explicit signature key: " + method);
        }
        assertEquals(new TreeMap<>(expected), actual);
    }

    private record Contract(EventPriority priority, boolean ignoreCancelled) {
    }
}
