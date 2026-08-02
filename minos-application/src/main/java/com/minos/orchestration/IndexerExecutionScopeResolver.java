package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the project-relative roots on which a negotiated provider must run.
 *
 * <p>A project-level negotiation says which provider covers a language. Execution
 * still has to happen at a build/module root that is meaningful to that provider.
 * This distinction is required for polyglot monorepos where the registered project
 * root is not itself a valid TypeScript, Go, Rust, .NET, CMake, ... build root.</p>
 */
public final class IndexerExecutionScopeResolver {

    public List<Path> resolve(ProjectDiscovery discovery, IndexerSelection selection) {
        Objects.requireNonNull(discovery, "discovery");
        Objects.requireNonNull(selection, "selection");

        List<DiscoveredModule> languageModules = discovery.modules().stream()
                .filter(module -> module.sourceRoots().stream()
                        .anyMatch(sourceRoot -> sourceRoot.language() == selection.language()))
                .toList();
        if (languageModules.isEmpty()) {
            throw new IllegalArgumentException("discovery contains no module for negotiated language: "
                    + selection.language());
        }

        // A provider that explicitly declares MULTI_MODULE plus a compatible root build
        // system may execute once at the registered root (for example a Maven reactor).
        // Providers without an explicit build-system contract never receive this shortcut:
        // a random project root must not be treated as their manifest root.
        DiscoveredModule rootModule = discovery.modules().stream()
                .filter(module -> isRoot(module.relativePath()))
                .findFirst()
                .orElse(null);
        if (rootModule != null
                && selection.indexer().capabilities().contains(IndexerCapability.MULTI_MODULE)
                && !selection.indexer().buildSystems().isEmpty()
                && rootModule.buildSystems().stream().anyMatch(selection.indexer().buildSystems()::contains)) {
            return List.of(Path.of(""));
        }

        Set<Path> roots = new LinkedHashSet<>();
        for (DiscoveredModule module : languageModules) {
            if (selection.indexer().buildSystems().isEmpty()
                    || module.buildSystems().stream().anyMatch(selection.indexer().buildSystems()::contains)) {
                roots.add(module.relativePath().normalize());
            }
        }
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("no compatible module/build root for provider "
                    + selection.indexer().id() + " and language " + selection.language());
        }
        return roots.stream()
                .sorted(Comparator.comparing(IndexerExecutionScopeResolver::portable))
                .toList();
    }

    private static boolean isRoot(Path path) {
        return path == null || path.toString().isEmpty();
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
