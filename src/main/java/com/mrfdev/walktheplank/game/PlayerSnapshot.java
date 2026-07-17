package com.mrfdev.walktheplank.game;

import com.mrfdev.walktheplank.recovery.PlayerRecoveryRecord;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

public record PlayerSnapshot(
        Location returnLocation,
        double health,
        int foodLevel,
        float saturation,
        float exhaustion,
        float walkSpeed,
        boolean allowFlight,
        boolean flying,
        boolean collidable) {
    public PlayerSnapshot {
        Objects.requireNonNull(returnLocation, "returnLocation");
        if (!Double.isFinite(health) || health < 0.0
                || foodLevel < 0 || foodLevel > 20
                || !Float.isFinite(saturation) || saturation < 0.0F
                || !Float.isFinite(exhaustion) || exhaustion < 0.0F
                || !Float.isFinite(walkSpeed) || walkSpeed < -1.0F || walkSpeed > 1.0F) {
            throw new IllegalArgumentException("Player snapshot contains invalid state");
        }
        returnLocation = returnLocation.clone();
    }

    public static PlayerSnapshot capture(Player player) {
        return new PlayerSnapshot(
                player.getLocation(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getWalkSpeed(),
                player.getAllowFlight(),
                player.isFlying(),
                player.isCollidable());
    }

    @Override
    public Location returnLocation() {
        return returnLocation.clone();
    }

    public void prepare(Player player) {
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setGliding(false);
        player.setVelocity(player.getVelocity().zero());
        player.setWalkSpeed(0.2F);
        player.setCollidable(false);
        player.setHealth(Math.min(20.0, maximumHealth(player)));
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setExhaustion(0.0F);
        player.setFallDistance(0.0F);
    }

    /**
     * Revalidates the exact captured state before an asynchronous recovery record is allowed to
     * authorize temporary player mutation.
     */
    boolean matchesCurrent(Player player) {
        Objects.requireNonNull(player, "player");
        Location current = player.getLocation();
        Location captured = returnLocation();
        World currentWorld = current.getWorld();
        World capturedWorld = captured.getWorld();
        return currentWorld != null
                && capturedWorld != null
                && currentWorld.getUID().equals(capturedWorld.getUID())
                && Double.compare(current.getX(), captured.getX()) == 0
                && Double.compare(current.getY(), captured.getY()) == 0
                && Double.compare(current.getZ(), captured.getZ()) == 0
                && Float.compare(current.getYaw(), captured.getYaw()) == 0
                && Float.compare(current.getPitch(), captured.getPitch()) == 0
                && Double.compare(player.getHealth(), health) == 0
                && player.getFoodLevel() == foodLevel
                && Float.compare(player.getSaturation(), saturation) == 0
                && Float.compare(player.getExhaustion(), exhaustion) == 0
                && Float.compare(player.getWalkSpeed(), walkSpeed) == 0
                && player.getAllowFlight() == allowFlight
                && player.isFlying() == flying
                && player.isCollidable() == collidable;
    }

    public void restore(Player player) {
        restoreTemporaryState(player);
        if (!player.isDead()) {
            player.setHealth(Math.min(health, maximumHealth(player)));
        }
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
    }

    /** Restores only state temporarily changed by a run, preserving respawn health and hunger. */
    void restoreTemporaryState(Player player) {
        player.setFallDistance(0.0F);
        player.setVelocity(player.getVelocity().zero());
        player.setCollidable(collidable);
        player.setWalkSpeed(walkSpeed);
        player.setAllowFlight(allowFlight);
        if (allowFlight) {
            player.setFlying(flying);
        }
    }

    PlayerRecoveryRecord recoveryRecord(UUID playerId, UUID runId, String arenaId) {
        Location capturedReturn = returnLocation();
        World world = Objects.requireNonNull(capturedReturn.getWorld(), "Captured return world");
        return new PlayerRecoveryRecord(
                playerId,
                runId,
                arenaId,
                world.getUID(),
                capturedReturn.getX(),
                capturedReturn.getY(),
                capturedReturn.getZ(),
                capturedReturn.getYaw(),
                capturedReturn.getPitch(),
                health,
                foodLevel,
                saturation,
                exhaustion,
                walkSpeed,
                allowFlight,
                flying,
                collidable);
    }

    static PlayerSnapshot fromRecoveryRecord(PlayerRecoveryRecord record, World world) {
        Objects.requireNonNull(record, "record");
        if (world != null && !record.returnWorldId().equals(world.getUID())) {
            throw new IllegalArgumentException("Recovery world does not match the recorded world UUID");
        }
        return new PlayerSnapshot(
                new Location(
                        world,
                        record.returnX(),
                        record.returnY(),
                        record.returnZ(),
                        record.returnYaw(),
                        record.returnPitch()),
                record.health(),
                record.foodLevel(),
                record.saturation(),
                record.exhaustion(),
                record.walkSpeed(),
                record.allowFlight(),
                record.flying(),
                record.collidable());
    }

    private static double maximumHealth(Player player) {
        AttributeInstance maximumHealth = player.getAttribute(Attribute.MAX_HEALTH);
        return maximumHealth == null ? 20.0 : maximumHealth.getValue();
    }
}
