package com.minos.adapter.scip.runtime;

import com.minos.adapter.scip.runtime.EmbeddedToolsCatalog.Artifact;
import com.minos.adapter.scip.runtime.PinnedArtifactSource.EmbeddedToolIntegrityException;
import com.minos.adapter.scip.runtime.PinnedArtifactSource.Origin;
import com.minos.io.Sha256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An embedded tool is accepted exactly when a download of the same artifact would be, and an altered one
 * is refused, never replaced by a download. A local proxy listener counts every connection attempt: with the
 * payload the count must stay at zero, and a positive control proves the listener would have seen one.
 */
class PinnedArtifactSourceTest {

    private static final byte[] TOOL = "pretend this is a pinned tool archive".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    private ServerSocket canary;
    private final AtomicInteger attempts = new AtomicInteger();
    private String previousProxyHost;
    private String previousProxyPort;
    private String previousOffline;

    @BeforeEach
    void startCanaryProxy() throws IOException {
        canary = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread listener = new Thread(() -> {
            while (!canary.isClosed()) {
                try (Socket ignored = canary.accept()) {
                    attempts.incrementAndGet();
                } catch (IOException closed) {
                    return;
                }
            }
        }, "network-canary");
        listener.setDaemon(true);
        listener.start();
        previousProxyHost = System.getProperty("https.proxyHost");
        previousProxyPort = System.getProperty("https.proxyPort");
        previousOffline = System.getProperty(ToolsNetworkPolicy.SYSTEM_PROPERTY);
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", Integer.toString(canary.getLocalPort()));
        System.clearProperty(ToolsNetworkPolicy.SYSTEM_PROPERTY);
    }

    @AfterEach
    void stopCanaryProxy() throws IOException {
        canary.close();
        restore("https.proxyHost", previousProxyHost);
        restore("https.proxyPort", previousProxyPort);
        restore(ToolsNetworkPolicy.SYSTEM_PROPERTY, previousOffline);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private Artifact artifact(byte[] expectedBytes) {
        return new Artifact("sample-tool", EmbeddedToolsCatalog.PLATFORM_ANY, "1.0", "zip",
                "https://tools.example.invalid/sample-tool-1.0.zip", Sha256.hex(expectedBytes), expectedBytes.length,
                "Apache-2.0", true, "artifacts/sample-tool-1.0-any.zip");
    }

    /** The manifest of a distribution that ships the sample tool (as the build always writes it). */
    private static String manifestListingTheTool() {
        return "{\"formatVersion\":1,\"platform\":\"windows-x64\",\"files\":[{\"id\":\"sample-tool\",\"kind\":\"artifact\","
                + "\"version\":\"1.0\",\"path\":\"artifacts/sample-tool-1.0-any.zip\",\"sha256\":\"" + Sha256.hex(TOOL)
                + "\",\"sizeBytes\":" + TOOL.length + "}]}";
    }

    private Path payloadWith(byte[] content) throws IOException {
        Path payload = directory.resolve("payload");
        Files.createDirectories(payload.resolve("artifacts"));
        Files.writeString(payload.resolve(EmbeddedToolsPayload.MANIFEST_FILE), manifestListingTheTool());
        Files.write(payload.resolve("artifacts").resolve("sample-tool-1.0-any.zip"), content);
        return payload;
    }

    @Test
    void anEmbeddedArtifactMatchingTheCataloguedSha256IsAcceptedWithoutTouchingTheNetwork() throws Exception {
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payloadWith(TOOL));
        Path partial = directory.resolve("tool.partial");

        Origin origin = source.acquire(artifact(TOOL), partial, 1024, "test", true);

        assertEquals(Origin.EMBEDDED, origin);
        assertArrayEquals(TOOL, Files.readAllBytes(partial));
        assertEquals(0, attempts.get(), "no connection may be attempted when the payload provides the tool");
    }

    @Test
    void anAlteredEmbeddedArtifactIsRefusedAndNeverReplacedByADownload() throws Exception {
        byte[] altered = TOOL.clone();
        altered[3] ^= 0x01;
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payloadWith(altered));
        Path partial = directory.resolve("tool.partial");

        EmbeddedToolIntegrityException refusal = assertThrows(EmbeddedToolIntegrityException.class,
                () -> source.acquire(artifact(TOOL), partial, 1024, "test", true));

