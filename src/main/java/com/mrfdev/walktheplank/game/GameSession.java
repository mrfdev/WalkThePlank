package com.mrfdev.walktheplank.game;

import com.mrfdev.walktheplank.config.RuntimeSettings;
import com.mrfdev.walktheplank.database.ParticlePreference;
import com.mrfdev.walktheplank.database.RunCategoryScore;
import com.mrfdev.walktheplank.database.ScoreCategory;
import com.mrfdev.walktheplank.recovery.RecoveryDurabilityService;
import com.mrfdev.walktheplank.recovery.RestorationCoordinator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

final class GameSession {
    private final Player player;
    private final Arena arena;
    private final PlayerSnapshot playerSnapshot;
    private final RuntimeSettings settings;
    private final ArenaBounds bounds;
    private final RestorationCoordinator restoration;
    private final JumpPlanner jumpPlanner;
    private final UUID runId;
    private final Instant startedAt;
    private final ArenaLeaseRegistry.ArenaLease arenaLease;
    private final SplittableRandom random = new SplittableRandom();
    private final BlockLeaseRegistry blockLeases;
    private final ParticlePreference particlePreference;
    private final Deque<PlacedBlock> placedBlocks = new ArrayDeque<>(2);
    private final SuccessorPipeline<PreparedBlock,
            com.mrfdev.walktheplank.recovery.RestorationRecord> successorPipeline =
            new SuccessorPipeline<>();

    private GridPoint currentPoint;
    private PlacedBlock targetBlock;
    private StartPreparation startPreparation;
    private PreparedBlock incompleteStartPreparation;
    private int score;
    private boolean ended;
    private long startedAtNanos;
    private long lastProgressAtNanos;
    private long nextPlatformGeneration = 1L;
    private long lastMilestoneFeedbackAtNanos = Long.MIN_VALUE;
    private int currentCombo;
    private int maximumCombo;
    private boolean flawless = true;

    GameSession(
            Player player,
            Arena arena,
            UUID runId,
            Instant startedAt,
            ArenaLeaseRegistry.ArenaLease arenaLease,
            RuntimeSettings settings,
            ParticlePreference particlePreference,
            BlockLeaseRegistry blockLeases,
            RestorationCoordinator restoration) {
        this.player = Objects.requireNonNull(player, "player");
        this.arena = Objects.requireNonNull(arena, "arena");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.arenaLease = Objects.requireNonNull(arenaLease, "arenaLease");
        if (!arenaLease.runId().equals(runId)) {
            throw new IllegalArgumentException("Arena lease run does not match session run");
        }
        this.settings = Objects.requireNonNull(settings, "settings");
        this.particlePreference =
                Objects.requireNonNull(particlePreference, "particlePreference");
        this.blockLeases = Objects.requireNonNull(blockLeases, "blockLeases");
        this.restoration = Objects.requireNonNull(restoration, "restoration");
        playerSnapshot = PlayerSnapshot.capture(player);
        bounds = ArenaBounds.around(arena, settings.horizontalRadius(), settings.fallDistance());
        jumpPlanner = new JumpPlanner(settings.horizontalRadius());
    }

    StartPreparation prepareStart() {
        ensureActive();
        if (startPreparation != null || !placedBlocks.isEmpty()) {
            throw new IllegalStateException("Session start is already prepared");
        }
        Location baseLocation = arena.baseBlock();
        if (!ArenaBounds.hasClearHeadroom(baseLocation.getBlock())) {
            throw new IllegalStateException("Arena start no longer has two clear air blocks of headroom");
        }
        startedAtNanos = System.nanoTime();
        lastProgressAtNanos = startedAtNanos;
        currentPoint = point(baseLocation);
        PreparedBlock base = prepare(baseLocation, false);
        incompleteStartPreparation = base;
        PreparedBlock target;
        try {
            GridPoint next = findNext(currentPoint, score);
            target = prepare(location(next), settings.onlyReplaceAir());
        } catch (RuntimeException | LinkageError failure) {
            throw failure;
        }
        CompletableFuture<StartRecords> durable = base.durableRecord().thenCombine(
                target.durableRecord(),
                StartRecords::new);
        startPreparation = new StartPreparation(base, target, durable);
        incompleteStartPreparation = null;
        return startPreparation;
    }

