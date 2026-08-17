package com.minos.orchestration;

import com.minos.orchestration.ExecutionPathIdentityProvider.IdentityPair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/** Strong filesystem-object identity used by the pre-launch anti-TOCTOU authorization. */
final class StableFileSystemIdentity {

    private StableFileSystemIdentity() {
    }

    /**
     * Captures both roots using one strong identity mechanism.
     *
     * <p>The Java provider key is preferred. Unix filesystems can additionally expose the device and
     * inode pair. When neither standard mechanism is available, trusted runtime implementations of
     * {@link ExecutionPathIdentityProvider} may provide a platform-native equivalent. Mixing one
     * strong mechanism for one root with another mechanism for the second root is deliberately
     * avoided so capture and revalidation stay deterministic.</p>
     */
    static Optional<IdentityPair> capture(Path registeredProjectRoot, Path projectRoot) throws IOException {
        Optional<String> registeredFileKey = basicFileKey(registeredProjectRoot);
        Optional<String> projectFileKey = basicFileKey(projectRoot);
        if (registeredFileKey.isPresent() && projectFileKey.isPresent()) {
            return Optional.of(new IdentityPair(
                    "nio:" + registeredFileKey.orElseThrow(),
                    "nio:" + projectFileKey.orElseThrow()));
        }

        Optional<String> registeredUnix = unixIdentity(registeredProjectRoot);
        Optional<String> projectUnix = unixIdentity(projectRoot);
        if (registeredUnix.isPresent() && projectUnix.isPresent()) {
            return Optional.of(new IdentityPair(
                    registeredUnix.orElseThrow(),
                    projectUnix.orElseThrow()));
        }

        for (ExecutionPathIdentityProvider provider : ServiceLoader.load(ExecutionPathIdentityProvider.class)) {
            Optional<IdentityPair> identity = provider.capture(registeredProjectRoot, projectRoot);
            if (identity.isPresent()) {
                return identity;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> basicFileKey(Path realPath) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                realPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Object key = attributes.fileKey();
        return key == null ? Optional.empty() : Optional.of(key.toString());
    }

    private static Optional<String> unixIdentity(Path realPath) throws IOException {
        try {
            Map<String, Object> attributes = Files.readAttributes(
                    realPath, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
            Object device = attributes.get("dev");
            Object inode = attributes.get("ino");
            if (device == null || inode == null) {
                return Optional.empty();
            }
            return Optional.of("unix:" + device + ':' + inode);
        } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
            return Optional.empty();
        }
    }
}
