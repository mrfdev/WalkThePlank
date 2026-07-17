package com.mrfdev.walktheplank.scenario.tools;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Java 25 Class-File API transformer for the disposable destructive-test artifact.
 *
 * <p>The input production JAR is never modified. Every call-site specification has a strict
 * expected-match count so source drift fails the build instead of silently weakening coverage.
 */
public final class ScenarioJarInstrumenter {
    private static final String BRIDGE_INTERNAL_NAME =
            "com/mrfdev/walktheplank/scenario/instrumentation/ScenarioFailpoints";
    private static final ClassDesc BRIDGE_CLASS =
            ClassDesc.of("com.mrfdev.walktheplank.scenario.instrumentation.ScenarioFailpoints");
    private static final MethodTypeDesc HIT_METHOD =
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String);
    private static final MethodTypeDesc ACCEPTED_METHOD =
            MethodTypeDesc.of(
                    ConstantDescs.CD_boolean,
                    ConstantDescs.CD_boolean,
                    ConstantDescs.CD_String);
    private static final MethodTypeDesc DATABASE_COMMIT_METHOD =
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String);
    private static final MethodTypeDesc EXPORT_RENAMED_METHOD =
            MethodTypeDesc.of(
                    ConstantDescs.CD_void,
                    ClassDesc.of("java.nio.file.Path"));
    private static final String TEST_CANARY = "WTP_SCENARIO_TEST_ONLY_7E4C0D98";
    private static final String TEST_ARTIFACT_ATTRIBUTE = "WalkThePlank-Test-Artifact";
    private static final String TEST_CANARY_ATTRIBUTE = "WalkThePlank-Test-Canary";
    private static final String TEST_MARKER_ENTRY = "META-INF/WTP-SCENARIO-TEST-ONLY";
    private static final String DATABASE_REPOSITORY_ENTRY =
            "com/mrfdev/walktheplank/database/JdbcScoreRepository.class";
    private static final Set<String> DATABASE_OPERATION_LABELS = Set.of(
            "run completion with reward intent",
            "reward step dispatch marker",
            "reward step completion");

    private static final Map<String, String> TARGET_METHOD_DESCRIPTORS = Map.ofEntries(
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/recovery/PlayerRecoveryJournal",
                            "writeRecord"),
                    "(Ljava/nio/file/Path;"
                            + "Lcom/mrfdev/walktheplank/recovery/PlayerRecoveryRecord;)V"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/recovery/PlayerRecoveryJournal",
                            "append"),
                    "(Lcom/mrfdev/walktheplank/recovery/PlayerRecoveryRecord;)"
                            + "Lcom/mrfdev/walktheplank/recovery/PlayerRecoveryRecord;"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/recovery/PlayerRecoveryJournal",
                            "complete"),
                    "(Ljava/util/UUID;Ljava/util/UUID;Ljava/lang/String;)V"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/recovery/RestorationJournal",
                            "writeRecord"),
                    "(Ljava/nio/file/Path;"
                            + "Lcom/mrfdev/walktheplank/recovery/RestorationRecord;)V"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/recovery/RestorationJournal",
                            "append"),
                    "(Ljava/util/UUID;Ljava/lang/String;Ljava/util/UUID;Ljava/lang/String;III"
                            + "Lcom/mrfdev/walktheplank/recovery/SerializedBlockState;"
                            + "Lcom/mrfdev/walktheplank/recovery/SerializedBlockState;[B)"
                            + "Lcom/mrfdev/walktheplank/recovery/RestorationRecord;"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/recovery/RestorationJournal",
                            "complete"),
                    "(Ljava/util/UUID;)V"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/recovery/RestorationCoordinator",
                            "place"),
                    "(Lcom/mrfdev/walktheplank/recovery/RestorationRecord;)V"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/recovery/RestorationCoordinator",
                            "restoreExpected"),
                    "(Lcom/mrfdev/walktheplank/recovery/RestorationRecord;"
                            + "Lorg/bukkit/block/Block;)"
                            + "Lcom/mrfdev/walktheplank/recovery/RestorationOutcome;"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/game/GameManager",
                            "completePendingStart"),
                    "(Lcom/mrfdev/walktheplank/game/GameManager$PendingStart;"
                            + "Lcom/mrfdev/walktheplank/database/RunRecord;"
                            + "Ljava/lang/Throwable;)V"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/game/GameManager",
                            "returnPlayerSafely"),
                    "(Lorg/bukkit/entity/Player;Ljava/util/UUID;Ljava/lang/String;"
                            + "Lorg/bukkit/Location;Lorg/bukkit/Location;)"
                            + "Lcom/mrfdev/walktheplank/game/GameManager$ReturnAttempt;"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/game/GameManager",
                            "dispatchClaimedRewardStep"),
                    "(Lcom/mrfdev/walktheplank/database/RunRecord;"
                            + "Lcom/mrfdev/walktheplank/reward/RewardService$PreparedRewardPlan;"
                            + "Ljava/util/UUID;I"
                            + "Lcom/mrfdev/walktheplank/database/RewardStepDispatchResult;)V"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/database/JdbcScoreRepository",
                            "mutateWithRetry"),
                    "(Ljava/lang/String;Ljava/lang/Object;"
                            + "Lcom/mrfdev/walktheplank/database/"
                            + "JdbcScoreRepository$DatabaseMutation;)Ljava/lang/Object;"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/export/LeaderboardExportService",
                            "writeAtomically"),
                    "(Ljava/nio/file/Path;Ljava/lang/String;)V"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/config/AtomicConfigFile",
                            "replaceWithBackup"),
                    "([BLjava/lang/String;)"
                            + "Lcom/mrfdev/walktheplank/config/AtomicConfigFile$CommitToken;"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/config/ConfigurationManager",
                            "commit"),
                    "(Lcom/mrfdev/walktheplank/config/"
                            + "ConfigurationManager$ConfigurationSnapshot;)V"));

    private static final Map<String, String> INVOKED_METHOD_DESCRIPTORS = Map.ofEntries(
            Map.entry(memberKey("java/io/OutputStream", "flush"), "()V"),
            Map.entry(memberKey("java/nio/channels/FileChannel", "force"), "(Z)V"),
            Map.entry(
                    memberKey("java/nio/file/Files", "move"),
                    "(Ljava/nio/file/Path;Ljava/nio/file/Path;"
                            + "[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/recovery/PlayerRecoveryJournal",
                            "forceDirectory"),
                    "(Ljava/nio/file/Path;)V"),
            Map.entry(
                    memberKey("java/nio/file/Files", "deleteIfExists"),
                    "(Ljava/nio/file/Path;)Z"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/recovery/RestorationJournal",
                            "forceDirectory"),
                    "()V"),
            Map.entry(
                    memberKey("org/bukkit/block/Block", "setBlockData"),
                    "(Lorg/bukkit/block/data/BlockData;Z)V"),
            Map.entry(
                    memberKey("org/bukkit/structure/Structure", "place"),
                    "(Lorg/bukkit/Location;Z"
                            + "Lorg/bukkit/block/structure/StructureRotation;"
                            + "Lorg/bukkit/block/structure/Mirror;"
                            + "IFLjava/util/Random;)V"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/game/GameManager",
                            "teleportInternally"),
                    "(Lorg/bukkit/entity/Player;Lorg/bukkit/Location;)Z"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/database/JdbcScoreRepository",
                            "mutate"),
                    "(Lcom/mrfdev/walktheplank/database/"
                            + "JdbcScoreRepository$DatabaseMutation;)Ljava/lang/Object;"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/reward/RewardService",
                            "dispatch"),
                    "(Lcom/mrfdev/walktheplank/reward/"
                            + "RewardService$PreparedRewardStep;)Z"),
            Map.entry(
                    memberKey(
                            "com/mrfdev/walktheplank/config/AtomicConfigFile",
                            "move"),
                    "(Ljava/nio/file/Path;Ljava/nio/file/Path;)V"));

    private static final Injection DATABASE_COMMIT = builder ->
            builder.aload(1).invokestatic(
                    BRIDGE_CLASS,
                    "databaseCommit",
                    DATABASE_COMMIT_METHOD);
    private static final Injection EXPORT_RENAMED = builder ->
            builder.aload(0).invokestatic(
                    BRIDGE_CLASS,
                    "exportRenamed",
                    EXPORT_RENAMED_METHOD);

    private static final List<Target> TARGETS = List.of(
            afterCall(
                    "com/mrfdev/walktheplank/recovery/PlayerRecoveryJournal",
                    "writeRecord",
                    "java/io/OutputStream",
                    "flush",
                    1,
                    1,
                    Injection.hit("player_journal.after_temp_write")),
            afterCall(
                    "com/mrfdev/walktheplank/recovery/PlayerRecoveryJournal",
                    "writeRecord",
                    "java/nio/channels/FileChannel",
                    "force",
                    1,
                    1,
                    Injection.hit("player_journal.after_temp_fsync")),
            afterCall(
                    "com/mrfdev/walktheplank/recovery/PlayerRecoveryJournal",
                    "append",
                    "java/nio/file/Files",
                    "move",
                    1,
                    1,
                    Injection.hit("player_journal.after_rename")),
            afterCall(
                    "com/mrfdev/walktheplank/recovery/PlayerRecoveryJournal",
                    "append",
                    "com/mrfdev/walktheplank/recovery/PlayerRecoveryJournal",
                    "forceDirectory",
                    1,
                    1,
                    Injection.hit("player_journal.after_directory_fsync")),
            afterCall(
                    "com/mrfdev/walktheplank/recovery/PlayerRecoveryJournal",
                    "complete",
                    "java/nio/file/Files",
                    "deleteIfExists",
                    1,
                    1,
                    Injection.hit("player_journal.after_delete")),
            afterCall(
                    "com/mrfdev/walktheplank/recovery/RestorationJournal",
                    "writeRecord",
                    "java/io/OutputStream",
                    "flush",
                    1,
                    1,
                    Injection.hit("restoration_journal.after_temp_write")),
            afterCall(
                    "com/mrfdev/walktheplank/recovery/RestorationJournal",
                    "writeRecord",
                    "java/nio/channels/FileChannel",
                    "force",
                    1,
                    1,
                    Injection.hit("restoration_journal.after_temp_fsync")),
            afterCall(
                    "com/mrfdev/walktheplank/recovery/RestorationJournal",
                    "append",
                    "java/nio/file/Files",
                    "move",
                    1,
                    1,
                    Injection.hit("restoration_journal.after_rename")),
            afterCall(
                    "com/mrfdev/walktheplank/recovery/RestorationJournal",
                    "append",
                    "com/mrfdev/walktheplank/recovery/RestorationJournal",
                    "forceDirectory",
                    1,
                    1,
                    Injection.hit("restoration_journal.after_directory_fsync")),
            afterCall(
                    "com/mrfdev/walktheplank/recovery/RestorationJournal",
                    "complete",
                    "java/nio/file/Files",
                    "deleteIfExists",
                    1,
                    1,
                    Injection.hit("restoration_journal.after_delete")),
            afterCall(
                    "com/mrfdev/walktheplank/recovery/RestorationCoordinator",
                    "place",
                    "org/bukkit/block/Block",
                    "setBlockData",
                    1,
                    1,
                    Injection.hit("block.after_place")),
            afterCall(
                    "com/mrfdev/walktheplank/recovery/RestorationCoordinator",
                    "restoreExpected",
                    "org/bukkit/structure/Structure",
                    "place",
                    1,
                    1,
                    Injection.hit("block.after_restore")),
            afterCall(
                    "com/mrfdev/walktheplank/game/GameManager",
                    "completePendingStart",
                    "com/mrfdev/walktheplank/game/GameManager",
                    "teleportInternally",
                    1,
                    1,
                    Injection.accepted("teleport.after_start")),
            afterCall(
                    "com/mrfdev/walktheplank/game/GameManager",
                    "returnPlayerSafely",
                    "com/mrfdev/walktheplank/game/GameManager",
                    "teleportInternally",
                    1,
                    1,
                    Injection.accepted("teleport.after_return")),
            afterCall(
                    "com/mrfdev/walktheplank/database/JdbcScoreRepository",
                    "mutateWithRetry",
                    "com/mrfdev/walktheplank/database/JdbcScoreRepository",
                    "mutate",
                    1,
                    1,
                    DATABASE_COMMIT),
            afterCall(
                    "com/mrfdev/walktheplank/game/GameManager",
                    "dispatchClaimedRewardStep",
                    "com/mrfdev/walktheplank/reward/RewardService",
                    "dispatch",
                    1,
                    1,
                    Injection.hit("reward.after_dispatch")),
            afterCall(
                    "com/mrfdev/walktheplank/export/LeaderboardExportService",
                    "writeAtomically",
                    "java/nio/file/Files",
                    "move",
                    1,
                    1,
                    EXPORT_RENAMED),
            afterCall(
                    "com/mrfdev/walktheplank/config/AtomicConfigFile",
                    "replaceWithBackup",
                    "com/mrfdev/walktheplank/config/AtomicConfigFile",
                    "move",
                    2,
                    1,
                    Injection.hit("config.backup.after_rename")),
            afterCall(
                    "com/mrfdev/walktheplank/config/AtomicConfigFile",
                    "replaceWithBackup",
                    "com/mrfdev/walktheplank/config/AtomicConfigFile",
                    "move",
                    2,
                    2,
                    Injection.hit("config.candidate.after_rename")),
            beforeReturn(
                    "com/mrfdev/walktheplank/config/AtomicConfigFile",
                    "replaceWithBackup",
                    1,
                    Injection.hit("config.after_disk_commit")),
            beforeReturn(
                    "com/mrfdev/walktheplank/config/ConfigurationManager",
                    "commit",
                    1,
                    Injection.hit("config.after_runtime_commit")));

    private ScenarioJarInstrumenter() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: ScenarioJarInstrumenter <production.jar> <bridge-classes> <output.jar>");
        }
        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Path bridgeClasses = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(input)
                || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Production JAR must be a real regular file");
        }
        if (input.getFileName().toString().startsWith("TEST-ONLY-")) {
            throw new IOException("A test-only artifact cannot be used as the production input");
        }
        if (Files.isSymbolicLink(bridgeClasses)
                || !Files.isDirectory(bridgeClasses, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Scenario bridge classes must be a real directory");
        }
        String outputName = output.getFileName().toString();
        if (!outputName.startsWith("TEST-ONLY-") || !outputName.endsWith(".jar")) {
            throw new IllegalArgumentException(
                    "The instrumented output must use a TEST-ONLY-*.jar filename");
        }
        if (input.equals(output)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSameFile(input, output)) {
            throw new IllegalArgumentException("The instrumented output must not replace production");
        }
        if (Files.isSymbolicLink(output)) {
            throw new IOException("The instrumented output must not be a symbolic link");
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("The existing instrumented output is not a regular file");
            }
            verifyInstrumentedArtifact(output);
        }
        Path parent = Objects.requireNonNull(output.getParent(), "output parent");
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The instrumented output parent must be a real directory");
        }
        Path temporary = Files.createTempFile(parent, ".wtp-instrumented-", ".jar.tmp");
        boolean moved = false;
        try {
            instrument(input, bridgeClasses, temporary);
            verifyInstrumentedArtifact(temporary);
            Files.move(
                    temporary,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void instrument(Path input, Path bridgeClasses, Path output) throws IOException {
        Map<String, List<Target>> byClass = new HashMap<>();
        for (Target target : TARGETS) {
            byClass.computeIfAbsent(target.className(), ignored -> new ArrayList<>())
                    .add(target);
        }
        Set<Target> transformedTargets = new HashSet<>();
        boolean verifiedDatabaseRouting = false;

        try (JarFile source = new JarFile(input.toFile(), true)) {
            Manifest sourceManifest = source.getManifest();
            if (sourceManifest == null) {
                throw new IOException("Production JAR manifest is missing");
            }
            requireProductionArtifact(source, sourceManifest);
            Manifest manifest = new Manifest(sourceManifest);
            Attributes attributes = manifest.getMainAttributes();
            attributes.putValue(TEST_ARTIFACT_ATTRIBUTE, "instrumented-failpoints");
            attributes.putValue(TEST_CANARY_ATTRIBUTE, TEST_CANARY);

            try (JarOutputStream target = new JarOutputStream(
                    Files.newOutputStream(output), manifest)) {
                List<JarEntry> entries = entries(source);
                Set<String> written = new HashSet<>();
                written.add(JarFile.MANIFEST_NAME);
                for (JarEntry entry : entries) {
                    String name = entry.getName();
                    if (JarFile.MANIFEST_NAME.equalsIgnoreCase(name)) {
                        continue;
                    }
                    if (!written.add(name)) {
                        throw new IOException("Production JAR contains duplicate entry " + name);
                    }
                    byte[] bytes;
                    try (InputStream stream = source.getInputStream(entry)) {
                        bytes = stream.readAllBytes();
                    }
                    if (DATABASE_REPOSITORY_ENTRY.equals(name)) {
                        requireAsciiTokens(
                                bytes,
                                DATABASE_OPERATION_LABELS,
                                "production database operation routing");
                        verifiedDatabaseRouting = true;
                    }
                    List<Target> classTargets = byClass.get(className(name));
                    if (classTargets != null) {
                        bytes = transformClass(bytes, classTargets);
                        transformedTargets.addAll(classTargets);
                    }
                    putEntry(target, name, bytes, entry.isDirectory());
                }

                addBridgeClasses(target, bridgeClasses, written);
                if (!written.add(TEST_MARKER_ENTRY)) {
                    throw new IOException(
                            "Test marker collides with production entry " + TEST_MARKER_ENTRY);
                }
                putEntry(
                        target,
                        TEST_MARKER_ENTRY,
                        (TEST_CANARY + "\n").getBytes(StandardCharsets.UTF_8),
                        false);
            }
        }

        if (!transformedTargets.equals(Set.copyOf(TARGETS))) {
            Set<Target> missing = new HashSet<>(TARGETS);
            missing.removeAll(transformedTargets);
            throw new IOException("Instrumentation target classes were missing: " + missing);
        }
        if (!verifiedDatabaseRouting) {
            throw new IOException("Production database routing class was not verified");
        }
    }

    private static void requireProductionArtifact(JarFile source, Manifest manifest)
            throws IOException {
        Attributes attributes = manifest.getMainAttributes();
        if (attributes.getValue(TEST_ARTIFACT_ATTRIBUTE) != null
                || attributes.getValue(TEST_CANARY_ATTRIBUTE) != null) {
            throw new IOException("Production input is already marked as a test artifact");
        }
        for (JarEntry entry : entries(source)) {
            String name = entry.getName();
            if (TEST_MARKER_ENTRY.equals(name) || isBridgeClassEntry(name)) {
                throw new IOException("Production input contains test-only entry " + name);
            }
        }
    }

    private static void verifyInstrumentedArtifact(Path artifact) throws IOException {
        try (JarFile jar = new JarFile(artifact.toFile(), true)) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                throw new IOException("Instrumented artifact manifest is missing");
            }
            Attributes attributes = manifest.getMainAttributes();
            if (!"instrumented-failpoints".equals(
                            attributes.getValue(TEST_ARTIFACT_ATTRIBUTE))
                    || !TEST_CANARY.equals(attributes.getValue(TEST_CANARY_ATTRIBUTE))) {
                throw new IOException("Instrumented artifact manifest markers are missing");
            }
            JarEntry bridge = jar.getJarEntry(BRIDGE_INTERNAL_NAME + ".class");
            JarEntry marker = jar.getJarEntry(TEST_MARKER_ENTRY);
            if (bridge == null || marker == null) {
                throw new IOException("Instrumented artifact test-only entries are missing");
            }
            try (InputStream input = jar.getInputStream(marker)) {
                String markerContents = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                if (!(TEST_CANARY + "\n").equals(markerContents)) {
                    throw new IOException("Instrumented artifact canary is invalid");
                }
            }
        }
    }

    private static byte[] transformClass(byte[] original, List<Target> targets)
            throws IOException {
        ClassFile classFile = ClassFile.of();
        ClassModel model = classFile.parse(original);
        Map<Target, Counter> counters = new HashMap<>();
        for (Target target : targets) {
            counters.put(target, new Counter());
        }

        ClassTransform transform = (classBuilder, classElement) -> {
            if (!(classElement instanceof MethodModel method)) {
                classBuilder.with(classElement);
                return;
            }
            List<Target> methodTargets = targets.stream()
                    .filter(target ->
                            target.methodName().equals(method.methodName().stringValue())
                                    && target.methodDescriptor().equals(
                                            method.methodTypeSymbol().descriptorString()))
                    .toList();
            if (methodTargets.isEmpty()) {
                classBuilder.with(method);
                return;
            }
            classBuilder.transformMethod(method, (methodBuilder, methodElement) -> {
                if (!(methodElement instanceof CodeModel code)) {
                    methodBuilder.with(methodElement);
                    return;
                }
                methodBuilder.transformCode(
                        code,
                        (codeBuilder, codeElement) -> transformCodeElement(
                                codeBuilder,
                                codeElement,
                                methodTargets,
                                counters));
            });
        };

        byte[] transformed = classFile.transformClass(model, transform);
        for (Map.Entry<Target, Counter> entry : counters.entrySet()) {
            Target target = entry.getKey();
            Counter counter = entry.getValue();
            if (counter.matches != target.expectedMatches() || counter.injections != 1) {
                throw new IOException(
                        "Instrumentation drift for " + target.description()
                                + ": matches=" + counter.matches
                                + " expected=" + target.expectedMatches()
                                + " injections=" + counter.injections);
            }
        }
        List<VerifyError> verificationErrors = classFile.verify(transformed);
        if (!verificationErrors.isEmpty()) {
            throw new IOException(
                    "Instrumented class failed verification: " + verificationErrors);
        }
        return transformed;
    }

    private static void transformCodeElement(
            java.lang.classfile.CodeBuilder builder,
            CodeElement element,
            List<Target> targets,
            Map<Target, Counter> counters) {
        for (Target target : targets) {
            if (target.kind() == TargetKind.BEFORE_RETURN
                    && element instanceof ReturnInstruction) {
                Counter counter = counters.get(target);
                counter.matches++;
                if (counter.matches == target.ordinal()) {
                    target.injection().emit(builder);
                    counter.injections++;
                }
            }
        }

        builder.with(element);

        if (!(element instanceof InvokeInstruction invocation)) {
            return;
        }
        for (Target target : targets) {
            if (target.kind() != TargetKind.AFTER_CALL || !target.matches(invocation)) {
                continue;
            }
            Counter counter = counters.get(target);
            counter.matches++;
            if (counter.matches == target.ordinal()) {
                target.injection().emit(builder);
                counter.injections++;
            }
        }
    }

    private static void addBridgeClasses(
            JarOutputStream output,
            Path bridgeClasses,
            Set<String> written) throws IOException {
        List<Path> files;
        try (var paths = Files.walk(bridgeClasses)) {
            files = paths.filter(path -> !path.equals(bridgeClasses))
                    .sorted()
                    .toList();
        }
        if (files.isEmpty()) {
            throw new IOException("No scenario bridge classes were compiled");
        }
        boolean foundBridge = false;
        for (Path file : files) {
            if (Files.isSymbolicLink(file)) {
                throw new IOException("Scenario bridge tree contains a symbolic link");
            }
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Scenario bridge tree contains a non-regular entry");
            }
            String name = bridgeClasses.relativize(file).toString().replace('\\', '/');
            if (!name.endsWith(".class")) {
                throw new IOException("Unexpected non-class bridge entry " + name);
            }
            if (!isBridgeClassEntry(name)) {
                throw new IOException("Unexpected bridge class " + name);
            }
            if (!written.add(name)) {
                throw new IOException("Bridge class collides with production entry " + name);
            }
            byte[] bytes = Files.readAllBytes(file);
            if (name.equals(BRIDGE_INTERNAL_NAME + ".class")) {
                requireAsciiTokens(
                        bytes,
                        DATABASE_OPERATION_LABELS,
                        "scenario bridge database routing");
                foundBridge = true;
            }
            putEntry(output, name, bytes, false);
        }
        if (!foundBridge) {
            throw new IOException("Primary ScenarioFailpoints bridge class is missing");
        }
    }

    private static List<JarEntry> entries(JarFile jar) {
        List<JarEntry> entries = new ArrayList<>();
        Enumeration<JarEntry> enumeration = jar.entries();
        while (enumeration.hasMoreElements()) {
            entries.add(enumeration.nextElement());
        }
        entries.sort(Comparator.comparing(JarEntry::getName));
        return entries;
    }

    private static void putEntry(
            JarOutputStream output,
            String name,
            byte[] bytes,
            boolean directory) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        if (!directory) {
            output.write(bytes);
        }
        output.closeEntry();
    }

    private static void requireAsciiTokens(
            byte[] classBytes,
            Set<String> required,
            String context) throws IOException {
        String raw = new String(classBytes, StandardCharsets.ISO_8859_1);
        List<String> missing = required.stream()
                .filter(token -> !raw.contains(token))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IOException(context + " is missing exact labels: " + missing);
        }
    }

    private static String className(String entryName) {
        return entryName.endsWith(".class")
                ? entryName.substring(0, entryName.length() - ".class".length())
                : entryName;
    }

    private static boolean isBridgeClassEntry(String entryName) {
        if (entryName.equals(BRIDGE_INTERNAL_NAME + ".class")) {
            return true;
        }
        String nestedPrefix = BRIDGE_INTERNAL_NAME + '$';
        return entryName.startsWith(nestedPrefix)
                && entryName.endsWith(".class")
                && entryName.indexOf('/', nestedPrefix.length()) < 0;
    }

    private static String memberKey(String owner, String name) {
        return owner + '#' + name;
    }

    private static String requiredDescriptor(
            Map<String, String> descriptors,
            String owner,
            String name) {
        String key = memberKey(owner, name);
        String descriptor = descriptors.get(key);
        if (descriptor == null) {
            throw new IllegalStateException("No exact descriptor is registered for " + key);
        }
        return descriptor;
    }

    private static Target afterCall(
            String className,
            String methodName,
            String owner,
            String invokedName,
            int expectedMatches,
            int ordinal,
            Injection injection) {
        return new Target(
                className,
                methodName,
                TargetKind.AFTER_CALL,
                owner,
                invokedName,
                expectedMatches,
                ordinal,
                injection);
    }

    private static Target beforeReturn(
            String className,
            String methodName,
            int expectedReturns,
            Injection injection) {
        return new Target(
                className,
                methodName,
                TargetKind.BEFORE_RETURN,
                "",
                "",
                expectedReturns,
                expectedReturns,
                injection);
    }

    private enum TargetKind {
        AFTER_CALL,
        BEFORE_RETURN
    }

    @FunctionalInterface
    private interface Injection {
        static Injection hit(String point) {
            String checkedPoint = Objects.requireNonNull(point, "point");
            return builder ->
                    builder.ldc(checkedPoint).invokestatic(BRIDGE_CLASS, "hit", HIT_METHOD);
        }

        static Injection accepted(String point) {
            String checkedPoint = Objects.requireNonNull(point, "point");
            return builder -> builder
                    .ldc(checkedPoint)
                    .invokestatic(BRIDGE_CLASS, "accepted", ACCEPTED_METHOD);
        }

        void emit(java.lang.classfile.CodeBuilder builder);
    }

    private record Target(
            String className,
            String methodName,
            TargetKind kind,
            String owner,
            String invokedName,
            int expectedMatches,
            int ordinal,
            Injection injection) {
        private Target {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(invokedName, "invokedName");
            Objects.requireNonNull(injection, "injection");
            if (expectedMatches < 1 || ordinal < 1 || ordinal > expectedMatches) {
                throw new IllegalArgumentException("Invalid target match count or ordinal");
            }
        }

        private boolean matches(InvokeInstruction invocation) {
            return invocation.owner().asInternalName().equals(owner)
                    && invocation.name().equalsString(invokedName)
                    && invocation.typeSymbol().descriptorString().equals(invokedDescriptor());
        }

        private String methodDescriptor() {
            return requiredDescriptor(
                    TARGET_METHOD_DESCRIPTORS,
                    className,
                    methodName);
        }

        private String invokedDescriptor() {
            return requiredDescriptor(
                    INVOKED_METHOD_DESCRIPTORS,
                    owner,
                    invokedName);
        }

        private String description() {
            return className + '#' + methodName + methodDescriptor() + '/' + kind + '/'
                    + owner + '#' + invokedName
                    + (kind == TargetKind.AFTER_CALL ? invokedDescriptor() : "")
                    + '[' + ordinal + ']';
        }
    }

    private static final class Counter {
        private int matches;
        private int injections;
    }
}
