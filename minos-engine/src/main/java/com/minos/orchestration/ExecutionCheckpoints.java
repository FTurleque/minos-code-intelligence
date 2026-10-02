package com.minos.orchestration;

import com.minos.io.ConfinedFileOpener;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.io.BoundedInputStream;
import com.minos.io.Sha256;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;

/**
 * Matériel d'un point de contrôle de cible (ADR 0039 §1) : empreinte du scope et SHA-256 de
 * l'artefact. Toute impossibilité est signalée par {@link Unavailable} avec une raison publique
 * (jamais de chemin) : l'orchestrateur enregistre alors l'exécution sans point de contrôle, ce qui
 * rend la cible non reprenable sans changer l'issue du run.
 */
final class ExecutionCheckpoints {

    /** Fichier compagnon écrit par le runtime à côté de l'artefact : {@code <artifact>.sha256}. */
    static final String DIGEST_SIDECAR_SUFFIX = ".sha256";

    private static final int SHA256_HEX_LENGTH = 64;
    private static final long MAX_SIDECAR_BYTES = 1024L;
    private static final ProjectFingerprintService FINGERPRINTS = new ProjectFingerprintService();

    private ExecutionCheckpoints() {
    }

    /** Empreinte des sources d'un scope, à capturer avant de lancer le provider. */
    static String scopeFingerprint(Path projectRoot, Path projectRelativeRoot) throws Unavailable {
        try {
            return FINGERPRINTS.captureScope(projectRoot, projectRelativeRoot).projectSha256();
        } catch (IOException | RuntimeException failure) {
            throw new Unavailable("scope fingerprint unavailable: " + failure.getClass().getSimpleName());
        }
    }

    /** Taille et SHA-256 de l'artefact final, lu sans suivre de lien symbolique et sous le budget SCIP. */
    static ArtifactDigest artifactDigest(Path artifact) throws Unavailable {
        Path path = Objects.requireNonNull(artifact, "artifact");
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new Unavailable("artifact digest unavailable: not a regular file");
        }
        MessageDigest digest = Sha256.newDigest();
        byte[] buffer = new byte[64 * 1024];
        long bytes = 0L;
        try (InputStream raw = ConfinedFileOpener.openRegularFileNoFollow(path);
             BoundedInputStream input = new BoundedInputStream(
                     raw, IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES, "SCIP artifact digest")) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                    bytes += read;
                }
            }
        } catch (IOException failure) {
            throw new Unavailable("artifact digest unavailable: " + failure.getClass().getSimpleName());
        }
        if (bytes < 1L) throw new Unavailable("artifact digest unavailable: empty artifact");
        ArtifactDigest computed = new ArtifactDigest(bytes, Sha256.hex(digest));
        verifySidecar(path, computed.sha256());
        return computed;
    }

    /**
     * Si le runtime a laissé un fichier compagnon, il doit décrire exactement les octets lus ici :
     * un désaccord signifie que l'artefact a changé entre sa promotion et cette lecture, et la
     * cible ne doit alors jamais être réutilisée.
     */
    private static void verifySidecar(Path artifact, String computedSha256) throws Unavailable {
        Path sidecar = artifact.resolveSibling(artifact.getFileName() + DIGEST_SIDECAR_SUFFIX);
        if (!Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(sidecar) || !Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
            throw new Unavailable("artifact digest sidecar is not a regular file");
        }
        final String content;
        try (InputStream raw = ConfinedFileOpener.openRegularFileNoFollow(sidecar);
             BoundedInputStream input = new BoundedInputStream(raw, MAX_SIDECAR_BYTES, "artifact digest sidecar")) {
            content = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException failure) {
            throw new Unavailable("artifact digest sidecar unreadable: " + failure.getClass().getSimpleName());
        }
        if (content.length() < SHA256_HEX_LENGTH) {
            throw new Unavailable("artifact digest sidecar is malformed");
        }
        String recorded = content.substring(0, SHA256_HEX_LENGTH).toLowerCase(Locale.ROOT);
        if (!recorded.equals(computedSha256)) {
            throw new Unavailable("artifact digest sidecar disagrees with artifact bytes");
        }
    }

    record ArtifactDigest(long bytes, String sha256) {
        ArtifactDigest {
            if (bytes < 1L) throw new IllegalArgumentException("bytes must be positive");
            sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.ROOT);
        }
    }

    /** Raison publique pour laquelle un point de contrôle n'a pas pu être établi. */
    static final class Unavailable extends Exception {
        private static final long serialVersionUID = 1L;

        Unavailable(String reason) {
            super(reason);
        }
    }
}
