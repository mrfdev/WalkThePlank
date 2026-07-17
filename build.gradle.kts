import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.jar.JarFile
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
    id("com.gradleup.shadow") version "9.5.1"
}

group = "com.mrfdev"

val pluginVersion = providers.gradleProperty("pluginVersion").get()
val buildNumber = providers.gradleProperty("buildNumber").get()
val javaTarget = providers.gradleProperty("javaTarget").get()
val paperTarget = providers.gradleProperty("paperTarget").get()
val paperApiVersion = providers.gradleProperty("paperApiVersion").get()
val placeholderApiVersion = providers.gradleProperty("placeholderApiVersion").get()
val sqliteJdbcVersion = providers.gradleProperty("sqliteJdbcVersion").get()
val junitVersion = providers.gradleProperty("junitVersion").get()
val sourceCommit = providers.exec {
    workingDir(rootDir)
    commandLine("git", "rev-parse", "--verify", "HEAD")
}.standardOutput.asText.get().trim().also {
    require(it.matches(Regex("[0-9a-f]{40}"))) {
        "Git HEAD must resolve to a full lowercase 40-character commit"
    }
}
val sourceDirty = providers.exec {
    workingDir(rootDir)
    commandLine("git", "status", "--porcelain=v1", "--untracked-files=all", "--", ".")
}.standardOutput.asText.get().isNotBlank()

require(buildNumber.matches(Regex("\\d{3}"))) {
    "buildNumber must contain exactly three digits (for example 001)"
}
require(javaTarget == "25") { "This release line must compile for Java 25" }
require(paperTarget == "26.2") { "This release line must target Paper 26.2" }

version = pluginVersion

val releaseStem = "1MB-WalkThePlank-v$pluginVersion-$buildNumber-j$javaTarget-$paperTarget"
val releaseJarName = "$releaseStem.jar"
val pluginDescriptorVersion = "$pluginVersion-$buildNumber"
val scenarioArtifactDirectory = layout.buildDirectory.dir("scenario-artifacts")
val scenarioHarnessJarName =
    "TEST-ONLY-1MB-WalkThePlank-ScenarioHarness-v$pluginVersion-$buildNumber.jar"
val scenarioInstrumentedJarName =
    "TEST-ONLY-1MB-WalkThePlank-v$pluginVersion-$buildNumber-Failpoints.jar"
val scenarioFailpointIds = listOf(
    "player_journal.after_temp_write",
    "player_journal.after_temp_fsync",
    "player_journal.after_rename",
    "player_journal.after_directory_fsync",
    "player_journal.after_delete",
    "restoration_journal.after_temp_write",
    "restoration_journal.after_temp_fsync",
    "restoration_journal.after_rename",
    "restoration_journal.after_directory_fsync",
    "restoration_journal.after_delete",
    "block.after_place",
    "block.after_restore",
    "teleport.after_start",
    "teleport.after_return",
    "score.after_commit",
    "reward.after_dispatch",
    "reward_claim.after_commit",
    "reward_outcome.after_commit",
    "export.csv.after_rename",
    "export.json.after_rename",
    "config.backup.after_rename",
    "config.candidate.after_rename",
    "config.after_disk_commit",
    "config.after_runtime_commit",
)
val productionScenarioCanaries = listOf(
    "WTP_SCENARIO_TEST_ONLY_7E4C0D98",
    "walktheplank.scenario.",
    "com/mrfdev/walktheplank/scenario/",
    "ScenarioFailpoints",
    "ScenarioJarInstrumenter",
    "WalkThePlank-Test-Artifact",
    "walktheplankscenario",
) + scenarioFailpointIds

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("me.clip:placeholderapi:$placeholderApiVersion")

    implementation("org.xerial:sqlite-jdbc:$sqliteJdbcVersion") {
        exclude(group = "org.slf4j")
    }

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

}

