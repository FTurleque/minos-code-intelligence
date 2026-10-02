package com.minos.adapter.scip.runtime;

import com.minos.io.BoundedInputStream;
import com.minos.io.Sha256;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;

/**
 * Where a pinned artifact's bytes come from: the distribution payload first, the network second, and
 * in both cases through the same SHA-256 comparison against the packaged catalogue.
 *
 * <p>Embedding a tool is therefore not a second trust model: the file shipped in the installation is
 * accepted exactly when a download of the same artifact would be, and an altered one is refused
 * (never silently replaced by a download).</p>
 */
final class PinnedArtifactSource {

    /** The embedded artifact (or the payload that should have carried it) failed verification. */
    static final class EmbeddedToolIntegrityException extends IllegalStateException {
        EmbeddedToolIntegrityException(String message) {
            super(message);
        }
    }

    enum Origin { EMBEDDED, DOWNLOADED }

    private static final String USER_AGENT_HEADER = "User-Agent";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);

    private final EmbeddedToolsPayload payload;
    private final String payloadFailure;

    private PinnedArtifactSource(EmbeddedToolsPayload payload, String payloadFailure) {
        this.payload = payload;
        this.payloadFailure = payloadFailure;
    }

    /** The source of this installation: its payload when it ships one, otherwise the network only. */
    static PinnedArtifactSource forHost() {
        Optional<Path> directory = EmbeddedToolsPayload.locateDirectory();
        return directory.map(PinnedArtifactSource::forPayloadDirectory).orElseGet(PinnedArtifactSource::networkOnly);
    }

    static PinnedArtifactSource forPayloadDirectory(Path directory) {
        try {
            return new PinnedArtifactSource(EmbeddedToolsPayload.open(directory), null);
        } catch (IOException failure) {
            return new PinnedArtifactSource(null, "the distribution tools payload is unreadable or malformed");
        }
    }

    static PinnedArtifactSource networkOnly() {
        return new PinnedArtifactSource(null, null);
    }

    Optional<EmbeddedToolsPayload> payload() {
        return Optional.ofNullable(payload);
    }

    /** Whether this installation ships {@code artifact} (a payload that exists but is broken counts: it must be refused). */
    boolean ships(EmbeddedToolsCatalog.Artifact artifact) {
        if (!artifact.embedded()) return false;
        if (payloadFailure != null) return true;
        try {
            return payload != null && payload.artifactFile(artifact).isPresent();
        } catch (IOException invalid) {
            return true;
        }
    }

    /**
     * Writes the verified bytes of {@code artifact} to {@code partial}, which the caller has made private
     * and cleared. Returns where they came from.
     *
     * @param allowNetwork false for a read-only seeding pass that must never reach out
     */
    Origin acquire(
            EmbeddedToolsCatalog.Artifact artifact,
            Path partial,
            long maxBytes,
            String userAgent,
            boolean allowNetwork
    ) throws IOException, InterruptedException {
        if (artifact.embedded() && payloadFailure != null) {
            throw new EmbeddedToolIntegrityException(payloadFailure);
        }
        Optional<Path> shipped = payload == null ? Optional.empty() : payload.artifactFile(artifact);
        if (shipped.isPresent()) {
            copyAndVerify(artifact, shipped.orElseThrow(), partial, maxBytes);
            return Origin.EMBEDDED;
        }
        if (!allowNetwork) {
            throw new IllegalStateException("the distribution does not provide " + artifact.id());
        }
        ToolsNetworkPolicy.requireOnline(artifact.id());
        download(artifact, partial, maxBytes, userAgent);
        return Origin.DOWNLOADED;
    }

    /** Whether this installation ships {@code component} (a payload that exists but is broken counts: it must be refused). */
    boolean shipsAssembled(EmbeddedToolsCatalog.Assembled component) {
        if (!component.embedded()) return false;
        if (payloadFailure != null) return true;
        return payload != null && payload.entry(component.id(), EmbeddedToolsPayload.KIND_ASSEMBLED).isPresent();
    }

    /**
     * Copies an assembled component to {@code partial}, verified against the SHA-256 and size the manifest
     * of the distribution records for it (no upstream pins these bytes; see the tools documentation).
     */
    void copyAssembled(EmbeddedToolsCatalog.Assembled component, Path partial, long maxBytes) throws IOException {
        if (payloadFailure != null) throw new EmbeddedToolIntegrityException(payloadFailure);
        EmbeddedToolsPayload.Entry entry = payload == null ? null
                : payload.entry(component.id(), EmbeddedToolsPayload.KIND_ASSEMBLED).orElse(null);
        if (entry == null) {
            throw new EmbeddedToolIntegrityException(
                    "the distribution manifest does not list the embedded component " + component.id());
        }
        copyVerified(payload.file(entry), partial, entry.sha256(), entry.sizeBytes(), maxBytes, component.id());
    }

    private static void copyAndVerify(
            EmbeddedToolsCatalog.Artifact artifact, Path source, Path partial, long maxBytes
    ) throws IOException {
        copyVerified(source, partial, artifact.sha256(), artifact.sizeBytes(), maxBytes, artifact.id());
    }

    private static void copyVerified(
            Path source, Path partial, String expectedSha256, long expectedSize, long maxBytes, String label
    ) throws IOException {
        long limit = expectedSize > 0L ? Math.min(maxBytes, expectedSize) : maxBytes;
        MessageDigest digest = Sha256.newDigest();
        try (InputStream raw = EmbeddedToolsPayload.openNoFollow(source);
             BoundedInputStream input = new BoundedInputStream(raw, limit, "embedded tool " + label);
             OutputStream output = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException failure) {
            Files.deleteIfExists(partial);
            throw new EmbeddedToolIntegrityException(
                    "embedded tool " + label + " is larger than the pinned artifact or unreadable");
        }
        String actual = Sha256.hex(digest);
        boolean sizeOk = expectedSize <= 0L || Files.size(partial) == expectedSize;
        if (!sizeOk || !expectedSha256.equals(actual)) {
            Files.deleteIfExists(partial);
            throw new EmbeddedToolIntegrityException("embedded tool " + label
                    + " does not match its pinned SHA-256 and was refused");
        }
    }

    private static void download(
            EmbeddedToolsCatalog.Artifact artifact, Path partial, long maxBytes, String userAgent
    ) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(artifact.url())).timeout(REQUEST_TIMEOUT);
        if (userAgent != null && !userAgent.isBlank()) request.header(USER_AGENT_HEADER, userAgent);
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream ignored = response.body()) { /* close the error response */ }
            throw new IllegalStateException(artifact.id() + " download failed with HTTP " + response.statusCode());
        }
        MessageDigest digest = Sha256.newDigest();
        try (InputStream body = response.body();
             BoundedInputStream bounded = new BoundedInputStream(body, maxBytes, artifact.id() + " archive");
             OutputStream output = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = bounded.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(partial);
            throw failure;
        }
        if (!Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) || Files.size(partial) == 0L) {
            Files.deleteIfExists(partial);
            throw new IllegalStateException(artifact.id() + " download produced an empty archive");
        }
        String actual = Sha256.hex(digest);
        if (!artifact.sha256().equals(actual)) {
            Files.deleteIfExists(partial);
            throw new IllegalStateException(artifact.id() + " checksum mismatch: expected="
                    + artifact.sha256() + " actual=" + actual);
        }
    }
}
