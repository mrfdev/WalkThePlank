package com.mrfdev.walktheplank.listener;

import com.mrfdev.walktheplank.database.ScoreRepository;
import com.mrfdev.walktheplank.game.BlockKey;
import com.mrfdev.walktheplank.game.GameManager;
import com.mrfdev.walktheplank.game.SessionEndReason;
import com.mrfdev.walktheplank.text.MessageService;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class GameListener implements Listener {
    private final JavaPlugin plugin;
    private final GameManager games;
    private final ScoreRepository scores;
    private final MessageService messages;
    private final Map<PlayerTeleportEvent, UUID> externalTeleportAttempts = new IdentityHashMap<>();

    public GameListener(
            JavaPlugin plugin,
            GameManager games,
            ScoreRepository scores,
            MessageService messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.games = Objects.requireNonNull(games, "games");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        games.retryPendingReturn(player);
        String playerName = player.getName();
        scores.touchIdentity(player.getUniqueId(), playerName).exceptionally(failure -> {
            plugin.getLogger().log(Level.WARNING, "Could not refresh score identity for " + playerName, failure);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }
        if (games.isRecovering(event.getPlayer())) {
            if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                    || event.getFrom().getBlockY() != event.getTo().getBlockY()
                    || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
                event.setCancelled(true);
            }
            return;
        }
        if (!games.isPlaying(event.getPlayer())) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        games.handleMove(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleportDecision(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (games.isInternalTeleport(player)) {
            return;
        }
        if (games.isRecovering(player)) {
            event.setCancelled(true);
            return;
        }
        if (!games.isPlaying(player)) {
            return;
        }
        if (games.isExploitTeleport(event.getCause())) {
            games.recordMovementAnomaly(
                    player,
                    event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                            ? "ender_pearl"
                            : "consumable_teleport",
                    "blocked");
            event.setCancelled(true);
            return;
        }
        games.prepareExternalTeleportCompletion(player).ifPresentOrElse(
                attemptId -> externalTeleportAttempts.put(event, attemptId),
                () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleportOutcome(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID attemptId = externalTeleportAttempts.remove(event);
        if (attemptId != null) {
            games.observeExternalTeleportCompletion(
                    player,
                    attemptId,
                    !event.isCancelled(),
                    event.getTo());
            return;
        }
        if (event.isCancelled() || games.isInternalTeleport(player)) {
            return;
        }
        if (!games.isPlaying(player)
                && event.getTo() != null
                && event.getFrom().getWorld() != event.getTo().getWorld()) {
            games.removeQueuedPlayer(player, "WORLD_CHANGE");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        games.end(event.getPlayer(), SessionEndReason.QUIT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        games.end(event.getEntity(), SessionEndReason.DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        try {
            plugin.getServer().getScheduler().runTask(
                    plugin,
                    () -> games.retryPendingReturn(event.getPlayer()));
        } catch (RuntimeException schedulingFailure) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not schedule post-respawn player recovery",
                    schedulingFailure);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isRestricted(event.getPlayer()) || isArenaBlock(event.getBlock())) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "chat.blockInArena");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isRestricted(event.getPlayer()) || isArenaBlock(event.getBlock())) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "chat.blockInArena");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Block clickedBlock = event.getClickedBlock();
        if (!isRestricted(event.getPlayer())
                && (clickedBlock == null || !isArenaBlock(clickedBlock))) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        protectBucketUse(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        protectBucketUse(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (isArenaBlock(event.getBlock()) || isArenaBlock(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidLevelChange(FluidLevelChangeEvent event) {
        if (isArenaBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        if (event.getBlocks().stream().anyMatch(state -> isArenaBlock(state.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (isArenaBlock(event.getBlock())
                || event.getIgnitingEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isArenaBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (isArenaBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (isArenaBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityForm(EntityBlockFormEvent event) {
        if (isArenaBlock(event.getBlock())
                || event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (isArenaBlock(event.getBlock()) || isArenaBlock(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isArenaBlock(event.getBlock())
                || event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isArenaBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isArenaBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        boolean movedCellProtected = movesArenaBlock(
                event.getBlocks(),
                event.getDirection().getModX(),
                event.getDirection().getModY(),
                event.getDirection().getModZ());
        if (PistonProtectionPolicy.shouldCancel(
                isArenaBlock(event.getBlock()),
                isArenaBlock(event.getBlock().getRelative(event.getDirection())),
                movedCellProtected)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        boolean movedCellProtected = movesArenaBlock(
                event.getBlocks(),
                -event.getDirection().getModX(),
                -event.getDirection().getModY(),
                -event.getDirection().getModZ());
        if (PistonProtectionPolicy.shouldCancel(
                isArenaBlock(event.getBlock()),
                isArenaBlock(event.getBlock().getRelative(event.getDirection())),
                movedCellProtected)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOutgoingDamage(EntityDamageByEntityEvent event) {
        if (isPlayingAttacker(event.getDamager())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
            games.recordMovementAnomaly(player, "mount_attempt", "blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlight(PlayerToggleFlightEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().setFlying(false);
            games.recordMovementAnomaly(event.getPlayer(), "flight_toggle", "blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
            games.recordMovementAnomaly(player, "glide_toggle", "blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (event.getEntity() instanceof Player player
                && isRestricted(player)
                && event.getNewEffect() != null
                && games.isDisallowedMovementEffect(event.getNewEffect().getType())) {
            event.setCancelled(true);
            games.recordMovementAnomaly(player, "movement_effect", "blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
            games.recordMovementAnomaly(event.getPlayer(), "external_velocity", "blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
            games.recordMovementAnomaly(player, "vehicle_enter", "blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player
                && games.shouldBlockProjectile(player)) {
            event.setCancelled(true);
            games.recordMovementAnomaly(player, "projectile_launch", "blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRiptide(PlayerRiptideEvent event) {
        Player player = event.getPlayer();
        if (games.shouldBlockRiptide(player)) {
            event.setCancelled(true);
            games.recordMovementAnomaly(player, "riptide", "blocked");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (games.isPlaying(event.getPlayer())) {
            games.end(event.getPlayer(), SessionEndReason.GAME_MODE_CHANGE);
        }
    }

    private boolean movesArenaBlock(List<Block> blocks, int deltaX, int deltaY, int deltaZ) {
        for (Block block : blocks) {
            if (isArenaBlock(block) || isArenaBlock(block.getRelative(deltaX, deltaY, deltaZ))) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlayingAttacker(org.bukkit.entity.Entity attacker) {
        if (attacker instanceof Player player) {
            return isRestricted(player);
        }
        return attacker instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player
                && isRestricted(player);
    }

    private void protectBucketUse(PlayerBucketEvent event) {
        if (isRestricted(event.getPlayer()) || isArenaBlock(event.getBlock())) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "chat.blockInArena");
        }
    }

    private boolean isArenaBlock(Block block) {
        return games.isProtected(BlockKey.from(block))
                || games.isInsideActiveArena(block.getLocation());
    }

    private boolean isRestricted(Player player) {
        return games.isPlaying(player) || games.isRecovering(player);
    }
}
