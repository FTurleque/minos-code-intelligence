package com.minos.discovery;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.io.Sha256;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MINOS-AUD-F04 : la règle d'attribution fichier vers module, partagée par l'architecture et l'indexation. */
class ModuleAssignmentRuleTest {
    private static final String PROJECT = "11111111-2222-3333-4444-555555555555";

    @Test
    void aFileGoesToTheModuleOfItsMostSpecificSourceRoot() {
        ModuleAssignmentRule rule = ModuleAssignmentRule.of(PROJECT, discovery(
                module("api", "packages/api", "packages/api/src"),
                module("web", "packages/web", "packages/web/src", "packages/web/src/generated")));

        assertEquals(Optional.of(expectedId("packages/api")), rule.moduleIdOf("packages/api/src/Greeting.ts"));
        assertEquals(Optional.of(expectedId("packages/web")), rule.moduleIdOf("packages/web/src/App.ts"));
        assertEquals(Optional.of(expectedId("packages/web")), rule.moduleIdOf("packages/web/src/generated/Api.ts"));
    }

    @Test
    void nestedSourceRootsOfAnotherModuleWinOverTheEnclosingModule() {
        ModuleAssignmentRule rule = ModuleAssignmentRule.of(PROJECT, discovery(
                module("outer", "outer", "outer/src"),
                module("inner", "outer/inner", "outer/inner/src")));

        assertEquals(Optional.of(expectedId("outer/inner")), rule.moduleIdOf("outer/inner/src/A.java"));
        assertEquals(Optional.of(expectedId("outer")), rule.moduleIdOf("outer/src/B.java"));
    }

    @Test
    void aFileOutsideEverySourceRootFallsBackToTheDeepestEnclosingModule() {
        ModuleAssignmentRule rule = ModuleAssignmentRule.of(PROJECT, discovery(
                module("outer", "outer", "outer/src"),
                module("inner", "outer/inner", "outer/inner/src")));

        assertEquals(Optional.of(expectedId("outer/inner")), rule.moduleIdOf("outer/inner/build.gradle"));
        assertEquals(Optional.of(expectedId("outer")), rule.moduleIdOf("outer/pom.xml"));
    }

    @Test
    void aFileOutsideAnyModuleOrOutsideTheProjectHasNoModule() {
        ModuleAssignmentRule rule = ModuleAssignmentRule.of(PROJECT, discovery(
                module("api", "packages/api", "packages/api/src")));

        assertEquals(Optional.empty(), rule.moduleIdOf("docs/readme.md"));
        assertEquals(Optional.empty(), rule.moduleIdOf("../elsewhere/A.java"));
        assertEquals(Optional.empty(), rule.moduleIdOf("/etc/passwd"));
        assertEquals(Optional.empty(), rule.moduleIdOf("C:/Users/x/A.java"));
        assertEquals(Optional.empty(), rule.moduleIdOf("file:///x/A.java"));
        assertEquals(Optional.empty(), rule.moduleIdOf(" "));
        assertEquals(Optional.empty(), rule.moduleIdOf(null));
    }

    @Test
    void backslashAndSlashSeparatorsDesignateTheSameFileAndTheSameIdentifier() {
        ModuleAssignmentRule rule = ModuleAssignmentRule.of(PROJECT, discovery(
                module("api", "packages/api", "packages/api/src")));

        assertEquals(rule.moduleIdOf("packages/api/src/Greeting.ts"),
                rule.moduleIdOf("packages\\api\\src\\Greeting.ts"));
        assertEquals(Optional.of(expectedId("packages/api")), rule.moduleIdOf("packages\\api\\src\\Greeting.ts"));
    }

    @Test
    void theRootModuleOwnsEveryFileThatNoOtherModuleClaims() {
        ModuleAssignmentRule rule = ModuleAssignmentRule.of(PROJECT, discovery(
                module("root", "", "src"),
                module("api", "packages/api", "packages/api/src")));

        assertEquals(Optional.of(expectedId("")), rule.moduleIdOf("src/Main.java"));
        assertEquals(Optional.of(expectedId("")), rule.moduleIdOf("README.md"));
        assertEquals(Optional.of(expectedId("packages/api")), rule.moduleIdOf("packages/api/src/A.java"));
    }

    @Test
    void theIdentifierDependsOnTheProjectAndOnThePortableModulePathOnly() {
        assertEquals("module:" + Sha256.hex(PROJECT + "\u001F" + "packages/api"),
                ModuleAssignmentRule.moduleId(PROJECT, Path.of("packages", "api")));
        assertFalse(ModuleAssignmentRule.moduleId(PROJECT, Path.of("packages/api"))
                .equals(ModuleAssignmentRule.moduleId("other-project", Path.of("packages/api"))));
    }

    @Test
    void theEmptyRuleAssignsNothing() {
        assertTrue(ModuleAssignmentRule.none().isEmpty());
        assertSame(ModuleAssignmentRule.none(), ModuleAssignmentRule.none());
        assertEquals(Optional.empty(), ModuleAssignmentRule.none().moduleIdOf("src/A.java"));
        assertNull(ModuleAssignmentRule.safeRelativePath(""));
    }

    private static String expectedId(String modulePath) {
        return "module:" + Sha256.hex(PROJECT + "\u001F" + modulePath);
    }

    private static ProjectDiscovery discovery(DiscoveredModule... modules) {
        return new ProjectDiscovery(Path.of("project"), "project", Set.of(Language.JAVA), Set.of(BuildSystem.MAVEN),
                List.of(modules));
    }

    private static DiscoveredModule module(String name, String path, String... sourceRoots) {
        return new DiscoveredModule(Path.of(path), name, Set.of(BuildSystem.MAVEN),
                java.util.Arrays.stream(sourceRoots)
                        .map(root -> new SourceRoot(Path.of(root), SourceRootKind.SOURCE, Language.JAVA))
                        .toList());
    }
}