    PreparedBlock commitStart(
            StartPreparation prepared,
            StartRecords records) {
        ensureActive();
        if (startPreparation != Objects.requireNonNull(prepared, "prepared")) {
            throw new IllegalStateException("Session start preparation is stale");
        }
        Objects.requireNonNull(records, "records");
        PlacedBlock base = prepared.base().claim(records.base());
        placedBlocks.addLast(base);
        base.place();
        showParticles(base);

        PlacedBlock target = prepared.target().claim(records.target());
        placedBlocks.addLast(target);
        target.place();
        showParticles(target);
        targetBlock = target;
        startPreparation = null;
        playerSnapshot.prepare(player);
        return prepareSuccessor();
    }

    void markSuccessorDurable(
            PreparedBlock prepared,
            com.mrfdev.walktheplank.recovery.RestorationRecord record) {
        ensureActive();
        successorPipeline.complete(
                Objects.requireNonNull(prepared, "prepared"),
                Objects.requireNonNull(record, "record"));
    }

    SuccessorCommit readySuccessor() {
        SuccessorPipeline.Ready<PreparedBlock,
                com.mrfdev.walktheplank.recovery.RestorationRecord> ready =
                successorPipeline.ready();
        return ready == null
                ? null
                : new SuccessorCommit(ready);
    }

    AdvanceResult advance(long nowNanos, SuccessorCommit expectedCommit) {
        ensureActive();
        PlacedBlock expectedTarget = targetBlock;
        if (expectedTarget == null
                || !expectedTarget.isIntact()
                || !blockLeases.owns(expectedTarget.key(), expectedTarget.lease())) {
            throw new IllegalStateException("The current parkour target is no longer intact and protected");
        }

        PlacedBlock previous = placedBlocks.peekFirst();
        if (previous == null) {
            throw new IllegalStateException("Session lost its previous block");
        }
        PlacedBlock landed = placedBlocks.peekFirst();
        if (landed != previous) {
            throw new IllegalStateException("Session platform deque changed unexpectedly");
        }
        landed = placedBlocks.size() < 2
                ? null
                : placedBlocks.stream().skip(1L).findFirst().orElse(null);
        if (landed == null) {
            throw new IllegalStateException("Session lost its current block");
        }
        if (landed != expectedTarget) {
            throw new IllegalStateException("Session target does not match the landed block");
        }

        SuccessorPipeline.Ready<PreparedBlock,
                com.mrfdev.walktheplank.recovery.RestorationRecord> successor =
                successorPipeline.consume(
                        Objects.requireNonNull(expectedCommit, "expectedCommit").ready());
        PlacedBlock nextTarget = successor.preparation().claim(successor.record());
        /*
         * Register the claimed record before either world mutation so the normal failure cleanup
         * owns it even if restoring the departed platform or placing the destination throws.
         */
        placedBlocks.addLast(nextTarget);

        /*
         * Preserve the original game's two-visible-platform invariant. The durable successor has
         * stayed hidden until this confirmed landing. Restore the departed platform first, then
         * place exactly one new destination in the same primary-thread transition.
         */
        RestorationCoordinator.DeferredRestoration cleanup = previous.restoreDeferred();
        if (!cleanup.worldSettled()) {
            throw new IllegalStateException(
                    "Previous platform could not be restored: " + cleanup.worldOutcome());
        }
        placedBlocks.removeFirst();
        nextTarget.place();
        showParticles(nextTarget);

        score++;
        Duration jumpInterval =
                Duration.ofNanos(Math.max(0L, nowNanos - lastProgressAtNanos));
        if (settings.combo().enabled()) {
            if (score == 1) {
                currentCombo = 1;
            } else if (jumpInterval.compareTo(settings.combo().maximumGap()) <= 0) {
                currentCombo++;
            } else {
                currentCombo = 1;
                flawless = false;
            }
            maximumCombo = Math.max(maximumCombo, currentCombo);
        }

        currentPoint = new GridPoint(landed.key().x(), landed.key().y(), landed.key().z());
        targetBlock = nextTarget;
        PreparedBlock next = prepareSuccessor();
        lastProgressAtNanos = nowNanos;
        return new AdvanceResult(
                score,
                currentCombo,
                maximumCombo,
                flawless,
                jumpInterval,
                next,
                new BlockCleanup(
                        previous.key(),
                        previous.lease(),
                        cleanup.completion(),
                        blockLeases));
    }

