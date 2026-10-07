package com.minos.architecture;

import com.minos.discovery.ModuleAssignmentRule;
import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-F04 : l'identifiant de module vu par l'architecture est celui que l'indexation autonome attribue aux
 * symboles. Une seule règle ; ce test échoue si le résolveur d'architecture s'en écarte à nouveau.
 */
class ArchitectureModuleResolverParityTest {
    private static final String PROJECT = "66666666-7777-8888-9999-000000000000";
    private static final List<String> FILES = List.of(
            "packages/api/src/Greeting.ts",
            "packages/api/package.json",
            "packages/web/src/App.ts",
            "packages/web/src/generated/Api.ts",
            "packages\\web\\src\\App.ts",
            "README.md",
            "docs/guide.md",
            "../outside/A.ts");

    @Test
    void theArchitectureResolverAndTheAttributionRuleReturnTheSameIdentifierForEveryFile() {
        ProjectDiscovery discovery = new ProjectDiscovery(Path.of("project"), "project", Set.of(Language.TYPESCRIPT),
                Set.of(BuildSystem.NPM), List.of(
                module("root", "", "src"),
                module("api", "packages/api", "packages/api/src"),
                module("web", "packages/web", "packages/web/src", "packages/web/src/generated")));
        ArchitectureModuleResolver resolver = new ArchitectureModuleResolver(PROJECT, discovery);
        ModuleAssignmentRule rule = ModuleAssignmentRule.of(PROJECT, discovery);

        for (String file : FILES) {
            Path path = ArchitectureModuleResolver.safeRelativePath(file);
            Optional<String> viaResolver = path == null
                    ? Optional.empty()
                    : resolver.resolve(path).map(ArchitectureModuleResolver.Assignment::moduleId);
            assertEquals(viaResolver, rule.moduleIdOf(file), file);
        }
        assertTrue(rule.moduleIdOf("packages/api/src/Greeting.ts").isPresent());
        assertEquals(ArchitectureModuleResolver.moduleId(PROJECT, Path.of("packages/api")),
                rule.moduleIdOf("packages/api/src/Greeting.ts").orElseThrow());
    }

    private static DiscoveredModule module(String name, String path, String... sourceRoots) {
        return new DiscoveredModule(Path.of(path), name, Set.of(BuildSystem.NPM),
                java.util.Arrays.stream(sourceRoots)
                        .map(root -> new SourceRoot(Path.of(root), SourceRootKind.SOURCE, Language.TYPESCRIPT))
                        .toList());
    }
}
