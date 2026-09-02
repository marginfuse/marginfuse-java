package com.marginfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The version the SDK reports has to be the version that was published.
 *
 * <p>The user-agent is how a support conversation starts: someone reports odd
 * behaviour and the first question is which version sent the request. The Node
 * SDK answered that with "0.1.0" across three releases, because the string was
 * written once and nothing compared it to the build's own version.
 *
 * <p>Gradle passes its version in as a system property, since the published
 * version lives in the build script where Java cannot see it.
 */
final class VersionTest {

    private static String publishedVersion() {
        String version = System.getProperty("marginfuse.publishedVersion");
        assertNotNull(version, "run through Gradle, which supplies marginfuse.publishedVersion");
        return version;
    }

    @Test
    @DisplayName("the reported version is the published version")
    void versionMatchesTheBuild() {
        assertEquals(publishedVersion(), MarginFuse.VERSION);
    }

    @Test
    @DisplayName("the user-agent carries that version")
    void userAgentCarriesTheVersion() {
        assertEquals("marginfuse-java/" + publishedVersion(), MarginFuse.USER_AGENT);
    }
}
