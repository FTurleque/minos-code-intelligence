package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.runtime.install.EmbeddedToolsCatalog.Artifact;
import com.minos.io.Sha256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue entry and the archive format of the managed Coursier launcher follow the platform: the Windows ZIP
 * must hold exactly one executable, and a platform the catalogue packages nothing for is refused with an actionable
 * diagnostic. The pinned source is a fake distribution payload, so both formats are exercised on any host.
 */
class CoursierLauncherInstallationTest {

    private static final byte[] LAUNCHER = "pretend this is cs.exe".getBytes(StandardCharsets.UTF_8);
    private static final String PAYLOAD_FILE = "artifacts/coursier-windows-x64.zip";

    @TempDir Path directory;

    private Path toolsRoot;
    private Path payload;
    private String previousPlatform;

    @BeforeEach
    void isolate() {
        toolsRoot = directory.resolve("home").resolve("tools");
        payload = directory.resolve("install").resolve("tools");
        previousPlatform = System.getProperty("minos.tools.platform");
    }

    @AfterEach
    void restore() {
        if (previousPlatform == null) System.clearProperty("minos.tools.platform");
        else System.setProperty("minos.tools.platform", previousPlatform);
    }

    @Test
    void theWindowsZipIsVerifiedThenItsSingleExecutableIsPublished() throws Exception {
        byte[] archive = zip(List.of("cs-x86_64-pc-win32.exe"));
        writePayload(archive);
        System.setProperty("minos.tools.platform", EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64);
        CoursierLauncherInstallation launcher = installation();

        Path installed = launcher.install(PinnedArtifactSource.forPayloadDirectory(payload), pinnedTo(archive), false);

        assertEquals("cs.exe", installed.getFileName().toString());
        assertArrayEquals(LAUNCHER, Files.readAllBytes(installed));
        assertEquals(installed, launcher.managedExecutable().orElseThrow());
        assertTrue(Files.readString(toolsRoot.resolve(ToolOriginLedger.FILE_NAME)).contains("\"coursier\":\"embedded\""));
    }

    @Test
    void aWindowsZipWithoutExactlyOneExecutableIsRefusedAndPublishesNothing() throws Exception {
        byte[] archive = zip(List.of("one.exe", "two.exe"));
        writePayload(archive);
        System.setProperty("minos.tools.platform", EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64);
        CoursierLauncherInstallation launcher = installation();
        PinnedArtifactSource source = PinnedArtifactSource.forPayloadDirectory(payload);
        EmbeddedToolsCatalog.Artifact pinned = pinnedTo(archive);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> launcher.install(source, pinned, false));

        assertTrue(refused.getMessage().contains("Coursier"), refused.getMessage());
        assertTrue(launcher.managedExecutable().isEmpty());
        assertTrue(executablesUnder(toolsRoot).isEmpty());
    }

    @Test
    void aPlatformWithoutAPackagedLauncherIsRefusedAndNamesThePathAlternative() {
        System.setProperty("minos.tools.platform", "plan9-mips");
        CoursierLauncherInstallation launcher = installation();

        PinnedArtifactSource source = PinnedArtifactSource.networkOnly();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> launcher.installForPlatform(source, false));

        assertTrue(launcher.artifact().isEmpty());
        assertTrue(launcher.managedExecutable().isEmpty());
        assertTrue(refused.getMessage().contains("Coursier") && refused.getMessage().contains("PATH"), refused.getMessage());
    }

    @Test
    void eachSupportedPlatformGetsItsOwnCatalogueEntry() {
        for (String platform : List.of(EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64, EmbeddedToolsCatalog.PLATFORM_LINUX_X64)) {
            System.setProperty("minos.tools.platform", platform);
            assertEquals(platform, installation().artifact().orElseThrow().platform());
        }
    }

    private CoursierLauncherInstallation installation() {
        return new CoursierLauncherInstallation(
                EmbeddedToolsCatalog.load(), toolsRoot, new ToolOriginLedger(toolsRoot), "MINOS-test");
    }

    private static Artifact pinnedTo(byte[] archive) {
        Artifact catalogued = EmbeddedToolsCatalog.load().artifact("coursier", EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64);
        return new Artifact(catalogued.id(), catalogued.platform(), catalogued.version(), catalogued.format(),
                catalogued.url(), Sha256.hex(archive), archive.length, catalogued.license(), true, PAYLOAD_FILE);
    }

    private void writePayload(byte[] servedBytes) throws IOException {
        Files.createDirectories(payload.resolve("artifacts"));
        Files.write(payload.resolve(PAYLOAD_FILE), servedBytes);
        Files.writeString(payload.resolve(EmbeddedToolsPayload.MANIFEST_FILE), "{\"formatVersion\":1,"
                + "\"platform\":\"windows-x64\",\"files\":[{\"id\":\"coursier\",\"kind\":\"artifact\","
                + "\"version\":\"pinned\",\"path\":\"" + PAYLOAD_FILE + "\",\"sha256\":\"" + Sha256.hex(servedBytes)
                + "\",\"sizeBytes\":" + servedBytes.length + "}]}");
    }

    private static List<Path> executablesUnder(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> file.getFileName().toString().endsWith(".exe"))
                    .toList();
        }
    }

    private static byte[] zip(List<String> executables) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write("not an executable".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (String name : executables) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(LAUNCHER);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
