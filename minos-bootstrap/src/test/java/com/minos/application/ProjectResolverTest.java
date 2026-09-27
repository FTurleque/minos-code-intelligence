package com.minos.application;

import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectResolverTest {

    @Test
    void resolvesUuidAndExactDisplayName(@TempDir Path root) throws Exception {
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        Path projectRoot = Files.createDirectories(root.resolve("alpha"));
        RegisteredProject project = registry.registerProject(projectRoot, "alpha");
        ProjectResolver resolver = new ProjectResolver(registry);

        assertEquals(project, resolver.resolve(project.id().toString()));
        assertEquals(project, resolver.resolve("alpha"));
        assertEquals(project, resolver.resolveById(project.id()));
        assertEquals(project, resolver.resolveByName("alpha"));
        assertEquals(List.of(project), resolver.listCandidates("alpha"));
        assertEquals(List.of(project), resolver.listCandidates(project.id().toString()));
    }

    @Test
    void reportsInvalidAndMissingReferencesWithStableDiagnostics(@TempDir Path root) throws Exception {
        ProjectResolver resolver = new ProjectResolver(new LocalProjectRegistry(root.resolve("registry")));

        ProjectResolver.ResolutionException invalid = assertThrows(
                ProjectResolver.ResolutionException.class,
                () -> resolver.resolve("  ")
        );
        assertSame(ProjectResolver.ErrorCode.INVALID_PROJECT_REFERENCE, invalid.code());
        assertEquals("  ", invalid.reference());
        assertEquals(List.of(), invalid.candidateIds());
        assertEquals("project identifier must not be blank", invalid.getMessage());

        ProjectResolver.ResolutionException missing = assertThrows(
                ProjectResolver.ResolutionException.class,
                () -> resolver.resolve("missing-project")
        );
        assertSame(ProjectResolver.ErrorCode.PROJECT_NOT_FOUND, missing.code());
        assertEquals("missing-project", missing.reference());
        assertEquals(List.of(), missing.candidateIds());
        assertEquals("unknown project: missing-project", missing.getMessage());
    }

    @Test
    void reportsAmbiguousDisplayNameAndDeterministicCandidates(@TempDir Path root) throws Exception {
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject first = registry.registerProject(
                Files.createDirectories(root.resolve("first")),
                "shared"
        );
        RegisteredProject second = registry.registerProject(
                Files.createDirectories(root.resolve("second")),
                "shared"
        );
        ProjectResolver resolver = new ProjectResolver(registry);

        List<RegisteredProject> candidates = resolver.listCandidates("shared");
        assertEquals(2, candidates.size());
        assertEquals(
                registry.listProjects().stream()
                        .filter(project -> "shared".equals(project.displayName()))
                        .toList(),
                candidates
        );

        ProjectResolver.ResolutionException ambiguous = assertThrows(
                ProjectResolver.ResolutionException.class,
                () -> resolver.resolve("shared")
        );
        assertSame(ProjectResolver.ErrorCode.PROJECT_REFERENCE_AMBIGUOUS, ambiguous.code());
        assertEquals("shared", ambiguous.reference());
        assertEquals(candidates.stream().map(RegisteredProject::id).toList(), ambiguous.candidateIds());
        assertEquals("ambiguous project name, use its UUID: shared", ambiguous.getMessage());
        assertEquals(List.of(first.id(), second.id()).stream().sorted().toList(),
                ambiguous.candidateIds().stream().sorted().toList());
    }
}
