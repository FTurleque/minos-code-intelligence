package com.minos.adapter.scip.ingestion;

import com.minos.io.BoundedInputStream;
import com.minos.io.ConfinedFileOpener;
import org.scip_code.scip.Index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads a binary SCIP file at the infrastructure boundary of MINOS.
 *
 * <p>The {@link Index} type must not cross the SCIP adaptation package.
 * The raw size is enforced during the protobuf read, not only through a pre-check. Before the
 * full materialization of the protobuf, a streaming preflight also bounds the estimated memory
 * amplification of the object graph relative to the available heap.</p>
 */
public final class ScipIndexReader {

    private final ScipIngestionLimits limits;
    private final Runnable beforeOpen;

    public ScipIndexReader() {
        this(ScipIngestionLimits.DEFAULT);
    }

    ScipIndexReader(ScipIngestionLimits limits) {
        this(limits, () -> { });
    }

    /**
     * Test seam: {@code beforeOpen} runs after the path checks and immediately before the artifact is opened, which is
     * when a hostile process would put a link or another file in its place. Instance state, not static.
     */
    ScipIndexReader(ScipIngestionLimits limits, Runnable beforeOpen) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.beforeOpen = Objects.requireNonNull(beforeOpen, "beforeOpen");
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

        beforeOpen.run();
        try (SeekableByteChannel channel = ConfinedFileOpener.openRegularFileNoFollowChannel(indexFile)) {
            // The size on the descriptor that was opened is the one which counts: the file may have changed since the
            // path checks above.
            observedSize = channel.size();
            if (observedSize < 1L) {
                throw new IOException("SCIP index is empty: " + indexFile);
            }
            if (observedSize > limits.maxArtifactBytes()) {
                throw new IOException("SCIP artifact exceeds configured byte limit: " + observedSize
                        + "/" + limits.maxArtifactBytes());
            }
            BoundedInputStream preflight = new BoundedInputStream(
                    Channels.newInputStream(channel), limits.maxArtifactBytes(), "SCIP artifact preflight");
            ScipIngestionLimits.PreflightMetrics metrics = limits.preflight(preflight);
            limits.enforceDecodeHeapBudget(metrics, observedSize, Runtime.getRuntime().maxMemory());

            channel.position(0L);
            BoundedInputStream input = new BoundedInputStream(
                    Channels.newInputStream(channel), limits.maxArtifactBytes(), "SCIP artifact");
            return Index.parseFrom(input);
        }
    }
}