        assertTrue(refusal.getMessage().contains("sample-tool"));
        assertFalse(Files.exists(partial), "a refused artifact must not be left on disk");
        assertEquals(0, attempts.get(), "an altered payload must not fall back to the network");
    }

    @Test
    void aTruncatedOrOversizedEmbeddedArtifactIsRefused() throws Exception {
        for (byte[] wrong : new byte[][] {
                java.util.Arrays.copyOf(TOOL, TOOL.length - 1),
                java.util.Arrays.copyOf(TOOL, TOOL.length + 1)}) {
            Path root = Files.createTempDirectory(directory, "payload-case");
            Path payload = root.resolve("payload");
            Files.createDirectories(payload.resolve("artifacts"));
            Files.writeString(payload.resolve(EmbeddedToolsPayload.MANIFEST_FILE), manifestListingTheTool());
            Files.write(payload.resolve("artifacts").resolve("sample-tool-1.0-any.zip"), wrong);
            PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload);

            assertThrows(EmbeddedToolIntegrityException.class,
                    () -> source.acquire(artifact(TOOL), root.resolve("tool.partial"), 1024, "test", true));
            assertFalse(Files.exists(root.resolve("tool.partial")));
        }
        assertEquals(0, attempts.get());
    }

    @Test
    void aShippedToolThatWentMissingIsARefusalWithARepairInstructionNeverASilentDownload() throws Exception {
        Path payload = payloadWith(TOOL);
        Files.delete(payload.resolve("artifacts").resolve("sample-tool-1.0-any.zip"));
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload);

        EmbeddedToolIntegrityException refusal = assertThrows(EmbeddedToolIntegrityException.class,
                () -> source.acquire(artifact(TOOL), directory.resolve("tool.partial"), 1024, "test", true));

        assertTrue(refusal.getMessage().contains("reinstall the MINOS package"), refusal.getMessage());
        assertEquals(0, attempts.get(), "a damaged payload must not fall back to the network");
    }

    @Test
    void aShippedToolReplacedByALinkIsARefusal() throws Exception {
        Path payload = payloadWith(TOOL);
        Path file = payload.resolve("artifacts").resolve("sample-tool-1.0-any.zip");
        Files.delete(file);
        TestLinks.directoryLink(file, directory.resolve("elsewhere"));
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload);

        assertThrows(EmbeddedToolIntegrityException.class,
                () -> source.acquire(artifact(TOOL), directory.resolve("tool.partial"), 1024, "test", true));
        assertEquals(0, attempts.get());
    }

    @Test
    void aPayloadWhoseManifestIsMalformedRefusesWhatItShouldHaveCarried() throws Exception {
        Path payload = payloadWith(TOOL);
        Files.writeString(payload.resolve(EmbeddedToolsPayload.MANIFEST_FILE), "{ not json");
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload);

        assertThrows(EmbeddedToolIntegrityException.class,
                () -> source.acquire(artifact(TOOL), directory.resolve("tool.partial"), 1024, "test", true));
        assertEquals(0, attempts.get());
    }

    @Test
    void offlineModeFailsFastWithTheReasonAndNeverConnects() throws Exception {
        System.setProperty(ToolsNetworkPolicy.SYSTEM_PROPERTY, "1");
        PinnedArtifactSource source = PinnedArtifactSource.networkOnly();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> source.acquire(artifact(TOOL), directory.resolve("tool.partial"), 1024, "test", true));

        assertTrue(failure.getMessage().contains("disabled"), failure.getMessage());
        assertEquals(0, attempts.get());
    }

    @Test
    void aSeedingPassThatForbidsTheNetworkNeverConnectsWhenThePayloadIsMissing() {
        PinnedArtifactSource source = PinnedArtifactSource.networkOnly();

        assertThrows(IllegalStateException.class,
                () -> source.acquire(artifact(TOOL), directory.resolve("tool.partial"), 1024, "test", false));

        assertEquals(0, attempts.get());
    }

    @Test
    void positiveControlTheCanaryDoesSeeAnAttemptWhenTheNetworkIsAllowed() {
        PinnedArtifactSource source = PinnedArtifactSource.networkOnly();

        assertThrows(IOException.class,
                () -> source.acquire(artifact(TOOL), directory.resolve("tool.partial"), 1024, "test", true));

        assertTrue(attempts.get() >= 1, "the listener must observe the attempt, otherwise the zero counts prove nothing");
    }
}
