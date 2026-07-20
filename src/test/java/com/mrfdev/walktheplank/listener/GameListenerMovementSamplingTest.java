package com.mrfdev.walktheplank.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;

final class GameListenerMovementSamplingTest {
    private static final Player PLAYER = proxy(Player.class);
    private static final World WORLD = proxy(World.class);

    @Test
    void inspectsLandingMovementWithinTheSameBlockCoordinate() {
        Location from = new Location(WORLD, 10.25, 65.90, -4.75);
        Location to = new Location(WORLD, 10.25, 65.02, -4.75);
        PlayerMoveEvent event = new PlayerMoveEvent(PLAYER, from, to);

        assertFalse(event.hasChangedBlock());
        assertTrue(event.hasChangedPosition());
        assertTrue(GameListener.hasLandingRelevantMovement(event));
    }

    @Test
    void ignoresOrientationOnlyMovementEvents() {
        Location from = new Location(WORLD, 10.25, 65.02, -4.75, 0.0F, 0.0F);
        Location to = new Location(WORLD, 10.25, 65.02, -4.75, 90.0F, 20.0F);
        PlayerMoveEvent event = new PlayerMoveEvent(PLAYER, from, to);

        assertTrue(event.hasChangedOrientation());
        assertFalse(event.hasChangedPosition());
        assertFalse(GameListener.hasLandingRelevantMovement(event));
    }

    private static <T> T proxy(Class<T> type) {
        Object instance = Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == arguments[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> type.getSimpleName() + "TestProxy";
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    Class<?> returnType = method.getReturnType();
                    if (!returnType.isPrimitive()) {
                        return null;
                    }
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == char.class) {
                        return '\0';
                    }
                    return 0;
                });
        return type.cast(instance);
    }
}
