package com.minos.adapter.scip.runtime.install;

import com.minos.io.BoundedInputStream;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.PrivateLocalStorage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.minos.adapter.scip.runtime.install.ManagedToolInstallation.moveFile;
import static com.minos.adapter.scip.runtime.install.ManagedToolInstallation.writeNewFile;

/**
 * The managed Coursier launcher of {@code MINOS_HOME/tools}: which catalogue entry serves this platform, where its
 * launcher lives, and how it is installed. The archive is always verified against its pinned SHA-256
 * ({@link PinnedArtifactSource#acquire}) before anything is extracted, and the launcher is published by an atomic
 * move. The manager falls back to a {@code cs} found in PATH when no managed launcher is installed.
 */
final class CoursierLauncherInstallation {

    static final String COURSIER_ID = "coursier";

    private static final long MAX_COURSIER_ARCHIVE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_COURSIER_LAUNCHER_BYTES = 256L * 1024L * 1024L;
    private static final Set<PosixFilePermission> OWNER_ONLY_EXECUTABLE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final String COURSIER_INSTALL_FAILED = "Coursier launcher could not be installed";
    private static final String COURSIER_INSTALL_HINT =
            "; run `minos tools install scip-java` with network access, or put Coursier (`cs`) in PATH";

    private final EmbeddedToolsCatalog catalog;
    private final Path coursierRoot;
    private final ToolOriginLedger origins;
    private final String userAgent;

    CoursierLauncherInstallation(EmbeddedToolsCatalog catalog, Path toolsRoot, ToolOriginLedger origins, String userAgent) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.coursierRoot = Objects.requireNonNull(toolsRoot, "toolsRoot").resolve(COURSIER_ID);
        this.origins = Objects.requireNonNull(origins, "origins");
        this.userAgent = userAgent;
    }

    /** The Coursier launcher the catalogue pins for this platform; empty where none is packaged. */
    Optional<EmbeddedToolsCatalog.Artifact> artifact() {
        String platform = EmbeddedToolsCatalog.currentPlatform();
        if (!EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64.equals(platform)
                && !EmbeddedToolsCatalog.PLATFORM_LINUX_X64.equals(platform)) {
            return Optional.empty();
        }
        return catalog.findArtifact(COURSIER_ID, platform).filter(artifact -> platform.equals(artifact.platform()));
    }

    /** The managed launcher of this platform, when it is installed. */
    Optional<Path> managedExecutable() {
        return artifact().map(this::destination).filter(Files::isRegularFile);
    }

    /** Installs the launcher pinned for this platform; refuses where the catalogue packages none. */
    Path installForPlatform(PinnedArtifactSource source, boolean allowNetwork) throws IOException, InterruptedException {
        EmbeddedToolsCatalog.Artifact artifact = artifact().orElseThrow(() -> new IllegalStateException(
                "automatic Coursier installation is not packaged for this platform; install `cs` in PATH"));
        return install(source, artifact, allowNetwork);
    }

    /** Installs {@code artifact} from {@code artifactSource}: a ZIP holding {@code cs.exe} on Windows, a gzip elsewhere. */
    Path install(PinnedArtifactSource artifactSource, EmbeddedToolsCatalog.Artifact artifact, boolean allowNetwork)
            throws IOException, InterruptedException {
        return EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64.equals(artifact.platform())
                ? installZip(artifactSource, artifact, allowNetwork)
                : installGzip(artifactSource, artifact, allowNetwork);
    }

    /** The folder of a launcher: its platform and the short commit of the launchers repository. */
    private Path directory(EmbeddedToolsCatalog.Artifact artifact) {
        return coursierRoot.resolve(artifact.platform() + "-" + artifact.version().substring(0, 12));
    }

    private Path destination(EmbeddedToolsCatalog.Artifact artifact) {
        boolean windows = EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64.equals(artifact.platform());
        return directory(artifact).resolve(windows ? "cs.exe" : "cs");
    }

    private Path installZip(PinnedArtifactSource artifactSource, EmbeddedToolsCatalog.Artifact artifact, boolean allowNetwork)
            throws IOException, InterruptedException {
        Path directory = directory(artifact);
        PrivateLocalStorage.ensurePrivateDirectory(directory);
        Path archive = directory.resolve("cs-x86_64-pc-win32.zip");
        Path archivePartial = directory.resolve("cs-x86_64-pc-win32.partial.zip");
        Path executablePartial = directory.resolve("cs.partial.exe");
        Files.deleteIfExists(archivePartial);
        Files.deleteIfExists(executablePartial);

        PinnedArtifactSource.Origin origin = artifactSource.acquire(
                artifact, archivePartial, MAX_COURSIER_ARCHIVE_BYTES, userAgent, allowNetwork);
        moveFile(archivePartial, archive);

        int executableEntries = 0;
        try (InputStream input = ConfinedFileOpener.openRegularFileNoFollow(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".exe")) {
                    executableEntries++;
                    if (executableEntries == 1) writeNewFile(executablePartial, zip);
                }
            }
        }
        if (executableEntries != 1 || !Files.isRegularFile(executablePartial) || Files.size(executablePartial) == 0L) {
            Files.deleteIfExists(executablePartial);
            throw new IllegalStateException("Coursier launcher ZIP did not contain a Windows executable");
        }
        Path destination = destination(artifact);
        moveFile(executablePartial, destination);
        origins.record(COURSIER_ID, origin);
        return destination;
    }

    /**
     * The Linux launcher is a single gzip-compressed executable. It is decompressed under a byte bound into a partial
     * file created private, made executable for its owner only, then moved into place; the archive and the partial
     * never outlive the call. A failure names Coursier and how to proceed, never a path.
     */
    private Path installGzip(PinnedArtifactSource artifactSource, EmbeddedToolsCatalog.Artifact artifact, boolean allowNetwork)
            throws IOException, InterruptedException {
        Path directory = directory(artifact);
        PrivateLocalStorage.ensurePrivateDirectory(directory);
        Path archivePartial = directory.resolve("cs.partial.gz");
        Path executablePartial = directory.resolve("cs.partial");
        Files.deleteIfExists(archivePartial);
        Files.deleteIfExists(executablePartial);
        try {
            PinnedArtifactSource.Origin origin = artifactSource.acquire(
                    artifact, archivePartial, MAX_COURSIER_ARCHIVE_BYTES, userAgent, allowNetwork);
            try (InputStream raw = ConfinedFileOpener.openRegularFileNoFollow(archivePartial);
                 GZIPInputStream gzip = new GZIPInputStream(raw);
                 BoundedInputStream launcher = new BoundedInputStream(gzip, MAX_COURSIER_LAUNCHER_BYTES, "Coursier launcher")) {
                writeNewFile(executablePartial, launcher);
            }
            if (Files.size(executablePartial) == 0L) {
                throw new IllegalStateException("the Coursier launcher archive is empty");
            }
            Files.setPosixFilePermissions(executablePartial, OWNER_ONLY_EXECUTABLE);
            Path destination = destination(artifact);
            moveFile(executablePartial, destination);
            origins.record(COURSIER_ID, origin);
            return destination;
        } catch (IllegalStateException refused) {
            throw new IllegalStateException(COURSIER_INSTALL_FAILED + ": " + refused.getMessage() + COURSIER_INSTALL_HINT, refused);
        } catch (IOException failure) {
            throw new IllegalStateException(COURSIER_INSTALL_FAILED + " (" + failure.getClass().getSimpleName() + ")"
                    + COURSIER_INSTALL_HINT, failure);
        } finally {
            Files.deleteIfExists(archivePartial);
            Files.deleteIfExists(executablePartial);
        }
    }
}