    FinishResult finishDeferred() {
        ended = true;
        List<CompletableFuture<Void>> abandoned = new ArrayList<>();
        PreparedBlock incompleteStart = incompleteStartPreparation;
        incompleteStartPreparation = null;
        if (incompleteStart != null) {
            abandoned.add(incompleteStart.abandon());
        }
        StartPreparation pendingStart = startPreparation;
        startPreparation = null;
        if (pendingStart != null) {
            abandoned.add(pendingStart.base().abandon());
            abandoned.add(pendingStart.target().abandon());
        }
        PreparedBlock pendingSuccessor = successorPipeline.clear();
        if (pendingSuccessor != null) {
            abandoned.add(pendingSuccessor.abandon());
        }
        if (placedBlocks.isEmpty()) {
            return new FinishResult(
                    score,
                    categoryScores(),
                    List.of(),
                    List.copyOf(abandoned));
        }
        RuntimeException failure = null;
        Deque<PlacedBlock> failedRestores = new ArrayDeque<>();
        List<BlockCleanup> cleanups = new ArrayList<>();
        int pendingBlocks = placedBlocks.size();
        for (int index = 0; index < pendingBlocks; index++) {
            PlacedBlock placed = placedBlocks.removeLast();
            try {
                RestorationCoordinator.DeferredRestoration cleanup =
                        placed.restoreDeferred();
                if (!cleanup.worldSettled()) {
                    throw new IllegalStateException(
                            "Platform restoration remains unresolved: " + cleanup.worldOutcome());
                }
                cleanups.add(new BlockCleanup(
                        placed.key(),
                        placed.lease(),
                        cleanup.completion(),
                        blockLeases));
            } catch (RuntimeException exception) {
                failedRestores.addFirst(placed);
                failure = appendFailure(failure, exception);
            }
        }
        placedBlocks.addAll(failedRestores);
        if (failure != null) {
            throw failure;
        }
        return new FinishResult(
                score,
                categoryScores(),
                List.copyOf(cleanups),
                List.copyOf(abandoned));
    }

    CompletableFuture<Void> abandonIncompleteStartPreparation() {
        PreparedBlock incompleteStart = incompleteStartPreparation;
        if (incompleteStart == null) {
            return CompletableFuture.completedFuture(null);
        }
        return incompleteStart.abandon();
    }

    boolean isTarget(BlockKey key) {
        return !ended
                && targetBlock != null
                && key.equals(targetBlock.key())
                && targetBlock.isIntact()
                && blockLeases.owns(key, targetBlock.lease());
    }

    boolean hasFallen(Location location) {
        return location.getWorld() == arena.start().getWorld()
                && location.getY() < arena.start().getY() - settings.fallDistance();
    }

    boolean contains(Location location) {
        return bounds.contains(location);
    }

    boolean jumpWouldBeTooFast(long nowNanos) {
        return settings.antiCheat().enabled()
                && score > 0
                && Duration.ofNanos(Math.max(0L, nowNanos - lastProgressAtNanos))
                        .compareTo(settings.antiCheat().minimumJumpInterval()) < 0;
    }

