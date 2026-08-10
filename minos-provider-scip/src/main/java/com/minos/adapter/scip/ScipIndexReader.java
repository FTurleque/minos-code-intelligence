package com.minos.adapter.scip;

import org.scip_code.scip.Index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Lit un fichier binaire SCIP à la frontière infrastructure de MINOS.
 *
 * <p>Le type {@link Index} ne doit pas franchir le package d'adaptation SCIP.
 * La taille brute est validée avant toute matérialisation protobuf.</p>
 */
public final class ScipIndexReader {

    private final ScipIngestionLimits limits;

    public ScipIndexReader() {
        this(ScipIngestionLimits.DEFAULT);
    }

    ScipIndexReader(ScipIngestionLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public Index read(Path indexFile) throws IOException {
        Objects.requireNonNull(indexFile, "indexFile");

        if (Files.isSymbolicLink(indexFile)
                || !Files.isRegularFile(indexFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SCIP index does not exist or is not a regular file: " + indexFile);
        }
        long size = Files.size(indexFile);
        if (size < 1L) {
            throw new IOException("SCIP index is empty: " + indexFile);
        }
        if (size > limits.maxArtifactBytes()) {
            throw new IOException("SCIP artifact exceeds configured byte limit: " + size
                    + "/" + limits.maxArtifactBytes());
        }

        try (InputStream input = Files.newInputStream(indexFile)) {
            return Index.parseFrom(input);
        }
    }
}
