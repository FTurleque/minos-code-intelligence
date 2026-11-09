package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.runtime.plan.ScipJavaProjectJdk;
import com.minos.adapter.scip.runtime.install.EmbeddedToolsCatalog.Artifact;
import com.minos.adapter.scip.runtime.plan.ScipJavaMavenOfflineFixtures;
import com.minos.io.Sha256;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Under Linux, scip-java installs the Coursier launcher pinned by the catalogue: the gzip archive is verified against
 * its pinned SHA-256 before anything is extracted, the launcher is published atomically as an owner-only executable
 * and its origin is recorded; a mismatch or a refused network leaves no executable and no partial file behind, and
 * the diagnostic names Coursier without any absolute path. The pinned source is a fake distribution payload: the
 * real verification path ({@link PinnedArtifactSource#acquire}) runs on bytes the test controls.
 */
@EnabledOnOs(OS.LINUX)
class ManagedScipProviderRuntimeManagerCoursierLinuxTest {

    private static final byte[] LAUNCHER = "#!/bin/sh\necho fake coursier launcher\n".getBytes(StandardCharsets.UTF_8);
    private static final String PAYLOAD_FILE = "artifacts/coursier-linux-x64.gz";
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(^|[\\s'\"(=:])/[A-Za-z0-9._-]+/");

    @TempDir Path directory;

    private Path home;
    private Path payload;
    private String previousOffline;

    @BeforeEach
    void isolate() {
        home = directory.resolve("home");
        payload = directory.resolve("install").resolve("tools");
        previousOffline = System.getProperty(ToolsNetworkPolicy.SYSTEM_PROPERTY);
        System.clearProperty(ToolsNetworkPolicy.SYSTEM_PROPERTY);
    }

    @AfterEach
    void restore() {
        if (previousOffline == null) System.clearProperty(ToolsNetworkPolicy.SYSTEM_PROPERTY);
        else System.setProperty(ToolsNetworkPolicy.SYSTEM_PROPERTY, previousOffline);
    }

    @Test
    void theLauncherIsInstalledFromThePinnedSourceAsAnOwnerOnlyExecutable() throws Exception {
        byte[] archive = gzip(LAUNCHER);
        writePayload(archive);
        ManagedScipProviderRuntimeManager manager = manager();

        Path installed = manager.installManagedCoursier(
                PinnedArtifactSource.forPayloadDirectory(payload), pinnedTo(archive), false);

        assertEquals(launcherDirectory().resolve("cs"), installed);
        assertArrayEquals(LAUNCHER, Files.readAllBytes(installed));
        assertEquals("rwx------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(installed, LinkOption.NOFOLLOW_LINKS)));
        assertEquals(List.of(installed), regularFilesUnder(coursierRoot()));
        assertTrue(Files.readString(home.resolve("tools").resolve(ToolOriginLedger.FILE_NAME)).contains("\"coursier\""));
        ProviderRuntimeStatus status = manager.inspect(ManagedScipProviderRuntimeManager.SCIP_JAVA_ID);
        assertTrue(status.diagnostics().stream().noneMatch(line -> line.startsWith("Coursier is not installed")),
                status.diagnostics()::toString);
        assertEquals(installed, status.executable().orElseThrow());
    }

    @Test
    void aSourceThatServesOtherBytesIsRefusedAndLeavesNoCoursierFile() throws Exception {
        byte[] archive = gzip(LAUNCHER);
        writePayload(gzip("#!/bin/sh\necho tampered\n".getBytes(StandardCharsets.UTF_8)));
        ManagedScipProviderRuntimeManager manager = manager();
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload);
        Artifact pinned = pinnedTo(archive);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> manager.installManagedCoursier(source, pinned, false));

        assertTrue(refused.getMessage().contains("Coursier"), refused.getMessage());
        assertNoAbsolutePath(refused.getMessage());
        assertEquals(List.of(), regularFilesUnder(coursierRoot()));
    }

    @Test
    void aVerifiedArchiveThatIsNotGzipIsRefusedAndLeavesNoCoursierFile() throws Exception {
        byte[] notGzip = "not a gzip stream".getBytes(StandardCharsets.UTF_8);
        writePayload(notGzip);
        ManagedScipProviderRuntimeManager manager = manager();
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload);
        Artifact pinned = pinnedTo(notGzip);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> manager.installManagedCoursier(source, pinned, false));

        assertTrue(refused.getMessage().contains("Coursier"), refused.getMessage());
        assertNoAbsolutePath(refused.getMessage());
        assertEquals(List.of(), regularFilesUnder(coursierRoot()));
    }

    @Test
    void withTheNetworkRefusedTheDiagnosticNamesCoursierAndIsActionable() throws Exception {
        System.setProperty(ToolsNetworkPolicy.SYSTEM_PROPERTY, "1");
        Artifact catalogued = EmbeddedToolsCatalog.load().artifact("coursier", EmbeddedToolsCatalog.PLATFORM_LINUX_X64);

        ManagedScipProviderRuntimeManager manager = manager();
        PinnedArtifactSource source = PinnedArtifactSource.networkOnly();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> manager.installManagedCoursier(source, catalogued, true));

        assertTrue(refused.getMessage().contains("Coursier"), refused.getMessage());
        assertTrue(refused.getMessage().contains(ToolsNetworkPolicy.ENVIRONMENT_VARIABLE), refused.getMessage());
        assertTrue(refused.getMessage().contains("PATH"), refused.getMessage());
        assertNoAbsolutePath(refused.getMessage());
        assertEquals(List.of(), regularFilesUnder(coursierRoot()));
    }

    private ManagedScipProviderRuntimeManager manager() {
        return new ManagedScipProviderRuntimeManager(home, ScipJavaMavenOfflineFixtures.unconfigured(home),
                ScipJavaProjectJdk.fromEnvironment());
    }

    /** The catalogue's Linux launcher, re-pinned to {@code archive} and served by the fake payload. */
    private static Artifact pinnedTo(byte[] archive) {
        Artifact catalogued = EmbeddedToolsCatalog.load().artifact("coursier", EmbeddedToolsCatalog.PLATFORM_LINUX_X64);
        return new Artifact(catalogued.id(), catalogued.platform(), catalogued.version(), catalogued.format(),
                catalogued.url(), Sha256.hex(archive), 0L, catalogued.license(), true, PAYLOAD_FILE);
    }

    private void writePayload(byte[] servedBytes) throws IOException {
        Files.createDirectories(payload.resolve("artifacts"));
        Files.write(payload.resolve(PAYLOAD_FILE), servedBytes);
        Files.writeString(payload.resolve(EmbeddedToolsPayload.MANIFEST_FILE), "{\"formatVersion\":1,"
                + "\"platform\":\"linux-x64\",\"files\":[{\"id\":\"coursier\",\"kind\":\"artifact\","
                + "\"version\":\"pinned\",\"path\":\"" + PAYLOAD_FILE + "\",\"sha256\":\"" + Sha256.hex(servedBytes)
                + "\",\"sizeBytes\":" + servedBytes.length + "}]}");
    }

    private Path coursierRoot() {
        return home.resolve("tools").resolve("coursier");
    }

    private Path launcherDirectory() {
        String version = EmbeddedToolsCatalog.load().artifact("coursier", EmbeddedToolsCatalog.PLATFORM_LINUX_X64).version();
        return coursierRoot().resolve("linux-x64-" + version.substring(0, 12));
    }

    private static List<Path> regularFilesUnder(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
    }

    private void assertNoAbsolutePath(String message) {
        assertFalse(message.contains(directory.toString()), message);
        assertFalse(ABSOLUTE_PATH.matcher(message).find(), message);
    }

    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(content);
        }
        return bytes.toByteArray();
    }
}
