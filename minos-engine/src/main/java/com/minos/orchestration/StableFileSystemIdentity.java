package com.minos.orchestration;

import com.minos.orchestration.ExecutionPathIdentityProvider.IdentityPair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/** Strong filesystem-object identity used by the pre-launch anti-TOCTOU authorization. */
final class StableFileSystemIdentity {

    private static final System.Logger LOGGER = System.getLogger(StableFileSystemIdentity.class.getName());

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

        return providedIdentity(registeredProjectRoot, projectRoot,
                ExecutionPathIdentityProvider.class.getClassLoader());
    }

    /**
     * Identity from the single registered {@link ExecutionPathIdentityProvider}, discovered through the given
     * class loader (MINOS' own loader in production, never the thread context loader). More than one registered
     * provider makes the identity unavailable — the launch then fails closed — instead of letting discovery order
     * pick one (AUD-ARC-04).
     */
    static Optional<IdentityPair> providedIdentity(Path registeredProjectRoot, Path projectRoot, ClassLoader loader)
            throws IOException {
        List<ExecutionPathIdentityProvider> providers = new ArrayList<>();
        ServiceLoader.load(ExecutionPathIdentityProvider.class, loader).forEach(providers::add);
        if (providers.size() > 1) {
            LOGGER.log(System.Logger.Level.WARNING, "More than one execution path identity provider is registered ("
                    + providers.stream().map(provider -> provider.getClass().getName()).sorted()
                            .collect(Collectors.joining(", "))
                    + "); path identity is unavailable and local launches are refused");
            return Optional.empty();
        }
        if (providers.isEmpty()) {
            return Optional.empty();
        }
        return providers.getFirst().capture(registeredProjectRoot, projectRoot);
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
