package com.minos.runtime;

import com.minos.io.FileTreeOperations;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Reclaims everything an untrusted provider left in its MINOS run directory.
 *
 * <p>Success, failure, timeout and containment breach all reach this reclamation, so a hostile
 * provider cannot poison {@code MINOS_HOME/runs} with a tree that the next run would have to pay
 * for. Only MINOS-authored diagnostics survive; every other entry is removed without following
 * symbolic links and without ever leaving the MINOS runs root.</p>
 */
final class ProviderResidueReclamation {

    private static final System.Logger LOGGER = System.getLogger(ProviderResidueReclamation.class.getName());

    /** Diagnostics MINOS itself authors and that retention, not reclamation, bounds. */
    static final Set<String> RETAINED_ENTRIES = Set.of(
            "provider.stdout.log",
            "provider.stderr.log",
            "process.txt",
            "index.scip",
            "failed-index.scip",
            "preexisting-artifact.scip",
            "scopes");

    private ProviderResidueReclamation() {
    }

    /** Removes every provider-authored entry of {@code runDirectory}; never throws. */
    static void reclaim(Path runsRoot, Path runDirectory) {
        reclaim(runsRoot, runDirectory, Set.of());
    }

    /**
     * Removes every provider-authored entry of {@code runDirectory}; never throws.
     *
     * <p>{@code extraRetainedTopLevelEntries} additionally exempts specific direct children of
     * {@code runDirectory} beyond {@link #RETAINED_ENTRIES}. A caller that just restored a
     * preexisting provider artifact into a top-level entry not covered by that fixed name list
     * uses this to stop reclamation from deleting the entry moments after it was restored.</p>
     */
    static void reclaim(Path runsRoot, Path runDirectory, Set<Path> extraRetainedTopLevelEntries) {
        Path root = Objects.requireNonNull(runsRoot, "runsRoot").toAbsolutePath().normalize();
        Path directory = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
        Objects.requireNonNull(extraRetainedTopLevelEntries, "extraRetainedTopLevelEntries");
        if (!directory.startsWith(root) || directory.equals(root)) return;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                deleteResidue(directory, child, extraRetainedTopLevelEntries);
            }
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS could not enumerate provider residue in " + directory, exception);
        }
    }

    private static void deleteResidue(Path directory, Path child, Set<Path> extraRetainedTopLevelEntries) {
        Path name = child.getFileName();
        if (name != null && RETAINED_ENTRIES.contains(name.toString())) return;
        Path normalized = child.toAbsolutePath().normalize();
        if (extraRetainedTopLevelEntries.contains(normalized)) return;
        if (!normalized.startsWith(directory) || normalized.equals(directory)) return;
        try {
            if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(normalized);
            } else {
                FileTreeOperations.deleteRecursively(normalized);
            }
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS could not reclaim provider residue " + normalized, exception);
        }
    }
}