    boolean claimMilestoneFeedback(int candidateScore, long nowNanos) {
        if (!settings.milestones().isMilestone(candidateScore)) {
            return false;
        }
        long cooldownNanos = settings.milestones().cooldown().toNanos();
        if (lastMilestoneFeedbackAtNanos != Long.MIN_VALUE
                && nowNanos - lastMilestoneFeedbackAtNanos < cooldownNanos) {
            return false;
        }
        lastMilestoneFeedbackAtNanos = nowNanos;
        return true;
    }

    Player player() {
        return player;
    }

    Arena arena() {
        return arena;
    }

    PlayerSnapshot playerSnapshot() {
        return playerSnapshot;
    }

    int score() {
        return score;
    }

    int currentCombo() {
        return currentCombo;
    }

    int maximumCombo() {
        return maximumCombo;
    }

    boolean flawless() {
        return flawless;
    }

    UUID runId() {
        return runId;
    }

    long generation() {
        return arenaLease.sessionGeneration();
    }

    ArenaLeaseRegistry.ArenaLease arenaLease() {
        return arenaLease;
    }

    Instant startedAt() {
        return Objects.requireNonNull(startedAt, "Session has not started");
    }

    boolean isExpired(long nowNanos) {
        if (startedAtNanos == 0L || ended) {
            return false;
        }
        return elapsedSeconds(nowNanos) >= settings.maximumRunSeconds()
                || idleSeconds(nowNanos) >= settings.idleTimeoutSeconds();
    }

    long elapsedSeconds(long nowNanos) {
        return elapsedDuration(nowNanos).toSeconds();
    }

    Duration elapsedDuration(long nowNanos) {
        return Duration.ofNanos(Math.max(0L, nowNanos - startedAtNanos));
    }

    long idleSeconds(long nowNanos) {
        return Duration.ofNanos(Math.max(0L, nowNanos - lastProgressAtNanos)).toSeconds();
    }

    private GridPoint findNext(GridPoint current, int plannerScore) {
        GridPoint center = point(arena.baseBlock());
        return jumpPlanner.next(center, current, plannerScore, random, candidate -> {
            Location location = location(candidate);
            World world = location.getWorld();
            if (world == null
                    || candidate.y() < world.getMinHeight()
                    || candidate.y() + 2 >= world.getMaxHeight()) {
                return false;
            }
            Block block = location.getBlock();
            if (blockLeases.isReserved(BlockKey.from(block))) {
                return false;
            }
            if (!ArenaBounds.hasClearHeadroom(block)) {
                return false;
            }
            return !settings.onlyReplaceAir() || block.getType().isAir();
        });
    }

    private PreparedBlock prepareSuccessor() {
        if (!successorPipeline.isEmpty()) {
            throw new IllegalStateException("A successor is already prepared");
        }
        PlacedBlock futureCurrent = Objects.requireNonNull(targetBlock, "targetBlock");
        GridPoint from = new GridPoint(
                futureCurrent.key().x(),
                futureCurrent.key().y(),
                futureCurrent.key().z());
        GridPoint next = findNext(from, score + 1);
        PreparedBlock prepared = prepare(location(next), settings.onlyReplaceAir());
        successorPipeline.begin(prepared);
        return prepared;
    }

    private PreparedBlock prepare(Location location, boolean requireAir) {
        Block block = location.getBlock();
        BlockKey key = BlockKey.from(block);
        if (blockLeases.isReserved(key)) {
            throw new IllegalStateException("Parkour block location is already in use: " + key);
        }
        if (requireAir && !block.getType().isAir()) {
            throw new IllegalStateException("Parkour block location is not air: " + key);
        }
        List<Material> materials = settings.parkourBlocks();
        Material material = materials.get(random.nextInt(materials.size()));
        BlockLeaseRegistry.BlockLease lease = new BlockLeaseRegistry.BlockLease(
                runId,
                generation(),
                nextPlatformGeneration++);
        if (!blockLeases.reserve(key, lease)) {
            throw new IllegalStateException("Parkour block location lease was taken: " + key);
        }
        RecoveryDurabilityService.RestorationPreparation preparation;
        try {
            preparation = restoration.prepareAsync(
                    runId,
                    arena.id(),
                    block,
                    material);
        } catch (RuntimeException | LinkageError failure) {
            boolean durableEvidenceExists = restoration.pendingRecords().stream()
                    .anyMatch(record -> record.sessionId().equals(runId)
                            && record.worldId().equals(key.worldId())
                            && record.x() == key.x()
                            && record.y() == key.y()
                            && record.z() == key.z());
            if (!durableEvidenceExists) {
                blockLeases.release(key, lease);
            }
            throw failure;
        }
        return new PreparedBlock(
                block,
                lease,
                blockLeases,
                restoration,
                preparation);
    }

