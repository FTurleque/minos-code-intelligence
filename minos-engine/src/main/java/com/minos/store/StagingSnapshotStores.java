package com.minos.store;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Opens the working snapshot stores an indexing run stages its snapshots in, each under a directory the caller owns.
 *
 * <p>A working store is not the active store: it holds the normalized snapshot of one provider, or the staged project
 * snapshot that a later promotion reads back, possibly from another process. The store opened for a directory must
 * therefore read what an earlier store opened for the same directory has published. The composition root chooses the
 * implementation; an adapter receives this port and never creates a store itself.
 */
@FunctionalInterface
public interface StagingSnapshotStores {

    /**
     * Opens the working store rooted at {@code root}.
     *
     * @param root directory of the working store, owned by the indexing run
     * @return a store whose publications stay under {@code root}
     * @throws IOException when the store cannot be opened
     */
    CodeKnowledgeSnapshotStore open(Path root) throws IOException;
}
