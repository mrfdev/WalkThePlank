package com.mrfdev.walktheplank.game;

import com.mrfdev.walktheplank.api.event.WalkJumpEvent;
import com.mrfdev.walktheplank.api.event.WalkPersonalBestEvent;
import com.mrfdev.walktheplank.api.event.WalkRewardPlanEvent;
import com.mrfdev.walktheplank.api.event.WalkRunEndEvent;
import com.mrfdev.walktheplank.api.event.WalkRunStartEvent;
import com.mrfdev.walktheplank.config.RuntimeSettings;
import com.mrfdev.walktheplank.database.CompletedRunResult;
import com.mrfdev.walktheplank.database.CompletedRunWithRewardPlanResult;
import com.mrfdev.walktheplank.database.RewardPlanBeginResult;
import com.mrfdev.walktheplank.database.RewardPlanRequest;
import com.mrfdev.walktheplank.database.RewardPlanStatus;
import com.mrfdev.walktheplank.database.RewardStepDispatchResult;
import com.mrfdev.walktheplank.database.RewardStepStatus;
import com.mrfdev.walktheplank.database.RunCompletion;
import com.mrfdev.walktheplank.database.RunInvestigationRecord;
import com.mrfdev.walktheplank.database.RunRecord;
import com.mrfdev.walktheplank.database.RunStart;
import com.mrfdev.walktheplank.database.RunStatus;
import com.mrfdev.walktheplank.database.ScoreRepository;
import com.mrfdev.walktheplank.database.ScoreUpdateResult;
import com.mrfdev.walktheplank.database.Season;
import com.mrfdev.walktheplank.ops.OperationalContext;
import com.mrfdev.walktheplank.ops.OperationalMetrics;
import com.mrfdev.walktheplank.queue.PlayerQueue;
import com.mrfdev.walktheplank.recovery.RestorationCoordinator;
import com.mrfdev.walktheplank.recovery.PlayerRecoveryJournal;
import com.mrfdev.walktheplank.recovery.PlayerRecoveryOwnership;
import com.mrfdev.walktheplank.recovery.PlayerRecoveryRecord;
import com.mrfdev.walktheplank.recovery.RecoveryDurabilityService;
import com.mrfdev.walktheplank.recovery.RestorationFailure;
import com.mrfdev.walktheplank.recovery.RestorationJournal;
import com.mrfdev.walktheplank.recovery.RestorationRecord;
import com.mrfdev.walktheplank.recovery.RestorationRetryResult;
import com.mrfdev.walktheplank.reward.RewardService;
import com.mrfdev.walktheplank.reward.RewardService.PreparedRewardPlan;
import com.mrfdev.walktheplank.reward.RewardService.PreparedRewardStep;
import com.mrfdev.walktheplank.reward.RewardService.RewardPlan;
import com.mrfdev.walktheplank.text.MessageService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class GameManager {
    private static final Set<String> DISALLOWED_EFFECTS = Set.of(
            "speed",
            "jump_boost",
            "levitation",
            "slow_falling",
            "dolphins_grace",
            "wind_charged");

    private final JavaPlugin plugin;
    private final Supplier<RuntimeSettings> settings;
    private final ScoreRepository scoreRepository;
    private final MessageService messages;
    private final RewardService rewards;
    private final String releaseIdentity;
    private final OperationalContext operations;
    private final RestorationCoordinator restoration;
    private final PlayerRecoveryJournal playerRecovery;
    private final RecoveryDurabilityService recoveryDurability;
    private final ArenaLeaseRegistry arenaLeases = new ArenaLeaseRegistry();
    private final ArenaSelector arenaSelector = new ArenaSelector(ThreadLocalRandom.current());
    private final Map<UUID, GameSession> sessions = new HashMap<>();
    private final Map<UUID, PendingStart> pendingStarts = new HashMap<>();
    private final Map<UUID, PendingStart> pendingStartAbandonments = new HashMap<>();
    private final Map<UUID, Instant> nextQueueReminder = new HashMap<>();
    private final BlockLeaseRegistry blockLeases = new BlockLeaseRegistry();
    private final Set<BlockKey> journalProtectedBlocks = new HashSet<>();
    private final Set<UUID> internalTeleports = new HashSet<>();
    private final Map<String, GameSession> quarantinedSessions = new HashMap<>();
    private final Map<String, PendingPlayerReturn> pendingPlayerReturns = new HashMap<>();
    private final Set<PlayerRecoveryCompletion> pendingPlayerRecoveryCompletions =
            new HashSet<>();
    private final Map<UUID, PendingExternalTeleport> pendingExternalTeleports = new HashMap<>();
    private final Set<UUID> playerRecoveryLookups = ConcurrentHashMap.newKeySet();
    private final List<Arena> freeArenas = new ArrayList<>();
    private final RewardCompletionBarrier rewardCompletionBarrier = new RewardCompletionBarrier();
    private final ConcurrentLinkedQueue<Runnable> durabilityCompletions =
            new ConcurrentLinkedQueue<>();
    private final AtomicBoolean durabilityDrainScheduled = new AtomicBoolean();

    private PlayerQueue queue;
    private volatile PublishedGameState publishedGameState = PublishedGameState.empty();
    private volatile RestorationRetryResult lastRestorationRetry;
    private volatile boolean shuttingDown;
    private long nextSessionGeneration = 1L;

    public GameManager(
            JavaPlugin plugin,
            Supplier<RuntimeSettings> settings,
            ScoreRepository scoreRepository,
            MessageService messages,
            RewardService rewards,
            RestorationJournal restorationJournal,
            PlayerRecoveryJournal playerRecoveryJournal,
            RecoveryDurabilityService recoveryDurability,
            String releaseIdentity,
            OperationalContext operations) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.scoreRepository = Objects.requireNonNull(scoreRepository, "scoreRepository");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.rewards = Objects.requireNonNull(rewards, "rewards");
        this.releaseIdentity = Objects.requireNonNull(releaseIdentity, "releaseIdentity");
        this.operations = Objects.requireNonNull(operations, "operations");
        playerRecovery = Objects.requireNonNull(playerRecoveryJournal, "playerRecoveryJournal");
        this.recoveryDurability =
                Objects.requireNonNull(recoveryDurability, "recoveryDurability");
        queue = new PlayerQueue(
                settings.get().queue().joinCooldown(),
                settings.get().queue().readinessWindow());
        restoration = new RestorationCoordinator(
                Objects.requireNonNull(restorationJournal, "restorationJournal"),
                recoveryDurability);
        int pendingAtStartup = restoration.pendingCount();
        if (pendingAtStartup > 0) {
            plugin.getLogger().warning("Detected " + pendingAtStartup
                    + " pending arena block restoration record(s); attempting safe startup recovery");
            lastRestorationRetry = restoration.retryPending(Set.of());
            recordRestorationRetryMetrics(lastRestorationRetry);
            auditRestorationRetry("restoration.startup_recovery", null, lastRestorationRetry);
            logRestorationRetry("Startup", lastRestorationRetry);
        } else {
            lastRestorationRetry = RestorationRetryResult.empty(0, 0);
        }
        refreshJournalProtection();
        rebuildFreeArenas();
        PlayerRecoveryJournal.Health playerRecoveryHealth = playerRecovery.health();
        if (playerRecoveryHealth.pendingRecords() > 0) {
            plugin.getLogger().warning("Detected " + playerRecoveryHealth.pendingRecords()
                    + " pending player recovery record(s); recovery will run when each player joins");
        }
        if (!playerRecoveryHealth.healthy()) {
            plugin.getLogger().warning("Player recovery journal health is degraded; affected runs fail closed");
        }
        operations.audit("player_recovery.startup", null, null, Map.of(
                "pending", playerRecoveryHealth.pendingRecords(),
                "invalid", playerRecoveryHealth.invalidRecords(),
                "healthy", playerRecoveryHealth.healthy()));
    }

    public boolean start(Player player) {
        Objects.requireNonNull(player, "player");
        if (!canStart(player, true)) {
            return false;
        }
        if (freeArenas.isEmpty()) {
            messages.send(player, "chat.allArenasUsed");
            if (settings.get().queue().enabled()) {
                messages.send(player, "chat.queueOffer");
            }
            player.closeInventory();
            return false;
        }

        if (settings.get().queue().enabled() && queue.size() > 0) {
            if (queue.isPaused()) {
                messages.send(player, "chat.queuePaused");
                return false;
            }
            if (queue.readyUntil(player.getUniqueId()).isEmpty()) {
                messages.send(player, "chat.queueFairness");
                return false;
            }
            if (!queue.consumeReady(player.getUniqueId(), Instant.now())) {
                messages.send(player, "chat.queueExpired");
                return false;
            }
            nextQueueReminder.remove(player.getUniqueId());
        }

        Arena arena = removeSelectedArena();
        UUID runId = UUID.randomUUID();
        long sessionGeneration = nextSessionGeneration++;
        ArenaLeaseRegistry.ArenaLease arenaLease =
                new ArenaLeaseRegistry.ArenaLease(runId, sessionGeneration);
        if (!arenaLeases.reserve(arena.id(), arenaLease)) {
            freeArenas.add(arena);
            rebuildFreeArenas();
            throw new IllegalStateException("Selected arena already has a different ownership lease");
        }
        Instant startedAt = Instant.now();
        PendingStart pending = new PendingStart(
                runId,
                player.getUniqueId(),
                arena,
                startedAt,
                arenaLease);
        pendingStarts.put(player.getUniqueId(), pending);
        publishGameState();
        player.closeInventory();
        messages.send(player, "chat.preparingRun");
        scoreRepository.startRun(new RunStart(
                        runId,
                        player.getUniqueId(),
                        player.getName(),
                        arena.id(),
                        startedAt,
                        releaseIdentity))
                .whenComplete((record, failure) -> schedulePendingStart(pending, record, failure));
        return true;
    }

    private void schedulePendingStart(
            PendingStart pending,
            RunRecord record,
            Throwable failure) {
        if (!plugin.isEnabled() || shuttingDown) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(
                    plugin,
                    () -> completePendingStart(pending, record, failure));
        } catch (RuntimeException schedulingFailure) {
            pending.recordUnscheduledOutcome(record, failure);
            operations.metrics().recordRepositoryFailure(schedulingFailure);
            plugin.getLogger().log(Level.WARNING, "Could not schedule a persisted run start", schedulingFailure);
        }
    }

    private void completePendingStart(
            PendingStart pending,
            RunRecord record,
            Throwable failure) {
        if (pendingStarts.get(pending.playerId()) != pending) {
            return;
        }
        Player player = plugin.getServer().getPlayer(pending.playerId());
        if (failure != null) {
            pendingStarts.remove(pending.playerId());
            releaseReservedArena(pending);
            operations.metrics().recordRepositoryFailure(failure);
            operations.audit("run.start_failed", pending.playerId(), pending.arena().id(), Map.of(
                    "failure", failure.getClass().getSimpleName()));
            plugin.getLogger().log(Level.SEVERE, "Could not durably start a WalkThePlank run", failure);
            if (player != null) {
                messages.send(player, "chat.startFailed");
            }
            refreshQueue();
            return;
        }

        RunRecord persisted = Objects.requireNonNull(record, "persisted run");
        String cancellation = pending.cancellationReason();
        if (cancellation == null && !canActivatePendingStart(pending, player)) {
            cancellation = shuttingDown
                    ? "SHUTDOWN"
                    : player == null || !player.isOnline()
                            ? "PLAYER_OFFLINE"
                            : player.isDead()
                                    ? "PLAYER_DEAD"
                                    : !hasEligibleMovementAttribute(player)
                                            ? "MOVEMENT_MODIFIED"
                                            : !player.hasPermission(settings.get().permissions().playGame())
                                                    ? "PERMISSION_REVOKED"
                                                    : "INELIGIBLE";
        }
        if (cancellation != null) {
            abortPendingStart(pending, cancellation);
            refreshQueue();
            return;
        }

        WalkRunStartEvent startEvent = new WalkRunStartEvent(player, pending.arena().id());
        try {
            plugin.getServer().getPluginManager().callEvent(startEvent);
        } catch (RuntimeException | LinkageError listenerFailure) {
            pending.cancel("EVENT_FAILED");
            operations.audit("run.start_event_failed", player.getUniqueId(), pending.arena().id(), Map.of(
                    "run_id", persisted.id(),
                    "failure", listenerFailure.getClass().getSimpleName()));
            plugin.getLogger().log(
                    Level.SEVERE,
                    "A WalkRunStartEvent listener failed; the run will not activate",
                    listenerFailure);
        }
        if (startEvent.isCancelled() && pending.cancellationReason() == null) {
            pending.cancel("EVENT_CANCELLED");
        }
        if (!canActivatePendingStart(pending, player)) {
            String abortReason = pending.cancellationReason() != null
                    ? pending.cancellationReason()
                    : !hasEligibleMovementAttribute(player)
                            ? "MOVEMENT_MODIFIED"
                            : "POST_EVENT_INELIGIBLE";
            abortPendingStart(pending, abortReason);
            messages.send(player, abortReason.equals("EVENT_CANCELLED")
                    ? "chat.startCancelled"
                    : "chat.startFailed");
            refreshQueue();
            return;
        }

        GameSession session;
        try {
            session = new GameSession(
                    player,
                    pending.arena(),
                    persisted.id(),
                    persisted.startedAt(),
                    pending.arenaLease(),
                    settings.get(),
                    blockLeases,
                    restoration);
        } catch (RuntimeException | LinkageError constructionFailure) {
            abortPendingStart(pending, "SESSION_CONSTRUCTION_FAILED");
            operations.metrics().recordRestorationFailure(constructionFailure);
            operations.audit("run.start_failed", player.getUniqueId(), pending.arena().id(), Map.of(
                    "run_id", persisted.id(),
                    "stage", "construction",
                    "failure", constructionFailure.getClass().getSimpleName()));
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not construct arena session " + pending.arena().id(),
                    constructionFailure);
            messages.send(player, "chat.startFailed");
            refreshQueue();
            return;
        }
        try {
            RecoveryDurabilityService.PlayerRecoveryPreparation playerPreparation =
                    recoveryDurability.preparePlayerRecovery(
                            session.playerSnapshot().recoveryRecord(
                                    player.getUniqueId(),
                                    persisted.id(),
                                    pending.arena().id()));
            pending.installDurabilityAbandoner(playerPreparation::discard);
            GameSession.StartPreparation platformPreparation;
            try {
                platformPreparation = session.prepareStart();
            } catch (RuntimeException | LinkageError platformFailure) {
                pending.installDurabilityAbandoner(() -> CompletableFuture.allOf(
                        playerPreparation.discard(),
                        session.abandonIncompleteStartPreparation()));
                throw platformFailure;
            }
            PendingActivation activation = new PendingActivation(
                    session,
                    playerPreparation,
                    platformPreparation);
            if (!pending.installActivation(activation)) {
                activation.abandon();
                throw new IllegalStateException("Pending start activation was already installed");
            }
            activation.durable().whenComplete((records, durabilityFailure) ->
                    enqueueDurabilityCompletion(() ->
                            completePreparedStart(
                                    pending,
                                    persisted,
                                    activation,
                                    records,
                                    durabilityFailure)));
        } catch (RuntimeException | LinkageError preparationFailure) {
            abortPendingStart(pending, "RECOVERY_PREPARATION_FAILED");
            operations.metrics().recordRestorationFailure(preparationFailure);
            operations.audit("player_recovery.capture_failed", player.getUniqueId(), pending.arena().id(), Map.of(
                    "run_id", persisted.id(),
                    "failure", preparationFailure.getClass().getSimpleName()));
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not queue durable run preparation; refusing to activate the run",
                    preparationFailure);
            messages.send(player, "chat.startFailed");
            refreshQueue();
        }
    }

    private void completePreparedStart(
            PendingStart pending,
            RunRecord persisted,
            PendingActivation activation,
            ActivationRecords records,
            Throwable failure) {
        Player player = plugin.getServer().getPlayer(pending.playerId());
        if (pendingStarts.get(pending.playerId()) != pending
                || pending.activation() != activation) {
            if (!pending.arenaReleased()
                    && pendingStartAbandonments.putIfAbsent(
                            pending.runId(), pending) == null) {
                CompletableFuture<Void> abandonment = pending.abandonDurability();
                if (abandonment != null) {
                    attachPendingStartAbandonment(pending, abandonment);
                }
            }
            return;
        }
        GameSession session = activation.session();
        if (failure != null
                || player == null
                || !canActivatePendingStart(pending, player)
                || !session.playerSnapshot().matchesCurrent(player)) {
            String reason = failure != null
                    ? "DURABILITY_FAILED"
                    : player == null || !player.isOnline()
                            ? "PLAYER_OFFLINE"
                            : !session.playerSnapshot().matchesCurrent(player)
                                    ? "PLAYER_STATE_CHANGED"
                                    : "POST_DURABILITY_INELIGIBLE";
            abortPendingStart(pending, reason);
            Throwable reported = failure != null
                    ? failure
                    : new IllegalStateException("Pending start revalidation failed: " + reason);
            operations.metrics().recordRestorationFailure(reported);
            operations.audit("run.start_failed", pending.playerId(), pending.arena().id(), Map.of(
                    "run_id", pending.runId(),
                    "stage", "durability_revalidation",
                    "reason", reason));
            if (player != null) {
                messages.send(player, "chat.startFailed");
            }
            refreshQueue();
            return;
        }

        try {
            ActivationRecords checkedRecords = Objects.requireNonNull(records, "records");
            if (!activation.playerPreparation().claim(checkedRecords.playerRecovery())) {
                throw new IllegalStateException("Player recovery preparation is stale");
            }
            PreparedBlock successor = session.commitStart(
                    activation.platformPreparation(),
                    checkedRecords.platforms());
            sessions.put(player.getUniqueId(), session);
            pendingStarts.remove(player.getUniqueId(), pending);
            pending.clearActivation(activation);
            publishSessionScores();
            if (!teleportInternally(player, pending.arena().playerSpawn())) {
                throw new IllegalStateException("Paper rejected the arena teleport");
            }
            attachSuccessorPreparation(session, successor);
            operations.metrics().recordSessionStarted();
            operations.audit("run.start", player.getUniqueId(), pending.arena().id(), Map.of(
                    "run_id", persisted.id(),
                    "season_id", persisted.seasonId().map(UUID::toString).orElse("")));
            messages.send(player, "chat.arenaStart");
        } catch (RuntimeException | LinkageError activationFailure) {
            sessions.remove(player.getUniqueId(), session);
            pendingStarts.remove(player.getUniqueId(), pending);
            pending.clearActivation(activation);
            publishSessionScores();
            FailedStartCleanup cleanup = cleanupFailedStart(player, session, activationFailure);
            releaseArena(session, cleanup.blocksRestored() && cleanup.playerReturned());
            markRunInterrupted(persisted.id(), RunStatus.ABORTED, "START_FAILED");
            operations.metrics().recordRestorationFailure(activationFailure);
            operations.audit("run.start_failed", player.getUniqueId(), pending.arena().id(), Map.of(
                    "run_id", persisted.id(),
                    "failure", activationFailure.getClass().getSimpleName()));
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not activate arena " + pending.arena().id(),
                    activationFailure);
            messages.send(player, "chat.startFailed");
            refreshQueue();
        }
    }

    private FailedStartCleanup cleanupFailedStart(
            Player player,
            GameSession session,
            Throwable originalFailure) {
        boolean blocksRestored = true;
        try {
            finishSessionWorld(session);
        } catch (RuntimeException | LinkageError cleanupFailure) {
            blocksRestored = false;
            originalFailure.addSuppressed(cleanupFailure);
        }
        boolean stateRestored = false;
        try {
            session.playerSnapshot().restore(player);
            stateRestored = !player.isDead();
            if (!stateRestored) {
                originalFailure.addSuppressed(new IllegalStateException(
                        "Player state cannot be fully restored while the player is dead"));
            }
        } catch (RuntimeException | LinkageError cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
        ReturnAttempt returnAttempt;
        try {
            returnAttempt = returnPlayerSafely(session, session.playerSnapshot(), false);
        } catch (RuntimeException | LinkageError cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
            returnAttempt = new ReturnAttempt(false, asRuntimeFailure(cleanupFailure));
        }
        if (returnAttempt.failure() != null) {
            originalFailure.addSuppressed(returnAttempt.failure());
        }
        boolean recoveryQueued = stateRestored
                && returnAttempt.success()
                && queuePlayerRecoveryCompletion(session);
        if (!returnAttempt.success()) {
            pendingPlayerReturns.put(
                    session.arena().id(),
                    new PendingPlayerReturn(player.getUniqueId(), session.runId()));
        }
        return new FailedStartCleanup(
                blocksRestored,
                stateRestored,
                returnAttempt.success(),
                recoveryQueued);
    }

    private Arena removeSelectedArena() {
        String selectedId = arenaSelector.select(
                freeArenas.stream().map(Arena::id).toList(),
                settings.get().arenaSelection().policy(),
                settings.get().arenaSelection().pinnedArenaId().orElse(null));
        for (int index = 0; index < freeArenas.size(); index++) {
            if (freeArenas.get(index).id().equals(selectedId)) {
                return freeArenas.remove(index);
            }
        }
        throw new IllegalStateException("Selected arena is not available: " + selectedId);
    }

    private boolean canStart(Player player, boolean sendMessages) {
        if (shuttingDown) {
            return false;
        }
        if (!player.hasPermission(settings.get().permissions().playGame())) {
            if (sendMessages) {
                messages.send(player, "chat.noPermissionPlay", Map.of(
                        "permissionName", settings.get().permissions().playGame()));
                player.closeInventory();
            }
            return false;
        }
        if (player.isDead()) {
            if (sendMessages) {
                messages.send(player, "chat.startFailed");
                player.closeInventory();
            }
            return false;
        }
        if (sessions.containsKey(player.getUniqueId())
                || pendingStarts.containsKey(player.getUniqueId())
                || hasPendingStartAbandonment(player.getUniqueId())) {
            if (sendMessages) {
                messages.send(player, "chat.alreadyInGame");
                player.closeInventory();
            }
            return false;
        }
        if (!playerRecovery.canSafelyRecord(player.getUniqueId())) {
            if (sendMessages) {
                messages.send(player, "chat.startFailed");
                player.closeInventory();
            }
            operations.audit("player_recovery.start_blocked", player.getUniqueId(), null, Map.of(
                    "healthy", playerRecovery.health().healthy()));
            return false;
        }
        if (rewardCompletionBarrier.isBlocked(player.getUniqueId())) {
            if (sendMessages) {
                messages.send(player, "chat.slowDown");
                player.closeInventory();
            }
            return false;
        }
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
            if (sendMessages) {
                messages.send(player, "chat.unsupportedGameMode");
                player.closeInventory();
            }
            return false;
        }
        if (player.isInsideVehicle() || player.isGliding()) {
            if (sendMessages) {
                messages.send(player, "chat.movementStateActive");
                player.closeInventory();
            }
            return false;
        }
        if (hasDisallowedMovementEffect(player)) {
            if (sendMessages) {
                messages.send(player, "chat.movementEffectActive");
                player.closeInventory();
            }
            return false;
        }
        if (!hasEligibleMovementAttribute(player)) {
            if (sendMessages) {
                messages.send(player, "chat.movementAttributeActive");
                player.closeInventory();
            }
            return false;
        }
        return true;
    }

    private boolean canActivatePendingStart(PendingStart pending, Player player) {
        boolean supportedGameMode = player != null
                && (player.getGameMode() == GameMode.SURVIVAL
                        || player.getGameMode() == GameMode.ADVENTURE);
        return !rewardCompletionBarrier.isBlocked(pending.playerId())
                && playerRecovery.canSafelyRecord(pending.playerId())
                && arenaLeases.owns(pending.arena().id(), pending.arenaLease())
                && player != null
                && !player.isDead()
                && StartActivationPolicy.mayActivate(
                shuttingDown,
                pendingStarts.get(pending.playerId()) == pending,
                sessions.containsKey(pending.playerId()),
                !pending.arenaReleased(),
                pending.cancellationReason() == null,
                player != null && player.isOnline(),
                player != null && player.hasPermission(settings.get().permissions().playGame()),
                supportedGameMode,
                player != null
                        && !player.isInsideVehicle()
                        && !player.isGliding()
                        && hasEligibleMovementAttribute(player),
                player != null && hasDisallowedMovementEffect(player));
    }

    public boolean end(Player player, SessionEndReason reason) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reason, "reason");
        GameSession session = sessions.remove(player.getUniqueId());
        pendingExternalTeleports.remove(player.getUniqueId());
        if (session == null) {
            PendingStart pending = pendingStarts.get(player.getUniqueId());
            if (pending != null) {
                abortPendingStart(pending, reason.name());
                messages.send(player, "chat.startCancelled");
                refreshQueue();
                return true;
            }
            if (queue.remove(player.getUniqueId(), Instant.now())) {
                nextQueueReminder.remove(player.getUniqueId());
                messages.send(player, "chat.queueLeft");
                refreshQueue();
                return true;
            }
            if (reason == SessionEndReason.LEAVE) {
                messages.send(player, "chat.meaninglessLeave");
            }
            return false;
        }

        try {
            publishSessionScores();
        } catch (RuntimeException | LinkageError publicationFailure) {
            recordLifecycleFailure(
                    "session.publication_failed",
                    player.getUniqueId(),
                    session.arena().id(),
                    publicationFailure);
        }
        int score = session.score();
        PlayerSnapshot snapshot = session.playerSnapshot();
        RuntimeException cleanupFailure = null;
        boolean blocksRestored = true;
        try {
            finishSessionWorld(session);
        } catch (RuntimeException | LinkageError exception) {
            blocksRestored = false;
            cleanupFailure = appendFailure(cleanupFailure, asRuntimeFailure(exception));
        }
        boolean stateRestored = false;
        try {
            if (reason == SessionEndReason.DEATH) {
                snapshot.restoreTemporaryState(player);
            } else {
                snapshot.restore(player);
            }
            stateRestored = reason == SessionEndReason.DEATH || !player.isDead();
            if (!stateRestored) {
                cleanupFailure = appendFailure(
                        cleanupFailure,
                        new IllegalStateException("Player state cannot be fully restored while the player is dead"));
            }
        } catch (RuntimeException | LinkageError exception) {
            cleanupFailure = appendFailure(cleanupFailure, asRuntimeFailure(exception));
        }

        boolean playerReturned = true;
        if (reason.returnPlayer()) {
            ReturnAttempt returnAttempt;
            try {
                returnAttempt = returnPlayerSafely(session, snapshot);
            } catch (RuntimeException | LinkageError returnFailure) {
                returnAttempt = new ReturnAttempt(false, asRuntimeFailure(returnFailure));
            }
            playerReturned = returnAttempt.success();
            if (returnAttempt.failure() != null) {
                cleanupFailure = appendFailure(cleanupFailure, returnAttempt.failure());
            }
            if (!playerReturned) {
                pendingPlayerReturns.put(
                        session.arena().id(),
                        new PendingPlayerReturn(player.getUniqueId(), session.runId()));
            }
        }

        boolean recoveryQueued = false;
        if (stateRestored && playerReturned) {
            try {
                recoveryQueued = queuePlayerRecoveryCompletion(session);
            } catch (RuntimeException | LinkageError recoveryFailure) {
                cleanupFailure = appendFailure(
                        cleanupFailure,
                        asRuntimeFailure(recoveryFailure));
                recordLifecycleFailure(
                        "player_recovery.completion_failed",
                        player.getUniqueId(),
                        session.arena().id(),
                        recoveryFailure);
            }
        }
        if (!recoveryQueued && cleanupFailure == null) {
            cleanupFailure = new IllegalStateException(
                    "Player recovery journal completion could not be queued");
        }

        try {
            releaseArena(session, blocksRestored && playerReturned);
        } catch (RuntimeException | LinkageError releaseFailure) {
            cleanupFailure = appendFailure(cleanupFailure, asRuntimeFailure(releaseFailure));
            quarantinedSessions.put(session.arena().id(), session);
            recordLifecycleFailure(
                    "arena.release_failed",
                    player.getUniqueId(),
                    session.arena().id(),
                    releaseFailure);
        }

        if (reason == SessionEndReason.FALL || reason == SessionEndReason.LEAVE) {
            try {
                messages.send(player, "chat.areaLeave");
            } catch (RuntimeException | LinkageError messageFailure) {
                recordLifecycleFailure(
                        "session.leave_message_failed",
                        player.getUniqueId(),
                        session.arena().id(),
                        messageFailure);
            }
        }

        try {
            persistResult(session, reason, score);
        } catch (RuntimeException | LinkageError persistenceFailure) {
            recordRunPersistenceFailure(session, persistenceFailure);
            try {
                markRunInterrupted(
                        session.runId(),
                        RunStatus.UNKNOWN,
                        "COMPLETION_SUBMIT_FAILED");
            } catch (RuntimeException | LinkageError interruptionFailure) {
                recordLifecycleFailure(
                        "run.persistence_fallback_failed",
                        player.getUniqueId(),
                        session.arena().id(),
                        interruptionFailure);
            }
        }
        boolean cleanupQueued = cleanupFailure == null
                && blocksRestored
                && stateRestored
                && playerReturned
                && recoveryQueued;
        boolean cleanupComplete = cleanupQueued
                && !restoration.hasPendingSession(session.runId())
                && playerRecovery.pending(player.getUniqueId())
                        .filter(record -> record.runId().equals(session.runId()))
                        .isEmpty()
                && quarantinedSessions.get(session.arena().id()) != session
                && !arenaLeases.owns(session.arena().id(), session.arenaLease());
        try {
            operations.metrics().recordSessionEnded(reason);
        } catch (RuntimeException | LinkageError metricsFailure) {
            recordLifecycleFailure(
                    "run.end_metrics_failed",
                    player.getUniqueId(),
                    session.arena().id(),
                    metricsFailure);
        }
        try {
            operations.audit("run.end", player.getUniqueId(), session.arena().id(), Map.of(
                    "run_id", session.runId(),
                    "score", score,
                    "reason", reason,
                    "cleanup_queued", cleanupQueued,
                    "cleanup_complete", cleanupComplete));
        } catch (RuntimeException | LinkageError auditFailure) {
            recordLifecycleFailure(
                    "run.end_audit_failed",
                    player.getUniqueId(),
                    session.arena().id(),
                    auditFailure);
        }
        try {
            plugin.getServer().getPluginManager().callEvent(new WalkRunEndEvent(
                    player,
                    session.arena().id(),
                    score,
                    session.elapsedDuration(System.nanoTime()),
                    reason,
                    cleanupComplete));
        } catch (RuntimeException | LinkageError listenerFailure) {
            recordLifecycleFailure(
                    "run.end_event_failed",
                    player.getUniqueId(),
                    session.arena().id(),
                    listenerFailure);
        }
        if (cleanupFailure != null) {
            recordLifecycleFailure(
                    "session.cleanup_incomplete",
                    player.getUniqueId(),
                    session.arena().id(),
                    cleanupFailure);
            try {
                messages.send(player, "chat.sessionFailed");
            } catch (RuntimeException | LinkageError messageFailure) {
                recordLifecycleFailure(
                        "session.cleanup_message_failed",
                        player.getUniqueId(),
                        session.arena().id(),
                        messageFailure);
            }
        }
        try {
            refreshQueue();
        } catch (RuntimeException | LinkageError refreshFailure) {
            recordLifecycleFailure(
                    "queue.session_end_refresh_failed",
                    player.getUniqueId(),
                    session.arena().id(),
                    refreshFailure);
        }
        return true;
    }

    private ReturnAttempt returnPlayerSafely(GameSession session, PlayerSnapshot snapshot) {
        return returnPlayerSafely(session, snapshot, true);
    }

    private ReturnAttempt returnPlayerSafely(
            GameSession session,
            PlayerSnapshot snapshot,
            boolean includeCustomExit) {
        return returnPlayerSafely(
                session.player(),
                session.runId(),
                session.arena().id(),
                snapshot.returnLocation(),
                includeCustomExit ? session.arena().exit() : null);
    }

    private ReturnAttempt returnPlayerSafely(
            Player player,
            UUID runId,
            String arenaId,
            Location capturedReturn,
            Location customExit) {
        List<ReturnCandidate> candidates = returnCandidates(capturedReturn, customExit);
        RuntimeException failure = null;
        for (ReturnCandidate candidate : candidates) {
            ReturnCandidateInspection inspection = inspectReturnCandidate(candidate.location());
            if (!inspection.safe()) {
                operations.audit("run.exit_candidate_rejected", player.getUniqueId(), arenaId, Map.of(
                        "run_id", runId,
                        "candidate", candidate.kind(),
                        "reason", inspection.reason()));
                continue;
            }
            try {
                if (teleportInternally(player, candidate.location())) {
                    if (!candidate.kind().equals("custom")) {
                        operations.audit("run.exit_fallback", player.getUniqueId(), arenaId, Map.of(
                                "run_id", runId,
                                "destination", candidate.kind()));
                    }
                    return new ReturnAttempt(true, null);
                }
                failure = appendFailure(
                        failure,
                        new IllegalStateException("Paper rejected a validated player return teleport"));
            } catch (RuntimeException | LinkageError exception) {
                failure = appendFailure(failure, asRuntimeFailure(exception));
            }
        }
        messages.send(player, "chat.cantTeleport");
        operations.audit("run.return_failed", player.getUniqueId(), arenaId, Map.of(
                "run_id", runId));
        return new ReturnAttempt(false, failure == null
                ? new IllegalStateException("No safe player return destination was accepted")
                : failure);
    }

    private List<ReturnCandidate> returnCandidates(Location capturedReturn, Location customExit) {
        List<ReturnCandidate> candidates = new ArrayList<>();
        addReturnCandidate(candidates, "custom", customExit);
        addReturnCandidate(candidates, "captured", capturedReturn);
        Set<UUID> spawnWorlds = new HashSet<>();
        World capturedWorld = capturedReturn.getWorld();
        if (capturedWorld != null) {
            addReturnCandidate(candidates, "world_spawn", capturedWorld.getSpawnLocation());
            spawnWorlds.add(capturedWorld.getUID());
        }
        for (World world : plugin.getServer().getWorlds()) {
            if (spawnWorlds.add(world.getUID())) {
                addReturnCandidate(candidates, "world_spawn", world.getSpawnLocation());
            }
        }
        return List.copyOf(candidates);
    }

    private static void addReturnCandidate(
            List<ReturnCandidate> candidates,
            String kind,
            Location location) {
        if (location == null || candidates.stream().anyMatch(existing -> existing.location().equals(location))) {
            return;
        }
        candidates.add(new ReturnCandidate(kind, location));
    }

    private ReturnCandidateInspection inspectReturnCandidate(Location candidate) {
        SafePlayerExit.Inspection physical = SafePlayerExit.inspect(candidate);
        if (!physical.safe()) {
            return ReturnCandidateInspection.rejected(
                    physical.reason().name().toLowerCase(java.util.Locale.ROOT));
        }
        try {
            RuntimeSettings current = settings.get();
            boolean insideConfiguredArena = current.arenas().stream()
                    .map(arena -> ArenaBounds.around(
                            arena,
                            current.horizontalRadius(),
                            current.fallDistance()))
                    .anyMatch(bounds -> bounds.contains(candidate));
            return insideConfiguredArena
                    ? ReturnCandidateInspection.rejected("protected_volume")
                    : ReturnCandidateInspection.accepted();
        } catch (RuntimeException inspectionFailure) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not validate a player return against configured arena bounds",
                    inspectionFailure);
            return ReturnCandidateInspection.rejected("arena_bounds_failed");
        }
    }

    private boolean queuePlayerRecoveryCompletion(GameSession session) {
        return queuePlayerRecoveryCompletion(
                session.player().getUniqueId(),
                session.runId(),
                session.arena().id(),
                null);
    }

    private boolean queuePlayerRecoveryCompletion(
            UUID playerId,
            UUID runId,
            String arenaId,
            String recoveredAction) {
        Optional<PlayerRecoveryRecord> existing = playerRecovery.pending(playerId);
        if (existing.isEmpty() || !existing.orElseThrow().owns(playerId, runId, arenaId)) {
            IllegalStateException ownershipFailure = new IllegalStateException(
                    "Expected player recovery ownership is missing or ambiguous");
            operations.metrics().recordRestorationFailure(ownershipFailure);
            operations.audit("player_recovery.completion_refused", playerId, arenaId, Map.of(
                    "run_id", runId,
                    "reason", "ownership_mismatch"));
            return false;
        }
        PlayerRecoveryCompletion completion =
                new PlayerRecoveryCompletion(playerId, runId, arenaId, recoveredAction);
        if (!pendingPlayerRecoveryCompletions.add(completion)) {
            return true;
        }
        CompletableFuture<Void> durableCompletion =
                recoveryDurability.completePlayerRecovery(playerId, runId, arenaId);
        durableCompletion.whenComplete((ignored, failure) ->
                enqueueDurabilityCompletion(() ->
                        completePlayerRecoveryCompletion(completion, failure)));
        return !durableCompletion.isCompletedExceptionally();
    }

    private void completePlayerRecoveryCompletion(
            PlayerRecoveryCompletion completion,
            Throwable failure) {
        pendingPlayerRecoveryCompletions.remove(completion);
        if (failure != null) {
            operations.metrics().recordRestorationFailure(failure);
            operations.audit(
                    "player_recovery.completion_failed",
                    completion.playerId(),
                    completion.arenaId(),
                    Map.of(
                    "run_id", completion.runId(),
                            "failure", failure.getClass().getSimpleName()));
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Player state was restored, but its recovery record could not be cleared",
                    failure);
            return;
        }

        operations.audit(
                "player_recovery.cleared",
                completion.playerId(),
                completion.arenaId(),
                Map.of("run_id", completion.runId()));
        pendingPlayerReturns.entrySet().removeIf(entry ->
                entry.getValue().playerId().equals(completion.playerId())
                        && entry.getValue().runId().equals(completion.runId()));
        if (completion.recoveredAction() != null) {
            operations.metrics().recordRestorationRecovered();
            operations.audit(
                    "player_recovery.completed",
                    completion.playerId(),
                    completion.arenaId(),
                    Map.of(
                            "run_id", completion.runId(),
                            "action", completion.recoveredAction()));
        }
        GameSession quarantined = quarantinedSessions.get(completion.arenaId());
        if (quarantined != null && quarantined.runId().equals(completion.runId())) {
            settleQuarantinedSession(quarantined);
        } else {
            rebuildFreeArenas();
        }
    }

    public void handleMove(Player player, Location destination) {
        GameSession session = sessions.get(player.getUniqueId());
        if (session == null || pendingExternalTeleports.containsKey(player.getUniqueId())) {
            return;
        }
        if (session.hasFallen(destination)) {
            end(player, SessionEndReason.FALL);
            return;
        }

        Location underPlayer = destination.clone().subtract(0.0, 1.0, 0.0);
        if (!LandingPolicy.isGroundedAndNotAscending(
                        hasGroundSupport(player),
                        player.getVelocity().getY())
                || !session.isTarget(BlockKey.from(underPlayer))) {
            return;
        }
        advanceLandedSession(player, session);
    }

    private void advanceLandedSession(Player player, GameSession session) {
        GameSession.AdvanceResult result;
        try {
            result = session.advance();
            if (!result.advanced()) {
                return;
            }
            attachSuccessorPreparation(session, result.successorPreparation());
            attachBlockCleanup(session, result.cleanup());
            publishSessionScores();
            operations.metrics().recordJump();
        } catch (RuntimeException | LinkageError exception) {
            failActiveSession(player, session, "advance", exception);
            return;
        }
        int score = result.score();
        try {
            plugin.getServer().getPluginManager().callEvent(
                    new WalkJumpEvent(player, session.arena().id(), score));
        } catch (RuntimeException | LinkageError eventFailure) {
            operations.metrics().recordFailure("event", eventFailure);
            operations.audit("event.jump_failed", player.getUniqueId(), session.arena().id(), Map.of(
                    "run_id", session.runId(),
                    "score", score,
                    "failure", eventFailure.getClass().getSimpleName()));
            plugin.getLogger().log(
                    Level.SEVERE,
                    "WalkJumpEvent listener failed; the active run will continue",
                    eventFailure);
        }
        try {
            List<String> scoreMessages = messages.rawList("chat.scoreMsgs");
            String template = scoreMessages.isEmpty()
                    ? "Score: &f{{score}}"
                    : scoreMessages.get(ThreadLocalRandom.current().nextInt(scoreMessages.size()));
            player.sendMessage(messages.prefixedTemplate(template, Map.of("score", score)));
        } catch (RuntimeException | LinkageError exception) {
            failActiveSession(player, session, "score_message", exception);
        }
    }

    private void attachSuccessorPreparation(
            GameSession session,
            PreparedBlock prepared) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(prepared, "prepared");
        prepared.durableRecord().whenComplete((record, failure) ->
                enqueueDurabilityCompletion(() ->
                        completeSuccessorPreparation(session, prepared, record, failure)));
    }

    private void completeSuccessorPreparation(
            GameSession session,
            PreparedBlock prepared,
            RestorationRecord record,
            Throwable failure) {
        Player player = session.player();
        if (failure != null) {
            prepared.abandon();
            if (sessions.get(player.getUniqueId()) == session) {
                failActiveSession(player, session, "successor_durability", failure);
            }
            return;
        }

        BlockLeaseRegistry.BlockLease blockLease = prepared.lease();
        PlacementCommitPolicy.Decision decision = PlacementCommitPolicy.decide(
                new PlacementCommitPolicy.CapturedOwner(
                        player.getUniqueId(),
                        session.runId(),
                        session.arena().id(),
                        session.generation(),
                        blockLease.platformGeneration(),
                        prepared.key()),
                new PlacementCommitPolicy.LiveOwner(
                        player.getUniqueId(),
                        session.runId(),
                        session.arena().id(),
                        session.generation(),
                        blockLease.platformGeneration(),
                        prepared.key(),
                        !shuttingDown,
                        sessions.get(player.getUniqueId()) == session,
                        player.isOnline(),
                        arenaLeases.owns(session.arena().id(), session.arenaLease()),
                        blockLeases.owns(prepared.key(), blockLease)));
        if (decision != PlacementCommitPolicy.Decision.COMMIT
                || player.isDead()
                || !player.hasPermission(settings.get().permissions().playGame())) {
            prepared.abandon();
            if (sessions.get(player.getUniqueId()) == session
                    && decision != PlacementCommitPolicy.Decision.PLAYER_OFFLINE
                    && !shuttingDown) {
                failActiveSession(
                        player,
                        session,
                        "successor_revalidation",
                        new IllegalStateException("Successor commit rejected: " + decision));
            }
            return;
        }

        try {
            session.commitSuccessor(prepared, Objects.requireNonNull(record, "record"));
        } catch (RuntimeException | LinkageError commitFailure) {
            failActiveSession(player, session, "successor_commit", commitFailure);
            return;
        }
        Location actual = player.getLocation();
        Location underPlayer = actual.clone().subtract(0.0, 1.0, 0.0);
        if (sessions.get(player.getUniqueId()) == session
                && LandingPolicy.isGroundedAndNotAscending(
                        hasGroundSupport(player),
                        player.getVelocity().getY())
                && session.isTarget(BlockKey.from(underPlayer))) {
            advanceLandedSession(player, session);
        }
    }

    private void attachBlockCleanup(
            GameSession session,
            GameSession.BlockCleanup cleanup) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(cleanup, "cleanup");
        cleanup.completion().whenComplete((outcome, failure) ->
                enqueueDurabilityCompletion(() ->
                        completeBlockCleanup(session, cleanup, outcome, failure)));
    }

    private void completeBlockCleanup(
            GameSession session,
            GameSession.BlockCleanup cleanup,
            com.mrfdev.walktheplank.recovery.RestorationOutcome outcome,
            Throwable failure) {
        if (failure != null || outcome == null || !outcome.completed()) {
            Throwable reported = failure != null
                    ? failure
                    : new IllegalStateException("Restoration deletion did not complete: " + outcome);
            operations.metrics().recordRestorationFailure(reported);
            if (sessions.get(session.player().getUniqueId()) == session) {
                failActiveSession(
                        session.player(),
                        session,
                        "restoration_completion",
                        reported);
            }
            return;
        }
        if (!cleanup.release()) {
            recordLifecycleFailure(
                    "restoration.lease_release_failed",
                    session.player().getUniqueId(),
                    session.arena().id(),
                    new IllegalStateException("A newer owner replaced a completed block lease"));
            return;
        }
        refreshJournalProtection();
        settleQuarantinedSession(session);
    }

    private void failActiveSession(
            Player player,
            GameSession session,
            String stage,
            Throwable failure) {
        recordLifecycleFailure(
                "session.runtime_failed",
                player.getUniqueId(),
                session.arena().id(),
                failure);
        try {
            messages.send(player, "chat.sessionFailed");
        } catch (RuntimeException | LinkageError messageFailure) {
            recordLifecycleFailure(
                    "session.failure_message_failed",
                    player.getUniqueId(),
                    session.arena().id(),
                    messageFailure);
        }
        try {
            end(player, SessionEndReason.ERROR);
        } catch (RuntimeException | LinkageError cleanupFailure) {
            recordLifecycleFailure(
                    "session.runtime_cleanup_failed",
                    player.getUniqueId(),
                    session.arena().id(),
                    cleanupFailure);
        }
        try {
            operations.audit("session.runtime_stage", player.getUniqueId(), session.arena().id(), Map.of(
                    "run_id", session.runId(),
                    "stage", stage));
        } catch (RuntimeException | LinkageError ignored) {
            // The primary failure and cleanup outcome were already reported above.
        }
    }

    /** Drains all state using the currently active settings before a new bundle is published. */
    public CompletableFuture<Void> prepareReload() {
        boolean clean = stopAll(SessionEndReason.RELOAD);
        clean &= cancelPendingStarts("RELOAD");
        try {
            for (UUID playerId : queue.drain()) {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    messages.send(player, "chat.queueDrained");
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            clean = false;
            recordLifecycleFailure("reload.queue_drain_failed", null, null, failure);
        }
        nextQueueReminder.clear();
        boolean mainCleanupClean = clean;
        CompletableFuture<Void> result = new CompletableFuture<>();
        operations.submitRequired(() -> {
            if (!recoveryDurability.flush(Duration.ofSeconds(10))) {
                throw new IllegalStateException(
                        "Recovery durability did not drain before configuration reload");
            }
            return null;
        }).whenComplete((ignored, failure) ->
                enqueueDurabilityCompletion(() ->
                        finishReloadPreparation(mainCleanupClean, failure, result)));
        return result;
    }

    private void finishReloadPreparation(
            boolean mainCleanupClean,
            Throwable durabilityFailure,
            CompletableFuture<Void> result) {
        boolean clean = mainCleanupClean && durabilityFailure == null;
        if (durabilityFailure != null) {
            recordLifecycleFailure(
                    "reload.durability_drain_failed",
                    null,
                    null,
                    durabilityFailure);
        }
        try {
            refreshJournalProtection();
            for (GameSession session : List.copyOf(quarantinedSessions.values())) {
                settleQuarantinedSession(session);
            }
            PlayerRecoveryJournal.Health recoveryHealth = playerRecovery.health();
            boolean unresolved = !sessions.isEmpty()
                    || !pendingStarts.isEmpty()
                    || !pendingStartAbandonments.isEmpty()
                    || !quarantinedSessions.isEmpty()
                    || restoration.pendingCount() > 0
                    || playerRecovery.pendingCount() > 0
                    || !pendingPlayerRecoveryCompletions.isEmpty()
                    || !durabilityCompletions.isEmpty()
                    || !recoveryHealth.healthy();
            if (unresolved) {
                clean = false;
                operations.audit("reload.unresolved_cleanup", null, null, Map.of(
                        "actor", "system",
                        "sessions", sessions.size(),
                        "pending_starts", pendingStarts.size(),
                        "pending_start_abandonments",
                                pendingStartAbandonments.size(),
                        "quarantined_arenas", quarantinedSessions.size(),
                        "pending_restorations", restoration.pendingCount(),
                        "pending_player_recoveries", playerRecovery.pendingCount(),
                        "pending_player_recovery_completions",
                                pendingPlayerRecoveryCompletions.size(),
                        "invalid_player_recoveries", recoveryHealth.invalidRecords()));
            }
        } catch (RuntimeException | LinkageError failure) {
            clean = false;
            recordLifecycleFailure("reload.cleanup_gate_failed", null, null, failure);
        }
        if (!clean) {
            result.completeExceptionally(
                    new IllegalStateException("WalkThePlank reload cleanup was incomplete"));
            return;
        }
        result.complete(null);
    }

    /** Rebuilds idle runtime structures from the already atomically published settings bundle. */
    public void applyReloadedSettings() {
        try {
            queue = new PlayerQueue(
                    settings.get().queue().joinCooldown(),
                    settings.get().queue().readinessWindow());
            refreshJournalProtection();
            rebuildFreeArenas();
            refreshQueue();
        } catch (RuntimeException | LinkageError failure) {
            recordLifecycleFailure("reload.rebuild_failed", null, null, failure);
            throw new IllegalStateException("WalkThePlank reload apply was incomplete", failure);
        }
    }

    public boolean shutdown() {
        shuttingDown = true;
        boolean clean = cancelPendingStarts("SHUTDOWN");
        try {
            queue.drain();
        } catch (RuntimeException | LinkageError failure) {
            clean = false;
            recordLifecycleFailure("shutdown.queue_drain_failed", null, null, failure);
        }
        nextQueueReminder.clear();
        clean &= stopAll(SessionEndReason.SHUTDOWN);
        drainDurabilityCompletions();
        return clean;
    }

    /**
     * Final main-thread shutdown gate, called only after the recovery writer has stopped and every
     * accepted completion has been published into the main-thread completion queue.
     */
    public boolean finishDurabilityShutdown() {
        if (!shuttingDown) {
            throw new IllegalStateException("Durability shutdown cannot finish before shutdown starts");
        }
        try {
            drainDurabilityCompletions();
            refreshJournalProtection();
            for (GameSession session : List.copyOf(quarantinedSessions.values())) {
                settleQuarantinedSession(session);
            }
            RecoveryDurabilityService.Status durability = recoveryDurability.status();
            return durability.terminated()
                    && quarantinedArenas() == 0
                    && pendingStartAbandonments.isEmpty()
                    && restoration.pendingCount() == 0
                    && playerRecovery.pendingCount() == 0
                    && pendingPlayerRecoveryCompletions.isEmpty()
                    && durabilityCompletions.isEmpty()
                    && arenaLeases.size() == 0
                    && blockLeases.size() == 0;
        } catch (RuntimeException | LinkageError failure) {
            recordLifecycleFailure("shutdown.health_check_failed", null, null, failure);
            return false;
        }
    }

    public boolean isPlaying(Player player) {
        return isPlaying(player.getUniqueId());
    }

    public boolean isPlaying(UUID playerId) {
        return publishedGameState.sessions().containsKey(playerId);
    }

    /** True while durable player-state evidence remains unresolved or is being verified. */
    public boolean isRecovering(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return RecoveryQuarantinePolicy.requiresQuarantine(
                sessions.containsKey(playerId)
                        || pendingStarts.containsKey(playerId)
                        || hasPendingStartAbandonment(playerId),
                playerRecovery.requiresRecovery(playerId),
                playerRecoveryLookups.contains(playerId));
    }

    public boolean isRecovering(Player player) {
        return isRecovering(Objects.requireNonNull(player, "player").getUniqueId());
    }

    public boolean isInternalTeleport(Player player) {
        return internalTeleports.contains(player.getUniqueId());
    }

    /**
     * Reserves and schedules an external-teleport verification before the event decision phase
     * ends. An empty result means the caller must reject the teleport at HIGHEST priority.
     */
    public Optional<UUID> prepareExternalTeleportCompletion(Player player) {
        Objects.requireNonNull(player, "player");
        GameSession session = sessions.get(player.getUniqueId());
        if (shuttingDown
                || session == null
                || pendingExternalTeleports.containsKey(player.getUniqueId())) {
            return Optional.empty();
        }
        UUID attemptId = UUID.randomUUID();
        PendingExternalTeleport pending = PendingExternalTeleport.awaiting(
                session.runId(), attemptId);
        pendingExternalTeleports.put(player.getUniqueId(), pending);
        try {
            plugin.getServer().getScheduler().runTask(
                    plugin,
                    () -> completeExternalTeleport(player, attemptId));
            return Optional.of(attemptId);
        } catch (RuntimeException | LinkageError schedulingFailure) {
            pendingExternalTeleports.remove(player.getUniqueId(), pending);
            recordLifecycleFailure(
                    "run.external_teleport_schedule_failed",
                    player.getUniqueId(),
                    session.arena().id(),
                    schedulingFailure);
            return Optional.empty();
        }
    }

    /** Records the final event state at MONITOR without modifying the event. */
    public void observeExternalTeleportCompletion(
            Player player,
            UUID attemptId,
            boolean eventAccepted,
            Location finalDestination) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(attemptId, "attemptId");
        PendingExternalTeleport pending = pendingExternalTeleports.get(player.getUniqueId());
        if (pending == null || !pending.matches(attemptId)) {
            return;
        }
        World destinationWorld = finalDestination == null ? null : finalDestination.getWorld();
        PendingExternalTeleport observed = pending.observe(
                eventAccepted,
                destinationWorld == null ? null : destinationWorld.getUID(),
                finalDestination == null ? Double.NaN : finalDestination.getX(),
                finalDestination == null ? Double.NaN : finalDestination.getY(),
                finalDestination == null ? Double.NaN : finalDestination.getZ());
        if (pendingExternalTeleports.get(player.getUniqueId()) == pending) {
            pendingExternalTeleports.put(player.getUniqueId(), observed);
        }
    }

    private void completeExternalTeleport(Player player, UUID attemptId) {
        PendingExternalTeleport pending = pendingExternalTeleports.get(player.getUniqueId());
        if (pending == null
                || !pending.matches(attemptId)
                || !pendingExternalTeleports.remove(player.getUniqueId(), pending)) {
            return;
        }
        GameSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.runId().equals(pending.runId())) {
            return;
        }
        Location actual = player.getLocation();
        UUID actualWorldId = actual.getWorld() == null ? null : actual.getWorld().getUID();
        boolean playerOnline = player.isOnline();
        if (!pending.reached(
                playerOnline,
                actualWorldId,
                actual.getX(),
                actual.getY(),
                actual.getZ())) {
            try {
                operations.audit(
                        "run.external_teleport_not_committed",
                        player.getUniqueId(),
                        session.arena().id(),
                        Map.of(
                                "run_id", session.runId(),
                                "reason", pending.notCommittedReason(playerOnline)));
            } catch (RuntimeException | LinkageError auditFailure) {
                recordLifecycleFailure(
                        "run.external_teleport_audit_failed",
                        player.getUniqueId(),
                        session.arena().id(),
                        auditFailure);
            }
            return;
        }
        end(player, SessionEndReason.TELEPORT);
    }

    public boolean isProtected(BlockKey key) {
        return blockLeases.isReserved(key) || journalProtectedBlocks.contains(key);
    }

    public boolean isInsideActiveArena(Location location) {
        for (GameSession session : sessions.values()) {
            if (session.contains(location)) {
                return true;
            }
        }
        for (GameSession session : quarantinedSessions.values()) {
            if (session.contains(location)) {
                return true;
            }
        }
        Set<String> unresolved = unresolvedArenaIds();
        RuntimeSettings current = settings.get();
        for (Arena arena : current.arenas()) {
            if (unresolved.contains(arena.id())
                    && ArenaBounds.around(
                            arena,
                            current.horizontalRadius(),
                            current.fallDistance())
                    .contains(location)) {
                return true;
            }
        }
        return false;
    }

    public int currentScore(Player player) {
        return currentScore(player.getUniqueId());
    }

    public int currentScore(UUID playerId) {
        SessionStatus session = publishedGameState.sessions().get(playerId);
        return session == null ? 0 : session.score();
    }

    public int activeSessions() {
        return publishedGameState.sessions().size();
    }

    public int availableArenas() {
        return publishedGameState.availableArenas();
    }

    public int totalArenas() {
        return publishedGameState.totalArenas();
    }

    public int quarantinedArenas() {
        return publishedGameState.quarantinedArenas();
    }

    public int pendingRestorations() {
        return restoration.pendingCount();
    }

    public int pendingPlayerRecoveries() {
        return playerRecovery.pendingCount();
    }

    /**
     * Main-thread gate for arena-layout mutations. Queue reservations and every recovery owner are
     * included so a validated edit cannot replace coordinates still referenced by live work.
     */
    public boolean isConfigurationMutationIdle() {
        return sessions.isEmpty()
                && pendingStarts.isEmpty()
                && pendingStartAbandonments.isEmpty()
                && quarantinedSessions.isEmpty()
                && pendingPlayerReturns.isEmpty()
                && pendingExternalTeleports.isEmpty()
                && playerRecoveryLookups.isEmpty()
                && pendingPlayerRecoveryCompletions.isEmpty()
                && queue.size() == 0
                && restoration.pendingCount() == 0
                && playerRecovery.pendingCount() == 0
                && arenaLeases.size() == 0
                && blockLeases.size() == 0;
    }

    public PlayerRecoveryJournal.Health playerRecoveryHealth() {
        return playerRecovery.health();
    }

    public int conflictedRestorations() {
        return restoration.conflictedCount();
    }

    public OperationalMetrics.Snapshot operationalMetrics() {
        return operations.metrics().snapshot();
    }

    public RecoveryDurabilityService.Status recoveryDurabilityStatus() {
        return recoveryDurability.status();
    }

    /** Privacy-safe counts for pending gameplay work owned by the main server thread. */
    public TaskHealth taskHealth() {
        return new TaskHealth(
                pendingStarts.size(),
                pendingStartAbandonments.size(),
                pendingExternalTeleports.size(),
                playerRecoveryLookups.size(),
                pendingPlayerRecoveryCompletions.size());
    }

    public void auditRewardResolution(
            UUID operatorId,
            UUID planId,
            int stepIndex,
            RewardStepStatus resolution) {
        operations.audit("reward.staff_resolution", operatorId, null, Map.of(
                "actor", operatorKind(operatorId),
                "plan_id", Objects.requireNonNull(planId, "planId"),
                "step", stepIndex,
                "resolution", Objects.requireNonNull(resolution, "resolution")));
    }

    public void auditRewardAbandon(UUID operatorId, UUID planId, RewardPlanStatus status) {
        operations.audit("reward.staff_abandon", operatorId, null, Map.of(
                "actor", operatorKind(operatorId),
                "plan_id", Objects.requireNonNull(planId, "planId"),
                "status", Objects.requireNonNull(status, "status")));
    }

    public void auditSeasonTransition(UUID operatorId, Season season) {
        Objects.requireNonNull(season, "season");
        operations.audit("season.transition", operatorId, null, Map.of(
                "actor", operatorKind(operatorId),
                "season_id", season.id(),
                "status", season.status()));
    }

    public void auditLeaderboardExport(UUID operatorId, String category, int rows) {
        operations.audit("leaderboard.export", operatorId, null, Map.of(
                "actor", operatorKind(operatorId),
                "category", Objects.requireNonNull(category, "category"),
                "rows", rows));
    }

    public void auditArenaEdit(
            UUID operatorId,
            String arenaId,
            String action,
            String result,
            String fingerprint) {
        operations.audit("arena.edit", operatorId, arenaId, Map.of(
                "actor", operatorKind(operatorId),
                "action", Objects.requireNonNull(action, "action"),
                "result", Objects.requireNonNull(result, "result"),
                "fingerprint", Objects.requireNonNull(fingerprint, "fingerprint")));
    }

    public boolean joinQueue(Player player) {
        Objects.requireNonNull(player, "player");
        if (!settings.get().queue().enabled()) {
            messages.send(player, "chat.queueDisabled");
            return false;
        }
        if (!canStart(player, true)) {
            return false;
        }
        PlayerQueue.JoinResult result = queue.join(player.getUniqueId(), Instant.now());
        switch (result.state()) {
            case JOINED -> {
                operations.metrics().recordQueueJoin();
                operations.audit("queue.join", player.getUniqueId(), null, Map.of(
                        "position", result.position()));
                messages.send(player, "chat.queueJoined", Map.of(
                        "position", result.position()));
                nextQueueReminder.put(
                        player.getUniqueId(),
                        Instant.now().plus(settings.get().queue().reminderInterval()));
            }
            case ALREADY_WAITING -> messages.send(player, "chat.queueAlready", Map.of(
                    "position", result.position()));
            case ALREADY_READY -> messages.send(player, "chat.queueReady", Map.of(
                    "seconds", queue.readyUntil(player.getUniqueId())
                            .map(expiry -> Math.max(1L, Duration.between(Instant.now(), expiry).toSeconds()))
                            .orElse(1L)));
            case COOLDOWN -> messages.send(player, "chat.queueCooldown", Map.of(
                    "seconds", Math.max(1L, result.cooldownRemaining().toSeconds())));
        }
        refreshQueue();
        return result.state() == PlayerQueue.JoinState.JOINED;
    }

    public boolean leaveQueue(Player player) {
        Objects.requireNonNull(player, "player");
        if (!queue.leave(player.getUniqueId(), Instant.now())) {
            messages.send(player, "chat.queueNotQueued");
            return false;
        }
        operations.audit("queue.leave", player.getUniqueId(), null, Map.of());
        nextQueueReminder.remove(player.getUniqueId());
        messages.send(player, "chat.queueLeft");
        refreshQueue();
        return true;
    }

    public boolean startReady(Player player) {
        Objects.requireNonNull(player, "player");
        if (queue.isPaused()) {
            messages.send(player, "chat.queuePaused");
            return false;
        }
        if (queue.readyUntil(player.getUniqueId()).isEmpty()) {
            messages.send(player, "chat.queueNotReady", Map.of(
                    "position", queue.position(player.getUniqueId())));
            return false;
        }
        return start(player);
    }

    /** Removes queue/pending state when a player quits or becomes ineligible. */
    public void removeQueuedPlayer(Player player, String reason) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reason, "reason");
        PendingStart pending = pendingStarts.get(player.getUniqueId());
        if (pending != null) {
            abortPendingStart(pending, reason);
        }
        if (queue.remove(player.getUniqueId(), Instant.now())) {
            nextQueueReminder.remove(player.getUniqueId());
            operations.audit("queue.remove", player.getUniqueId(), null, Map.of(
                    "reason", reason));
        }
        refreshQueue();
    }

    public QueueStatus queueStatus(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        PublishedGameState published = publishedGameState;
        return published.playerQueues().getOrDefault(playerId, published.globalQueue());
    }

    public QueueStatus queueStatus() {
        return publishedGameState.globalQueue();
    }

    public void setQueuePaused(boolean paused) {
        setQueuePaused(paused, null);
    }

    public void setQueuePaused(boolean paused, UUID operatorId) {
        queue.setPaused(paused, Instant.now());
        operations.audit(paused ? "queue.pause" : "queue.resume", operatorId, null, Map.of(
                "actor", operatorKind(operatorId),
                "size", queue.size()));
        if (!paused) {
            refreshQueue();
        } else {
            publishGameState();
        }
    }

    public int drainQueue() {
        return drainQueue(null);
    }

    public int drainQueue(UUID operatorId) {
        List<UUID> drained = queue.drain();
        for (UUID playerId : drained) {
            nextQueueReminder.remove(playerId);
        }
        for (UUID playerId : drained) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                messages.send(player, "chat.queueDrained");
            }
        }
        operations.audit("queue.drain", operatorId, null, Map.of(
                "actor", operatorKind(operatorId),
                "players", drained.size()));
        publishGameState();
        return drained.size();
    }

    public Optional<RestorationFailure> lastRestorationFailure() {
        return restoration.lastFailure();
    }

    public RestorationRetryResult lastRestorationRetry() {
        return lastRestorationRetry;
    }

    public Optional<SessionStatus> sessionStatus(UUID playerId) {
        return Optional.ofNullable(publishedGameState.sessions().get(playerId));
    }

    /** Returns one immutable publication suitable for PlaceholderAPI's asynchronous callbacks. */
    public PlaceholderSnapshot placeholderSnapshot(UUID playerId) {
        PublishedGameState published = publishedGameState;
        Optional<SessionStatus> session = playerId == null
                ? Optional.empty()
                : Optional.ofNullable(published.sessions().get(playerId));
        QueueStatus playerQueue = playerId == null
                ? published.globalQueue()
                : published.playerQueues().getOrDefault(playerId, published.globalQueue());
        return new PlaceholderSnapshot(
                published.globalQueue(),
                published.sessions().size(),
                published.availableArenas(),
                published.totalArenas(),
                published.quarantinedArenas(),
                playerQueue.playerPosition(),
                playerQueue.playerReadyUntil().isPresent(),
                session);
    }

    public void expireSessions() {
        drainDurabilityCompletions();
        retryPendingStartAbandonments();
        for (PendingStart pending : List.copyOf(pendingStarts.values())) {
            try {
                UnscheduledStart outcome = pending.takeUnscheduledOutcome();
                if (outcome != null) {
                    completePendingStart(pending, outcome.record(), outcome.failure());
                }
            } catch (RuntimeException | LinkageError failure) {
                recordLifecycleFailure(
                        "run.pending_sweep_failed",
                        pending.playerId(),
                        pending.arena().id(),
                        failure);
            }
        }
        long now = System.nanoTime();
        for (GameSession session : List.copyOf(sessions.values())) {
            try {
                expireSession(session, now);
            } catch (RuntimeException | LinkageError failure) {
                failActiveSession(session.player(), session, "periodic_sweep", failure);
            }
        }
        try {
            refreshQueue();
        } catch (RuntimeException | LinkageError failure) {
            recordLifecycleFailure("queue.periodic_refresh_failed", null, null, failure);
        }
    }

    private void expireSession(GameSession session, long now) {
        Player player = session.player();
        if (!player.hasPermission(settings.get().permissions().playGame())) {
            end(player, SessionEndReason.PERMISSION_REVOKED);
            messages.send(player, "chat.noPermissionPlay", Map.of(
                    "permissionName", settings.get().permissions().playGame()));
            return;
        }
        if (!hasEligibleMovementAttribute(player)) {
            end(player, SessionEndReason.MOVEMENT_MODIFIED);
            messages.send(player, "chat.movementAttributeActive");
            return;
        }
        if (player.getWalkSpeed() != 0.2F) {
            player.setWalkSpeed(0.2F);
        }
        if (session.isExpired(now)) {
            end(player, SessionEndReason.TIMEOUT);
            messages.send(player, "chat.runTimedOut");
        }
    }

    public CompletableFuture<Integer> retryQuarantinedArenas() {
        return retryQuarantinedArenas(null);
    }

    public CompletableFuture<Integer> retryQuarantinedArenas(UUID operatorId) {
        retryPendingStartAbandonments();
        Set<String> before = unresolvedArenaIds();
        int onlinePlayerRetries = 0;
        for (PlayerRecoveryRecord recoveryRecord : playerRecovery.pendingRecords()) {
            Player recoveringPlayer = plugin.getServer().getPlayer(recoveryRecord.playerId());
            if (recoveringPlayer != null
                    && recoveringPlayer.isOnline()
                    && retryPendingReturn(recoveringPlayer)) {
                onlinePlayerRetries++;
            }
        }
        if (operatorId != null || onlinePlayerRetries > 0) {
            operations.audit("player_recovery.retry", operatorId, null, Map.of(
                    "actor", operatorKind(operatorId),
                    "online_lookups_started", onlinePlayerRetries));
        }
        Set<UUID> protectedRunIds = recoveryProtectedRunIds();
        CompletableFuture<RestorationRetryResult> retry =
                restoration.retryPendingDeferred(protectedRunIds);
        CompletableFuture<Integer> result = new CompletableFuture<>();
        retry.whenComplete((journalResult, failure) ->
                enqueueDurabilityCompletion(() ->
                        completeRestorationRetry(
                                operatorId,
                                before,
                                journalResult,
                                failure,
                                result)));
        return result;
    }

    private void completeRestorationRetry(
            UUID operatorId,
            Set<String> before,
            RestorationRetryResult journalResult,
            Throwable failure,
            CompletableFuture<Integer> result) {
        if (failure != null) {
            recordLifecycleFailure(
                    "restoration.retry_failed",
                    operatorId,
                    null,
                    failure);
            result.completeExceptionally(failure);
            return;
        }
        try {
            refreshJournalProtection();

            for (Map.Entry<String, GameSession> entry : List.copyOf(quarantinedSessions.entrySet())) {
                PendingPlayerReturn pendingReturn = pendingPlayerReturns.get(entry.getKey());
                if (pendingReturn != null) {
                    Player returningPlayer = plugin.getServer().getPlayer(pendingReturn.playerId());
                    if (returningPlayer == null || !returningPlayer.isOnline()) {
                        continue;
                    }
                    Optional<PlayerRecoveryRecord> recovery =
                            playerRecovery.pending(pendingReturn.playerId());
                    if (recovery.isPresent()) {
                        if (recovery.orElseThrow().runId().equals(pendingReturn.runId())) {
                            retryPendingReturn(returningPlayer);
                        }
                        continue;
                    } else if (!playerRecovery.canSafelyRecord(pendingReturn.playerId())) {
                        continue;
                    }
                    pendingPlayerReturns.remove(entry.getKey(), pendingReturn);
                }
                try {
                    finishSessionWorld(entry.getValue());
                } catch (RuntimeException | LinkageError exception) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Arena " + entry.getKey()
                                    + " is still quarantined after a restoration retry",
                            exception);
                    continue;
                }
                settleQuarantinedSession(entry.getValue());
            }
            rebuildFreeArenas();
            Set<String> after = unresolvedArenaIds();
            int recovered = 0;
            for (String arenaId : before) {
                if (!after.contains(arenaId)) {
                    recovered++;
                }
            }
            lastRestorationRetry = new RestorationRetryResult(
                    journalResult.attemptedRecords(),
                    journalResult.restoredRecords(),
                    journalResult.alreadyRestoredRecords(),
                    journalResult.conflictRecords(),
                    journalResult.missingWorldRecords(),
                    journalResult.failedRecords(),
                    recovered,
                    restoration.pendingCount(recoveryProtectedRunIds()),
                    restoration.conflictedCount());
            recordRestorationRetryMetrics(lastRestorationRetry);
            auditRestorationRetry("restoration.retry", operatorId, lastRestorationRetry);
            logRestorationRetry("Administrative", lastRestorationRetry);
            refreshQueue();
            result.complete(recovered);
        } catch (RuntimeException | LinkageError completionFailure) {
            recordLifecycleFailure(
                    "restoration.retry_completion_failed",
                    operatorId,
                    null,
                    completionFailure);
            result.completeExceptionally(completionFailure);
        }
    }

    public boolean retryPendingReturn(Player player) {
        Objects.requireNonNull(player, "player");
        if (sessions.containsKey(player.getUniqueId())
                || pendingStarts.containsKey(player.getUniqueId())
                || hasPendingStartAbandonment(player.getUniqueId())) {
            return false;
        }
        Optional<PlayerRecoveryRecord> recovery = playerRecovery.pending(player.getUniqueId());
        if (recovery.isEmpty()) {
            if (playerRecovery.canSafelyRecord(player.getUniqueId())) {
                return false;
            }
            PlayerRecoveryJournal.Health health = playerRecovery.health();
            operations.audit("player_recovery.unreadable", player.getUniqueId(), null, Map.of(
                    "invalid", health.invalidRecords(),
                    "healthy", health.healthy()));
            return false;
        }

        PlayerRecoveryRecord record = recovery.orElseThrow();
        if (!playerRecoveryLookups.add(player.getUniqueId())) {
            return false;
        }
        try {
            scoreRepository.run(record.runId()).whenComplete((run, failure) -> {
                Runnable completion = () -> completePendingRecoveryLookup(record, run, failure);
                if (!scheduleMain(completion, "verified player recovery")) {
                    playerRecoveryLookups.remove(record.playerId());
                }
            });
            return true;
        } catch (RuntimeException lookupFailure) {
            playerRecoveryLookups.remove(player.getUniqueId());
            operations.metrics().recordRepositoryFailure(lookupFailure);
            operations.audit("player_recovery.lookup_failed", player.getUniqueId(), record.arenaId(), Map.of(
                    "run_id", record.runId(),
                    "failure", lookupFailure.getClass().getSimpleName()));
            plugin.getLogger().log(Level.WARNING, "Could not verify pending player recovery ownership", lookupFailure);
            return false;
        }
    }

    private void completePendingRecoveryLookup(
            PlayerRecoveryRecord requested,
            Optional<RunInvestigationRecord> retainedRun,
            Throwable failure) {
        playerRecoveryLookups.remove(requested.playerId());
        if (failure != null || retainedRun == null) {
            Throwable lookupFailure = failure == null
                    ? new IllegalStateException("Player recovery lookup returned no result container")
                    : failure;
            operations.metrics().recordRepositoryFailure(lookupFailure);
            operations.audit("player_recovery.lookup_failed", requested.playerId(), requested.arenaId(), Map.of(
                    "run_id", requested.runId(),
                    "failure", lookupFailure.getClass().getSimpleName()));
            plugin.getLogger().log(Level.WARNING, "Could not verify pending player recovery ownership", lookupFailure);
            return;
        }
        Optional<PlayerRecoveryRecord> current = playerRecovery.pending(requested.playerId());
        if (current.isEmpty() || !current.orElseThrow().equals(requested)) {
            return;
        }
        PlayerRecoveryOwnership.Decision ownership = PlayerRecoveryOwnership.verify(requested, retainedRun);
        if (ownership != PlayerRecoveryOwnership.Decision.VERIFIED) {
            operations.audit("player_recovery.verification_refused", requested.playerId(), requested.arenaId(), Map.of(
                    "run_id", requested.runId(),
                    "reason", ownership.name().toLowerCase(java.util.Locale.ROOT)));
            plugin.getLogger().warning(
                    "Refusing automatic player recovery because retained run ownership did not verify");
            return;
        }
        Player player = plugin.getServer().getPlayer(requested.playerId());
        if (player == null
                || !player.isOnline()
                || sessions.containsKey(requested.playerId())
                || pendingStarts.containsKey(requested.playerId())) {
            return;
        }
        RunInvestigationRecord verifiedRun = retainedRun.orElseThrow();
        if (!recoverPendingPlayer(
                player,
                requested,
                PlayerRecoveryOwnership.recoveryAction(verifiedRun),
                verifiedRun)) {
            return;
        }
    }

    private boolean recoverPendingPlayer(
            Player player,
            PlayerRecoveryRecord record,
            PlayerRecoveryOwnership.RecoveryAction action,
            RunInvestigationRecord retainedRun) {
        if (!player.getUniqueId().equals(record.playerId())) {
            operations.audit("player_recovery.ownership_mismatch", player.getUniqueId(), null, Map.of());
            return false;
        }
        if (player.isDead()) {
            operations.audit("player_recovery.deferred", player.getUniqueId(), record.arenaId(), Map.of(
                    "run_id", record.runId(),
                    "reason", "player_dead"));
            return false;
        }
        World recordedWorld = plugin.getServer().getWorld(record.returnWorldId());
        PlayerSnapshot snapshot;
        try {
            snapshot = PlayerSnapshot.fromRecoveryRecord(record, recordedWorld);
            if (action == PlayerRecoveryOwnership.RecoveryAction.RESTORE_TEMPORARY_STATE_ONLY) {
                snapshot.restoreTemporaryState(player);
            } else {
                snapshot.restore(player);
            }
        } catch (RuntimeException | LinkageError stateFailure) {
            operations.metrics().recordRestorationFailure(stateFailure);
            operations.audit("player_recovery.state_failed", player.getUniqueId(), record.arenaId(), Map.of(
                    "run_id", record.runId(),
                    "failure", stateFailure.getClass().getSimpleName()));
            plugin.getLogger().log(Level.WARNING, "Could not restore pending player state", stateFailure);
            return false;
        }

        if (action == PlayerRecoveryOwnership.RecoveryAction.RESTORE_STATE_AND_RETURN) {
            ReturnAttempt returnAttempt;
            try {
                returnAttempt = returnPlayerSafely(
                        player,
                        record.runId(),
                        record.arenaId(),
                        snapshot.returnLocation(),
                        configuredRecoveryExit(retainedRun));
            } catch (RuntimeException | LinkageError returnFailure) {
                operations.metrics().recordRestorationFailure(returnFailure);
                operations.audit("player_recovery.return_failed", player.getUniqueId(), record.arenaId(), Map.of(
                        "run_id", record.runId(),
                        "failure", returnFailure.getClass().getSimpleName()));
                plugin.getLogger().log(Level.WARNING, "Could not return a player during crash recovery", returnFailure);
                return false;
            }
            if (!returnAttempt.success()) {
                if (returnAttempt.failure() != null) {
                    operations.metrics().recordRestorationFailure(returnAttempt.failure());
                }
                return false;
            }
        }
        if (!queuePlayerRecoveryCompletion(
                record.playerId(),
                record.runId(),
                record.arenaId(),
                action.name().toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        return true;
    }

    private Location configuredRecoveryExit(RunInvestigationRecord retainedRun) {
        if (retainedRun.status() != RunStatus.COMPLETED) {
            return null;
        }
        for (Arena arena : settings.get().arenas()) {
            if (arena.id().equals(retainedRun.arenaId())) {
                return arena.exit();
            }
        }
        return null;
    }

    private void recordRestorationRetryMetrics(RestorationRetryResult result) {
        operations.metrics().recordRestorationsRecovered(result.completedRecords());
        operations.metrics().recordRestorationFailures(result.failedRecords());
    }

    private void auditRestorationRetry(
            String event,
            UUID operatorId,
            RestorationRetryResult result) {
        operations.audit(event, operatorId, null, Map.of(
                "actor", operatorKind(operatorId),
                "attempted", result.attemptedRecords(),
                "completed", result.completedRecords(),
                "conflicts", result.conflictRecords(),
                "missing_worlds", result.missingWorldRecords(),
                "failed", result.failedRecords(),
                "recovered_arenas", result.recoveredArenas(),
                "pending", result.pendingRecords()));
    }

    public boolean isDisallowedMovementEffect(PotionEffectType type) {
        NamespacedKey key = Registry.MOB_EFFECT.getKey(type);
        return key != null && DISALLOWED_EFFECTS.contains(key.getKey());
    }

    private boolean stopAll(SessionEndReason reason) {
        boolean clean = true;
        for (GameSession session : List.copyOf(sessions.values())) {
            try {
                end(session.player(), reason);
            } catch (RuntimeException | LinkageError failure) {
                clean = false;
                recordLifecycleFailure(
                        "session.cleanup_failed",
                        session.player().getUniqueId(),
                        session.arena().id(),
                        failure);
            }
        }
        return clean;
    }

    private GameSession.FinishResult finishSessionWorld(GameSession session) {
        GameSession.FinishResult result = session.finishDeferred();
        for (GameSession.BlockCleanup cleanup : result.cleanups()) {
            attachBlockCleanup(session, cleanup);
        }
        for (CompletableFuture<Void> abandonment : result.abandonedPreparations()) {
            abandonment.whenComplete((ignored, failure) ->
                    enqueueDurabilityCompletion(() -> {
                        if (failure != null) {
                            recordLifecycleFailure(
                                    "restoration.abandonment_failed",
                                    session.player().getUniqueId(),
                                    session.arena().id(),
                                    failure);
                        }
                        refreshJournalProtection();
                        settleQuarantinedSession(session);
                    }));
        }
        return result;
    }

    private boolean cancelPendingStarts(String reason) {
        boolean clean = true;
        for (PendingStart pending : List.copyOf(pendingStarts.values())) {
            try {
                abortPendingStart(pending, reason);
            } catch (RuntimeException | LinkageError failure) {
                clean = false;
                recordLifecycleFailure(
                        "run.pending_abort_failed",
                        pending.playerId(),
                        pending.arena().id(),
                        failure);
            }
        }
        return clean;
    }

    private void recordLifecycleFailure(
            String event,
            UUID playerId,
            String arenaId,
            Throwable failure) {
        try {
            operations.metrics().recordRestorationFailure(failure);
        } catch (RuntimeException | LinkageError ignored) {
            // Continue cleanup even if diagnostic accounting itself is unavailable.
        }
        try {
            operations.audit(event, playerId, arenaId, Map.of(
                    "failure", failure.getClass().getSimpleName()));
        } catch (RuntimeException | LinkageError ignored) {
            // Continue to the server log and every later cleanup stage.
        }
        try {
            plugin.getLogger().log(Level.SEVERE, "WalkThePlank lifecycle cleanup failed", failure);
        } catch (RuntimeException | LinkageError ignored) {
            // No further safe reporting surface exists here.
        }
    }

    /**
     * Settles a start that was accepted by the repository but must not activate. Repository
     * operations are FIFO, so this interruption is accepted after the corresponding start write
     * and is drained before an orderly repository close.
     */
    private boolean abortPendingStart(PendingStart pending, String reason) {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(reason, "reason");
        if (pendingStarts.get(pending.playerId()) != pending) {
            return false;
        }
        pending.cancel(reason);
        pendingStarts.remove(pending.playerId(), pending);
        CompletableFuture<Void> durabilityAbandonment;
        try {
            durabilityAbandonment = pending.abandonDurability();
        } catch (RuntimeException | LinkageError abandonmentFailure) {
            pendingStartAbandonments.put(pending.runId(), pending);
            recordLifecycleFailure(
                    "run.pending_abandonment_submit_failed",
                    pending.playerId(),
                    pending.arena().id(),
                    abandonmentFailure);
            durabilityAbandonment = null;
        }
        if (durabilityAbandonment == null) {
            if (!pendingStartAbandonments.containsKey(pending.runId())) {
                releaseReservedArena(pending);
            }
        } else {
            pendingStartAbandonments.put(pending.runId(), pending);
            attachPendingStartAbandonment(pending, durabilityAbandonment);
        }
        if (pending.queueInterruption()) {
            markRunInterrupted(
                    pending.runId(),
                    RunStatus.ABORTED,
                    pending.cancellationReason());
        }
        return true;
    }

    private void attachPendingStartAbandonment(
            PendingStart pending,
            CompletableFuture<Void> abandonment) {
        if (!pending.beginAbandonment(abandonment)) {
            return;
        }
        abandonment.whenComplete((ignored, failure) ->
                enqueueDurabilityCompletion(() ->
                        completePendingStartAbandonment(pending, abandonment, failure)));
    }

    private void completePendingStartAbandonment(
            PendingStart pending,
            CompletableFuture<Void> abandonment,
            Throwable failure) {
        if (!pending.finishAbandonment(abandonment, failure == null)) {
            return;
        }
        if (failure != null) {
            recordLifecycleFailure(
                    "run.pending_abandonment_failed",
                    pending.playerId(),
                    pending.arena().id(),
                    failure);
            return;
        }
        try {
            releaseReservedArenaLease(pending);
        } catch (RuntimeException | LinkageError releaseFailure) {
            pending.deferAbandonmentRetry();
            recordLifecycleFailure(
                    "run.pending_abandonment_release_failed",
                    pending.playerId(),
                    pending.arena().id(),
                    releaseFailure);
            return;
        }
        publishAfterClearingPendingAbandonment(
                () -> {
                    PendingActivation activation = pending.activation();
                    if (activation != null) {
                        pending.clearActivation(activation);
                    }
                    pendingStartAbandonments.remove(pending.runId(), pending);
                },
                this::rebuildFreeArenas);
        refreshQueue();
    }

    private void retryPendingStartAbandonments() {
        long now = System.nanoTime();
        for (PendingStart pending : List.copyOf(pendingStartAbandonments.values())) {
            if (!pending.mayRetryAbandonment(now)) {
                continue;
            }
            try {
                CompletableFuture<Void> abandonment = pending.abandonDurability();
                if (abandonment != null) {
                    attachPendingStartAbandonment(pending, abandonment);
                }
            } catch (RuntimeException | LinkageError retryFailure) {
                pending.deferAbandonmentRetry();
                recordLifecycleFailure(
                        "run.pending_abandonment_retry_failed",
                        pending.playerId(),
                        pending.arena().id(),
                        retryFailure);
            }
        }
    }

    private boolean hasPendingStartAbandonment(UUID playerId) {
        for (PendingStart pending : pendingStartAbandonments.values()) {
            if (pending.playerId().equals(playerId)) {
                return true;
            }
        }
        return false;
    }

    private void releaseReservedArena(PendingStart pending) {
        if (releaseReservedArenaLease(pending)) {
            rebuildFreeArenas();
        }
    }

    private boolean releaseReservedArenaLease(PendingStart pending) {
        if (pending.arenaReleased()) {
            return false;
        }
        if (!arenaLeases.release(pending.arena().id(), pending.arenaLease())) {
            throw new IllegalStateException(
                    "Pending start no longer owns its exact arena lease");
        }
        pending.markArenaReleased();
        return true;
    }

    static void publishAfterClearingPendingAbandonment(
            Runnable clearUnresolvedMarker,
            Runnable rebuildAvailability) {
        Objects.requireNonNull(clearUnresolvedMarker, "clearUnresolvedMarker").run();
        Objects.requireNonNull(rebuildAvailability, "rebuildAvailability").run();
    }

    private void markRunInterrupted(UUID runId, RunStatus status, String reason) {
        scoreRepository.markRunInterrupted(runId, status, Instant.now(), reason)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        operations.metrics().recordRepositoryFailure(failure);
                        plugin.getLogger().log(
                                Level.SEVERE,
                                "Could not persist interrupted run " + runId,
                                failure);
                    }
                });
    }

    private void refreshQueue() {
        if (!settings.get().queue().enabled() || shuttingDown) {
            publishGameState();
            return;
        }
        Instant now = Instant.now();
        PlayerQueue.QueueUpdate update = queue.refresh(
                freeArenas.size(),
                playerId -> {
                    Player player = plugin.getServer().getPlayer(playerId);
                    return player != null
                            && player.isOnline()
                            && player.hasPermission(settings.get().permissions().playGame())
                            && canStart(player, false);
                },
                now);
        for (PlayerQueue.ReadyClaim claim : update.assigned()) {
            Player player = plugin.getServer().getPlayer(claim.playerId());
            if (player != null) {
                messages.send(player, "chat.queueReady", Map.of(
                        "seconds", Math.max(1L, Duration.between(now, claim.expiresAt()).toSeconds())));
                nextQueueReminder.put(
                        claim.playerId(), now.plus(settings.get().queue().reminderInterval()));
            }
        }
        for (UUID playerId : update.expired()) {
            nextQueueReminder.remove(playerId);
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                messages.send(player, "chat.queueExpired");
            }
        }
        for (UUID playerId : update.removed()) {
            nextQueueReminder.remove(playerId);
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                messages.send(player, "chat.queueRemoved");
            }
        }
        Set<UUID> queued = Set.copyOf(queue.orderedPlayers());
        nextQueueReminder.keySet().removeIf(playerId -> !queued.contains(playerId));
        for (UUID playerId : queue.orderedPlayers()) {
            Instant next = nextQueueReminder.getOrDefault(playerId, now);
            if (next.isAfter(now)) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                boolean ready = queue.readyUntil(playerId).isPresent();
                player.sendActionBar(messages.translated(
                        ready ? "chat.queueReadyActionbar" : "chat.queuePositionActionbar",
                        Map.of(
                                "position", queue.position(playerId),
                                "total", queue.size())));
            }
            nextQueueReminder.put(playerId, now.plus(settings.get().queue().reminderInterval()));
        }
        publishGameState();
    }

    private boolean scheduleMain(Runnable task, String description) {
        if (!plugin.isEnabled() || shuttingDown) {
            return false;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
            return true;
        } catch (RuntimeException schedulingFailure) {
            operations.metrics().recordRepositoryFailure(schedulingFailure);
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not schedule " + description,
                    schedulingFailure);
            return false;
        }
    }

    private void enqueueDurabilityCompletion(Runnable completion) {
        durabilityCompletions.add(Objects.requireNonNull(completion, "completion"));
        if (shuttingDown || !plugin.isEnabled()) {
            return;
        }
        if (!durabilityDrainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, this::drainDurabilityCompletions);
        } catch (RuntimeException schedulingFailure) {
            durabilityDrainScheduled.set(false);
            operations.metrics().recordRestorationFailure(schedulingFailure);
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not schedule recovery durability completions",
                    schedulingFailure);
        }
    }

    private void drainDurabilityCompletions() {
        durabilityDrainScheduled.set(false);
        Runnable completion;
        while ((completion = durabilityCompletions.poll()) != null) {
            try {
                completion.run();
            } catch (RuntimeException | LinkageError failure) {
                recordLifecycleFailure(
                        "durability.main_completion_failed",
                        null,
                        null,
                        failure);
            }
        }
        if (!durabilityCompletions.isEmpty()
                && !shuttingDown
                && plugin.isEnabled()) {
            enqueueDurabilityCompletion(() -> {
                // Wake-up marker; the next drain processes any completion that raced this drain.
            });
        }
    }

    private boolean teleportInternally(Player player, Location destination) {
        internalTeleports.add(player.getUniqueId());
        try {
            return player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            internalTeleports.remove(player.getUniqueId());
        }
    }

    private void persistResult(GameSession session, SessionEndReason reason, int score) {
        UUID playerId = session.player().getUniqueId();
        String playerName = session.player().getName();
        String arenaId = session.arena().id();
        UUID runId = session.runId();
        Instant completedAt = Instant.ofEpochMilli(System.currentTimeMillis());
        RunCompletion completion = new RunCompletion(
                runId, completedAt, score, reason.name());
        CompletionReward prepared = prepareCompletionRewardSafely(
                session, reason, score, completedAt);
        CompletionReward reward;
        try {
            reward = acquireRewardBarrier(playerId, prepared);
        } catch (RuntimeException | LinkageError barrierFailure) {
            prepared.request().ifPresent(request -> {
                try {
                    rewardCompletionBarrier.clear(playerId, request.planId());
                } catch (RuntimeException | LinkageError clearFailure) {
                    recordLifecycleFailure(
                            "reward.barrier_clear_failed",
                            playerId,
                            session.arena().id(),
                            clearFailure);
                }
            });
            recordRewardFailureSafely(
                    "reward.barrier_acquire_failed",
                    session,
                    barrierFailure);
            reward = CompletionReward.none(prepared.policy());
        }

        CompletionReward submittedReward = reward;
        try {
            scoreRepository.completeRunWithRewardPlan(completion, submittedReward.request())
                    .whenComplete((result, failure) -> handlePersistedResult(
                            playerId,
                            playerName,
                            arenaId,
                            runId,
                            submittedReward,
                            result,
                            failure));
        } catch (RuntimeException | LinkageError submissionFailure) {
            releaseRewardBarrierSafely(playerId, submittedReward, session.arena().id());
            recordRunPersistenceFailure(session, submissionFailure);
            markRunUnknownAfterSubmissionFailure(session);
        }
    }

    private void handlePersistedResult(
            UUID playerId,
            String playerName,
            String arenaId,
            UUID runId,
            CompletionReward reward,
            CompletedRunWithRewardPlanResult result,
            Throwable failure) {
        try {
            if (failure != null) {
                releaseRewardBarrierSafely(playerId, reward, arenaId);
                recordRunPersistenceFailure(playerId, playerName, arenaId, runId, failure);
                return;
            }
            if (!plugin.isEnabled() || shuttingDown) {
                releaseRewardBarrierSafely(playerId, reward, arenaId);
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    if (plugin.isEnabled() && !shuttingDown) {
                        completeResultSafely(result, reward);
                    } else {
                        releaseRewardBarrierSafely(playerId, reward, arenaId);
                    }
                } catch (RuntimeException | LinkageError callbackFailure) {
                    releaseRewardBarrierSafely(playerId, reward, arenaId);
                    recordRewardFailureSafely(
                            "reward.main_callback_failed",
                            playerId,
                            arenaId,
                            runId,
                            callbackFailure);
                }
            });
        } catch (RuntimeException | LinkageError schedulingFailure) {
            releaseRewardBarrierSafely(playerId, reward, arenaId);
            if (!shuttingDown) {
                try {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Could not schedule score completion for " + playerName,
                            schedulingFailure);
                } catch (RuntimeException | LinkageError ignored) {
                    // Barrier release above is the mandatory action; logging is best effort.
                }
            }
        }
    }

    private void markRunUnknownAfterSubmissionFailure(GameSession session) {
        try {
            markRunInterrupted(
                    session.runId(),
                    RunStatus.UNKNOWN,
                    "COMPLETION_SUBMIT_FAILED");
        } catch (RuntimeException | LinkageError interruptionFailure) {
            recordLifecycleFailure(
                    "run.persistence_fallback_failed",
                    session.player().getUniqueId(),
                    session.arena().id(),
                    interruptionFailure);
        }
    }

    private void recordRunPersistenceFailure(GameSession session, Throwable failure) {
        recordRunPersistenceFailure(
                session.player().getUniqueId(),
                session.player().getName(),
                session.arena().id(),
                session.runId(),
                failure);
    }

    private void recordRunPersistenceFailure(
            UUID playerId,
            String playerName,
            String arenaId,
            UUID runId,
            Throwable failure) {
        try {
            operations.metrics().recordRepositoryFailure(failure);
        } catch (RuntimeException | LinkageError ignored) {
            // Continue to the remaining reporting surfaces.
        }
        try {
            operations.audit(
                    "run.persist_failed",
                    playerId,
                    arenaId,
                    Map.of(
                            "run_id", runId,
                            "failure", failure.getClass().getSimpleName()));
        } catch (RuntimeException | LinkageError ignored) {
            // Continue to the server log.
        }
        try {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not save score for " + playerName,
                    failure);
        } catch (RuntimeException | LinkageError ignored) {
            // No further safe reporting surface exists here.
        }
    }

    private void recordRewardFailureSafely(
            String event,
            GameSession session,
            Throwable failure) {
        recordRewardFailureSafely(
                event,
                session.player().getUniqueId(),
                session.arena().id(),
                session.runId(),
                failure);
    }

    private void recordRewardFailureSafely(
            String event,
            UUID playerId,
            String arenaId,
            UUID runId,
            Throwable failure) {
        try {
            operations.metrics().recordRewardFailure(failure);
        } catch (RuntimeException | LinkageError ignored) {
            // Continue to the remaining reporting surfaces.
        }
        try {
            operations.audit(event, playerId, arenaId, Map.of(
                    "run_id", runId,
                    "failure", failure.getClass().getSimpleName()));
        } catch (RuntimeException | LinkageError ignored) {
            // Continue to the server log.
        }
        try {
            plugin.getLogger().log(Level.SEVERE, "WalkThePlank reward processing failed", failure);
        } catch (RuntimeException | LinkageError ignored) {
            // No further safe reporting surface exists here.
        }
    }

    private void releaseRewardBarrierSafely(
            UUID playerId,
            CompletionReward reward,
            String arenaId) {
        try {
            releaseRewardBarrier(playerId, reward);
        } catch (RuntimeException | LinkageError releaseFailure) {
            try {
                reward.request().ifPresent(request ->
                        rewardCompletionBarrier.clear(playerId, request.planId()));
            } catch (RuntimeException | LinkageError ignored) {
                // The durable plan remains inspectable even if the in-memory barrier is unhealthy.
            }
            recordLifecycleFailure(
                    "reward.barrier_release_failed",
                    playerId,
                    arenaId,
                    releaseFailure);
        }
    }

    private void completeResultSafely(
            CompletedRunWithRewardPlanResult result,
            CompletionReward reward) {
        try {
            completeResult(result, reward);
        } catch (RuntimeException | LinkageError completionFailure) {
            RunRecord run = result.completion().run();
            try {
                operations.metrics().recordRewardFailure(completionFailure);
            } catch (RuntimeException | LinkageError ignored) {
                // Continue to durable plan settlement.
            }
            try {
                operations.audit(
                        "reward.completion_failed",
                        run.playerId(),
                        run.arenaId(),
                        Map.of(
                                "run_id", run.id(),
                                "failure", completionFailure.getClass().getSimpleName()));
            } catch (RuntimeException | LinkageError ignored) {
                // Continue to durable plan settlement.
            }
            try {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Reward completion callback failed; freezing any durable plan",
                        completionFailure);
            } catch (RuntimeException | LinkageError ignored) {
                // Continue to durable plan settlement.
            }
            try {
                if (reward.request().isPresent()) {
                    finalizeRewardPlan(run, reward.request().orElseThrow().planId());
                } else {
                    releaseRewardBarrierSafely(run.playerId(), reward, run.arenaId());
                }
            } catch (RuntimeException | LinkageError settlementFailure) {
                releaseRewardBarrierSafely(run.playerId(), reward, run.arenaId());
                try {
                    completionFailure.addSuppressed(settlementFailure);
                } catch (IllegalArgumentException ignored) {
                    // Self-suppression is diagnostic-only and must not affect barrier release.
                }
            }
        }
    }

    private CompletionReward acquireRewardBarrier(
            UUID playerId,
            CompletionReward reward) {
        if (reward.request().isEmpty()) {
            return reward;
        }
        UUID planId = reward.request().orElseThrow().planId();
        boolean acquired = rewardCompletionBarrier.begin(playerId, planId);
        if (!acquired) {
            operations.metrics().recordFailure(
                    "reward",
                    new IllegalStateException("Conflicting reward completion barrier"));
            operations.audit("reward.barrier_conflict", playerId, null, Map.of(
                    "plan_id", planId));
            plugin.getLogger().severe(
                    "Refusing automatic reward dispatch because another plan still owns the player barrier");
        }
        return reward.withBarrierOwned(acquired);
    }

    private CompletionReward prepareCompletionRewardSafely(
            GameSession session,
            SessionEndReason reason,
            int score,
            Instant completedAt) {
        try {
            return prepareCompletionReward(session, reason, score, completedAt);
        } catch (RuntimeException | LinkageError preparationFailure) {
            recordRewardFailureSafely("reward.intent_failed", session, preparationFailure);
            return CompletionReward.none(RewardPlan.none());
        }
    }

    private CompletionReward prepareCompletionReward(
            GameSession session,
            SessionEndReason reason,
            int score,
            Instant completedAt) {
        RewardPlan policy = reason.rewardsEligible()
                ? rewards.planFor(score)
                : RewardPlan.none();
        if (score <= 0 || policy.commands().isEmpty()) {
            return CompletionReward.none(policy);
        }

        PreparedRewardPlan prepared = rewards.prepare(
                policy,
                session.player().getName(),
                session.player().getUniqueId(),
                score,
                true);
        if (prepared.steps().isEmpty()) {
            return new CompletionReward(
                    policy, Optional.of(prepared), Optional.empty(), false);
        }

        UUID planId = UUID.nameUUIDFromBytes(
                ("walktheplank/reward/" + session.runId()).getBytes(StandardCharsets.UTF_8));
        RewardPlanRequest request = new RewardPlanRequest(
                planId,
                session.runId(),
                "run:" + session.runId(),
                completedAt,
                prepared.ledgerSteps());
        return new CompletionReward(
                policy, Optional.of(prepared), Optional.of(request), false);
    }

    private void completeResult(
            CompletedRunWithRewardPlanResult atomicResult,
            CompletionReward reward) {
        CompletedRunResult result = atomicResult.completion();
        if (!result.created()) {
            operations.audit("run.duplicate_completion", result.run().playerId(), result.run().arenaId(), Map.of(
                    "run_id", result.run().id()));
            releaseRewardBarrier(result.run().playerId(), reward);
            return;
        }

        ScoreUpdateResult allTime = result.allTimeScore().orElse(null);
        boolean personalBest = allTime != null && allTime.newBest();
        if (personalBest) {
            Player player = plugin.getServer().getPlayer(result.run().playerId());
            if (player != null) {
                messages.send(player, "chat.newRecord");
            }
            callPersonalBestEvent(result, allTime);
        }

        if (reward.prepared().isEmpty()) {
            return;
        }
        PreparedRewardPlan prepared = reward.prepared().orElseThrow();
        if (prepared.steps().isEmpty()) {
            operations.audit("reward.skipped", result.run().playerId(), result.run().arenaId(), Map.of(
                    "run_id", result.run().id(),
                    "status", prepared.status()));
            return;
        }
        RewardPlanRequest request = reward.request().orElseThrow();
        RewardPlanBeginResult begin = atomicResult.rewardPlan().orElse(null);
        if (begin == null) {
            rewardPersistenceFailure(
                    "atomic intent",
                    result.run(),
                    new IllegalStateException("Atomic completion omitted its reward plan"));
            releaseRewardBarrier(result.run().playerId(), request.planId());
            return;
        }
        if (!reward.barrierOwned()) {
            operations.audit("reward.barrier_rejected", result.run().playerId(), result.run().arenaId(), Map.of(
                    "plan_id", request.planId()));
            finalizeRewardPlan(result.run(), request.planId());
            return;
        }
        if (reward.policy().onlyOnPersonalBest() && !personalBest) {
            operations.audit("reward.skipped", result.run().playerId(), result.run().arenaId(), Map.of(
                    "plan_id", request.planId(),
                    "status", "SKIPPED_PERSONAL_BEST",
                    "steps", prepared.steps().size()));
            finalizeRewardPlan(result.run(), request.planId());
            return;
        }
        completeRewardPreparation(result, prepared, request.planId(), begin);
    }

    private void callPersonalBestEvent(CompletedRunResult result, ScoreUpdateResult allTime) {
        try {
            plugin.getServer().getPluginManager().callEvent(new WalkPersonalBestEvent(
                    result.run().playerId(),
                    result.run().username(),
                    allTime.previousBestScore(),
                    allTime.bestScore()));
        } catch (RuntimeException | LinkageError eventFailure) {
            operations.metrics().recordFailure("event", eventFailure);
            operations.audit(
                    "event.personal_best_failed",
                    result.run().playerId(),
                    result.run().arenaId(),
                    Map.of(
                            "run_id", result.run().id(),
                            "failure", eventFailure.getClass().getSimpleName()));
            plugin.getLogger().log(
                    Level.SEVERE,
                    "WalkPersonalBestEvent listener failed; continuing durable reward handling",
                    eventFailure);
        }
    }

    private void completeRewardPreparation(
            CompletedRunResult result,
            PreparedRewardPlan prepared,
            UUID planId,
            RewardPlanBeginResult begin) {
        if (!prepared.executable()) {
            operations.audit("reward.abandoned", result.run().playerId(), result.run().arenaId(), Map.of(
                    "plan_id", planId,
                    "status", prepared.status(),
                    "steps", prepared.steps().size()));
            finalizeRewardPlan(result.run(), planId);
            return;
        }
        if (begin.plan().status() != RewardPlanStatus.PENDING
                && begin.plan().status() != RewardPlanStatus.IN_PROGRESS) {
            operations.audit("reward.not_resumed", result.run().playerId(), result.run().arenaId(), Map.of(
                    "plan_id", planId,
                    "status", begin.plan().status()));
            releaseRewardBarrier(result.run().playerId(), planId);
            return;
        }

        WalkRewardPlanEvent event = new WalkRewardPlanEvent(
                planId,
                result.run().playerId(),
                result.run().score().orElseThrow(),
                prepared.steps().size());
        try {
            plugin.getServer().getPluginManager().callEvent(event);
        } catch (RuntimeException | LinkageError eventFailure) {
            operations.metrics().recordRewardFailure(eventFailure);
            operations.audit("reward.event_failed", result.run().playerId(), result.run().arenaId(), Map.of(
                    "plan_id", planId,
                    "steps", prepared.steps().size(),
                    "failure", eventFailure.getClass().getSimpleName()));
            plugin.getLogger().log(
                    Level.SEVERE,
                    "WalkRewardPlanEvent listener failed; freezing the durable plan without dispatch",
                    eventFailure);
            finalizeRewardPlan(result.run(), planId);
            return;
        }
        if (event.isCancelled()) {
            operations.audit("reward.cancelled", result.run().playerId(), result.run().arenaId(), Map.of(
                    "plan_id", planId,
                    "steps", prepared.steps().size()));
            finalizeRewardPlan(result.run(), planId);
            return;
        }
        claimRewardStep(result.run(), prepared, planId, 0);
    }

    private void claimRewardStep(
            RunRecord run,
            PreparedRewardPlan prepared,
            UUID planId,
            int stepIndex) {
        if (stepIndex >= prepared.steps().size()) {
            releaseRewardBarrierSafely(run.playerId(), planId, run.arenaId());
            return;
        }
        try {
            scoreRepository.claimRewardStep(planId, stepIndex, Instant.now())
                    .whenComplete((claim, failure) -> {
                try {
                    if (failure != null) {
                        rewardPersistenceFailure("claim", run, failure);
                        finalizeRewardPlan(run, planId);
                        return;
                    }
                    if (!scheduleMain(
                            () -> runRewardTaskSafely(
                                    run,
                                    planId,
                                    "dispatch callback",
                                    () -> dispatchClaimedRewardStep(
                                            run, prepared, planId, stepIndex, claim)),
                            "reward step claim")) {
                        finalizeRewardPlan(run, planId);
                    }
                } catch (RuntimeException | LinkageError callbackFailure) {
                    rewardPersistenceFailure("claim callback", run, callbackFailure);
                    finalizeRewardPlan(run, planId);
                }
                    });
        } catch (RuntimeException | LinkageError submissionFailure) {
            rewardPersistenceFailure("claim submission", run, submissionFailure);
            finalizeRewardPlan(run, planId);
        }
    }

    private void dispatchClaimedRewardStep(
            RunRecord run,
            PreparedRewardPlan prepared,
            UUID planId,
            int stepIndex,
            RewardStepDispatchResult claim) {
        if (!claim.shouldDispatch()) {
            RewardStepStatus storedStatus = claim.plan().steps().get(stepIndex).status();
            if (storedStatus == RewardStepStatus.SUCCEEDED) {
                claimRewardStep(run, prepared, planId, stepIndex + 1);
            } else if (storedStatus == RewardStepStatus.PENDING) {
                // An exact duplicate preparation is safe to resume through the atomic claim.
                claimRewardStep(run, prepared, planId, stepIndex);
            } else {
                releaseRewardBarrierSafely(run.playerId(), planId, run.arenaId());
            }
            return;
        }

        PreparedRewardStep step = prepared.steps().get(stepIndex);
        RewardStepStatus outcome;
        try {
            outcome = rewards.dispatch(step)
                    ? RewardStepStatus.SUCCEEDED
                    : RewardStepStatus.FAILED;
        } catch (RuntimeException | LinkageError dispatchFailure) {
            outcome = RewardStepStatus.UNKNOWN;
            operations.metrics().recordRewardFailure(dispatchFailure);
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Reward dispatch outcome is unknown for root " + step.commandRoot(),
                    dispatchFailure);
        }
        if (outcome == RewardStepStatus.FAILED) {
            operations.metrics().recordRewardFailure();
        }
        RewardStepStatus recordedOutcome = outcome;
        try {
            scoreRepository.recordRewardStepOutcome(planId, stepIndex, outcome, Instant.now())
                    .whenComplete((recorded, failure) -> {
                try {
                    if (failure != null) {
                        rewardPersistenceFailure("outcome", run, failure);
                        finalizeRewardPlan(run, planId);
                        return;
                    }
                    operations.audit("reward.step", run.playerId(), run.arenaId(), Map.of(
                            "plan_id", planId,
                            "step", stepIndex,
                            "root", step.commandRoot(),
                            "outcome", recordedOutcome));
                    if (recordedOutcome == RewardStepStatus.SUCCEEDED) {
                        operations.metrics().recordRewardStepCompleted();
                        if (!scheduleMain(
                                () -> runRewardTaskSafely(
                                        run,
                                        planId,
                                        "next-step callback",
                                        () -> claimRewardStep(
                                                run, prepared, planId, stepIndex + 1)),
                                "next reward step")) {
                            finalizeRewardPlan(run, planId);
                        }
                    } else {
                        finalizeRewardPlan(run, planId);
                    }
                } catch (RuntimeException | LinkageError callbackFailure) {
                    rewardPersistenceFailure("outcome callback", run, callbackFailure);
                    finalizeRewardPlan(run, planId);
                }
                    });
        } catch (RuntimeException | LinkageError submissionFailure) {
            rewardPersistenceFailure("outcome submission", run, submissionFailure);
            finalizeRewardPlan(run, planId);
        }
    }

    private void runRewardTaskSafely(
            RunRecord run,
            UUID planId,
            String stage,
            Runnable task) {
        try {
            task.run();
        } catch (RuntimeException | LinkageError callbackFailure) {
            rewardPersistenceFailure(stage + " callback", run, callbackFailure);
            finalizeRewardPlan(run, planId);
        }
    }

    private void finalizeRewardPlan(RunRecord run, UUID planId) {
        try {
            scoreRepository.finalizeRewardPlan(planId, Instant.now()).whenComplete((ignored, failure) -> {
                try {
                    if (failure != null) {
                        rewardPersistenceFailure("finalize", run, failure);
                    }
                } finally {
                    releaseRewardBarrierSafely(run.playerId(), planId, run.arenaId());
                }
            });
        } catch (RuntimeException | LinkageError submissionFailure) {
            rewardPersistenceFailure("finalize submission", run, submissionFailure);
            releaseRewardBarrierSafely(run.playerId(), planId, run.arenaId());
        }
    }

    private void releaseRewardBarrier(UUID playerId, CompletionReward reward) {
        reward.request().ifPresent(request ->
                releaseRewardBarrier(playerId, request.planId()));
    }

    private void releaseRewardBarrier(UUID playerId, UUID planId) {
        if (rewardCompletionBarrier.clear(playerId, planId)) {
            scheduleMain(this::refreshQueue, "queue refresh after reward completion");
        }
    }

    private void releaseRewardBarrierSafely(UUID playerId, UUID planId, String arenaId) {
        try {
            releaseRewardBarrier(playerId, planId);
        } catch (RuntimeException | LinkageError releaseFailure) {
            try {
                rewardCompletionBarrier.clear(playerId, planId);
            } catch (RuntimeException | LinkageError ignored) {
                // The durable plan remains inspectable even if the in-memory barrier is unhealthy.
            }
            recordLifecycleFailure(
                    "reward.barrier_release_failed",
                    playerId,
                    arenaId,
                    releaseFailure);
        }
    }

    private void rewardPersistenceFailure(String stage, RunRecord run, Throwable failure) {
        try {
            operations.metrics().recordRepositoryFailure(failure);
        } catch (RuntimeException | LinkageError ignored) {
            // Continue to the remaining reporting surfaces and barrier disposition.
        }
        try {
            operations.metrics().recordRewardFailure(failure);
        } catch (RuntimeException | LinkageError ignored) {
            // Continue to the remaining reporting surfaces and barrier disposition.
        }
        try {
            operations.audit("reward.persistence_failed", run.playerId(), run.arenaId(), Map.of(
                    "run_id", run.id(),
                    "stage", stage,
                    "failure", failure.getClass().getSimpleName()));
        } catch (RuntimeException | LinkageError ignored) {
            // Continue to the server log and barrier disposition.
        }
        try {
            plugin.getLogger().log(Level.SEVERE, "Durable reward " + stage + " failed", failure);
        } catch (RuntimeException | LinkageError ignored) {
            // Barrier disposition is handled by the caller.
        }
    }

    private boolean hasDisallowedMovementEffect(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (isDisallowedMovementEffect(effect.getType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEligibleMovementAttribute(Player player) {
        AttributeInstance movementSpeed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movementSpeed == null) {
            return false;
        }
        List<MovementAttributePolicy.ModifierState> modifiers = movementSpeed.getModifiers().stream()
                .map(modifier -> new MovementAttributePolicy.ModifierState(
                        modifier.getKey().toString(),
                        modifier.getOperation().name(),
                        modifier.getAmount()))
                .toList();
        return MovementAttributePolicy.isEligible(
                movementSpeed.getBaseValue(),
                Attribute.MOVEMENT_SPEED.getDefaultValue(),
                modifiers,
                player.isSprinting());
    }

    private static boolean hasGroundSupport(Player player) {
        return player.wouldCollideUsing(player.getBoundingBox().clone().shift(0.0, -0.0625, 0.0));
    }

    private void releaseArena(GameSession session, boolean blocksRestored) {
        Arena arena = session.arena();
        Optional<PlayerRecoveryRecord> recovery =
                playerRecovery.pending(session.player().getUniqueId());
        PendingPlayerReturn pendingReturn = pendingPlayerReturns.get(arena.id());
        boolean playerRecoverySettled =
                recovery.filter(record -> record.runId().equals(session.runId())).isEmpty()
                        && (pendingReturn == null
                                || !pendingReturn.runId().equals(session.runId()));
        boolean completelyRestored = blocksRestored
                && !restoration.hasPendingSession(session.runId())
                && playerRecoverySettled;
        if (completelyRestored) {
            quarantinedSessions.remove(arena.id());
            blockLeases.releaseRun(session.runId(), session.generation());
            if (!arenaLeases.release(arena.id(), session.arenaLease())) {
                throw new IllegalStateException(
                        "Session no longer owns its exact arena lease: " + arena.id());
            }
        } else {
            quarantinedSessions.put(arena.id(), session);
            plugin.getLogger().severe(
                    "Arena " + arena.id() + " was quarantined because session cleanup did not fully settle");
        }
        refreshJournalProtection();
        rebuildFreeArenas();
    }

    private void settleQuarantinedSession(GameSession session) {
        Objects.requireNonNull(session, "session");
        if (sessions.get(session.player().getUniqueId()) == session
                || quarantinedSessions.get(session.arena().id()) != session
                || restoration.hasPendingSession(session.runId())) {
            return;
        }
        Optional<PlayerRecoveryRecord> recovery =
                playerRecovery.pending(session.player().getUniqueId());
        if (recovery.isPresent() && recovery.orElseThrow().runId().equals(session.runId())) {
            return;
        }
        PendingPlayerReturn pendingReturn = pendingPlayerReturns.get(session.arena().id());
        if (pendingReturn != null && pendingReturn.runId().equals(session.runId())) {
            return;
        }
        blockLeases.releaseRun(session.runId(), session.generation());
        if (!arenaLeases.release(session.arena().id(), session.arenaLease())) {
            recordLifecycleFailure(
                    "arena.lease_release_failed",
                    session.player().getUniqueId(),
                    session.arena().id(),
                    new IllegalStateException("Quarantined session lost its exact arena lease"));
            return;
        }
        if (!quarantinedSessions.remove(session.arena().id(), session)) {
            arenaLeases.reserve(session.arena().id(), session.arenaLease());
            return;
        }
        plugin.getLogger().info("Recovered quarantined arena " + session.arena().id());
        rebuildFreeArenas();
    }

    private void refreshJournalProtection() {
        journalProtectedBlocks.clear();
        for (RestorationRecord record : restoration.pendingRecords()) {
            journalProtectedBlocks.add(new BlockKey(
                    record.worldId(),
                    record.x(),
                    record.y(),
                    record.z()));
        }
    }

    private Set<UUID> recoveryProtectedRunIds() {
        return arenaLeases.ownedRunIds();
    }

    private Set<String> unresolvedArenaIds() {
        Set<String> result = new HashSet<>(quarantinedSessions.keySet());
        for (PendingStart pending : pendingStartAbandonments.values()) {
            result.add(pending.arena().id());
        }
        result.addAll(restoration.pendingArenaIds(recoveryProtectedRunIds()));
        PlayerRecoveryJournal.Health recoveryHealth = playerRecovery.health();
        result.addAll(RecoveryArenaPolicy.unavailableArenaIds(
                settings.get().arenas().stream().map(Arena::id).toList(),
                playerRecovery.pendingRecords().stream()
                        .map(PlayerRecoveryRecord::arenaId)
                        .toList(),
                recoveryHealth.invalidRecords()));
        return Set.copyOf(result);
    }

    private void rebuildFreeArenas() {
        Set<String> unavailable = new HashSet<>(unresolvedArenaIds());
        for (Arena arena : settings.get().arenas()) {
            if (arenaLeases.isReserved(arena.id())) {
                unavailable.add(arena.id());
            }
        }
        for (GameSession session : sessions.values()) {
            unavailable.add(session.arena().id());
        }
        for (PendingStart pending : pendingStarts.values()) {
            if (!pending.arenaReleased()) {
                unavailable.add(pending.arena().id());
            }
        }
        freeArenas.clear();
        for (Arena arena : settings.get().arenas()) {
            if (!unavailable.contains(arena.id())) {
                freeArenas.add(arena);
            }
        }
        publishGameState();
    }

    private void logRestorationRetry(String context, RestorationRetryResult result) {
        if (result.attemptedRecords() == 0) {
            return;
        }
        Level level = result.pendingRecords() == 0 ? Level.INFO : Level.WARNING;
        plugin.getLogger().log(
                level,
                context + " restoration retry: " + result.completedRecords() + " completed, "
                        + result.conflictRecords() + " conflict(s), "
                        + result.missingWorldRecords() + " missing world(s), "
                        + result.failedRecords() + " failure(s), "
                        + result.pendingRecords() + " pending");
    }

    private void publishSessionScores() {
        publishGameState();
    }

    private void publishGameState() {
        long nowNanos = System.nanoTime();
        Map<UUID, SessionStatus> active = new HashMap<>();
        for (Map.Entry<UUID, GameSession> entry : sessions.entrySet()) {
            GameSession session = entry.getValue();
            active.put(entry.getKey(), new SessionStatus(
                    session.arena().id(),
                    session.score(),
                    session.elapsedSeconds(nowNanos),
                    session.idleSeconds(nowNanos),
                    session.runId(),
                    session.startedAt(),
                    session.elapsedDuration(nowNanos)));
        }
        QueueStatus globalQueue = new QueueStatus(
                settings.get().queue().enabled(),
                queue.isPaused(),
                queue.size(),
                queue.waitingCount(),
                queue.readyCount(),
                0,
                Optional.empty());
        Map<UUID, QueueStatus> playerQueues = new HashMap<>();
        for (UUID playerId : queue.orderedPlayers()) {
            playerQueues.put(playerId, new QueueStatus(
                    globalQueue.enabled(),
                    globalQueue.paused(),
                    globalQueue.total(),
                    globalQueue.waiting(),
                    globalQueue.ready(),
                    queue.position(playerId),
                    queue.readyUntil(playerId)));
        }
        publishedGameState = new PublishedGameState(
                Map.copyOf(active),
                globalQueue,
                Map.copyOf(playerQueues),
                freeArenas.size(),
                settings.get().arenas().size(),
                unresolvedArenaIds().size());
    }

    private static RuntimeException appendFailure(
            RuntimeException existing, RuntimeException additional) {
        if (existing == null) {
            return additional;
        }
        if (existing != additional) {
            existing.addSuppressed(additional);
        }
        return existing;
    }

    private static RuntimeException asRuntimeFailure(Throwable failure) {
        return failure instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("Paper API linkage failed during cleanup", failure);
    }

    private static String operatorKind(UUID operatorId) {
        return operatorId == null ? "system" : "player";
    }

    public record SessionStatus(
            String arenaId,
            int score,
            long elapsedSeconds,
            long idleSeconds,
            UUID runId,
            Instant startedAt,
            Duration elapsedDuration) {
    }

    public record QueueStatus(
            boolean enabled,
            boolean paused,
            int total,
            int waiting,
            int ready,
            int playerPosition,
            Optional<Instant> playerReadyUntil) {
        public QueueStatus {
            Objects.requireNonNull(playerReadyUntil, "playerReadyUntil");
        }
    }

    public record TaskHealth(
            int pendingStarts,
            int pendingStartAbandonments,
            int pendingExternalTeleportChecks,
            int pendingPlayerRecoveryLookups,
            int pendingPlayerRecoveryCompletions) {
        public TaskHealth {
            if (pendingStarts < 0
                    || pendingStartAbandonments < 0
                    || pendingExternalTeleportChecks < 0
                    || pendingPlayerRecoveryLookups < 0
                    || pendingPlayerRecoveryCompletions < 0) {
                throw new IllegalArgumentException("Task-health counts must not be negative");
            }
        }
    }

    public record PlaceholderSnapshot(
            QueueStatus queue,
            int activeArenas,
            int availableArenas,
            int totalArenas,
            int quarantinedArenas,
            int playerQueuePosition,
            boolean playerQueueReady,
            Optional<SessionStatus> session) {
        public PlaceholderSnapshot {
            Objects.requireNonNull(queue, "queue");
            Objects.requireNonNull(session, "session");
        }
    }

    private record PublishedGameState(
            Map<UUID, SessionStatus> sessions,
            QueueStatus globalQueue,
            Map<UUID, QueueStatus> playerQueues,
            int availableArenas,
            int totalArenas,
            int quarantinedArenas) {
        private PublishedGameState {
            sessions = Map.copyOf(Objects.requireNonNull(sessions, "sessions"));
            Objects.requireNonNull(globalQueue, "globalQueue");
            playerQueues = Map.copyOf(Objects.requireNonNull(playerQueues, "playerQueues"));
        }

        private static PublishedGameState empty() {
            return new PublishedGameState(
                    Map.of(),
                    new QueueStatus(false, false, 0, 0, 0, 0, Optional.empty()),
                    Map.of(),
                    0,
                    0,
                    0);
        }
    }

    private record CompletionReward(
            RewardPlan policy,
            Optional<PreparedRewardPlan> prepared,
            Optional<RewardPlanRequest> request,
            boolean barrierOwned) {

        private CompletionReward {
            Objects.requireNonNull(policy, "policy");
            prepared = Objects.requireNonNull(prepared, "prepared");
            request = Objects.requireNonNull(request, "request");
            if (request.isPresent()
                    && (prepared.isEmpty() || prepared.orElseThrow().steps().isEmpty())) {
                throw new IllegalArgumentException(
                        "A durable reward request requires prepared redacted steps");
            }
            if (barrierOwned && request.isEmpty()) {
                throw new IllegalArgumentException(
                        "A reward barrier requires a durable reward request");
            }
        }

        private static CompletionReward none(RewardPlan policy) {
            return new CompletionReward(
                    policy, Optional.empty(), Optional.empty(), false);
        }

        private CompletionReward withBarrierOwned(boolean owned) {
            return new CompletionReward(policy, prepared, request, owned);
        }
    }

    private record ReturnAttempt(boolean success, RuntimeException failure) {
    }

    private record ReturnCandidate(String kind, Location location) {
        private ReturnCandidate {
            Objects.requireNonNull(kind, "kind");
            location = Objects.requireNonNull(location, "location").clone();
        }

        @Override
        public Location location() {
            return location.clone();
        }
    }

    private record ReturnCandidateInspection(boolean safe, String reason) {
        private ReturnCandidateInspection {
            Objects.requireNonNull(reason, "reason");
            if (safe != reason.equals("safe")) {
                throw new IllegalArgumentException("Return-candidate safety and reason must agree");
            }
        }

        private static ReturnCandidateInspection accepted() {
            return new ReturnCandidateInspection(true, "safe");
        }

        private static ReturnCandidateInspection rejected(String reason) {
            return new ReturnCandidateInspection(false, reason);
        }
    }

    private record FailedStartCleanup(
            boolean blocksRestored,
            boolean stateRestored,
            boolean playerReturned,
            boolean recoveryQueued) {
    }

    private record PlayerRecoveryCompletion(
            UUID playerId,
            UUID runId,
            String arenaId,
            String recoveredAction) {
        private PlayerRecoveryCompletion {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(arenaId, "arenaId");
        }
    }

    private record PendingPlayerReturn(UUID playerId, UUID runId) {
        private PendingPlayerReturn {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(runId, "runId");
        }
    }

    static final class PendingExternalTeleport {
        private final UUID runId;
        private final UUID attemptId;
        private final ExternalTeleportOutcome outcome;
        private final UUID worldId;
        private final double x;
        private final double y;
        private final double z;

        private PendingExternalTeleport(
                UUID runId,
                UUID attemptId,
                ExternalTeleportOutcome outcome,
                UUID worldId,
                double x,
                double y,
                double z) {
            this.runId = Objects.requireNonNull(runId, "runId");
            this.attemptId = Objects.requireNonNull(attemptId, "attemptId");
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static PendingExternalTeleport awaiting(UUID runId, UUID attemptId) {
            return terminal(runId, attemptId, ExternalTeleportOutcome.AWAITING);
        }

        PendingExternalTeleport observe(
                boolean eventAccepted,
                UUID observedWorldId,
                double observedX,
                double observedY,
                double observedZ) {
            if (!eventAccepted) {
                return terminal(runId, attemptId, ExternalTeleportOutcome.CANCELLED);
            }
            if (observedWorldId == null
                    || !Double.isFinite(observedX)
                    || !Double.isFinite(observedY)
                    || !Double.isFinite(observedZ)) {
                return terminal(runId, attemptId, ExternalTeleportOutcome.INVALID_DESTINATION);
            }
            return new PendingExternalTeleport(
                    runId,
                    attemptId,
                    ExternalTeleportOutcome.ACCEPTED,
                    observedWorldId,
                    observedX,
                    observedY,
                    observedZ);
        }

        UUID runId() {
            return runId;
        }

        boolean matches(UUID candidateAttemptId) {
            return attemptId.equals(candidateAttemptId);
        }

        boolean reached(
                boolean playerOnline,
                UUID actualWorldId,
                double actualX,
                double actualY,
                double actualZ) {
            return playerOnline
                    && outcome == ExternalTeleportOutcome.ACCEPTED
                    && ExternalTeleportCommitPolicy.reached(
                            worldId,
                            x,
                            y,
                            z,
                            actualWorldId,
                            actualX,
                            actualY,
                            actualZ);
        }

        String notCommittedReason(boolean playerOnline) {
            if (!playerOnline) {
                return "player_offline";
            }
            return switch (outcome) {
                case AWAITING -> "monitor_not_observed";
                case CANCELLED -> "event_cancelled";
                case INVALID_DESTINATION -> "invalid_destination";
                case ACCEPTED -> "destination_not_reached";
            };
        }

        private static PendingExternalTeleport terminal(
                UUID runId,
                UUID attemptId,
                ExternalTeleportOutcome outcome) {
            return new PendingExternalTeleport(runId, attemptId, outcome, null, 0.0, 0.0, 0.0);
        }

        private enum ExternalTeleportOutcome {
            AWAITING,
            CANCELLED,
            INVALID_DESTINATION,
            ACCEPTED
        }
    }

    private record ActivationRecords(
            PlayerRecoveryRecord playerRecovery,
            GameSession.StartRecords platforms) {
        private ActivationRecords {
            Objects.requireNonNull(playerRecovery, "playerRecovery");
            Objects.requireNonNull(platforms, "platforms");
        }
    }

    private static final class PendingActivation {
        private final GameSession session;
        private final RecoveryDurabilityService.PlayerRecoveryPreparation playerPreparation;
        private final GameSession.StartPreparation platformPreparation;
        private final CompletableFuture<ActivationRecords> durable;

        private PendingActivation(
                GameSession session,
                RecoveryDurabilityService.PlayerRecoveryPreparation playerPreparation,
                GameSession.StartPreparation platformPreparation) {
            this.session = Objects.requireNonNull(session, "session");
            this.playerPreparation = Objects.requireNonNull(
                    playerPreparation, "playerPreparation");
            this.platformPreparation = Objects.requireNonNull(
                    platformPreparation, "platformPreparation");
            durable = playerPreparation.durableRecord().thenCombine(
                    platformPreparation.durable(),
                    ActivationRecords::new);
        }

        private GameSession session() {
            return session;
        }

        private RecoveryDurabilityService.PlayerRecoveryPreparation playerPreparation() {
            return playerPreparation;
        }

        private GameSession.StartPreparation platformPreparation() {
            return platformPreparation;
        }

        private CompletableFuture<ActivationRecords> durable() {
            return durable;
        }

        private CompletableFuture<Void> abandon() {
            List<CompletableFuture<Void>> blocks = platformPreparation.abandon();
            CompletableFuture<?>[] operations =
                    new CompletableFuture<?>[blocks.size() + 1];
            operations[0] = playerPreparation.discard();
            for (int index = 0; index < blocks.size(); index++) {
                operations[index + 1] = blocks.get(index);
            }
            return CompletableFuture.allOf(operations);
        }
    }

    private static final class PendingStart {
        private final UUID runId;
        private final UUID playerId;
        private final Arena arena;
        private final Instant startedAt;
        private final ArenaLeaseRegistry.ArenaLease arenaLease;
        private String cancellationReason;
        private volatile UnscheduledStart unscheduledOutcome;
        private PendingActivation activation;
        private Supplier<CompletableFuture<Void>> durabilityAbandoner;
        private CompletableFuture<Void> abandonmentInFlight;
        private long nextAbandonmentRetryNanos;
        private boolean arenaReleased;
        private boolean interruptionQueued;

        private PendingStart(
                UUID runId,
                UUID playerId,
                Arena arena,
                Instant startedAt,
                ArenaLeaseRegistry.ArenaLease arenaLease) {
            this.runId = Objects.requireNonNull(runId, "runId");
            this.playerId = Objects.requireNonNull(playerId, "playerId");
            this.arena = Objects.requireNonNull(arena, "arena");
            this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
            this.arenaLease = Objects.requireNonNull(arenaLease, "arenaLease");
        }

        private UUID runId() {
            return runId;
        }

        private UUID playerId() {
            return playerId;
        }

        private Arena arena() {
            return arena;
        }

        private ArenaLeaseRegistry.ArenaLease arenaLease() {
            return arenaLease;
        }

        @SuppressWarnings("unused")
        private Instant startedAt() {
            return startedAt;
        }

        private String cancellationReason() {
            return cancellationReason;
        }

        private boolean installActivation(PendingActivation candidate) {
            if (activation != null) {
                return false;
            }
            activation = Objects.requireNonNull(candidate, "candidate");
            durabilityAbandoner = candidate::abandon;
            return true;
        }

        private void installDurabilityAbandoner(
                Supplier<CompletableFuture<Void>> abandoner) {
            if (activation != null) {
                throw new IllegalStateException(
                        "An activated durability abandonment cannot be replaced");
            }
            durabilityAbandoner = Objects.requireNonNull(abandoner, "abandoner");
        }

        private PendingActivation activation() {
            return activation;
        }

        private void clearActivation(PendingActivation expected) {
            if (activation == expected) {
                activation = null;
            }
        }

        private CompletableFuture<Void> abandonDurability() {
            Supplier<CompletableFuture<Void>> abandoner = durabilityAbandoner;
            return abandoner == null
                    ? null
                    : Objects.requireNonNull(abandoner.get(), "abandonment future");
        }

        private boolean beginAbandonment(CompletableFuture<Void> abandonment) {
            Objects.requireNonNull(abandonment, "abandonment");
            if (abandonmentInFlight != null || arenaReleased) {
                return false;
            }
            abandonmentInFlight = abandonment;
            return true;
        }

        private boolean finishAbandonment(
                CompletableFuture<Void> abandonment,
                boolean succeeded) {
            if (abandonmentInFlight != abandonment) {
                return false;
            }
            abandonmentInFlight = null;
            nextAbandonmentRetryNanos = succeeded
                    ? 0L
                    : System.nanoTime() + Duration.ofSeconds(5L).toNanos();
            return true;
        }

        private boolean mayRetryAbandonment(long nowNanos) {
            return !arenaReleased
                    && abandonmentInFlight == null
                    && durabilityAbandoner != null
                    && nowNanos >= nextAbandonmentRetryNanos;
        }

        private void deferAbandonmentRetry() {
            nextAbandonmentRetryNanos =
                    System.nanoTime() + Duration.ofSeconds(5L).toNanos();
        }

        private void cancel(String reason) {
            if (cancellationReason == null) {
                cancellationReason = Objects.requireNonNull(reason, "reason");
            }
        }

        private void recordUnscheduledOutcome(RunRecord record, Throwable failure) {
            unscheduledOutcome = new UnscheduledStart(record, failure);
        }

        private UnscheduledStart takeUnscheduledOutcome() {
            UnscheduledStart outcome = unscheduledOutcome;
            unscheduledOutcome = null;
            return outcome;
        }

        private boolean queueInterruption() {
            if (interruptionQueued) {
                return false;
            }
            interruptionQueued = true;
            return true;
        }

        private void markArenaReleased() {
            if (arenaReleased) {
                throw new IllegalStateException("Pending arena lease was already released");
            }
            arenaReleased = true;
        }

        private boolean arenaReleased() {
            return arenaReleased;
        }
    }

    private record UnscheduledStart(RunRecord record, Throwable failure) {
    }
}
