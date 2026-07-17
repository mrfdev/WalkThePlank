import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.jar.JarFile

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

            val forbiddenPrefixes = listOf(
                "com/mysql/",
                "org/mariadb/",
                "io/papermc/paper/",
                "org/bukkit/",
                "me/clip/placeholderapi/",
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
        }
    }
}

tasks.build {
    dependsOn(verifyReleaseJar)
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

tasks.register("syncTestServer") {
    group = "1MB release"
    description = "Builds and copies this release to the Paper 26.2 test server, disabling older builds."
    dependsOn(freezeCandidate)
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
