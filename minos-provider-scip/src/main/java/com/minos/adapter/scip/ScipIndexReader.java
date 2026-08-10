package com.minos.adapter.scip;

import com.minos.io.BoundedInputStream;
import org.scip_code.scip.Index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.Objects;

/**
 * Lit un fichier binaire SCIP à la frontière infrastructure de MINOS.
 *
 * <p>Le type {@link Index} ne doit pas franchir le package d'adaptation SCIP.
 * La taille brute est imposée pendant la lecture protobuf, pas uniquement via un précheck.</p>
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
        long observedSize = Files.size(indexFile);
        if (observedSize < 1L) {
            throw new IOException("SCIP index is empty: " + indexFile);
        }
        if (observedSize > limits.maxArtifactBytes()) {
            throw new IOException("SCIP artifact exceeds configured byte limit: " + observedSize
                    + "/" + limits.maxArtifactBytes());
        }

        try (SeekableByteChannel channel = Files.newByteChannel(
                indexFile, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            BoundedInputStream preflight = new BoundedInputStream(
                    Channels.newInputStream(channel), limits.maxArtifactBytes(), "SCIP artifact preflight");
            limits.preflight(preflight);
            channel.position(0L);
            BoundedInputStream input = new BoundedInputStream(
                    Channels.newInputStream(channel), limits.maxArtifactBytes(), "SCIP artifact");
            return Index.parseFrom(input);
        }
    }
}
