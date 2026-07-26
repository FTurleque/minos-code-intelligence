package com.minos.architecture;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.domain.Symbol;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Résout de manière déterministe un symbole local vers le module découvert qui
 * le contient. Cette logique est partagée par les vues de topologie et les
 * agrégations inter-modules afin d'éviter des classifications divergentes.
 */
final class ArchitectureModuleResolver {

    private final String projectId;
    private final List<DiscoveredModule> modules;

    ArchitectureModuleResolver(String projectId, ProjectDiscovery discovery) {
        this.projectId = requireText(projectId, "projectId");
        Objects.requireNonNull(discovery, "discovery");
        this.modules = discovery.modules().stream()
                .sorted(Comparator.comparing(module -> portable(module.relativePath())))
                .toList();
    }

    Optional<Assignment> resolve(Symbol symbol) {
        Objects.requireNonNull(symbol, "symbol");
        if (symbol.external()) {
            return Optional.empty();
        }
        Path filePath = safeRelativePath(symbol.fileId());
        if (filePath == null) {
            return Optional.empty();
        }
        return resolve(filePath);
    }

    Optional<Assignment> resolve(Path filePath) {
        Objects.requireNonNull(filePath, "filePath");

        DiscoveredModule selected = null;
        SourceRoot selectedRoot = null;
        int bestRootScore = -1;
        for (DiscoveredModule module : modules) {
            SourceRoot candidateRoot = module.sourceRoots().stream()
                    .filter(root -> startsWith(filePath, root.relativePath()))
                    .max(Comparator.comparingInt(root -> portable(root.relativePath()).length()))
                    .orElse(null);
            if (candidateRoot == null) {
                continue;
            }
            int score = portable(candidateRoot.relativePath()).length();
            if (score > bestRootScore) {
                selected = module;
                selectedRoot = candidateRoot;
                bestRootScore = score;
            }
        }

        if (selected == null) {
            int bestModuleScore = -1;
            for (DiscoveredModule module : modules) {
                if (!startsWith(filePath, module.relativePath())) {
                    continue;
                }
                int score = portable(module.relativePath()).length();
                if (score > bestModuleScore) {
                    selected = module;
                    selectedRoot = null;
                    bestModuleScore = score;
                }
            }
        }

        if (selected == null) {
            return Optional.empty();
        }
        return Optional.of(new Assignment(
                selected,
                filePath,
                selectedRoot,
                moduleId(projectId, selected.relativePath())
        ));
    }

    static String moduleId(String projectId, Path modulePath) {
        return "module:" + sha256(requireText(projectId, "projectId")
                + "\u001F" + portable(Objects.requireNonNull(modulePath, "modulePath")));
    }

    static Path safeRelativePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String portable = value.replace('\\', '/');
        if (portable.startsWith("/")
                || portable.startsWith("file:")
                || portable.matches("^[A-Za-z]:/.*")) {
            return null;
        }
        try {
            Path path = Path.of(portable).normalize();
            if (path.isAbsolute() || path.startsWith("..")) {
                return null;
            }
            return path;
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    static boolean startsWith(Path path, Path prefix) {
        return portable(prefix).isEmpty() || path.startsWith(prefix);
    }

    static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    record Assignment(
            DiscoveredModule module,
            Path filePath,
            SourceRoot sourceRoot,
            String moduleId
    ) {
        Assignment {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(filePath, "filePath");
            if (moduleId == null || moduleId.isBlank()) {
                throw new IllegalArgumentException("moduleId must not be blank");
            }
        }
    }
}
