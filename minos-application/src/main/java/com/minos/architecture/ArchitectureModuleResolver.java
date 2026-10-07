package com.minos.architecture;

import com.minos.discovery.ModuleAssignmentRule;
import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.domain.Symbol;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Résout de manière déterministe un symbole local vers le module découvert qui
 * le contient. Cette logique est partagée par les vues de topologie et les
 * agrégations inter-modules afin d'éviter des classifications divergentes.
 *
 * <p>La règle elle-même est {@link ModuleAssignmentRule} : l'indexation autonome l'applique aux symboles avant
 * publication, de sorte que l'identifiant d'un module est le même dans l'architecture et dans un filtre.</p>
 */
final class ArchitectureModuleResolver {

    private final ModuleAssignmentRule rule;

    ArchitectureModuleResolver(String projectId, ProjectDiscovery discovery) {
        this.rule = ModuleAssignmentRule.of(projectId, Objects.requireNonNull(discovery, "discovery"));
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
        return rule.resolve(filePath).map(assignment -> new Assignment(
                assignment.module(), assignment.filePath(), assignment.sourceRoot(), assignment.moduleId()));
    }

    static String moduleId(String projectId, Path modulePath) {
        return ModuleAssignmentRule.moduleId(projectId, modulePath);
    }

    static Path safeRelativePath(String value) {
        return ModuleAssignmentRule.safeRelativePath(value);
    }

    static boolean startsWith(Path path, Path prefix) {
        return ModuleAssignmentRule.startsWith(path, prefix);
    }

    static String portable(Path path) {
        return ModuleAssignmentRule.portable(path);
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
