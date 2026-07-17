package com.mrfdev.walktheplank.recovery;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

/** Coordinates full block snapshots, exact-state decisions, restoration, and journal completion. */
public final class RestorationCoordinator {
    private final RestorationJournal journal;
    private final StructureManager structures;
    private final Set<UUID> conflictedRecords = ConcurrentHashMap.newKeySet();

    private volatile RestorationRetryResult lastRetryResult;

    public RestorationCoordinator(RestorationJournal journal) {
        this(journal, Bukkit.getStructureManager());
    }

    RestorationCoordinator(RestorationJournal journal, StructureManager structures) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.structures = Objects.requireNonNull(structures, "structures");
        lastRetryResult = RestorationRetryResult.empty(journal.pendingCount(), 0);
    }

    public RestorationRecord prepare(
            UUID sessionId,
            String arenaId,
            Block block,
            Material placedMaterial) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(arenaId, "arenaId");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(placedMaterial, "placedMaterial");
        try {
            CapturedBlock original = capture(block);
            BlockData expectedData = placedMaterial.createBlockData();
            SerializedBlockState expected = state(placedMaterial, expectedData);
            return journal.append(
                    sessionId,
                    arenaId,
                    block.getWorld().getUID(),
                    block.getWorld().getName(),
                    block.getX(),
                    block.getY(),
                    block.getZ(),
                    expected,
                    original.state(),
                    original.structure());
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not durably journal arena block before placement", exception);
        }
    }

    public void place(RestorationRecord record) {
        Objects.requireNonNull(record, "record");
        if (!journal.isPending(record.journalId())) {
            throw new IllegalStateException("Restoration record is no longer pending: " + record.journalId());
        }
        try {
            Block block = resolveBlock(record);
            CapturedBlock current = capture(block);
            if (!record.originalState().equals(current.state())
                    || !record.originalFingerprint().equals(current.fingerprint())) {
                conflictedRecords.add(record.journalId());
                throw new IllegalStateException(
                        "Arena block changed after journaling and before placement: " + describe(record));
            }

            BlockData expected = parse(record.expectedState());
            block.setBlockData(expected, false);
            if (!record.expectedState().equals(state(block))) {
                throw new IllegalStateException("Placed arena block did not retain its expected state: "
                        + describe(record));
            }
            if (block.getState() instanceof TileState) {
                RestorationOutcome rollback = restore(record);
                if (!rollback.completed()) {
                    throw new IllegalStateException("Tile-backed parkour material could not be rolled back safely: "
                            + record.expectedState().material() + " at " + describe(record));
                }
                throw new IllegalStateException("Tile-backed parkour materials are not supported: "
                        + record.expectedState().material());
            }
        } catch (IOException | RuntimeException exception) {
            recordFailure(record, exception.getMessage());
            if (exception instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("Could not place journaled arena block " + describe(record), exception);
        }
    }

    public RestorationOutcome restore(RestorationRecord record) {
        Objects.requireNonNull(record, "record");
        if (!journal.isPending(record.journalId())) {
            conflictedRecords.remove(record.journalId());
            return RestorationOutcome.NO_LONGER_PENDING;
        }
        conflictedRecords.remove(record.journalId());

        World world = Bukkit.getWorld(record.worldId());
        if (world == null) {
            String message = "World " + record.worldId() + " (last known as '"
                    + record.worldName() + "') is not loaded for " + describe(record);
            recordFailure(record, message);
            conflictedRecords.remove(record.journalId());
            return RestorationOutcome.WORLD_MISSING;
        }

        try {
            Block block = world.getBlockAt(record.x(), record.y(), record.z());
            CapturedBlock current = capture(block);
            RestorationDecision decision = RestorationPolicy.decide(
                    record,
                    Optional.of(current.state()),
                    Optional.of(current.fingerprint()));
            return switch (decision) {
                case RESTORE_EXPECTED -> restoreExpected(record, block);
                case ALREADY_RESTORED -> completeAlreadyRestored(record);
                case CONFLICT -> conflict(record, current.state());
                case WORLD_MISSING -> throw new IllegalStateException(
                        "World unexpectedly disappeared during restoration for " + describe(record));
            };
        } catch (IOException | RuntimeException exception) {
            recordFailure(record, message(exception));
            return RestorationOutcome.FAILED;
        }
    }

    public RestorationRetryResult retryPending(Set<UUID> excludedSessionIds) {
        Objects.requireNonNull(excludedSessionIds, "excludedSessionIds");
        Set<String> arenasBefore = journal.pendingArenaIds(excludedSessionIds);
        int attempted = 0;
        int restored = 0;
        int alreadyRestored = 0;
        int conflicts = 0;
        int missingWorlds = 0;
        int failures = 0;

        List<RestorationRecord> records = journal.pendingRecords();
        for (RestorationRecord record : records) {
            if (excludedSessionIds.contains(record.sessionId())) {
                continue;
            }
            attempted++;
            switch (restore(record)) {
                case RESTORED -> restored++;
                case ALREADY_RESTORED -> alreadyRestored++;
                case CONFLICT -> conflicts++;
                case WORLD_MISSING -> missingWorlds++;
                case FAILED -> failures++;
                case NO_LONGER_PENDING -> attempted--;
            }
        }

        Set<String> arenasAfter = journal.pendingArenaIds(excludedSessionIds);
        int recoveredArenas = 0;
        for (String arenaId : arenasBefore) {
            if (!arenasAfter.contains(arenaId)) {
                recoveredArenas++;
            }
        }
        RestorationRetryResult result = new RestorationRetryResult(
                attempted,
                restored,
                alreadyRestored,
                conflicts,
                missingWorlds,
                failures,
                recoveredArenas,
                pendingCount(excludedSessionIds),
                conflictedCount());
        lastRetryResult = result;
        return result;
    }

    public int pendingCount() {
        return journal.pendingCount();
    }

    public int pendingCount(Set<UUID> excludedSessionIds) {
        Objects.requireNonNull(excludedSessionIds, "excludedSessionIds");
        int count = 0;
        for (RestorationRecord record : journal.pendingRecords()) {
            if (!excludedSessionIds.contains(record.sessionId())) {
                count++;
            }
        }
        return count;
    }

    public int conflictedCount() {
        return conflictedRecords.size();
    }

    public boolean hasPendingSession(UUID sessionId) {
        return journal.hasPendingSession(sessionId);
    }

    public Set<String> pendingArenaIds(Set<UUID> excludedSessionIds) {
        return journal.pendingArenaIds(excludedSessionIds);
    }

    public List<RestorationRecord> pendingRecords() {
        return journal.pendingRecords();
    }

    public Optional<RestorationFailure> lastFailure() {
        return journal.lastFailure();
    }

    public RestorationRetryResult lastRetryResult() {
        return lastRetryResult;
    }

    private RestorationOutcome restoreExpected(RestorationRecord record, Block block) throws IOException {
        Structure structure = structures.loadStructure(new ByteArrayInputStream(record.originalStructure()));
        if (structure == null) {
            throw new IOException("Paper returned no structure for journal record " + record.journalId());
        }
        BlockVector size = structure.getSize();
        if (size.getBlockX() != 1 || size.getBlockY() != 1 || size.getBlockZ() != 1) {
            throw new IOException("Journal structure is not exactly one block for " + record.journalId());
        }
        structure.place(
                block.getLocation(),
                false,
                StructureRotation.NONE,
                Mirror.NONE,
                0,
                1.0F,
                new Random(0L));

        CapturedBlock restored = capture(block);
        if (!record.originalState().equals(restored.state())
                || !record.originalFingerprint().equals(restored.fingerprint())) {
            throw new IOException("Restored block failed exact snapshot verification for " + describe(record));
        }
        journal.complete(record.journalId());
        conflictedRecords.remove(record.journalId());
        return RestorationOutcome.RESTORED;
    }

    private RestorationOutcome completeAlreadyRestored(RestorationRecord record) throws IOException {
        journal.complete(record.journalId());
        conflictedRecords.remove(record.journalId());
        return RestorationOutcome.ALREADY_RESTORED;
    }

    private RestorationOutcome conflict(RestorationRecord record, SerializedBlockState current) {
        conflictedRecords.add(record.journalId());
        recordFailure(record, "Restoration conflict at " + describe(record)
                + "; expected plugin state " + record.expectedState()
                + " but found " + current);
        return RestorationOutcome.CONFLICT;
    }

    private Block resolveBlock(RestorationRecord record) {
        World world = Bukkit.getWorld(record.worldId());
        if (world == null) {
            throw new IllegalStateException("World is not loaded for " + describe(record));
        }
        return world.getBlockAt(record.x(), record.y(), record.z());
    }

    private CapturedBlock capture(Block block) throws IOException {
        Structure structure = structures.createStructure();
        structure.fill(block.getLocation(), new BlockVector(1, 1, 1), false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        structures.saveStructure(output, structure);
        byte[] snapshot = output.toByteArray();
        if (snapshot.length == 0) {
            throw new IOException("Paper produced an empty block snapshot");
        }
        return new CapturedBlock(state(block), snapshot, RestorationRecord.fingerprint(snapshot));
    }

    private static SerializedBlockState state(Block block) {
        return state(block.getType(), block.getBlockData());
    }

    private static SerializedBlockState state(Material material, BlockData blockData) {
        return new SerializedBlockState(material.name(), blockData.getAsString());
    }

    private static BlockData parse(SerializedBlockState state) {
        BlockData parsed = Bukkit.createBlockData(state.blockData());
        if (!parsed.getMaterial().name().equals(state.material())) {
            throw new IllegalStateException("Serialized block material does not match its block data");
        }
        return parsed;
    }

    private void recordFailure(RestorationRecord record, String message) {
        journal.recordFailure(record, message == null || message.isBlank()
                ? "Unknown restoration failure at " + describe(record)
                : message);
    }

    private static String message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static String describe(RestorationRecord record) {
        return "arena '" + record.arenaId() + "' block "
                + record.worldId() + ":" + record.x() + ":" + record.y() + ":" + record.z()
                + " (journal " + record.journalId() + ", release " + record.releaseIdentity() + ")";
    }

    private record CapturedBlock(
            SerializedBlockState state,
            byte[] structure,
            String fingerprint) {
        private CapturedBlock {
            Objects.requireNonNull(state, "state");
            structure = Objects.requireNonNull(structure, "structure").clone();
            Objects.requireNonNull(fingerprint, "fingerprint");
        }

        @Override
        public byte[] structure() {
            return structure.clone();
        }
    }
}
