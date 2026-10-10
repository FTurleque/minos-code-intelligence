package com.minos.bootstrap.application;

import com.minos.application.resolution.ProjectResolver;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.registry.RegisteredProject;
import com.minos.storage.local.registry.LocalProjectRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** MINOS-AUD-C01 : le message public d'une référence est le même sur les trois surfaces, sans écho sensible. */
    @Test
    void publicMessageKeepsAPlainReferenceAndNeverEchoesASensitiveOne(@TempDir Path root) throws Exception {
        ProjectResolver resolver = new ProjectResolver(new LocalProjectRegistry(root.resolve("registry")));

        assertEquals("unknown project: demo", publicMessageOf(resolver, "demo"));
        for (String sensitive : List.of("C:\\Users\\x\\proj", "D:/work/proj", "/home/x/proj", "\\\\srv\\share\\p",
                "my-api-key-service", "password=hunter2", "jdbc:postgresql://db/minos")) {
            String message = publicMessageOf(resolver, sensitive);

            assertTrue(message.contains("unknown project"), sensitive + " -> " + message);
            assertTrue(message.contains("project name or UUID"), sensitive + " -> " + message);
            assertTrue(message.contains("minos project list"), sensitive + " -> " + message);
            assertFalse(message.contains(sensitive), sensitive + " -> " + message);
            assertFalse(PublicErrorMessages.looksSensitive(message),
                    "the fixed text must itself pass the public redaction policy: " + message);
        }
    }

    @Test
    void publicMessageOfAnAmbiguousNameIsRedactedOnlyWhenTheNameIsSensitive(@TempDir Path root) throws Exception {
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        registry.registerProject(Files.createDirectories(root.resolve("first")), "shared");
        registry.registerProject(Files.createDirectories(root.resolve("second")), "shared");
        registry.registerProject(Files.createDirectories(root.resolve("third")), "my-api-key-service");
        registry.registerProject(Files.createDirectories(root.resolve("fourth")), "my-api-key-service");
        ProjectResolver resolver = new ProjectResolver(registry);

        assertEquals("ambiguous project name, use its UUID: shared", publicMessageOf(resolver, "shared"));
        String redacted = publicMessageOf(resolver, "my-api-key-service");
        assertTrue(redacted.contains("ambiguous project name"), redacted);
        assertTrue(redacted.contains("project UUID"), redacted);
        assertFalse(redacted.contains("api-key"), redacted);
        assertFalse(PublicErrorMessages.looksSensitive(redacted), redacted);
    }

    @Test
    void anInvalidReferenceNeverEchoesItsValue(@TempDir Path root) throws Exception {
        ProjectResolver resolver = new ProjectResolver(new LocalProjectRegistry(root.resolve("registry")));

        assertEquals("project identifier must not be blank", publicMessageOf(resolver, "   "));
        String tooLong = publicMessageOf(resolver, "x".repeat(ProjectResolver.MAX_REFERENCE_UTF8_BYTES + 1));
        assertEquals("project identifier exceeds UTF-8 byte limit: " + ProjectResolver.MAX_REFERENCE_UTF8_BYTES, tooLong);
        assertFalse(tooLong.contains("xxxx"));
    }

    private static String publicMessageOf(ProjectResolver resolver, String reference) {
        return assertThrows(ProjectResolver.ResolutionException.class, () -> resolver.resolve(reference))
                .publicMessage();
    }
}