dependencyLocking {
    lockAllConfigurations()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaTarget.toInt()))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaTarget.toInt())
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.processResources {
    val properties = mapOf(
        "version" to pluginDescriptorVersion,
        "semanticVersion" to pluginVersion,
        "buildNumber" to buildNumber,
        "javaTarget" to javaTarget,
        "paperTarget" to paperTarget,
        "paperApiVersion" to paperApiVersion,
        "placeholderApiVersion" to placeholderApiVersion,
        "artifactFile" to releaseJarName,
        "sourceCommit" to sourceCommit,
        "sourceDirty" to sourceDirty.toString(),
    )
    inputs.properties(properties)
    filteringCharset = "UTF-8"
    filesMatching(listOf("plugin.yml", "build-info.properties")) {
        expand(properties)
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.jar {
    archiveFileName.set("$releaseStem-unshaded.jar")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set(releaseJarName)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
}

val scenarioHarnessClasses =
    layout.buildDirectory.dir("classes/java/scenarioHarness")
val scenarioInstrumentationClasses =
    layout.buildDirectory.dir("classes/java/scenarioInstrumentation")
val scenarioToolsClasses =
    layout.buildDirectory.dir("classes/java/scenarioTools")

val compileScenarioHarness = tasks.register<JavaCompile>("compileScenarioHarness") {
    group = "verification"
    description = "Compiles the isolated Paper scenario plugin."
    dependsOn(tasks.classes)
    source(fileTree("src/scenarioHarness/java") {
        include("**/*.java")
    })
    classpath = sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    destinationDirectory.set(scenarioHarnessClasses)
}

val compileScenarioInstrumentation =
    tasks.register<JavaCompile>("compileScenarioInstrumentation") {
        group = "verification"
        description = "Compiles the test-only failpoint bridge."
        source(fileTree("src/scenarioInstrumentation/java") {
            include("**/*.java")
        })
        classpath = files()
        destinationDirectory.set(scenarioInstrumentationClasses)
    }

val compileScenarioTools = tasks.register<JavaCompile>("compileScenarioTools") {
    group = "verification"
    description = "Compiles the Java 25 Class-File API scenario instrumenter."
    source(fileTree("src/scenarioTools/java") {
        include("**/*.java")
    })
    classpath = files()
    destinationDirectory.set(scenarioToolsClasses)
}

val scenarioHarnessJar = tasks.register<Jar>("scenarioHarnessJar") {
    group = "verification"
    description = "Builds the separate test-only Paper scenario plugin."
    dependsOn(compileScenarioHarness)
    archiveFileName.set(scenarioHarnessJarName)
    destinationDirectory.set(scenarioArtifactDirectory)
    from(scenarioHarnessClasses)
    from("src/scenarioHarness/resources") {
        filesMatching("plugin.yml") {
            expand(
                "version" to pluginDescriptorVersion,
                "paperTarget" to paperTarget,
            )
        }
    }
    manifest.attributes(
        "Implementation-Title" to "WalkThePlank Scenario Harness",
        "Implementation-Version" to pluginDescriptorVersion,
        "WalkThePlank-Test-Artifact" to "scenario-harness",
        "WalkThePlank-Test-Canary" to "WTP_SCENARIO_TEST_ONLY_7E4C0D98",
    )
}

val scenarioInstrumentedJar = layout.buildDirectory
    .file("scenario-artifacts/$scenarioInstrumentedJarName")
val instrumentScenarioJar = tasks.register<JavaExec>("instrumentScenarioJar") {
    group = "verification"
    description = "Copies and instruments the production JAR for destructive scenarios."
    dependsOn(tasks.shadowJar, compileScenarioInstrumentation, compileScenarioTools)
    val productionJar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    classpath = files(
        scenarioToolsClasses,
        scenarioInstrumentationClasses,
        productionJar,
    ) + sourceSets.main.get().compileClasspath
    mainClass.set(
        "com.mrfdev.walktheplank.scenario.tools.ScenarioJarInstrumenter")
    inputs.file(productionJar)
    inputs.dir(scenarioInstrumentationClasses)
    outputs.file(scenarioInstrumentedJar)
    doFirst {
        args(
            productionJar.get().asFile.absolutePath,
            scenarioInstrumentationClasses.get().asFile.absolutePath,
            scenarioInstrumentedJar.get().asFile.absolutePath,
        )
    }
}

val scenarioReproducibilityJar = layout.buildDirectory.file(
    "tmp/scenario-reproducibility/" +
        "TEST-ONLY-Reproducibility-$scenarioInstrumentedJarName")
val instrumentScenarioJarReproducibilityCopy =
    tasks.register<JavaExec>("instrumentScenarioJarReproducibilityCopy") {
        group = "verification"
        description = "Builds an independent instrumented copy for reproducibility verification."
        dependsOn(tasks.shadowJar, compileScenarioInstrumentation, compileScenarioTools)
        val productionJar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
        classpath = files(
            scenarioToolsClasses,
            scenarioInstrumentationClasses,
            productionJar,
        ) + sourceSets.main.get().compileClasspath
        mainClass.set(
            "com.mrfdev.walktheplank.scenario.tools.ScenarioJarInstrumenter")
        inputs.file(productionJar)
        inputs.dir(scenarioInstrumentationClasses)
        outputs.file(scenarioReproducibilityJar)
        doFirst {
            args(
                productionJar.get().asFile.absolutePath,
                scenarioInstrumentationClasses.get().asFile.absolutePath,
                scenarioReproducibilityJar.get().asFile.absolutePath,
            )
        }
    }

val verifyScenarioInstrumenterReproducibility =
    tasks.register("verifyScenarioInstrumenterReproducibility") {
        group = "verification"
        description =
            "Requires two independent instrumented scenario JARs to be byte-for-byte identical."
        dependsOn(instrumentScenarioJar, instrumentScenarioJarReproducibilityCopy)
        inputs.files(scenarioInstrumentedJar, scenarioReproducibilityJar)
        doLast {
            val primary = scenarioInstrumentedJar.get().asFile.toPath()
            val independent = scenarioReproducibilityJar.get().asFile.toPath()
            check(Files.mismatch(primary, independent) == -1L) {
                "Independent instrumented scenario JARs are not byte-for-byte identical"
            }
        }
    }

val scenarioArtifacts = tasks.register("scenarioArtifacts") {
    group = "verification"
    description = "Builds every isolated test-only scenario artifact."
    dependsOn(scenarioHarnessJar, instrumentScenarioJar)
}

val controlledScenarios = tasks.register<Exec>("controlledScenarios") {
    group = "verification"
    description =
        "Runs the disposable two-start Paper 26.2 lifecycle and PlaceholderAPI scenario."
    dependsOn("verifyProductionScenarioIsolation")
    workingDir(rootDir)
    commandLine(
        "./scripts/run-controlled-scenarios.sh",
        "--skip-build",
    )
}

val controlledRuntimeCommitFailpoint =
    tasks.register<Exec>("controlledRuntimeCommitFailpoint") {
    group = "verification"
    description =
        "Runs the disposable config runtime-commit hard-kill and recovery scenario."
    dependsOn("verifyProductionScenarioIsolation")
    workingDir(rootDir)
    commandLine(
        "./scripts/run-controlled-scenarios.sh",
        "--skip-build",
        "--failpoint",
        "config.after_runtime_commit",
    )
}

controlledRuntimeCommitFailpoint.configure {
    mustRunAfter(controlledScenarios)
}

val controlledReleaseScenarios = tasks.register("controlledReleaseScenarios") {
    group = "verification"
    description =
        "Runs the ordered two-start and runtime-commit recovery gates for a release candidate."
    dependsOn(controlledScenarios, controlledRuntimeCommitFailpoint)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Jar>().configureEach {
    manifest.attributes(
        "Implementation-Title" to "1MB-WalkThePlank",
        "Implementation-Version" to pluginDescriptorVersion,
        "1MB-Build-Number" to buildNumber,
        "Build-Java-Target" to javaTarget,
        "Build-Paper-Target" to paperTarget,
        "Build-Source-Commit" to sourceCommit,
        "Build-Source-Dirty" to sourceDirty.toString(),
    )
}

tasks.register("releaseInfo") {
    group = "1MB release"
    description = "Prints the immutable release identity for this source tree."
    doLast {
        logger.lifecycle("Version: $pluginVersion")
        logger.lifecycle("Build: $buildNumber")
        logger.lifecycle("Artifact: $releaseJarName")
        logger.lifecycle("Source: $sourceCommit${if (sourceDirty) "-dirty" else "-clean"}")
    }
}

val verifyReleaseJar = tasks.register("verifyReleaseJar") {
    group = "verification"
    description = "Verifies standalone dependencies and immutable 1MB release metadata."
    dependsOn(tasks.shadowJar)
    val releaseJar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    inputs.file(releaseJar)

    doLast {
        val jarPath = releaseJar.get().asFile.toPath()
        check(jarPath.fileName.toString() == releaseJarName) {
            "Unexpected release filename: ${jarPath.fileName}"
        }

        JarFile(jarPath.toFile(), true).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            check("org/sqlite/JDBC.class" in names) {
                "Standalone release is missing the SQLite JDBC driver"
            }
            check("META-INF/services/java.sql.Driver" in names) {
                "Standalone release is missing the JDBC service registration"
            }
            val driverProviders = jar.getInputStream(
                checkNotNull(jar.getJarEntry("META-INF/services/java.sql.Driver")),
            ).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toSet()
            }
            check(driverProviders == setOf("org.sqlite.JDBC")) {
                "Standalone release must register only the SQLite JDBC driver: $driverProviders"
            }

            val forbiddenPrefixes = listOf(
                "com/mysql/",
                "org/mariadb/",
                "io/papermc/paper/",
                "org/bukkit/",
                "me/clip/placeholderapi/",
                "com/mrfdev/walktheplank/scenario/",
            )
            val forbiddenClasses = names.filter { name ->
                name.endsWith(".class") && forbiddenPrefixes.any(name::startsWith)
            }
            check(forbiddenClasses.isEmpty()) {
                "Provided or remote-database classes leaked into the release: " +
                    forbiddenClasses.take(10).joinToString()
            }
            check(names.none { it.lowercase().endsWith(".db") || it.lowercase().endsWith(".sqlite") }) {
                "A database file must never be embedded in the release artifact"
            }
            val scenarioLeaks = mutableListOf<String>()
            for (entry in jar.entries().asSequence().filterNot { it.isDirectory }) {
                val content = jar.getInputStream(entry).use { stream ->
                    stream.readAllBytes().toString(Charsets.ISO_8859_1)
                }
                for (marker in productionScenarioCanaries) {
                    if (marker in content) {
                        scenarioLeaks.add("${entry.name}:$marker")
                    }
                }
            }
            check(scenarioLeaks.isEmpty()) {
                "Test-only scenario controls leaked into production: " +
                    scenarioLeaks.take(10).joinToString()
            }

            val buildProperties = Properties().apply {
                val entry = checkNotNull(jar.getJarEntry("build-info.properties")) {
                    "Release is missing build-info.properties"
                }
                jar.getInputStream(entry).use { stream -> load(stream) }
            }
            check(buildProperties.getProperty("pluginVersion") == pluginVersion)
            check(buildProperties.getProperty("buildNumber") == buildNumber)
            check(buildProperties.getProperty("artifactFile") == releaseJarName)
            check(buildProperties.getProperty("javaTarget") == javaTarget)
            check(buildProperties.getProperty("paperTarget") == paperTarget)
            check(buildProperties.getProperty("sourceCommit") == sourceCommit)
            check(buildProperties.getProperty("sourceDirty") == sourceDirty.toString())

            val descriptorEntry = checkNotNull(jar.getJarEntry("plugin.yml")) {
                "Release is missing plugin.yml"
            }
            val descriptor = jar.getInputStream(descriptorEntry)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            check(descriptor.contains("name: InfinityParkour"))
            check(descriptor.contains("version: '$pluginDescriptorVersion'"))
            check(descriptor.contains("api-version: '26.2'"))

            val manifest = checkNotNull(jar.manifest) { "Release is missing its manifest" }
            val attributes = manifest.mainAttributes
            check(attributes.getValue("Implementation-Version") == pluginDescriptorVersion)
            check(attributes.getValue("1MB-Build-Number") == buildNumber)
            check(attributes.getValue("Build-Java-Target") == javaTarget)
            check(attributes.getValue("Build-Paper-Target") == paperTarget)
            check(attributes.getValue("Build-Source-Commit") == sourceCommit)
            check(attributes.getValue("Build-Source-Dirty") == sourceDirty.toString())
            check(attributes.getValue("WalkThePlank-Test-Artifact") == null)
            check(attributes.getValue("WalkThePlank-Test-Canary") == null)
            check(attributes.getValue("Premain-Class") == null)
            check(attributes.getValue("Agent-Class") == null)
            check(attributes.getValue("Launcher-Agent-Class") == null)
            check(attributes.getValue("Boot-Class-Path") == null)
            check(attributes.getValue("Can-Redefine-Classes") == null)
            check(attributes.getValue("Can-Retransform-Classes") == null)
        }
    }
}

val verifyProductionScenarioIsolation =
    tasks.register("verifyProductionScenarioIsolation") {
        group = "verification"
        description =
            "Proves scenario plugins, instrumentation, controls, and canaries are absent from production."
        dependsOn(scenarioArtifacts, verifyScenarioInstrumenterReproducibility)
        val productionJar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
        val harnessJar = scenarioHarnessJar.flatMap { it.archiveFile }
        inputs.files(productionJar, harnessJar, scenarioInstrumentedJar)

        doLast {
            fun markerHits(path: java.nio.file.Path): Set<String> {
                val hits = linkedSetOf<String>()
                JarFile(path.toFile(), true).use { jar ->
                    for (entry in jar.entries().asSequence().filterNot { it.isDirectory }) {
                        val content = jar.getInputStream(entry).use { stream ->
                            stream.readAllBytes().toString(Charsets.ISO_8859_1)
                        }
                        for (marker in productionScenarioCanaries) {
                            if (marker in content) {
                                hits.add(marker)
                            }
                        }
                    }
                    val attributes = jar.manifest?.mainAttributes
                    if (attributes?.getValue("Premain-Class") != null) {
                        hits.add("Premain-Class")
                    }
                    if (attributes?.getValue("Agent-Class") != null) {
                        hits.add("Agent-Class")
                    }
                    if (attributes?.getValue("Launcher-Agent-Class") != null) {
                        hits.add("Launcher-Agent-Class")
                    }
                }
                return hits
            }

            val productionPath = productionJar.get().asFile.toPath()
            val harnessPath = harnessJar.get().asFile.toPath()
            val instrumentedPath = scenarioInstrumentedJar.get().asFile.toPath()
            check(productionPath != instrumentedPath) {
                "Scenario instrumentation must never replace the production JAR"
            }
            val productionHits = markerHits(productionPath)
            check(productionHits.isEmpty()) {
                "Production JAR contains test-only scenario evidence: $productionHits"
            }
            val harnessHits = markerHits(harnessPath)
            check(harnessHits.isNotEmpty()) {
                "Positive control failed: scenario harness was not recognized as test-only"
            }
            check(scenarioFailpointIds.all(harnessHits::contains)) {
                "Scenario harness is missing one or more named failpoint controls"
            }
            val instrumentedHits = markerHits(instrumentedPath)
            check("WTP_SCENARIO_TEST_ONLY_7E4C0D98" in instrumentedHits) {
                "Positive control failed: instrumented JAR canary was not detected"
            }
            check(scenarioFailpointIds.all(instrumentedHits::contains)) {
                "Instrumented JAR is missing one or more named failpoint controls"
            }

            JarFile(harnessPath.toFile(), true).use { harness ->
                val names = harness.entries().asSequence().map { it.name }.toList()
                val classes = names.filter { it.endsWith(".class") }
                check(classes.isNotEmpty())
                check(classes.all {
                    it.startsWith("com/mrfdev/walktheplank/scenario/harness/")
                }) {
                    "Scenario harness contains a class outside its isolated package"
                }
                val shadedPrefixes = listOf(
                    "com/mrfdev/walktheplank/WalkThePlankPlugin",
                    "org/sqlite/",
                    "org/bukkit/",
                    "io/papermc/paper/",
                    "me/clip/placeholderapi/",
                )
                check(classes.none { name -> shadedPrefixes.any(name::startsWith) }) {
                    "Scenario harness shaded production or provided runtime classes"
                }
                val descriptor = harness.getJarEntry("plugin.yml")
                check(descriptor != null) {
                    "Scenario harness is missing its independent plugin.yml"
                }
                val descriptorText = harness.getInputStream(descriptor)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                check(descriptorText.contains("name: WalkThePlank-ScenarioHarness"))
                check(descriptorText.contains("depend: [InfinityParkour]"))
            }

            JarFile(instrumentedPath.toFile(), true).use { instrumented ->
                check(instrumented.getJarEntry(
                    "com/mrfdev/walktheplank/WalkThePlankPlugin.class") != null)
                check(instrumented.getJarEntry(
                    "com/mrfdev/walktheplank/scenario/instrumentation/ScenarioFailpoints.class") != null)
                check(instrumented.getJarEntry("META-INF/WTP-SCENARIO-TEST-ONLY") != null)
                check(instrumented.manifest.mainAttributes
                    .getValue("WalkThePlank-Test-Artifact") == "instrumented-failpoints")
            }
        }
    }

tasks.build {
    dependsOn(verifyReleaseJar)
}

tasks.check {
    dependsOn(verifyProductionScenarioIsolation)
}

val verifyCleanSource = tasks.register("verifyCleanSource") {
    group = "verification"
    description = "Rejects a release candidate built from an uncommitted source tree."
    doLast {
        check(!sourceDirty) {
            "Release candidate source is dirty; commit the exact tree before freezing or syncing it"
        }
    }
}

val freezeCandidate = tasks.register("freezeCandidate") {
    group = "1MB release"
    description = "Builds and verifies a release candidate from an exact clean Git commit."
    dependsOn(tasks.build)
    dependsOn(verifyCleanSource)
}

controlledScenarios.configure {
    mustRunAfter(freezeCandidate)
}
controlledRuntimeCommitFailpoint.configure {
    mustRunAfter(freezeCandidate)
}

tasks.register("syncTestServer") {
    group = "1MB release"
    description = "Builds and copies this release to the Paper 26.2 test server, disabling older builds."
    dependsOn(freezeCandidate)
    dependsOn(controlledReleaseScenarios)
    doLast {
        val pluginsDirectory = layout.projectDirectory.dir("servers/Paper-26.2/plugins").asFile.toPath()
        val disabledDirectory = layout.projectDirectory
            .dir("servers/Paper-26.2/plugins-disabled/walktheplank")
            .asFile
            .toPath()
        Files.createDirectories(pluginsDirectory)
        Files.createDirectories(disabledDirectory)

        Files.list(pluginsDirectory).use { files ->
            files.filter(Files::isRegularFile)
                .filter { it.fileName.toString().lowercase().endsWith(".jar") }
                .filter {
                    val name = it.fileName.toString().lowercase()
                    name != releaseJarName.lowercase()
                        && (name.contains("walktheplank") || name.contains("infinityparkour"))
                }
                .forEach { source ->
                    val destination = disabledDirectory.resolve(source.fileName)
                    Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
                    logger.lifecycle("Disabled older build: ${source.fileName}")
                }
        }

        val releaseJar = tasks.named<ShadowJar>("shadowJar").get().archiveFile.get().asFile.toPath()
        val destination = pluginsDirectory.resolve(releaseJarName)
        Files.copy(releaseJar, destination, StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("Synced test-server plugin: $destination")
    }
}