    private void showParticles(PlacedBlock placed) {
        int visibleCount = particlePreference.apply(settings.particleCount());
        if (!settings.particlesEnabled() || visibleCount <= 0) {
            return;
        }
        Location particleLocation = placed.location().add(0.5, 1.1, 0.5);
        player.spawnParticle(
                settings.particle(),
                particleLocation,
                visibleCount,
                0.25,
                0.2,
                0.25,
                0.02);
    }

    private Location location(GridPoint point) {
        World world = arena.start().getWorld();
        return new Location(world, point.x(), point.y(), point.z());
    }

    private static GridPoint point(Location location) {
        return new GridPoint(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    record StartPreparation(
            PreparedBlock base,
            PreparedBlock target,
            CompletableFuture<StartRecords> durable) {
        StartPreparation {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(durable, "durable");
        }

        List<CompletableFuture<Void>> abandon() {
            return List.of(base.abandon(), target.abandon());
        }
    }

    record StartRecords(
            com.mrfdev.walktheplank.recovery.RestorationRecord base,
            com.mrfdev.walktheplank.recovery.RestorationRecord target) {
        StartRecords {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(target, "target");
        }
    }

    record AdvanceResult(
            int score,
            int combo,
            int maximumCombo,
            boolean flawless,
            Duration jumpInterval,
            PreparedBlock successorPreparation,
            BlockCleanup cleanup) {
    }

    record SuccessorCommit(
            SuccessorPipeline.Ready<PreparedBlock,
                    com.mrfdev.walktheplank.recovery.RestorationRecord> ready) {
        SuccessorCommit {
            Objects.requireNonNull(ready, "ready");
        }

        PreparedBlock prepared() {
            return ready.preparation();
        }
    }

    record FinishResult(
            int score,
            List<RunCategoryScore> categoryScores,
            List<BlockCleanup> cleanups,
            List<CompletableFuture<Void>> abandonedPreparations) {
        FinishResult {
            categoryScores = List.copyOf(categoryScores);
            cleanups = List.copyOf(cleanups);
            abandonedPreparations = List.copyOf(abandonedPreparations);
        }
    }

    List<RunCategoryScore> categoryScores() {
        if (!settings.combo().enabled() || score < 1) {
            return List.of();
        }
        List<RunCategoryScore> result = new ArrayList<>(2);
        if (maximumCombo > 0) {
            result.add(new RunCategoryScore(ScoreCategory.COMBO, maximumCombo));
        }
        if (flawless) {
            result.add(new RunCategoryScore(ScoreCategory.FLAWLESS, score));
        }
        return List.copyOf(result);
    }

    record BlockCleanup(
            BlockKey key,
            BlockLeaseRegistry.BlockLease lease,
            CompletableFuture<com.mrfdev.walktheplank.recovery.RestorationOutcome> completion,
            BlockLeaseRegistry leases) {
        BlockCleanup {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(completion, "completion");
            Objects.requireNonNull(leases, "leases");
        }

        BlockLeaseRegistry.CompletionRelease release() {
            return leases.completeRelease(key, lease);
        }
    }

    private void ensureActive() {
        if (ended) {
            throw new IllegalStateException("Session has already ended");
        }
    }

    private static RuntimeException appendFailure(
            RuntimeException existing, RuntimeException additional) {
        if (existing == null) {
            return additional;
        }
        existing.addSuppressed(additional);
        return existing;
    }
}
