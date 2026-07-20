package com.mrfdev.walktheplank.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BuildInfoTest {
    @Test
    void processedResourceContainsExactReleaseIdentity() throws Exception {
        try (InputStream stream = BuildInfoTest.class.getResourceAsStream("/build-info.properties")) {
            assertNotNull(stream);
            BuildInfo info = BuildInfo.loadProperties(stream);
            assertEquals("v2.5.4 build 021", info.releaseLabel());
            assertEquals("1MB-WalkThePlank-v2.5.4-021-j25-26.2.jar", info.artifactFile());
            assertEquals("26.2.build.62-beta", info.paperApiVersion());
            assertEquals(62, info.paperApiBuild());
            assertEquals(40, info.sourceCommit().length());
        }
    }

    @Test
    void loadsEmbeddedReleaseIdentity() throws Exception {
        String properties = """
                pluginVersion=2.0.1
                buildNumber=001
                artifactFile=1MB-WalkThePlank-v2.0.1-001-j25-26.2.jar
                javaTarget=25
                paperTarget=26.2
                paperApiVersion=26.2.build.60-beta
                placeholderApiVersion=2.12.3
                sourceCommit=0123456789abcdef0123456789abcdef01234567
                sourceDirty=false
                """;

        BuildInfo info = BuildInfo.loadProperties(new ByteArrayInputStream(
                properties.getBytes(StandardCharsets.ISO_8859_1)));

        assertEquals("v2.0.1 build 001", info.releaseLabel());
        assertEquals("1MB-WalkThePlank-v2.0.1-001-j25-26.2.jar", info.artifactFile());
        assertEquals("0123456789ab-clean", info.sourceLabel());
        assertEquals(60, info.paperApiBuild());
    }

    @Test
    void rejectsUnexpandedBuildValues() {
        assertThrows(IllegalArgumentException.class, () -> new BuildInfo(
                "${semanticVersion}",
                "001",
                "release.jar",
                "25",
                "26.2",
                "api",
                "papi",
                "0123456789abcdef0123456789abcdef01234567",
                false));
    }

    @Test
    void rejectsMalformedSourceCommit() {
        assertThrows(IllegalArgumentException.class, () -> new BuildInfo(
                "2.1.0",
                "003",
                "release.jar",
                "25",
                "26.2",
                "api",
                "papi",
                "not-a-commit",
                false));
    }

    @Test
    void rejectsNonBooleanDirtyProperty() {
        String properties = """
                pluginVersion=2.1.0
                buildNumber=003
                artifactFile=release.jar
                javaTarget=25
                paperTarget=26.2
                paperApiVersion=api
                placeholderApiVersion=papi
                sourceCommit=0123456789abcdef0123456789abcdef01234567
                sourceDirty=maybe
                """;

        assertThrows(IllegalArgumentException.class, () -> BuildInfo.loadProperties(
                new ByteArrayInputStream(properties.getBytes(StandardCharsets.ISO_8859_1))));
    }
}
